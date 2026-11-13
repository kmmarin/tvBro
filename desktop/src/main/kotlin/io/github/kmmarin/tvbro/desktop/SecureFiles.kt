// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Owner-only files on Linux / the Pi: tvBro's folders are created 0700 and its files 0600, so other local accounts
 * cannot read them. On Windows the per-user profile folders are already private and nothing changes.
 */
object Perms {
    val posix: Boolean = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
    val DIR: Set<PosixFilePermission> = PosixFilePermissions.fromString("rwx------")
    val FILE: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")

    /** Creates [f] (and missing parents) owner-only and tightens it when it already exists. */
    fun dir(f: File): File {
        if (!posix) { f.mkdirs(); return f }
        if (!f.isDirectory) try { Files.createDirectories(f.toPath(), PosixFilePermissions.asFileAttribute(DIR)) } catch (e: Exception) { f.mkdirs() }
        chmod(f, DIR)
        return f
    }

    fun file(f: File) { if (posix && f.exists()) chmod(f, FILE) }

    /** Folders 0700 and files 0600 below [root] (files written by older versions, or by SQLite / mpv with the umask). */
    fun tighten(root: File) {
        if (!posix || !root.isDirectory) return
        try {
            Files.walk(root.toPath()).use { s ->
                s.forEach { p -> if (!Files.isSymbolicLink(p)) chmod(p.toFile(), if (Files.isDirectory(p)) DIR else FILE) }
            }
        } catch (e: Exception) { }
    }

    private fun chmod(f: File, p: Set<PosixFilePermission>) { try { Files.setPosixFilePermissions(f.toPath(), p) } catch (e: Exception) { } }

    /** A new file that is owner-only from the first byte (no window with the umask's 0644); an old one is replaced. */
    fun newOutput(f: File): OutputStream {
        val p = f.toPath()
        Files.deleteIfExists(p)
        val opts = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        return if (posix) Channels.newOutputStream(Files.newByteChannel(p, opts, PosixFilePermissions.asFileAttribute(FILE)))
        else Files.newOutputStream(p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }

    /** Creates [f] owner-only with [b], failing when it exists (key files: never overwritten). */
    fun createNew(f: File, b: ByteArray) {
        val opts = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        val ch = if (posix) Files.newByteChannel(f.toPath(), opts, PosixFilePermissions.asFileAttribute(FILE)) else Files.newByteChannel(f.toPath(), opts)
        ch.use { it.write(ByteBuffer.wrap(b)) }
    }

    /** Moves [tmp] over [f] atomically where the file system can. */
    fun replace(tmp: File, f: File) {
        try { Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (e: AtomicMoveNotSupportedException) { Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
}

/** Writes [b] to a new owner-only temp file and moves it over [f]. */
fun atomicWrite(f: File, b: ByteArray) {
    val t = File(f.parentFile, f.name + ".tmp")
    Perms.newOutput(t).use { it.write(b) }
    Perms.replace(t, f)
}

/**
 * Chunked AES-256-GCM for cache files (playlists and catalogues hold stream addresses with the provider login).
 * Layout: "TVS1" | key id (8) | nonce prefix (8) | chunks. A chunk is a 4-byte length (high bit = last chunk) and the
 * ciphertext with its tag; the IV is the nonce prefix + the chunk counter, the AAD the header + counter + last flag,
 * so chunks cannot be reordered, dropped or cut off without the read failing.
 */
object Sealed {
    private val MAGIC = "TVS1".toByteArray()
    const val HEADER = 20
    const val CHUNK = 1 shl 20
    private val rnd = SecureRandom()

    /** Identifies the key a file was written with (a lost key = a cache miss, not a parse error). */
    fun keyId(key: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest("tvbro-cache-key".toByteArray() + key).copyOf(8)

    internal fun header(key: ByteArray): ByteArray = MAGIC + keyId(key) + ByteArray(8).also { rnd.nextBytes(it) }

    /** [f] is a sealed file written with [key]. */
    fun isSealedWith(f: File, key: ByteArray): Boolean = try {
        f.inputStream().use { i -> val h = ByteArray(HEADER); DataInputStream(i).readFully(h); h.copyOf(4).contentEquals(MAGIC) && h.copyOfRange(4, 12).contentEquals(keyId(key)) }
    } catch (e: Exception) { false }

    internal fun cipher(mode: Int, key: ByteArray, header: ByteArray, n: Int, last: Boolean): Cipher {
        val iv = ByteBuffer.allocate(12).put(header, 12, 8).putInt(n).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            updateAAD(ByteBuffer.allocate(HEADER + 5).put(header).putInt(n).put(if (last) 1 else 0).array())
        }
    }

    fun seal(b: ByteArray, key: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> SealedOutputStream(o, key).use { it.write(b) } }.toByteArray()
    fun open(b: ByteArray, key: ByteArray): ByteArray = SealedInputStream(ByteArrayInputStream(b), key).use { it.readBytes() }
}

class SealedOutputStream(private val out: OutputStream, private val key: ByteArray, private val chunk: Int = Sealed.CHUNK) : OutputStream() {
    init { require(key.size == 32 && chunk in 1..Sealed.CHUNK) }
    private val header = Sealed.header(key).also { out.write(it) }
    private val buf = ByteArray(chunk)
    private var len = 0
    private var n = 0
    private var closed = false

    private fun flushChunk(last: Boolean) {
        val ct = Sealed.cipher(Cipher.ENCRYPT_MODE, key, header, n++, last).doFinal(buf, 0, len)
        out.write(ByteBuffer.allocate(4).putInt(ct.size or (if (last) Int.MIN_VALUE else 0)).array())
        out.write(ct)
        len = 0
    }

    override fun write(b: Int) { if (len == chunk) flushChunk(false); buf[len++] = b.toByte() }
    override fun write(b: ByteArray, off: Int, l: Int) {
        var o = off; var r = l
        while (r > 0) {
            if (len == chunk) flushChunk(false)
            val k = minOf(r, chunk - len)
            System.arraycopy(b, o, buf, len, k); len += k; o += k; r -= k
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try { flushChunk(true) } finally { out.close() }
    }
}

class SealedInputStream(input: InputStream, private val key: ByteArray) : InputStream() {
    private val inp = DataInputStream(input)
    private val header = ByteArray(Sealed.HEADER).also {
        try { inp.readFully(it) } catch (e: EOFException) { throw IOException("not a sealed file") }
        if (!it.copyOf(4).contentEquals("TVS1".toByteArray())) throw IOException("not a sealed file")
        if (!it.copyOfRange(4, 12).contentEquals(Sealed.keyId(key))) throw IOException("sealed with another key")
    }
    private var buf = ByteArray(0)
    private var pos = 0
    private var n = 0
    private var done = false

    private fun next(): Boolean {
        if (done) return false
        val h = try { inp.readInt() } catch (e: EOFException) { throw IOException("sealed file is cut off") }
        val last = h < 0
        val size = h and Int.MAX_VALUE
        if (size < 16 || size > Sealed.CHUNK + 16) throw IOException("bad sealed chunk")
        val ct = ByteArray(size)
        try { inp.readFully(ct) } catch (e: EOFException) { throw IOException("sealed file is cut off") }
        buf = try { Sealed.cipher(Cipher.DECRYPT_MODE, key, header, n++, last).doFinal(ct) } catch (e: Exception) { throw IOException("sealed chunk failed its check") }
        pos = 0
        done = last
        if (last && inp.read() != -1) throw IOException("data after the last sealed chunk")
        return true
    }

    override fun read(): Int {
        while (pos == buf.size) if (!next()) return -1
        return buf[pos++].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (pos == buf.size) if (!next()) return -1
        val k = minOf(len, buf.size - pos)
        System.arraycopy(buf, pos, b, off, k); pos += k
        return k
    }

    override fun available(): Int = buf.size - pos
    override fun close() = inp.close()
}

/** `secret-tool lookup` outcome: only "not found" may create a new key; any other failure must not. */
enum class KeyringLookup { FOUND, NOT_FOUND, ERROR }

object Keyring {
    /**
     * secret-tool exits 1 with no output when nothing is stored; a locked or unreachable keyring also exits non-zero
     * but prints its error on stderr. A found secret must be a 32-byte Base64 key.
     */
    fun classify(exit: Int, out: String, err: String): KeyringLookup = when {
        exit == 0 && out.trim().isNotEmpty() -> if (decode(out) != null) KeyringLookup.FOUND else KeyringLookup.ERROR
        exit == 1 && out.isBlank() && err.isBlank() -> KeyringLookup.NOT_FOUND
        else -> KeyringLookup.ERROR
    }

    fun decode(out: String): ByteArray? = try { java.util.Base64.getDecoder().decode(out.trim()).takeIf { it.size == 32 } } catch (e: Exception) { null }
}

/** The AES key in the config folder (Linux without a keyring): created once, owner-only, never replaced. */
object KeyFile {
    fun loadOrCreate(f: File, rnd: SecureRandom = SecureRandom()): ByteArray {
        if (f.exists()) return f.readBytes().also { if (it.size != 32) throw IOException("${f.name} is damaged (${it.size} bytes)"); Perms.file(f) }
        val k = ByteArray(32).also { rnd.nextBytes(it) }
        try { Perms.createNew(f, k) } catch (e: java.nio.file.FileAlreadyExistsException) { return loadOrCreate(f, rnd) } // another instance won the race
        return k
    }
}
