// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The encrypted backup file: format, passphrase, tampering, and the merge rules of an import. */
class FullBackupTest {
    private val pass = "correct horse"
    private val sources = """[{"id":"s1","name":"Home","kind":"xtream","server":"http://192.0.2.1","user":"u","pass":"p"}]"""

    private fun bundle() = BackupBundle(
        app = "0.3.4", pkg = "org.example.old", created = 1,
        prefs = mapOf(
            "settings" to mapOf(
                "favs" to PrefValue.of(setOf("s1|a", "s1|b"))!!, "epgHours" to PrefValue.of(48)!!, "recMinFreeMb" to PrefValue.of(700L)!!,
                "matchFps" to PrefValue.of(false)!!, "atRest" to PrefValue.of(3)!!, "lr_s1" to PrefValue.of(5L)!!,
                "recStorage" to PrefValue.of("/storage/emulated/0/Android/data/org.example.old/files")!!,
            ),
            "vod" to mapOf("recent" to PrefValue.of("m1\nm2")!!, "p_old" to PrefValue.of(5L)!!),
        ),
        secure = mapOf("sources" to sources, "recs" to """[{"id":"r1","path":"/storage/emulated/0/Android/data/org.example.old/files/recordings/a.ts"}]"""),
        watch = listOf(WatchRowB("m1", null, 1000, 5000, false, 10)),
        series = listOf(SeriesRowB("s1|series|9", 10, nextEp = "e2", subs = 1, subLang = "en")),
    )

    @Test fun roundTrip() {
        val b = bundle()
        val f = FullBackup.seal(b, pass, iterations = 10_000)
        assertTrue(FullBackup.isBackup(f))
        assertEquals(b, FullBackup.open(f, pass))
    }

    @Test fun defaultIterationsRoundTrip() {
        val f = FullBackup.seal(bundle(), pass)
        assertEquals(bundle(), FullBackup.open(f, pass))
    }

    @Test fun nothingReadableWithoutThePassphrase() {
        val f = FullBackup.seal(bundle(), pass, iterations = 10_000)
        val text = String(f, Charsets.ISO_8859_1)
        for (secret in listOf("192.0.2.1", "\"pass\"", "Home", "s1|a")) assertFalse(secret, text.contains(secret))
    }

    @Test fun wrongPassphraseFails() {
        val f = FullBackup.seal(bundle(), pass, iterations = 10_000)
        expectFail("wrong passphrase") { FullBackup.open(f, "correct horsE") }
    }

    @Test fun anyChangedByteFails() {
        val f = FullBackup.seal(bundle(), pass, iterations = 10_000)
        for (i in listOf(4, 6, 10, 30, 40, f.size / 2, f.size - 1)) {
            val g = f.copyOf(); g[i] = (g[i].toInt() xor 1).toByte()
            try { FullBackup.open(g, pass); fail("byte $i changed but opened") } catch (e: BackupException) { }
        }
        expectFail("not a tvBro backup") { FullBackup.open(f.copyOf(30), pass) }
        expectFail("not a tvBro backup") { FullBackup.open("{\"version\":1}".toByteArray(), pass) }
    }

    @Test fun shortPassphraseRefused() {
        try { FullBackup.seal(bundle(), "short", iterations = 10_000); fail() } catch (e: IllegalArgumentException) { }
    }

    @Test fun typedValuesSurvive() {
        val s = FullBackup.open(FullBackup.seal(bundle(), pass, iterations = 10_000), pass).prefs["settings"]!!
        assertEquals("i", s["epgHours"]!!.t); assertEquals(48L, s["epgHours"]!!.n)
        assertEquals("l", s["recMinFreeMb"]!!.t)
        assertEquals(false, s["matchFps"]!!.b)
        assertEquals(listOf("s1|a", "s1|b"), s["favs"]!!.ss)
    }

    @Test fun deviceKeysAreNotImported() {
        val w = FullBackup.mergePrefs("settings", emptyMap(), bundle().prefs["settings"]!!, "org.example.new")
        assertNull(w["atRest"]); assertNull(w["lr_s1"])
        assertEquals(PrefValue.of(48), w["epgHours"])
        assertEquals("/storage/emulated/0/Android/data/org.example.new/files", w["recStorage"]!!.s)
        assertNull(FullBackup.mergePrefs("vod", emptyMap(), bundle().prefs["vod"]!!, "x")["p_old"])
    }

    @Test fun favouritesAndRecentAreMerged() {
        val cur = mapOf("favs" to PrefValue.of(setOf("s2|z"))!!)
        assertEquals(listOf("s1|a", "s1|b", "s2|z"), FullBackup.mergePrefs("settings", cur, bundle().prefs["settings"]!!, "x")["favs"]!!.ss)
        val v = FullBackup.mergePrefs("vod", mapOf("recent" to PrefValue.of("m9\nm2")!!), bundle().prefs["vod"]!!, "x")
        assertEquals("m9\nm2\nm1", v["recent"]!!.s)
    }

    @Test fun sourcesMergedByIdAndLoginsRehomed() {
        val cur = mapOf("sources" to """[{"id":"s0","name":"Other","kind":"m3u"},{"id":"s1","name":"Home","kind":"xtream","pass":"old"}]""")
        val w = FullBackup.mergeSecure(cur, bundle().secure, "org.example.new")
        val l = Json.decodeFromString<List<Source>>(w["sources"]!!)
        assertEquals(listOf("s0", "s1"), l.map { it.id })
        assertEquals("p", l[1].pass) // the backup's login wins
        assertTrue(w["recs"]!!.contains("/Android/data/org.example.new/files/recordings/a.ts"))
    }

    @Test fun smbKeptWhenSetHere() {
        val inc = mapOf("smb" to """{"host":"192.0.2.9","share":"rec"}""")
        assertEquals(inc["smb"], FullBackup.mergeSecure(emptyMap(), inc, "x")["smb"])
        assertNull(FullBackup.mergeSecure(mapOf("smb" to """{"host":"192.0.2.8"}"""), inc, "x")["smb"])
    }

    @Test fun pinComesAlongOnlyWithoutOneHere() {
        val inc = Prefs(locked = setOf("Kids"), pinHash = "h-old", hiddenCh = setOf("a"))
        assertEquals("h-old", FullBackup.mergeOrg(null, inc).pinHash)
        assertEquals(setOf("Kids"), FullBackup.mergeOrg(Prefs(), inc).locked)
        val here = Prefs(pinHash = "h-here", locked = setOf("News"), names = mapOf("a" to "Mine"))
        val m = FullBackup.mergeOrg(here, inc.copy(names = mapOf("a" to "Theirs", "b" to "B")))
        assertEquals("h-here", m.pinHash)
        assertEquals(setOf("News", "Kids"), m.locked)
        assertEquals(mapOf("a" to "Mine", "b" to "B"), m.names)
        assertEquals(setOf("a"), m.hiddenCh)
        // no PIN anywhere: locks could never be removed, so none are kept
        assertEquals(emptySet<String>(), FullBackup.mergeOrg(Prefs(names = mapOf("x" to "y")), Prefs(locked = setOf("Kids"))).locked)
    }

    @Test fun organisationTravelsInPrefsJson() {
        val p = Prefs(order = listOf("s1|b", "s1|a"), pinHash = "h")
        val w = FullBackup.mergePrefs("settings", emptyMap(), mapOf("prefs" to PrefValue.of(Json.encodeToString(p))!!), "x")
        assertEquals(p, Json.decodeFromString<Prefs>(w["prefs"]!!.s!!))
    }

    @Test fun countsHoldNoNames() {
        val d = FullBackup.describe(FullBackup.counts(bundle()))
        assertTrue(d, d.startsWith("1 source(s), 2 favourite(s)"))
        assertFalse(d.contains("Home"))
    }

    private fun expectFail(part: String, f: () -> Unit) {
        try { f(); fail("expected: $part") } catch (e: BackupException) { assertTrue(e.message!!, e.message!!.contains(part)) }
    }
}
