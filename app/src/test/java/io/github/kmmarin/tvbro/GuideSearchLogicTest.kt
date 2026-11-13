// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import org.junit.Assert.*
import org.junit.Test
import io.github.kmmarin.tvbro.data.*
import io.github.kmmarin.tvbro.ui.GuideLayout

class GuideSearchLogicTest {
    private val t0 = 1_800_000_000L - (1_800_000_000L % 1800) // a half hour

    @Test fun guideStartsAtCurrentHalfHour() {
        assertEquals(t0, GuideLayout.initialStart(t0 + 17 * 60))
        assertEquals(t0, GuideLayout.initialStart(t0))
        assertEquals(t0 + 1800, GuideLayout.initialStart(t0 + 1800 + 1))
    }

    @Test fun revealDoesNotPullIntoThePast() {
        // the live programme started 20 min before the window: partly visible, window stays
        assertEquals(t0, GuideLayout.reveal(t0 - 1200, t0 + 1800, t0))
        // an earlier programme entirely before the window moves it back
        assertEquals(t0 - 3600, GuideLayout.reveal(t0 - 3600, t0 - 1800, t0))
        // a programme that starts inside and ends after the window moves it forward
        val n = GuideLayout.reveal(t0 + 7200, t0 + 7200 + 3600, t0)
        assertTrue(n > t0)
        // a programme that starts inside and ends after the window moves it forward
        assertTrue(GuideLayout.revealNoPast(t0 + 7200, t0 + 10800, t0, t0) > t0)
    }

    @Test fun guideNeverShowsThePast() {
        val now = t0 + 17 * 60
        // manual steps stop at the current half hour, however far back they are asked to go
        assertEquals(t0, GuideLayout.shifted(t0, -1800, now))
        assertEquals(t0, GuideLayout.shifted(t0, -100 * 3600, now))
        assertEquals(t0, GuideLayout.shifted(t0 + 3600, -7200, now))
        assertEquals(t0 + 1800, GuideLayout.shifted(t0, 1800, now))
        // and forward ones at 30 h ahead
        assertEquals(GuideLayout.floor30(now + 30 * 3600), GuideLayout.shifted(t0, 100 * 3600, now))
        // focus on a finished programme (or one long before now) never moves the window behind the current half hour
        assertEquals(t0, GuideLayout.revealNoPast(t0 - 3600, t0 - 1800, t0, now))
        assertEquals(t0, GuideLayout.revealNoPast(t0 - 6 * 3600, t0 - 5 * 3600, t0 + 3600, now))
        // a window left behind (screen open for hours) is pulled up to now
        assertEquals(t0, GuideLayout.revealNoPast(t0 - 3600, t0 + 1800, t0 - 7200, now))
        // Right keeps moving: each step forward is a real change until the 30 h limit
        var w = t0; var steps = 0
        while (true) { val n = GuideLayout.shifted(w, 1800, now); if (n == w) break; w = n; steps++ }
        assertEquals(60, steps)
    }

    @Test fun searchHistory() {
        var h = emptyList<String>()
        h = SearchHistory.add(h, "news"); h = SearchHistory.add(h, "Football"); h = SearchHistory.add(h, "NEWS ")
        assertEquals(listOf("NEWS", "Football"), h)
        assertEquals(h, SearchHistory.add(h, "a")) // too short
        repeat(30) { h = SearchHistory.add(h, "q$it") }
        assertEquals(20, h.size); assertEquals("q29", h.first())
        assertEquals(h, SearchHistory.decode(SearchHistory.encode(h)))
        assertEquals(19, SearchHistory.remove(h, "Q29").size)
    }

    @Test fun progStates() {
        val now = t0 + 600
        val ch = Channel("s|1", "s", "One", null, "G", "http://example.invalid/1", null, 1, Catchup("shift", null), 2)
        assertEquals(ProgState.FUTURE, ProgTime.classify(ch, now + 60, now + 3600, now))
        assertEquals(ProgState.NOW, ProgTime.classify(ch, now - 60, now + 60, now))
        assertEquals(ProgState.PAST_CATCHUP, ProgTime.classify(ch, now - 7200, now - 3600, now))
        assertEquals(ProgState.PAST, ProgTime.classify(ch, now - 5 * 86400, now - 5 * 86400 + 3600, now))
        assertEquals(ProgState.PAST, ProgTime.classify(ch.copy(catchup = null), now - 7200, now - 3600, now))
        assertEquals(2, ProgTime.minutesLeft(now + 61, now))
        assertEquals(0.5f, ProgTime.progress(now - 100, now + 100, now), 0.001f)
    }

    @Test fun xmltvEpisodeFields() {
        val xml = """<tv><programme start="20240101120000 +0000" stop="20240101130000 +0000" channel="a">
            <title>Show</title><sub-title>The Return</sub-title><episode-num system="dd_progid">EP123</episode-num>
            <episode-num system="xmltv_ns">1.3.0/1</episode-num><episode-num system="onscreen">S9E9</episode-num></programme>
            <programme start="20240101130000 +0000" stop="20240101140000 +0000" channel="a"><title>Other</title><episode-num system="onscreen">S03E12</episode-num></programme></tv>"""
        val p = org.kxml2.io.KXmlParser(); p.setInput(java.io.StringReader(xml))
        val got = ArrayList<Programme>()
        XmltvParser.parse(p, { c, _ -> "s|$c" }, 0L, Long.MAX_VALUE) { got += it }
        assertEquals("S2 E4", got[0].ep); assertEquals("The Return", got[0].sub) // xmltv_ns wins over onscreen
        assertEquals("S3 E12", got[1].ep); assertNull(got[1].sub)
    }

    @Test fun episodeCodes() {
        assertEquals("S2 E4", EpisodeNum.parse("xmltv_ns", "1.3."))
        assertEquals("S2 E4", EpisodeNum.parse("xmltv_ns", "1/5 . 3/10 . 0/1"))
        assertEquals("E7", EpisodeNum.parse("xmltv_ns", ".6."))
        assertEquals("S3 E12", EpisodeNum.parse("onscreen", "S03E12"))
        assertEquals("S1 E2", EpisodeNum.parse("onscreen", "1x02"))
        assertNull(EpisodeNum.parse("dd_progid", "EP0123"))
        assertEquals("S2 E4 The Return", EpisodeNum.line("S2 E4", "The Return"))
        assertNull(EpisodeNum.line(null, " "))
    }
}
