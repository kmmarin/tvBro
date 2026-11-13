// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class ParentalTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun pbkdf2MatchesTheStandard() {
        // RFC 7914 section 11 test vector (PBKDF2-HMAC-SHA256, P="passwd", S="salt", c=1, dkLen=64)
        assertEquals("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            hex(Pin.pbkdf2("passwd".toByteArray(), "salt".toByteArray(), 1, 64)))
        // and the JVM's own implementation at a real iteration count
        val salt = ByteArray(16) { it.toByte() }
        val jvm = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec("1234".toCharArray(), salt, 2000, 256)).encoded
        assertArrayEquals(jvm, Pin.pbkdf2("1234".toByteArray(), salt, 2000, 32))
    }

    @Test fun saltedHash() {
        val a = Pin.hash("1234"); val b = Pin.hash("1234")
        assertNotEquals(a, b) // salted
        assertTrue(a.startsWith("pbkdf2_sha256$120000$"))
        assertFalse(a.contains("1234$"))
        assertTrue(Pin.verify("1234", a)); assertFalse(Pin.verify("1235", a)); assertFalse(Pin.verify("", a))
        assertFalse(Pin.verify("1234", "")); assertFalse(Pin.verify("1234", "pbkdf2_sha256\$x\$zz\$00")); assertFalse(Pin.verify("1234", "md5$1$00$00"))
        assertTrue(Pin.check(Prefs(pinHash = a), "1234")); assertFalse(Pin.check(Prefs(), "1234"))
    }

    @Test fun oldHashesStillWork() {
        val old = Pin.legacy("4321")
        assertEquals(64, old.length)
        assertTrue(Pin.verify("4321", old)); assertFalse(Pin.verify("1234", old))
    }

    @Test fun format() {
        assertTrue(Pin.validFormat("1234")); assertTrue(Pin.validFormat("12345678"))
        assertFalse(Pin.validFormat("123")); assertFalse(Pin.validFormat("123456789")); assertFalse(Pin.validFormat("12a4"))
    }

    @Test fun fiveWrongTriesThenWaits() {
        var now = 1_000_000L
        val g = PinGate { now }
        val h = Pin.hash("2468", iterations = 1000)
        repeat(4) { assertEquals(PinGate.Result.Wrong(0), g.check(h, "0000")) }
        assertEquals(PinGate.Result.Wrong(30), g.check(h, "0000"))
        // while waiting even the right PIN is not checked
        assertEquals(PinGate.Result.Wait(30), g.check(h, "2468"))
        now += 10_000; assertEquals(PinGate.Result.Wait(20), g.check(h, "2468"))
        now += 20_000
        assertFalse(g.unlocked)
        // each further wrong try after five waits again; ten in a row wait longer
        repeat(4) { assertEquals(PinGate.Result.Wrong(30), g.check(h, "1111")); now += 30_000 }
        assertEquals(PinGate.Result.Wrong(60), g.check(h, "1111")); now += 60_000
        assertEquals(PinGate.Result.Ok, g.check(h, "2468"))
        assertEquals(PinGate.Result.Wrong(0), g.check(h, "1111")) // a right PIN starts the count again
    }

    @Test fun unlockLastsFourHours() {
        var now = 0L
        val g = PinGate { now }
        val h = Pin.hash("2468", iterations = 1000)
        assertFalse(g.unlocked)
        assertEquals(PinGate.Result.Ok, g.check(h, "2468"))
        now += 4 * 3600_000L - 1; assertTrue(g.unlocked)
        now += 1; assertFalse(g.unlocked)
        g.check(h, "2468"); assertTrue(g.unlocked)
        g.lockNow(); assertFalse(g.unlocked)
        assertEquals(PinGate.Result.Wrong(0), g.check("", "2468")) // no PIN set: nothing unlocks
    }

    @Test fun guideMapping() {
        val c = Channel("s|7", "s", "BBC One HD", null, "G", "u", "bbc1", 0, null, 0)
        assertEquals(c, GuideMap.apply(c, null)); assertEquals(c, GuideMap.apply(c, " "))
        assertEquals("s|bbc1.uk", GuideMap.apply(c, " BBC1.uk ").idKey)
        val none = GuideMap.apply(c, GuideMap.NONE)
        val epg = EpgIndex(mapOf("s|bbc1" to arrayOf(Programme("s|bbc1", 0, 10, "News", null)), "s|bbc one hd" to arrayOf(Programme("s|bbc one hd", 0, 10, "News", null))))
        assertEquals(1, epg.programmes(c).size)
        assertEquals(0, epg.programmes(none).size) // no guide: neither the id nor the name matches
    }

    @Test fun guideSearch() {
        val all = listOf(GuideChannel("bbc1.uk", "BBC One"), GuideChannel("bbc2.uk", "BBC Two"), GuideChannel("itv1", "ITV 1"),
            GuideChannel("one.de", "One"), GuideChannel("zdf", "ZDF Info"), GuideChannel("teleZ", "Télé Züri"))
        assertEquals(listOf("one.de", "bbc1.uk"), GuideMap.search(all, "one").map { it.id })
        assertEquals(listOf("bbc1.uk", "bbc2.uk"), GuideMap.search(all, "bbc").map { it.id })
        assertEquals(listOf("teleZ"), GuideMap.search(all, "tele zur").map { it.id })
        assertEquals(listOf("itv1"), GuideMap.search(all, "itv1").map { it.id }) // by id
        assertEquals(emptyList<GuideChannel>(), GuideMap.search(all, "nothing"))
        // no query: the channels named like the playlist channel first
        assertEquals("bbc1.uk", GuideMap.search(all, "", hint = "BBC One FHD").first().id)
        assertEquals(all.size, GuideMap.search(all, "", hint = "").size)
    }
}
