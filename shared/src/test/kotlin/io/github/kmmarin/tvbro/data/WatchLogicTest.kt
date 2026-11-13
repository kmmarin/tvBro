// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import org.junit.Assert.*
import org.junit.Test

class WatchLogicTest {
    private fun ep(id: String, s: Int, n: Int) = Episode(id, s, n, "T$id", "mp4", null, null)
    // deliberately unsorted, with a special in season 0
    private val eps = listOf(ep("21", 2, 1), ep("11", 1, 1), ep("12", 1, 2), ep("0", 0, 1), ep("22", 2, 2), ep("13", 1, 3))

    private fun st(vararg m: Pair<String, WatchState>): (Episode) -> WatchState? { val x = m.toMap(); return { x[it.id] } }
    private val done = WatchState(watched = true)
    private fun at(ms: Long) = WatchState(posMs = ms, durMs = 2_400_000)

    @Test fun watchedThreshold() {
        assertFalse(Watch.countsAsWatched(0, 0))
        assertFalse(Watch.countsAsWatched(500_000, 0)) // unknown duration
        assertTrue(Watch.countsAsWatched(0, 0, ended = true))
        assertFalse(Watch.countsAsWatched(899, 1000))
        assertTrue(Watch.countsAsWatched(900, 1000))
        assertFalse(WatchState(posMs = 4_999).inProgress)
        assertTrue(WatchState(posMs = 5_000).inProgress)
        assertFalse(WatchState(posMs = 60_000, watched = true).inProgress)
    }

    @Test fun upNextTiming() {
        assertFalse(Watch.upNextDue(2_360_000, 2_400_000 + 1))
        assertTrue(Watch.upNextDue(2_370_000, 2_400_000))
        assertFalse(Watch.upNextDue(100_000, 120_000)) // too short: wait for the end
        assertFalse(Watch.upNextDue(0, 0))
    }

    @Test fun nextEpisodeCrossesSeasonsAndSkipsSpecials() {
        assertEquals(listOf("11", "12", "13", "21", "22", "0"), Watch.ordered(eps).map { it.id })
        assertEquals("12", Watch.next(eps, "11")?.id)
        assertEquals("21", Watch.next(eps, "13")?.id) // season end -> next season's first
        assertEquals("0", Watch.next(eps, "22")?.id)
        assertNull(Watch.next(eps, "0"))
        assertNull(Watch.next(eps, "missing"))
    }

    @Test fun resumeTarget() {
        // nothing played: first episode
        assertEquals("11", Watch.resume(eps, st(), null)?.id)
        // last played in progress: that one
        assertEquals("12", Watch.resume(eps, st("11" to done, "12" to at(60_000)), "12")?.id)
        // last played watched: the next unwatched one after it
        assertEquals("21", Watch.resume(eps, st("11" to done, "12" to done, "13" to done), "13")?.id)
        assertEquals("22", Watch.resume(eps, st("13" to done, "21" to done), "13")?.id)
        // last played marked unwatched again: offer it
        assertEquals("13", Watch.resume(eps, st("11" to done), "13")?.id)
        // no last-played: in-progress first, else after the furthest watched
        assertEquals("21", Watch.resume(eps, st("21" to at(30_000)), null)?.id)
        assertEquals("21", Watch.resume(eps, st("11" to done, "13" to done), null)?.id)
        // everything after the last one watched: nothing left
        assertNull(Watch.resume(eps, st(*eps.map { it.id to done }.toTypedArray()), "0"))
        assertNull(Watch.resume(emptyList(), st(), null))
    }

    @Test fun summaryAndContinueWatching() {
        val fresh = Watch.summary(eps, st(), null)
        assertFalse(fresh.active); assertFalse(fresh.finished); assertEquals("11", fresh.next?.id)
        // started just now: active straight away, shows the episode being played
        val started = Watch.summary(eps, st(), "11")
        assertTrue(started.active); assertEquals("11", started.next?.id)
        // season marked watched without playing: active with the next season
        val marked = Watch.summary(eps, st("11" to done, "12" to done, "13" to done), null)
        assertTrue(marked.active); assertEquals("21", marked.next?.id)
        // last episode watched: finished, drops off
        val all = st(*eps.map { it.id to done }.toTypedArray())
        val fin = Watch.summary(eps, all, "0")
        assertTrue(fin.finished); assertFalse(fin.active); assertNull(fin.next)

        data class Row(val k: String, val active: Boolean, val t: Long)
        val rows = listOf(Row("a", true, 10), Row("b", false, 50), Row("c", true, 30), Row("d", true, 20))
        assertEquals(listOf("c", "d", "a"), Watch.continueOrder(rows, { it.active }, { it.t }).map { it.k })
    }

    @Test fun languages() {
        assertEquals("eng", Watch.langKey("en"))
        assertEquals("eng", Watch.langKey("en-US"))
        assertEquals("eng", Watch.langKey("ENG"))
        assertEquals("fra", Watch.langKey("fre"))
        assertEquals("fra", Watch.langKey("fr"))
        assertEquals("deu", Watch.langKey("ger"))
        assertNull(Watch.langKey("und"))
        assertNull(Watch.langKey(""))
        assertTrue(Watch.sameLang("de", "ger"))
        assertFalse(Watch.sameLang(null, null))
    }

    @Test fun captionPreference() {
        val tracks = listOf(TrackInfo("en"), TrackInfo("fr"), TrackInfo(null, "CC1"))
        assertEquals(TrackChoice.Default, Watch.pickText(null, tracks))
        assertEquals(TrackChoice.Off, Watch.pickText(SubPref(false), tracks))
        assertEquals(TrackChoice.Pick(1), Watch.pickText(SubPref(true, "fre"), tracks))
        assertEquals(TrackChoice.Pick(0), Watch.pickText(SubPref(true, "es"), tracks)) // language missing: first track
        assertEquals(TrackChoice.Pick(0), Watch.pickText(SubPref(true, ""), tracks))
        assertEquals(TrackChoice.Default, Watch.pickText(SubPref(true, "en"), emptyList())) // tracks not known yet
        assertEquals(1, Watch.pickAudio("fra", listOf(TrackInfo("eng"), TrackInfo("fre"))))
        assertNull(Watch.pickAudio("es", listOf(TrackInfo("eng"))))
        assertNull(Watch.pickAudio(null, listOf(TrackInfo("eng"))))
    }

    @Test fun labels() {
        assertEquals("12:31", Watch.clock(751_000))
        assertEquals("1:02:05", Watch.clock(3_725_000))
        assertEquals("S2 E4", Watch.code(ep("x", 2, 4)))
        assertEquals("E4", Watch.code(ep("x", 0, 4)))
    }
}
