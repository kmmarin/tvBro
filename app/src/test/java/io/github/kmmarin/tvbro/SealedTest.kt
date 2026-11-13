// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import io.github.kmmarin.tvbro.data.Sealed
import io.github.kmmarin.tvbro.data.SealedDir
import io.github.kmmarin.tvbro.data.SealedInputStream
import io.github.kmmarin.tvbro.data.SealedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.random.Random

/** The sealed cache files (M1): AES-256-GCM chunks, tamper / cut / foreign key, and the plaintext migration. */
class SealedTest {
    private val key = ByteArray(32) { it.toByte() }
    private val other = ByteArray(32) { (it + 1).toByte() }
    private val login = "http://tv.example/live/someuser/somepass/1.ts"

    private fun tmpDir(): File = Files.createTempDirectory("sealed").toFile().also { it.deleteOnExit() }

    @Test fun roundTripSmallAndMultiChunk() {
        for (n in listOf(0, 1, 15, 4096, 3 * 1000 + 7)) {
            val b = Random(n).nextBytes(n)
            val o = ByteArrayOutputStream()
            SealedOutputStream(o, key, chunk = 1000).use { it.write(b) }
            assertArrayEquals(b, SealedInputStream(ByteArrayInputStream(o.toByteArray()), key).readBytes())
        }
        val big = Random(1).nextBytes(Sealed.CHUNK + 123)
        assertArrayEquals(big, Sealed.open(Sealed.seal(big, key), key))
    }

    @Test fun noPlaintextOnDisk() {
        val s = Sealed.seal("#EXTM3U\n#EXTINF:-1,One\n$login\n".toByteArray(), key)
        val text = String(s, Charsets.ISO_8859_1)
        assertFalse(text.contains("somepass")); assertFalse(text.contains("EXTM3U")); assertFalse(text.contains("tv.example"))
    }

    private fun expectFail(b: ByteArray, k: ByteArray = key) {
        try { SealedInputStream(ByteArrayInputStream(b), k).readBytes(); fail("read should fail") } catch (e: IOException) { }
    }

    @Test fun tamperCutReorderAndForeignKeyFail() {
        val o = ByteArrayOutputStream()
        SealedOutputStream(o, key, chunk = 100).use { it.write(Random(2).nextBytes(350)) }
        val b = o.toByteArray()
        expectFail(b.copyOf(b.size - 1))                                   // cut inside the last chunk
        expectFail(b.copyOf(Sealed.HEADER + 4 + 116))                     // last chunk dropped
        expectFail(b.clone().also { it[Sealed.HEADER + 10] = (it[Sealed.HEADER + 10].toInt() xor 1).toByte() }) // bit flip
        expectFail(b + byteArrayOf(0))                                     // data after the end
        val c1 = b.copyOfRange(Sealed.HEADER, Sealed.HEADER + 120); val c2 = b.copyOfRange(Sealed.HEADER + 120, Sealed.HEADER + 240)
        expectFail(b.copyOf(Sealed.HEADER) + c2 + c1 + b.copyOfRange(Sealed.HEADER + 240, b.size)) // chunks swapped
        expectFail(b, other)                                               // another key
        expectFail("#EXTM3U\n".toByteArray())                              // plaintext
    }

    @Test fun sealedDirTreatsForeignOrDamagedFilesAsMissing() {
        val d = tmpDir()
        val sd = SealedDir(d) { key }
        val f = File(d, "a.m3u")
        sd.writeText(f, "#EXTM3U\n$login\n")
        assertTrue(sd.exists(f))
        assertTrue(sd.readText(f).contains(login))
        assertTrue(sd.head(f, 7) == "#EXTM3U")
        val foreign = SealedDir(d) { other }
        assertFalse(foreign.exists(f)) // sealed with another key: a cache miss, and the file is removed
        assertFalse(f.exists())
    }

    @Test fun migrationSealsOldPlaintextFilesAndDropsTemps() {
        val d = tmpDir()
        val plain = "#EXTM3U\n#EXTINF:-1,One\n$login\n"
        File(d, "src1.m3u").writeText(plain)
        File(d, "src1.epgurl").writeText("http://tv.example/xmltv.php?username=someuser&password=somepass")
        File(d, "src1.streams.json.tmp").writeText("[partial")
        val sd = SealedDir(d) { key }
        File(d, "kept.json").also { sd.writeText(it, "[1]") }
        File(d, "foreign.json").also { SealedDir(d) { other }.writeText(it, "[2]") }

        val (sealed, deleted) = sd.migrate()
        assertEquals(2, sealed)
        assertEquals(2, deleted) // the temp file and the file sealed with another key
        assertEquals(setOf("src1.m3u", "src1.epgurl", "kept.json"), d.list()!!.toSet())
        for (f in d.listFiles()!!) {
            assertTrue(f.name, Sealed.isSealedWith(f, key))
            assertFalse(f.name, String(f.readBytes(), Charsets.ISO_8859_1).contains("somepass"))
        }
        assertEquals(plain, sd.readText(File(d, "src1.m3u")))
        assertEquals("[1]", sd.readText(File(d, "kept.json")))
        assertEquals(0 to 0, sd.migrate()) // a second run changes nothing
    }
}
