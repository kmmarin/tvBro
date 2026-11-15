// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.kmmarin.tvbro.ui.FocusScroll

/** Settings > Appearance > Scrolling: a list walked with Down / Up the way Compose applies [FocusScroll.distance]. */
class FocusScrollTest {
    /** A list of [n] items of [size] px in a [container] px viewport; the scroll is clamped to the list like Compose's. */
    private class Sim(val n: Int, val size: Float, val container: Float, val centred: Boolean) {
        var scroll = 0f
        val max = (n * size - container).coerceAtLeast(0f)
        fun focus(i: Int) {
            val d = FocusScroll.distance(centred, i * size - scroll, size, container)
            scroll = (scroll + d).coerceIn(0f, max)
        }
        fun top(i: Int) = i * size - scroll
    }

    private fun walk(sim: Sim, check: (Int) -> Unit) {
        for (i in 0 until sim.n) { sim.focus(i); check(i) }
        for (i in sim.n - 1 downTo 0) { sim.focus(i); check(i) }
    }

    @Test fun centredKeepsTheSelectionInTheMiddle() {
        val s = Sim(60, 46f, 322f, centred = true)
        walk(s) { i ->
            val top = s.top(i)
            assertTrue("item $i on screen", top >= -0.5f && top + s.size <= s.container + 0.5f)
            val centre = top + s.size / 2
            when {
                s.scroll in 0.5f..(s.max - 0.5f) -> assertEquals("item $i centred", s.container / 2, centre, 1f)
                // at the ends the list stops: no empty space before the first / after the last item
                else -> assertTrue(s.scroll == 0f || s.scroll == s.max)
            }
        }
    }

    @Test fun centredNeverCutsTheTopItem() {
        // the TV guide on the Fire TV: 6.3 rows of 50 dp; the exact middle left half a row under the time ruler
        val s = Sim(60, 50f, 317f, centred = true)
        for (i in 0 until 50) {
            s.focus(i)
            if (s.scroll in 0.5f..(s.max - 0.5f)) {
                assertEquals("item $i: the top item whole", 0f, s.scroll % s.size, 0.5f)
                assertEquals("item $i: in the middle slot", s.container / 2, s.top(i) + s.size / 2, s.size / 2 + 0.5f)
                assertTrue("item $i on screen", s.top(i) + s.size <= s.container + 0.5f)
            }
        }
    }

    @Test fun centredStopsAtTheEndsAndFocusWalksToTheEdgeItems() {
        val s = Sim(30, 50f, 400f, centred = true)
        s.focus(0); assertEquals(0f, s.scroll)
        s.focus(1); assertEquals(0f, s.scroll) // still above the middle: the list does not move
        for (i in 2..29) s.focus(i)
        assertEquals(s.max, s.scroll) // last item: at the bottom, the list at its end
        assertEquals(s.container - s.size, s.top(29))
    }

    @Test fun centredMovesTheListOneItemPerKey() {
        val s = Sim(100, 46f, 322f, centred = true)
        for (i in 0..10) s.focus(i)
        val before = s.scroll
        s.focus(11)
        assertEquals(46f, s.scroll - before, 0.5f) // smooth: exactly one row, never a jump
    }

    @Test fun edgeOnlyScrollsAtTheEdges() {
        val s = Sim(60, 46f, 322f, centred = false)
        for (i in 0..6) { s.focus(i); assertEquals(0f, s.scroll) } // 7 rows fit: no scroll
        s.focus(7)
        assertEquals(s.container - s.size, s.top(7), 0.5f) // the selection sits at the bottom edge
        s.focus(6); assertEquals(s.container - 2 * s.size, s.top(6), 0.5f) // and moves up again without scrolling
        walk(s) { i -> val t = s.top(i); assertTrue(t >= -0.5f && t + s.size <= s.container + 0.5f) }
    }

    @Test fun shortListsAndHugeItemsNeverScrollAway() {
        val s = Sim(3, 46f, 322f, centred = true)
        walk(s) { assertEquals(0f, s.scroll) }
        // an item larger than the viewport: as in the edge mode (covering the viewport it stays, else its near end shows)
        assertEquals(0f, FocusScroll.distance(true, -20f, 500f, 300f), 0f)
        assertEquals(FocusScroll.edge(120f, 500f, 300f), FocusScroll.distance(true, 120f, 500f, 300f), 0f)
    }

    @Test fun firstVisibleBeforeMeasuring() {
        FocusScroll.centred = true
        assertEquals(17, FocusScroll.first(20, 2, 7))
        assertEquals(0, FocusScroll.first(1, 2, 7))
        FocusScroll.centred = false
        assertEquals(18, FocusScroll.first(20, 2, 7))
        FocusScroll.centred = true
    }
}
