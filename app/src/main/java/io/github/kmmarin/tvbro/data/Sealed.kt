// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chunked AES-256-GCM for the files in filesDir/cache (playlists, Xtream lists and catalogues, details, guides hold
 * stream addresses with the provider login). Same layout as the desktop's cache files:
 * "TVS1" | key id (8) | nonce prefix (8) | chunks. A chunk is a 4-byte length (high bit = last chunk) and the
 * ciphertext with its tag; the IV is the nonce prefix + the chunk counter, the AAD the header + counter + last flag,
 * so chunks cannot be reordered, dropped or cut off without the read failing.
 * The 32-byte key comes from [CacheKey] (wrapped by an Android Keystore key); this part is plain JVM and unit-tested.
 */
object Sealed {
    private val MAGIC = "TVS1".toByteArray()
    const val HEADER = 20
    const val CHUNK = 1 shl 20
    private val rnd = SecureRandom()

    /** Identifies the key a file was written with (a lost key = a cache miss, not a parse error). */
    fun keyId(key: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest("tvbro-cache-key".toByteArray() + key).copyOf(8)

    internal fun header(key: ByteArray): ByteArray = MAGIC + keyId(key) + ByteArray(8).also { rnd.nextBytes(it) }

    /** True when [f] starts like a sealed file (any key). */
    fun looksSealed(f: File): Boolean = try {
        f.inputStream().use { i -> val h = ByteArray(4); DataInputStream(i).readFully(h); h.contentEquals(MAGIC) }
    } catch (e: Exception) { false }

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

    /**
     * Rewrites a plaintext file of an older version sealed in place (temp file + rename, so a failure leaves the old
     * file); the plaintext is gone afterwards. Returns false when [f] could not be converted (then it is deleted).
     */
    fun sealInPlace(f: File, key: ByteArray): Boolean {
        val tmp = File(f.path + ".seal")
        return try {
            f.inputStream().use { i -> SealedOutputStream(tmp.outputStream(), key).use { o -> i.copyTo(o, 64 * 1024) } }
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw IOException("rename failed") }
            true
        } catch (e: Exception) { tmp.delete(); f.delete(); false }
    }
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
        try { inp.readFully(it) } catch (e: EOFException) { throw SafeIOException("not a sealed file") }
        if (!it.copyOf(4).contentEquals("TVS1".toByteArray())) throw SafeIOException("not a sealed file")
        if (!it.copyOfRange(4, 12).contentEquals(Sealed.keyId(key))) throw SafeIOException("sealed with another key")
    }
    private var buf = ByteArray(0)
    private var pos = 0
    private var n = 0
    private var done = false

    private fun next(): Boolean {
        if (done) return false
        val h = try { inp.readInt() } catch (e: EOFException) { throw SafeIOException("sealed file is cut off") }
        val last = h < 0
        val size = h and Int.MAX_VALUE
        if (size < 16 || size > Sealed.CHUNK + 16) throw SafeIOException("bad sealed chunk")
        val ct = ByteArray(size)
        try { inp.readFully(ct) } catch (e: EOFException) { throw SafeIOException("sealed file is cut off") }
        buf = try { Sealed.cipher(Cipher.DECRYPT_MODE, key, header, n++, last).doFinal(ct) } catch (e: Exception) { throw SafeIOException("sealed chunk failed its check") }
        pos = 0
        done = last
        if (last && inp.read() != -1) throw SafeIOException("data after the last sealed chunk")
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

/**
 * Files of one folder sealed under one key: what the cache code reads and writes. A file written with another key
 * (Keystore key lost, data moved to another device) or damaged reads as missing, and is deleted.
 */
class SealedDir(val dir: File, private val key: () -> ByteArray) {
    fun file(name: String) = File(dir, name)

    /** The file exists and opens with the current key. */
    fun exists(f: File): Boolean {
        if (!f.exists()) return false
        if (Sealed.isSealedWith(f, key())) return true
        f.delete(); return false
    }

    fun input(f: File): InputStream { val k = key(); return SealedInputStream(f.inputStream().buffered(64 * 1024), k) }
    fun output(f: File): OutputStream { val k = key(); return SealedOutputStream(f.outputStream(), k) }
    fun readText(f: File): String = input(f).use { String(it.readBytes(), Charsets.UTF_8) }
    fun writeText(f: File, s: String) { output(f).use { it.write(s.toByteArray(Charsets.UTF_8)) } }
    fun head(f: File, n: Int = 4096): String = try { input(f).use { val b = ByteArray(n); var k = 0; while (k < n) { val r = it.read(b, k, n - k); if (r < 0) break; k += r }; String(b, 0, k, Charsets.UTF_8) } } catch (e: IOException) { "" }

    /**
     * One-time migration from the plaintext files of older versions: every file of the folder that is not sealed
     * with the current key is sealed in place (kept data, no download needed); temp files and files sealed with
     * another key are deleted. Returns (sealed, deleted).
     */
    fun migrate(): Pair<Int, Int> {
        var sealed = 0; var deleted = 0
        val k = key()
        for (f in dir.listFiles() ?: emptyArray()) {
            if (!f.isFile || Sealed.isSealedWith(f, k)) continue
            if (f.name.endsWith(".tmp") || f.name.endsWith(".seal") || Sealed.looksSealed(f)) { f.delete(); deleted++; continue }
            if (Sealed.sealInPlace(f, k)) sealed++ else deleted++
        }
        return sealed to deleted
    }
}
