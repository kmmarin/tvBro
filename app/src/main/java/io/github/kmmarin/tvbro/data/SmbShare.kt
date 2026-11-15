// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.kmmarin.tvbro.data

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Properties

/** Network share (SMB/CIFS) for recordings. The password is kept in encrypted storage and never logged. */
@Serializable
data class SmbConfig(val host: String = "", val share: String = "", val path: String = "tvBro", val user: String = "", val pass: String = "", val domain: String = "") {
    val ok: Boolean get() = host.isNotBlank() && share.isNotBlank()
    /** Display form without the user name or password. */
    val label: String get() = "\\\\$host\\$share" + (if (path.isNotBlank()) "\\" + path.trim('/', '\\').replace('/', '\\') else "")
    /** smb://host/share/path/ (no credentials in the address). */
    val dirUrl: String get() = "smb://${host.trim()}/${share.trim('/', '\\')}/" + path.trim('/', '\\').replace('\\', '/').let { if (it.isEmpty()) "" else "$it/" }
}

object SmbShare {
    const val STORAGE_KEY = "smb"

    private fun context(c: SmbConfig, connMs: Int = 8000, responseMs: Int = 20000): CIFSContext {
        val p = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.responseTimeout", responseMs.toString())
            setProperty("jcifs.smb.client.connTimeout", connMs.toString())
            setProperty("jcifs.resolveOrder", "DNS,BCAST")
        }
        val base = BaseContext(PropertyConfiguration(p))
        return if (c.user.isBlank()) base.withGuestCrendentials() else base.withCredentials(NtlmPasswordAuthenticator(c.domain.ifBlank { null }, c.user, c.pass))
    }

    /** The share's free space in MB; throws when it cannot be reached. */
    fun freeMb(c: SmbConfig): Long = SmbFile(c.dirUrl, context(c)).use { d -> if (!d.exists()) d.mkdirs(); d.diskFreeSpace / (1024 * 1024) }

    /** Result of [check]: [text] is for the screen and never holds the credentials. */
    data class Check(val ok: Boolean, val text: String, val freeBytes: Long = -1)

    /**
     * Connects with [c] (unsaved values are fine), creates the folder when missing, writes, reads back and deletes a
     * small file. Short timeouts (about 4 s to reach the server, 8 s per answer): call it off the main thread.
     */
    fun check(c: SmbConfig, port: Int = 445): Check {
        if (!c.ok) return Check(false, "Fill in the server and share first")
        return try {
            // a plain TCP connect first: a wrong address or a server without file sharing fails fast and clearly
            java.net.Socket().use { s -> s.connect(java.net.InetSocketAddress(c.host.trim(), port), 3500) }
            SmbFile(c.dirUrl, context(c, 4000, 8000)).use { d ->
                if (!d.exists()) d.mkdirs()
                SmbFile(d, ".tvbro-test").use { f ->
                    f.openOutputStream().use { it.write("tvBro".toByteArray()) }
                    val back = f.openInputStream().use { it.readBytes().decodeToString() }
                    f.delete()
                    if (back != "tvBro") return Check(false, "Connected, but a test file could not be read back")
                }
                val free = d.diskFreeSpace
                Check(true, "Connected - ${size(free)} free", free)
            }
        } catch (e: Exception) { Check(false, "Could not connect: " + explain(e)) }
    }

    /** Settings > Test storage: the same check as the setup screen's Test button. */
    fun test(c: SmbConfig): String = check(c).let { if (it.ok) "OK: ${c.label} is writable, ${size(it.freeBytes)} free" else it.text }

    /** 1288490188800 -> "1.2 TB" (binary units, like Windows). */
    fun size(bytes: Long): String {
        val u = listOf("bytes", "KB", "MB", "GB", "TB", "PB")
        var v = bytes.toDouble(); var i = 0
        while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
        return when { i == 0 -> "$bytes bytes"; v >= 100 -> String.format(java.util.Locale.US, "%.0f %s", v, u[i]); else -> String.format(java.util.Locale.US, "%.1f %s", v, u[i]) }
    }

    /** A short plain-language reason (the exception and its causes: jcifs wraps the network errors). */
    fun describe(e: Exception): String {
        var t: Throwable? = e
        var n = 0
        while (t != null && n++ < 6) {
            val m = t.message ?: ""
            when {
                m.contains("LOGON_FAILURE", true) || m.contains("Logon failure", true) -> return "wrong user name or password"
                m.contains("BAD_NETWORK_NAME", true) -> return "share not found"
                m.contains("ACCESS_DENIED", true) || m.contains("Access is denied", true) -> return "access denied"
                m.contains("ACCOUNT_DISABLED", true) || m.contains("ACCOUNT_LOCKED", true) -> return "user account disabled or locked"
                m.contains("PASSWORD_EXPIRED", true) -> return "password expired"
                m.contains("OBJECT_PATH_NOT_FOUND", true) -> return "folder could not be created"
                t is java.net.UnknownHostException || m.contains("unknown host", true) -> return "server not found"
                t is java.net.ConnectException && m.contains("refused", true) -> return "connection refused"
                t is java.net.NoRouteToHostException || m.contains("unreachable", true) -> return "server not reachable"
                t is java.net.SocketTimeoutException || m.contains("timed out", true) || m.contains("timeout", true) -> return "server did not answer"
            }
            t = t.cause
        }
        return e.javaClass.simpleName
    }

    /** [describe] plus what to check, for the setup screen. */
    fun explain(e: Exception): String = describe(e).let { r ->
        r + when (r) {
            "wrong user name or password" -> ". Check both (leave the user empty for guest access)."
            "share not found" -> ". Check the share name (as shown under \\\\server)."
            "access denied" -> ". This user may not write to that share or folder."
            "server not found" -> ". Check the server name, or use its IP address."
            "connection refused" -> ". Is file sharing (SMB) turned on at that address?"
            "server not reachable", "server did not answer" -> ". Check the address and that the server is on."
            else -> "."
        }
    }

    /** Copies a finished local recording to the share; returns the smb:// address. The local file is deleted after. */
    fun upload(c: SmbConfig, local: File, onBytes: (Long) -> Unit = {}): String {
        val ctx = context(c)
        SmbFile(c.dirUrl, ctx).use { d ->
            if (!d.exists()) d.mkdirs()
            SmbFile(d, local.name).use { f ->
                local.inputStream().use { i ->
                    f.openOutputStream().use { o ->
                        val buf = ByteArray(256 * 1024); var total = 0L
                        while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); total += n; onBytes(total) }
                    }
                }
                if (f.length() != local.length()) throw java.io.IOException("size mismatch after copy")
                local.delete()
                return f.canonicalPath
            }
        }
    }

    fun delete(c: SmbConfig, url: String) { try { SmbFile(url, context(c)).use { it.delete() } } catch (e: Exception) { Diag.log("smb delete failed (${describe(e)})") } }

    /** ExoPlayer source for smb:// recordings (seekable). */
    class Source(private val cfg: () -> SmbConfig) : BaseDataSource(false) {
        private var raf: SmbRandomAccessFile? = null
        private var file: SmbFile? = null
        private var uri: Uri? = null
        private var left = 0L

        override fun open(spec: DataSpec): Long {
            uri = spec.uri
            transferInitializing(spec)
            val f = SmbFile(spec.uri.toString(), context(cfg())); file = f
            val r = SmbRandomAccessFile(f, "r"); raf = r
            r.seek(spec.position)
            left = if (spec.length != C.LENGTH_UNSET.toLong()) spec.length else f.length() - spec.position
            transferStarted(spec)
            return left
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (left == 0L) return C.RESULT_END_OF_INPUT
            val n = raf!!.read(buffer, offset, minOf(length.toLong(), left).toInt())
            if (n < 0) return C.RESULT_END_OF_INPUT
            left -= n; bytesTransferred(n)
            return n
        }

        override fun getUri(): Uri? = uri
        override fun close() {
            try { raf?.close(); file?.close() } finally { raf = null; file = null; if (uri != null) { uri = null; transferEnded() } }
        }
    }

    /** Data source factory: smb:// through [Source], everything else through [other]. */
    class Factory(private val other: DataSource.Factory, private val cfg: () -> SmbConfig) : DataSource.Factory {
        override fun createDataSource(): DataSource = Switch(other.createDataSource(), cfg)
    }

    private class Switch(private val other: DataSource, private val cfg: () -> SmbConfig) : DataSource {
        private var cur: DataSource = other
        private val listeners = ArrayList<androidx.media3.datasource.TransferListener>()
        override fun addTransferListener(l: androidx.media3.datasource.TransferListener) { listeners += l; other.addTransferListener(l) }
        override fun open(spec: DataSpec): Long {
            cur = if (spec.uri.scheme == "smb") Source(cfg).also { s -> listeners.forEach { s.addTransferListener(it) } } else other
            return cur.open(spec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = cur.read(buffer, offset, length)
        override fun getUri(): Uri? = cur.uri
        override fun getResponseHeaders(): Map<String, List<String>> = cur.responseHeaders
        override fun close() = cur.close()
    }
}
