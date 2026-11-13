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

    private fun context(c: SmbConfig): CIFSContext {
        val p = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.responseTimeout", "20000")
            setProperty("jcifs.smb.client.connTimeout", "8000")
            setProperty("jcifs.resolveOrder", "DNS,BCAST")
        }
        val base = BaseContext(PropertyConfiguration(p))
        return if (c.user.isBlank()) base.withGuestCrendentials() else base.withCredentials(NtlmPasswordAuthenticator(c.domain.ifBlank { null }, c.user, c.pass))
    }

    /** The share's free space in MB; throws when it cannot be reached. */
    fun freeMb(c: SmbConfig): Long = SmbFile(c.dirUrl, context(c)).use { d -> if (!d.exists()) d.mkdirs(); d.diskFreeSpace / (1024 * 1024) }

    /** Writes, reads back and deletes a small file. Returns a short result for the UI (never the credentials). */
    fun test(c: SmbConfig): String {
        if (!c.ok) return "Fill in the server and share first"
        return try {
            val ctx = context(c)
            SmbFile(c.dirUrl, ctx).use { d ->
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
        val m = (e.message ?: e.javaClass.simpleName)
        return when {
            m.contains("LOGON_FAILURE", true) || m.contains("Logon failure", true) -> "wrong user name or password"
            m.contains("BAD_NETWORK_NAME", true) -> "share not found"
            m.contains("ACCESS_DENIED", true) || m.contains("Access is denied", true) -> "access denied"
            e is java.net.UnknownHostException || m.contains("unknown host", true) -> "server not found"
            m.contains("timed out", true) || e is java.net.SocketTimeoutException -> "server did not answer"
            else -> e.javaClass.simpleName
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
