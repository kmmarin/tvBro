// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import org.junit.Assert.*
import org.junit.Test
import io.github.kmmarin.tvbro.data.*

class BackupFileTest {
    private val pin = Pin.hash("2468", iterations = 1000)
    private val full = Settings(
        myList = listOf("s|1", "g|News"), lastUi = "full|TV|s|1|0|All", epgMap = mapOf("s|1" to "bbc1.uk", "s|2" to GuideMap.NONE),
        prefs = Prefs(names = mapOf("s|1" to "One"), hiddenCh = setOf("s|9"), locked = setOf("Adult", "Horror"), lockedCh = setOf("s|5"), pinHash = pin,
            custom = mapOf("Mine" to listOf("s|1"))),
        reminders = listOf(Reminder("r1", "s|1", "One", "News", 1_000)), searchHistory = listOf("news"),
        smb = SmbCfg("nas", "tv", user = "kid", domain = "home"), externalPlayer = "vlc",
    )
    private val rules = listOf(SeriesRule("a", "s|1", "One", "News", 1))
    private val watch = WatchData(items = mapOf("m|s|1" to WItem(60_000, 600_000, updated = 5)), series = mapOf("x|s|3" to SeriesRow("x|s|3", nextEp = "77")))

    @Test fun roundTripWithoutSecrets() {
        val text = BackupFile.export(full, rules, watch, now = 1_700_000_000_000)
        assertFalse(text.contains(pin)); assertFalse(text.contains("pbkdf2")); assertFalse(text.contains("\"kid\""))
        assertTrue(text.contains("\"format\": \"tvbro-desktop-backup\""))
        val b = BackupFile.check(text)
        assertEquals(full.myList, b.settings.myList)
        assertEquals(full.epgMap, b.settings.epgMap)
        assertEquals(setOf("s|5"), b.settings.prefs.lockedCh)
        assertEquals(watch, b.watch)
        assertEquals("", b.settings.prefs.pinHash); assertEquals("", b.settings.lastUi)
        // restored onto another device: its own PIN, place and share login stay
        val other = Settings(prefs = Prefs(pinHash = "mine"), lastUi = "x", smb = SmbCfg("nas", "tv", user = "me"))
        val r = BackupFile.restored(other, b)
        assertEquals("mine", r.prefs.pinHash); assertEquals("x", r.lastUi); assertEquals("me", r.smb.user)
        assertEquals(full.prefs.locked, r.prefs.locked); assertEquals("vlc", r.externalPlayer)
        val sum = BackupFile.summary(b)
        assertEquals(5, sum.size)
        assertTrue(sum[1].startsWith("My list 2 · reminders 1 · series recordings 1"))
        assertTrue(sum[3].contains("locks 3") && sum[3].contains("guide mappings 2"))
    }

    @Test fun firstVersionBackupsStillRestore() {
        // version 1 (no format, no watch state; it still carried the PIN hash: never restored)
        val v1 = """{"version":1,"settings":{"myList":["s|1"],"prefs":{"pinHash":"abc","locked":["Adult"]}},"series":[]}"""
        val b = BackupFile.check(v1)
        assertNull(b.watch)
        assertEquals("keep", BackupFile.restored(Settings(prefs = Prefs(pinHash = "keep")), b).prefs.pinHash)
        assertTrue(BackupFile.summary(b)[2].contains("yours stay"))
    }

    private fun refused(text: String, why: String) {
        try { BackupFile.check(text); fail("accepted: $text") } catch (e: BadBackup) { assertTrue("${e.message} should say $why", e.message!!.contains(why)) }
    }

    @Test fun badFilesAreRefusedWhole() {
        refused("not json", "not a tvBro backup")
        refused("[1,2]", "not a tvBro backup")
        refused("""{"format":"tvbro-web-backup","version":1}""", "web app")
        refused("""{"format":"other","version":1,"settings":{}}""", "format")
        refused("""{"version":9,"settings":{}}""", "newer tvBro")
        refused("""{"version":2}""", "no settings")
        refused("""{"version":2,"settings":{"myList":"oops"}}""", "damaged")
        refused("""{"version":2,"settings":{"epgHours":0}}""", "out of range")
        refused("""{"version":2,"settings":{},"series":[{"id":"a","chKey":"","channel":"","title":"","created":1}]}""", "series recordings")
        refused("""{"version":2,"settings":{"epgMap":{"s|1":""}}}""", "guide mapping")
        refused("""{"version":2,"settings":{},"watch":{"items":{"m|1":{"posMs":-5}}}}""", "negative")
    }

    @Test fun seriesRulesAreAddedNotReplaced() {
        val cur = listOf(SeriesRule("a", "s|1", "One", "News", 1), SeriesRule("b", "s|2", "Two", "Film", 1))
        val add = listOf(SeriesRule("c", "s|1", "One", "news", 2), SeriesRule("d", "s|3", "Three", "Quiz", 2), SeriesRule("e", "s|3", "Three", "Quiz", 3))
        assertEquals(listOf("a", "b", "d"), BackupFile.mergeRules(cur, add).map { it.id })
    }
}
