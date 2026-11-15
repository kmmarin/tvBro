// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.kmmarin.tvbro.data.GroupOrder

class GroupOrderTest {
    @Test fun ppvMatchesWholeWordAnyCase() {
        assertTrue(GroupOrder.isPpv("PPV Events | 1"))
        assertTrue(GroupOrder.isPpv("UK: ppv"))
        assertTrue(GroupOrder.isPpv("Sports (PPV)"))
        assertFalse(GroupOrder.isPpv("PPVX Movies"))
        assertFalse(GroupOrder.isPpv("Happv"))
    }

    @Test fun ppvGoesLastByDefaultKeepingProviderOrder() {
        val p = listOf("PPV Events | 1", "News", "4K / UHD", "PPV Events | 2", "Kids")
        assertEquals(listOf("News", "4K / UHD", "Kids", "PPV Events | 1", "PPV Events | 2"), GroupOrder.order(p, emptyList()))
    }

    @Test fun savedOrderWins() {
        val p = listOf("PPV Events | 1", "News", "Kids")
        // the user sent PPV to the top: it stays there; groups new to the saved order follow
        assertEquals(listOf("PPV Events | 1", "Kids", "News"), GroupOrder.order(p, listOf("PPV Events | 1", "Kids")))
    }

    @Test fun sendToTopAndBottom() {
        val l = listOf("a", "b", "c", "d")
        assertEquals(listOf("c", "a", "b", "d"), GroupOrder.sendTo(l, "c", -1))
        assertEquals(listOf("a", "b", "d", "c"), GroupOrder.sendTo(l, "c", 1))
        assertEquals(l, GroupOrder.sendTo(l, "x", 1))
    }

    @Test fun neighbourIsTheNextGroupElseThePreviousSkippingLocked() {
        val l = listOf("Favourites", "All channels", "a", "b", "c")
        assertEquals("c", GroupOrder.neighbour(l, 4, "b"))              // b sent away from index 3: the next one
        assertEquals("b", GroupOrder.neighbour(l, 5, "c"))              // c was last: the one before
        assertEquals("c", GroupOrder.neighbour(l, 3, null) { it != "b" }) // b locked: skipped
        assertEquals("Favourites", GroupOrder.neighbour(listOf("Favourites"), 3, null))
    }
}

class PopupFocusTest {
    @Test fun menuOpensOnTheCurrentChoice() {
        assertEquals(1, io.github.kmmarin.tvbro.ui.MenuFocus.index(listOf("  Internal  (1 MB free)", "* USB drive  (2 MB free)", "  Network share"), -1))
        assertEquals(0, io.github.kmmarin.tvbro.ui.MenuFocus.index(listOf("Watch", "Record"), -1))
        assertEquals(2, io.github.kmmarin.tvbro.ui.MenuFocus.index(listOf("a", "* b", "c"), 2)) // an explicit focus wins
        assertEquals(0, io.github.kmmarin.tvbro.ui.MenuFocus.index(emptyList(), -1))
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun trapHoldsArrowsButNotFocusRequests() {
        val d = androidx.compose.ui.focus.FocusDirection
        for (x in listOf(d.Left, d.Right, d.Up, d.Down, d.Next, d.Previous)) assertTrue(io.github.kmmarin.tvbro.ui.trapsMove(x))
        // requestFocus() for a popup outside the Settings panel asks the panel's exit with Enter: must not be cancelled
        assertFalse(io.github.kmmarin.tvbro.ui.trapsMove(d.Enter))
        // a request leaves Settings (level 0) only for a popup above it, a popup (level 1) only for a second one
        val ui = io.github.kmmarin.tvbro.ui.FocusMemory
        ui.popups = 0; assertFalse(io.github.kmmarin.tvbro.ui.canLeaveTrap(0))
        ui.popups = 1; assertTrue(io.github.kmmarin.tvbro.ui.canLeaveTrap(0)); assertFalse(io.github.kmmarin.tvbro.ui.canLeaveTrap(1))
        ui.popups = 2; assertTrue(io.github.kmmarin.tvbro.ui.canLeaveTrap(1))
        ui.popups = 0
    }
}