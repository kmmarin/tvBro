// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import org.junit.Assert.*
import org.junit.Test

class GuideWindowTest {
    private val h = 3600L
    private val base = 1_800_000_000L / 1800 * 1800 // a half hour
    private fun p(s: Long, e: Long, t: String = "x") = Programme("k", s, e, t, null)

    @Test fun opensAtTheCurrentHalfHourNeverEarlier() {
        assertEquals(base, GuideWindow.initialStart(base + 17 * 60))
        assertEquals(base, GuideWindow.initialStart(base))
        assertEquals(base + 1800, GuideWindow.initialStart(base + 1800 + 1))
    }

    @Test fun windowNeverGoesIntoThePast() {
        val now = base + 60
        assertEquals(base, GuideWindow.clampWin(base - 3 * h, now))
        assertEquals(base, GuideWindow.shifted(base, -1800, now)) // a step back at now stays at now
        assertEquals(base, GuideWindow.shifted(base, -20 * h, now))
        assertEquals(base + 1800, GuideWindow.shifted(base + h, -1800, now)) // back towards now is fine
        assertEquals(GuideWindow.floor30(now + 30 * h), GuideWindow.shifted(base, 50 * h, now))
    }

    @Test fun revealMovesForwardOnlyAndNeverBehindNow() {
        val now = base + 60
        // started an hour before the window, still on: the window stays at now
        assertEquals(base, GuideWindow.reveal(base - h, base + 1800, base, now))
        // entirely in the past: the window does not follow it
        assertEquals(base, GuideWindow.reveal(base - 2 * h, base - h, base, now))
        // starts inside, ends after: moves forward only as far as needed
        assertEquals(base + 1800, GuideWindow.reveal(base + 2 * h, base + 3 * h, base, now))
    }

    @Test fun aWindowBehindNowSnapsToNow() {
        val now = base + 600
        assertEquals(base, GuideWindow.onTick(base - h, false, now)) // left behind: snaps, no idle wait
        assertEquals(base + h, GuideWindow.onTick(base + h, false, now)) // future: stays
        assertEquals(base + 1800, GuideWindow.onTick(base, true, base + 1800 + 5)) // following the clock
        assertEquals(base + 1800, GuideWindow.onTick(base, false, base + 1800 + 5)) // time moved past the window start
    }

    @Test fun blocksAreClippedToTheWindowOnOneScale() {
        val dpm = 4f
        val (x, w) = GuideWindow.block(base - 1800, base + 1800, base, dpm, gap = 0f)!!
        assertEquals(0f, x, 0.001f); assertEquals(30 * dpm, w, 0.001f)
        val (x2, w2) = GuideWindow.block(base + 1800, base + 3600, base, dpm, gap = 0f)!!
        assertEquals(30 * dpm, x2, 0.001f); assertEquals(30 * dpm, w2, 0.001f)
        assertEquals(GuideWindow.tickX(base + 1800, base, dpm), x2, 0.001f) // ruler tick lines up with the block
        assertNull(GuideWindow.block(base - h, base, base, dpm))
        assertNull(GuideWindow.block(base + GuideWindow.WINDOW_SEC, base + GuideWindow.WINDOW_SEC + h, base, dpm))
    }

    @Test fun rightFromTheChannelNameLandsOnNow() {
        val now = base + 600
        val all = arrayOf(p(base - h, base, "past"), p(base, base + 1800, "now"), p(base + 1800, base + h, "next"))
        val vis = GuideWindow.visible(all, base)
        assertEquals(listOf("now", "next"), vis.map { it.title })
        assertEquals("now", GuideWindow.nowTarget(vis, now)?.title)
        assertEquals("next", GuideWindow.nowTarget(vis.drop(1), now)?.title) // gap now: the next one
        assertNull(GuideWindow.nowTarget(emptyList(), now)) // no guide data: no target, no crash
        // from a window moved ahead, Right from the name brings it back to now
        val m = GuideWindow.rightOfLabel(all, now)
        assertEquals("now", m.prog?.title); assertEquals(base, m.win)
        assertNull(GuideWindow.rightOfLabel(emptyArray(), now).prog)
    }

    @Test fun leftAtTheWindowStartGoesToTheChannelName() {
        val now = base + 600
        val all = arrayOf(p(base - 2 * h, base - h, "old"), p(base - h, base + 1800, "now"), p(base + 1800, base + h, "next"))
        // on now (started before the window): the previous one is in the past, the window is at now -> the name
        val m = GuideWindow.leftOf(all, all[1], base, now)
        assertNull(m.prog); assertEquals(base, m.win)
        // from the next programme: the one on now, the window stays
        assertEquals(GuideWindow.Move(all[1], base), GuideWindow.leftOf(all, all[2], base, now))
        // nothing to the left at all / no guide: the name, never a trap
        assertNull(GuideWindow.leftOf(emptyArray(), null, base, now).prog)
        assertNull(GuideWindow.leftOf(arrayOf(all[1]), all[1], base, now).prog)
    }

    @Test fun leftInAWindowAheadStepsBackTowardsNowOnly() {
        val now = base + 600
        val all = arrayOf(p(base, base + 1800, "a"), p(base + 1800, base + 4 * h, "long"), p(base + 4 * h, base + 5 * h, "late"))
        // window 4 h ahead, focus on "late": the long one ends at the window start, the window goes back to its start
        assertEquals(GuideWindow.Move(all[1], base + 1800), GuideWindow.leftOf(all, all[2], base + 4 * h, now))
        // window ahead, previous one not visible: the window steps back towards it, never before now
        val far = arrayOf(p(base, base + 1800, "a"), p(base + 6 * h, base + 7 * h, "b"))
        val m = GuideWindow.leftOf(far, far[1], base + 6 * h, now)
        assertTrue(m.win >= base); assertTrue(m.win < base + 6 * h)
        // repeated Left always ends at the name with the window at now
        var focus: Programme? = far[1]; var win = base + 6 * h; var n = 0
        while (focus != null && n++ < 50) { val r = GuideWindow.leftOf(far, focus, win, now); focus = r.prog; win = r.win }
        assertNull(focus); assertEquals(base, win)
    }

    @Test fun rightMovesToTheNextProgrammeOrStepsForward() {
        val now = base + 600
        val all = arrayOf(p(base, base + 1800, "a"), p(base + 1800, base + 4 * h, "long"), p(base + 4 * h, base + 5 * h, "late"))
        // "long" starts inside and ends after the window: the window moves forward only to its start
        assertEquals(GuideWindow.Move(all[1], base + 1800), GuideWindow.rightOf(all, all[0], base, now))
        val m = GuideWindow.rightOf(all, all[1], base, now) // "late" starts after the window: a step, focus stays
        assertEquals(all[1], m.prog); assertEquals(base + 1800, m.win)
        val end = GuideWindow.rightOf(arrayOf(all[0]), all[0], base, now) // last programme: a step forward
        assertEquals(all[0], end.prog); assertEquals(base + 1800, end.win)
    }

    @Test fun anEndedFocusMovesToTheProgrammeOnNow() {
        val all = arrayOf(p(base, base + 1800, "a"), p(base + 1800, base + h, "b"))
        assertEquals("b", GuideWindow.afterEnd(all, all[0], base + 1800 + 10)?.title)
        assertEquals("a", GuideWindow.afterEnd(all, all[0], base + 600)?.title)
    }

    @Test fun catchupListIsThePastInTheArchiveNewestFirst() {
        val now = base + 600
        val ch = Channel("c", "s", "C", null, "g", "http://x", null, 1, Catchup("shift", null), 1)
        val all = arrayOf(p(base - 30 * h, base - 29 * h, "too old"), p(base - 2 * h, base - h, "old"), p(base - h, base, "older?"), p(base, base + h, "now"))
        assertEquals(listOf("older?", "old"), GuideWindow.pastCatchup(ch, all, now).map { it.title })
        assertTrue(GuideWindow.pastCatchup(ch.copy(catchup = null), all, now).isEmpty())
    }
}
