// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.kmmarin.tvbro.data.PressGate
import io.github.kmmarin.tvbro.data.PressGate.Act

class PressGateTest {
    @Test fun shortPressGoesThrough() {
        val g = PressGate()
        assertEquals(Act.PASS, g.key(true, 0, 100, true))
        assertEquals(Act.PASS, g.key(false, 0, 100, true))
    }

    @Test fun popupItemSwallowsTheOpeningPress() {
        // the press went down on the player / the long-pressed row; the pop-up item only gets its repeats and release
        val item = PressGate()
        assertEquals(Act.SWALLOW, item.key(true, 1, 100, false))
        assertEquals(Act.SWALLOW, item.key(true, 2, 100, false))
        assertEquals(Act.SWALLOW, item.key(false, 0, 100, false))
        // the next press is its own
        assertEquals(Act.PASS, item.key(true, 0, 200, false))
        assertEquals(Act.PASS, item.key(false, 0, 200, false))
    }

    @Test fun staleDownDoesNotLetAnotherPressRelease() {
        // went down here, focus left before the release; the release of a later press comes back with focus
        val row = PressGate()
        assertEquals(Act.PASS, row.key(true, 0, 100, true))
        assertEquals(Act.SWALLOW, row.key(false, 0, 300, true))
    }

    @Test fun longPressByRepeatOnceAndNoClickAfter() {
        val g = PressGate()
        g.key(true, 0, 100, true)
        assertEquals(Act.LONG, g.key(true, 1, 100, true))
        assertEquals(Act.SWALLOW, g.key(true, 2, 100, true))
        assertFalse(g.timerLong()) // Compose's timer late: not a second time
        assertEquals(Act.SWALLOW, g.key(false, 0, 100, true))
    }

    @Test fun longPressByTimerOnce() {
        val g = PressGate()
        g.key(true, 0, 100, true)
        assertTrue(g.timerLong())
        assertEquals(Act.SWALLOW, g.key(true, 1, 100, true))
        assertEquals(Act.PASS, g.key(false, 0, 100, true)) // Compose knows it was long: no click
    }

    @Test fun repeatsWithoutLongActionPass() {
        val g = PressGate()
        g.key(true, 0, 100, false)
        assertEquals(Act.PASS, g.key(true, 1, 100, false))
        assertTrue(PressGate().timerLong()) // touch
    }
}
