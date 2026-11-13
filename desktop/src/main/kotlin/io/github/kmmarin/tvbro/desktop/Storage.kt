// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import com.sun.net.httpserver.HttpServer
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import io.github.kmmarin.tvbro.data.Diag
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Properties

/** A drive that can hold recordings (USB stick, SD card, external or second disk). */
data class Drive(val path: String, val label: String, val removable: Boolean, val freeMb: Long)

object Drives {
    /**
     * Windows: every drive letter except the system drive, removable ones first (JNA GetDriveType).
     * Linux / Pi: mounts under /media/<user>, /media and /mnt (where the desktop mounts USB drives).
     */
    fun list(): List<Drive> = try {
        if (Platform.windows) {
            val sys = (System.getenv("SystemDrive") ?: "C:").uppercase()
            File.listRoots().filter { !it.path.uppercase().startsWith(sys) && it.totalSpace > 0 }.map { r ->
                val type = try { com.sun.jna.platform.win32.Kernel32.INSTANCE.GetDriveType(r.path) } catch (e: Throwable) { 0 }
                val removable = type == 2 // DRIVE_REMOVABLE
                val name = try { javax.swing.filechooser.FileSystemView.getFileSystemView().getSystemDisplayName(r) } catch (e: Throwable) { "" }
                Drive(r.path, name.ifBlank { r.path } + if (removable) " (removable)" else "", removable, r.usableSpace / (1024 * 1024))
            }.sortedBy { !it.removable }
        } else {
            val user = System.getProperty("user.name")
            listOf(File("/media/$user"), File("/media"), File("/mnt")).flatMap { d -> d.listFiles()?.filter { it.isDirectory && it.totalSpace > 0 && it.name != user }.orEmpty() }
                .distinctBy { it.canonicalPath }
                .map { Drive(it.path, it.name, true, it.usableSpace / (1024 * 1024)) }
        }
    } catch (e: Exception) { Diag.log("drive list failed (${e.javaClass.simpleName})"); emptyList() }

    fun freeMb(dir: File): Long { var d: File? = dir; while (d != null && !d.exists()) d = d.parentFile; return (d?.usableSpace ?: 0) / (1024 * 1024) }
}

/** Network share (SMB 2/3) through jcifs-ng (LGPL-2.1, unmodified separate library). Never logs the password. */
object SmbShare {
    @Volatile var password: String = ""

    private fun context(c: SmbCfg, pass: String = password): CIFSContext {
        val p = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.responseTimeout", "20000")
            setProperty("jcifs.smb.client.connTimeout", "8000")
            setProperty("jcifs.resolveOrder", "DNS,BCAST")
        }
        val base = BaseContext(PropertyConfiguration(p))
        return if (c.user.isBlank()) base.withGuestCrendentials() else base.withCredentials(NtlmPasswordAuthenticator(c.domain.ifBlank { null }, c.user, pass))
    }

    fun freeMb(c: SmbCfg): Long = SmbFile(c.dirUrl, context(c)).use { d -> if (!d.exists()) d.mkdirs(); d.diskFreeSpace / (1024 * 1024) }

    /** Writes, reads back and deletes a small file. A short result for the UI (never the credentials). */
    fun test(c: SmbCfg, pass: String = password): String {
        if (!c.ok) return "Fill in the server and share first"
        return try {
            SmbFile(c.dirUrl, context(c, pass)).use { d ->
                if (!d.exists()) d.mkdirs()
                SmbFile(d, ".tvbro-test").use { f ->
                    f.openOutputStream().use { it.write("tvBro".toByteArray()) }
                    val back = f.openInputStream().use { it.readBytes().decodeToString() }
                    f.delete()
                    if (back != "tvBro") return "Wrote a test file but could not read it back"
                }
                "OK: ${c.label} is writable, ${d.diskFreeSpace / (1024 * 1024)} MB free"
            }
        } catch (e: Exception) { "Failed: " + describe(e) }
    }

    fun describe(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("LOGON_FAILURE", true) || m.contains("Logon failure", true) -> "wrong user name or password"
            m.contains("BAD_NETWORK_NAME", true) -> "share not found"
            m.contains("ACCESS_DENIED", true) || m.contains("Access is denied", true) -> "access denied"
            e is java.net.UnknownHostException || m.contains("unknown host", true) -> "server not found"
            m.contains("timed out", true) || e is java.net.SocketTimeoutException -> "server did not answer"
            else -> e.javaClass.simpleName
        }
    }

    /** Copies a finished local recording to the share and deletes the local copy; returns the smb:// address. */
    fun upload(c: SmbCfg, local: File): String {
        SmbFile(c.dirUrl, context(c)).use { d ->
            if (!d.exists()) d.mkdirs()
            SmbFile(d, local.name).use { f ->
                local.inputStream().use { i -> f.openOutputStream().use { o -> i.copyTo(o, 256 * 1024) } }
                if (f.length() != local.length()) throw IOException("size mismatch after copy")
                local.delete()
                return f.canonicalPath
            }
        }
    }

    fun delete(c: SmbCfg, url: String) { try { SmbFile(url, context(c)).use { it.delete() } } catch (e: Exception) { Diag.log("smb delete failed (${describe(e)})") } }

    fun length(c: SmbCfg, url: String): Long = SmbFile(url, context(c)).use { it.length() }
    fun openAt(c: SmbCfg, url: String): SmbRandomAccessFile = SmbRandomAccessFile(SmbFile(url, context(c)), "r")
}

/**
 * mpv cannot open smb:// itself (the bundled builds have no libsmbclient), so recordings on a share are served to it
 * over HTTP on the loopback interface only, with byte ranges for seeking. Each address carries a random token.
 */
object SmbBridge {
    private var server: HttpServer? = null
    private val token = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    @Volatile var cfg: () -> SmbCfg = { SmbCfg() }

    @Synchronized private fun start(): Int {
        server?.let { return it.address.port }
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 4)
        s.createContext("/$token/") { ex ->
            try {
                val url = java.net.URLDecoder.decode(ex.requestURI.rawPath.removePrefix("/$token/"), "UTF-8")
                if (!url.startsWith("smb://")) { ex.sendResponseHeaders(404, -1); return@createContext }
                val c = cfg()
                val len = SmbShare.length(c, url)
                val range = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.split('-')
                val from = range?.getOrNull(0)?.toLongOrNull() ?: 0L
                val to = range?.getOrNull(1)?.toLongOrNull()?.coerceAtMost(len - 1) ?: (len - 1)
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.responseHeaders.add("Content-Type", "video/mp2t")
                if (range != null) ex.responseHeaders.add("Content-Range", "bytes $from-$to/$len")
                val n = (to - from + 1).coerceAtLeast(0)
                ex.sendResponseHeaders(if (range != null) 206 else 200, if (n == 0L || ex.requestMethod == "HEAD") -1 else n)
                if (ex.requestMethod != "HEAD" && n > 0) SmbShare.openAt(c, url).use { raf ->
                    raf.seek(from)
                    val buf = ByteArray(256 * 1024); var left = n
                    ex.responseBody.use { out ->
                        while (left > 0) { val r = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) break; out.write(buf, 0, r); left -= r }
                    }
                }
            } catch (e: IOException) { // player closed the connection or the share went away
            } catch (e: Exception) { Diag.log("share playback failed (${SmbShare.describe(e)})"); try { ex.sendResponseHeaders(500, -1) } catch (x: Exception) { } }
            finally { ex.close() }
        }
        s.executor = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "smb-bridge").apply { isDaemon = true } }
        s.start(); server = s
        return s.address.port
    }

    /** Local http:// address mpv can play for an smb:// recording. */
    fun url(smbUrl: String): String = "http://127.0.0.1:${start()}/$token/" + URLEncoder.encode(smbUrl, "UTF-8").replace("+", "%20")
}

/** Where a new recording goes for the chosen storage, and what happens when it ends. */
object RecTarget {
    /** Folder the recorder writes into now (for a share: the local staging folder). */
    fun dir(s: Settings): File = when (s.storage) {
        "drive" -> if (s.drivePath.isNotBlank()) File(s.drivePath, "tvBro") else File(s.recDir.ifBlank { Paths.recordings.path })
        "smb" -> Paths.recStaging
        else -> File(s.recDir.ifBlank { Paths.recordings.path })
    }

    fun describe(s: Settings): String = when (s.storage) {
        "drive" -> "USB / removable drive: " + (if (s.drivePath.isBlank()) "none chosen (using the local folder)" else File(s.drivePath, "tvBro").path)
        "smb" -> "Network share: " + (if (s.smb.ok) s.smb.label else "not set up (using the local folder)")
        else -> "Local folder: " + s.recDir.ifBlank { Paths.recordings.path }
    }

    fun mb(n: Long): String = if (n >= 10_240) "%.1f GB".format(n / 1024.0) else "$n MB"

    fun freeText(s: Settings): String = try {
        when {
            s.storage == "smb" && s.smb.ok -> "${mb(SmbShare.freeMb(s.smb))} free on the share"
            else -> "${mb(Drives.freeMb(dir(s)))} free"
        }
    } catch (e: Exception) { "Free space unknown (${SmbShare.describe(e)})" }
}
