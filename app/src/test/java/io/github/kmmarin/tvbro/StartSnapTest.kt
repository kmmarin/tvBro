// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.kmmarin.tvbro.data.Catchup
import io.github.kmmarin.tvbro.data.Channel
import io.github.kmmarin.tvbro.data.Programme
import io.github.kmmarin.tvbro.data.Sealed
import io.github.kmmarin.tvbro.data.StartSnap
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files

/** The start-up snapshot holds stream addresses with the provider login: sealed on disk, unreadable without the key. */
class StartSnapTest {
    private val key = ByteArray(32) { it.toByte() }
    private val login = "http://provider.example:8080/live/someuser/s3cretpass/101.ts"

    private fun snap(): StartSnap {
        val c = Channel("s|101", "s", "One HD", "http://provider.example/logo.png", "News", login, "one.example", 1, Catchup("xc", "http://provider.example/timeshift/someuser/s3cretpass/{duration}/{start}/101.ts"), 3)
        return StartSnap(1_700_000_000_000L, "LIST", "News", listOf("News", "Sport"), listOf(c), 0, 0, 12, c.key, c,
            mapOf(c.key to arrayOf(Programme("s|one.example", 100, 200, "Morning news", "Desc", "Part 1", "S1 E2"))))
    }

    private fun dir(): File = Files.createTempDirectory("startsnap").toFile()

    @Test fun roundTripIsSealedAndHasNoPlaintextLogin() {
        val d = dir()
        StartSnap.write(d, snap(), key)
        val f = StartSnap.file(d)
        assertTrue(Sealed.isSealedWith(f, key))
        val raw = String(f.readBytes(), Charsets.ISO_8859_1)
        assertFalse("login in the file", raw.contains("s3cretpass") || raw.contains("someuser") || raw.contains("provider.example"))
        val back = assertNotNullAnd(StartSnap.read(d, key))
        assertEquals(login, back.rows[0].url)
        assertEquals(login, back.current?.url)
        assertEquals("Morning news", back.progs["s|101"]!![0].title)
        assertEquals(listOf("News", "Sport"), back.groups)
        d.deleteRecursively()
    }

    @Test fun anotherKeyOrAnOldPlaintextFileReadsAsMissingAndIsDeleted() {
        val d = dir()
        StartSnap.write(d, snap(), key)
        assertNull(StartSnap.read(d, ByteArray(32) { 7 }))
        assertFalse(StartSnap.file(d).exists())
        // version 1 (plaintext) of the 2026-11-14 builds
        DataOutputStream(StartSnap.file(d).outputStream()).use { it.writeInt(0x54565331); it.writeInt(1); it.writeUTF(login) }
        assertNull(StartSnap.read(d, key))
        assertFalse(StartSnap.file(d).exists())
        d.deleteRecursively()
    }

    @Test fun damagedFileReadsAsMissing() {
        val d = dir()
        StartSnap.write(d, snap(), key)
        val f = StartSnap.file(d)
        val b = f.readBytes(); b[b.size / 2] = (b[b.size / 2].toInt() xor 1).toByte(); f.writeBytes(b)
        assertNull(StartSnap.read(d, key))
        d.deleteRecursively()
    }

    private fun <T> assertNotNullAnd(v: T?): T { assertNotNull(v); return v!! }
}
