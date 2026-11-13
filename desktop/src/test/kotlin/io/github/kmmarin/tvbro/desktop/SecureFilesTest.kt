// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import io.github.kmmarin.tvbro.data.Source
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

/** H6 (encrypted cache, owner-only files) and L10 (key file, keyring errors, no silent overwrite). */
class SecureFilesTest {
    private fun tmp() = Files.createTempDirectory("tvbro-sec").toFile()
    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val other = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private fun mode(f: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath()))

    // ---- sealed (encrypted) streams ----
    @Test fun sealedRoundTripsAnySize() {
        for (n in listOf(0, 1, 15, 16, 1000, 70_000)) {
            val b = ByteArray(n) { (it * 31).toByte() }
            val out = ByteArrayOutputStream()
            SealedOutputStream(out, key, chunk = 4096).use { it.write(b) }
            assertArrayEquals(b, SealedInputStream(out.toByteArray().inputStream(), key).readBytes())
            assertArrayEquals(b, Sealed.open(Sealed.seal(b, key), key))
        }
    }

    @Test fun sealedHidesThePlaintext() {
        val b = "#EXTINF:-1,News\nhttp://tv.example/live/alice/s3cret/1.ts\n".repeat(50).toByteArray()
        val s = Sealed.seal(b, key)
        assertFalse(String(s, Charsets.ISO_8859_1).contains("s3cret"))
        assertFalse(s.contentEquals(Sealed.seal(b, key))) // fresh nonce every time
    }

    @Test fun tamperedCutOrForeignFilesFailTheirCheck() {
        val out = ByteArrayOutputStream()
        SealedOutputStream(out, key, chunk = 100).use { it.write(ByteArray(1000) { it.toByte() }) }
        val good = out.toByteArray()
        val flipped = good.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        assertThrows(IOException::class.java) { SealedInputStream(flipped.inputStream(), key).readBytes() }
        // dropping the last chunk (cut at a chunk boundary) is noticed: the last-chunk flag is authenticated
        val oneChunk = 4 + 100 + 16
        assertEquals(Sealed.HEADER + 10 * oneChunk, good.size) // 9 full chunks + the last (full) one
        val cut = good.copyOf(good.size - oneChunk)
        assertThrows(IOException::class.java) { SealedInputStream(cut.inputStream(), key).readBytes() }
        assertThrows(IOException::class.java) { SealedInputStream(good.copyOf(good.size - 1).inputStream(), key).readBytes() }
        assertThrows(IOException::class.java) { SealedInputStream(good.inputStream(), other).readBytes() }
        assertThrows(IOException::class.java) { SealedInputStream("plain text".byteInputStream(), key).readBytes() }
        assertThrows(IOException::class.java) { SealedInputStream((good + byteArrayOf(1)).inputStream(), key).readBytes() }
    }

    // ---- the playlist cache ----
    @Test fun cacheFilesAreEncryptedAndOldPlainOnesGo() {
        val root = tmp()
        val src = Source("s1", "P", "m3u", url = "x")
        val dir = File(root, Cache.hash(src.id)).apply { mkdirs() }
        val old = File(dir, "m3u.gz").apply { writeText("plaintext of an older version") }
        val c = Cache(src, { key }, root)
        assertFalse(old.exists())
        val m3u = File(root, "list.m3u").apply { writeText("#EXTM3U\n#EXTINF:-1,One\nhttp://tv.example/live/alice/s3cret/1.ts\n") }
        c.download("m3u", m3u.path)
        val stored = c.file("m3u")
        assertTrue(stored.exists())
        assertFalse(String(stored.readBytes(), Charsets.ISO_8859_1).contains("s3cret"))
        assertEquals(m3u.readText(), c.open("m3u")!!.bufferedReader().readText())
        assertTrue(c.ageHours("m3u") < 1)
        assertFalse(File(stored.parentFile, stored.name + ".part").exists())
        // another key (lost keyring): a cache miss, so it is downloaded again rather than failing to parse
        val c2 = Cache(src, { other }, root)
        assertFalse(c2.has("m3u")); assertNull(c2.open("m3u")); assertEquals(Double.MAX_VALUE, c2.ageHours("m3u"), 0.0)
    }

    // ---- files and folders ----
    @Test fun atomicWriteReplacesContent() {
        val f = File(tmp(), "a.json")
        atomicWrite(f, "one".toByteArray()); atomicWrite(f, "two".toByteArray())
        assertEquals("two", f.readText())
        assertFalse(File(f.parentFile, "a.json.tmp").exists())
    }

    @Test fun ownerOnlyFoldersAndFilesOnLinux() {
        assumeTrue("POSIX file system", Perms.posix)
        val root = tmp()
        val d = Perms.dir(File(root, "config/tvbro"))
        assertEquals("rwx------", mode(d))
        val f = File(d, "settings.json"); atomicWrite(f, "{}".toByteArray())
        assertEquals("rw-------", mode(f))
        Perms.newOutput(File(d, "x.sgz")).use { it.write(1) }
        assertEquals("rw-------", mode(File(d, "x.sgz")))
        // files and folders of older versions are tightened
        val loose = File(d, "recordings.json").apply { writeText("[]") }
        Files.setPosixFilePermissions(loose.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
        val sub = File(d, "img").apply { mkdirs() }
        Files.setPosixFilePermissions(sub.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        Perms.tighten(d)
        assertEquals("rw-------", mode(loose)); assertEquals("rwx------", mode(sub))
        // an existing world-readable folder is tightened by dir() too
        Files.setPosixFilePermissions(d.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        Perms.dir(d); assertEquals("rwx------", mode(d))
    }

    // ---- key file (Linux without a keyring) ----
    @Test fun keyFileIsCreatedOnceOwnerOnlyAndNeverReplaced() {
        val f = File(tmp(), "key.bin")
        val k = KeyFile.loadOrCreate(f)
        assertEquals(32, k.size)
        assertArrayEquals(k, KeyFile.loadOrCreate(f))
        if (Perms.posix) assertEquals("rw-------", mode(f))
        f.writeBytes(ByteArray(5)) // damaged: an error, not a new key that would orphan every secret
        assertThrows(IOException::class.java) { KeyFile.loadOrCreate(f) }
        assertEquals(5L, f.length())
    }

    @Test fun createNewNeverOverwrites() {
        val f = File(tmp(), "k").apply { writeText("old") }
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) { Perms.createNew(f, byteArrayOf(1)) }
        assertEquals("old", f.readText())
    }

    @Test fun keyringLookupOnlyNotFoundMayCreateAKey() {
        val k = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        assertEquals(KeyringLookup.FOUND, Keyring.classify(0, "$k\n", ""))
        assertEquals(KeyringLookup.NOT_FOUND, Keyring.classify(1, "", ""))
        assertEquals(KeyringLookup.ERROR, Keyring.classify(1, "", "secret-tool: Cannot autolaunch D-Bus without X11 \$DISPLAY"))
        assertEquals(KeyringLookup.ERROR, Keyring.classify(1, "", "secret-tool: The collection is locked"))
        assertEquals(KeyringLookup.ERROR, Keyring.classify(-1, "", "IOException"))
        assertEquals(KeyringLookup.ERROR, Keyring.classify(0, "not base64!", ""))
        assertEquals(KeyringLookup.ERROR, Keyring.classify(0, java.util.Base64.getEncoder().encodeToString(ByteArray(8)), ""))
    }

    // ---- secrets that can no longer be decrypted are kept, not overwritten ----
    private fun box(dir: File, k: Byte, keyUp: () -> Boolean = { true }) = SecretBox(dir,
        { b -> if (!keyUp()) throw IOException("keyring not available"); byteArrayOf(k) + b },
        { b -> if (b[0] != k) throw IOException("wrong key"); b.copyOfRange(1, b.size) })

    @Test fun unreadableSecretIsKeptAsBakBeforeOverwrite() {
        val d = tmp()
        box(d, 1).write("sources", "old list".toByteArray())
        val now = box(d, 2) // a different key: the old file is unreadable
        val bak = now.write("sources", "new list".toByteArray())
        assertNotNull(bak)
        assertEquals("sources.bin.bak", bak!!.name)
        val kept = File(d, "sources.bin.bak").readBytes()
        assertEquals(1.toByte(), kept[0]); assertEquals("old list", String(kept.copyOfRange(1, kept.size)))
        assertEquals("new list", String(now.read("sources")!!))
        // a second unreadable one does not replace the first backup
        box(d, 3).write("sources", "third".toByteArray())
        assertTrue(File(d, "sources.bin.bak").exists())
        assertEquals(2, d.listFiles()!!.count { it.name.endsWith(".bak") })
    }

    @Test fun readableSecretIsReplacedWithoutBackup() {
        val d = tmp(); val b = box(d, 1)
        b.write("smb", "a".toByteArray())
        assertNull(b.write("smb", "b".toByteArray()))
        assertEquals("b", String(b.read("smb")!!))
        assertTrue(d.listFiles()!!.none { it.name.endsWith(".bak") })
    }

    @Test fun keyUnavailableChangesNothing() {
        val d = tmp()
        box(d, 1).write("sources", "kept".toByteArray())
        var up = false
        assertThrows(IOException::class.java) { box(d, 1) { up }.write("sources", "lost?".toByteArray()) }
        assertEquals("kept", String(box(d, 1).read("sources")!!))
        assertTrue(d.listFiles()!!.none { it.name.endsWith(".bak") })
    }
}
