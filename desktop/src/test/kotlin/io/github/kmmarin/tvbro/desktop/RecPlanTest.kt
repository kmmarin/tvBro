// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import org.junit.Assert.*
import org.junit.Test
import io.github.kmmarin.tvbro.data.*

class RecPlanTest {
    private val now = 1_800_000_000_000L
    private fun rec(id: String, start: Long, end: Long, st: String = "scheduled", ch: String = "s|a") = Rec(id, "T$id", "A", ch, "http://x", start, end, st)

    @Test fun startStopAndMissed() {
        val l = listOf(
            rec("due", now - 1000, now + 60_000),
            rec("later", now + 60_000, now + 120_000),
            rec("missed", now - 120_000, now - 60_000),
            rec("open", now - 5, 0),
            rec("over", now - 120_000, now - 1, "recording"),
            rec("run", now - 120_000, now + 1, "recording"),
        )
        assertEquals(listOf("due", "open"), RecPlan.toStart(l, now).map { it.id })
        assertEquals(listOf("missed"), RecPlan.missed(l, now).map { it.id })
        assertEquals(listOf("over"), RecPlan.toStop(l, now).map { it.id })
    }

    @Test fun recoverAfterRestart() {
        val r = rec("x", now - 1000, now + 1000, "recording")
        assertEquals("done", RecPlan.recover(r, 1234).status)
        assertEquals("failed", RecPlan.recover(r, 0).status)
        assertEquals("scheduled", RecPlan.recover(rec("y", now, now + 1), 0).status)
    }

    @Test fun seriesAiringsCoveredByAnyOverlappingRecording() {
        val s = now / 1000
        val progs = listOf(Programme("k", s, s + 1800, "News", null), Programme("k", s + 3600, s + 5400, "News", null))
        val recs = listOf(rec("a", (s - 60) * 1000, (s + 1980) * 1000), rec("b", (s + 3600) * 1000, (s + 5400) * 1000, "failed"), rec("c", (s + 3600) * 1000, 0, ch = "s|other"))
        assertEquals(setOf(s), RecPlan.covered(recs, "s|a", progs))
        // with the shared rule logic: only the uncovered airing is due
        val rule = SeriesRule("r", "s|a", "A", "News (R)", 0)
        assertEquals(listOf(s + 3600), SeriesRec.due(rule, progs, RecPlan.covered(recs, "s|a", progs), s).map { it.start })
    }

    @Test fun safeFileNames() {
        val n = RecPlan.fileName("Doctor Who: \"Rose\" / Part 1?", now)
        assertFalse(n.any { it in ":\"/?\\*<>|" })
        assertTrue(n.endsWith(".ts"))
        assertTrue(RecPlan.fileName("***", now).startsWith("___") || RecPlan.fileName("***", now).startsWith("Recording"))
    }

    @Test fun smbAddressHasNoCredentials() {
        val c = SmbCfg("nas", "media", "/tv\\rec/", "bob", "")
        assertEquals("smb://nas/media/tv/rec/", c.dirUrl)
        assertEquals("\\\\nas\\media\\tv\\rec", c.label)
        assertFalse(c.dirUrl.contains("bob"))
    }

    // ---- H6: recordings.json never holds the stream address (it carries the provider login) ----
    private fun ch(key: String, url: String) = Channel(key, "s", "A", null, "G", url, null, 0, null, 0)

    @Test fun recordingsAreSavedWithoutTheStreamAddress() {
        val l = listOf(Rec("1", "News", "A", "s|a", "http://tv.example/live/alice/s3cret/1.ts", now, now + 1), Rec("2", "B", "A", "s|a", startMs = now, endMs = 0))
        val json = Json2.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Rec.serializer()), RecPlan.forDisk(l))
        assertFalse(json.contains("s3cret")); assertFalse(json.contains("tv.example"))
        assertEquals(listOf("News", "B"), RecPlan.forDisk(l).map { it.title })
        // files of older versions (with "url") and new ones (without) both load
        val old = """[{"id":"1","title":"T","channel":"A","chKey":"s|a","url":"http://old.example/1.ts","startMs":1,"endMs":2}]"""
        assertEquals("http://old.example/1.ts", Json2.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Rec.serializer()), old).single().url)
        val new = """[{"id":"1","title":"T","channel":"A","chKey":"s|a","startMs":1,"endMs":2}]"""
        assertEquals("", Json2.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Rec.serializer()), new).single().url)
    }

    @Test fun theAddressComesFromTheChannelAtStart() {
        val r = Rec("1", "T", "A", "s|a", startMs = now, endMs = 0)
        assertEquals("http://tv.example/new.ts", RecPlan.streamUrl(r, listOf(ch("s|b", "http://b"), ch("s|a", "http://tv.example/new.ts"))))
        assertNull(RecPlan.streamUrl(r, listOf(ch("s|b", "http://b")))) // channel gone (or playlist not loaded yet)
        assertEquals("http://old", RecPlan.streamUrl(r.copy(url = "http://old"), emptyList())) // an older file's address as the fallback
        assertEquals("http://tv.example/new.ts", RecPlan.streamUrl(r.copy(url = "http://old"), listOf(ch("s|a", "http://tv.example/new.ts"))))
    }
}