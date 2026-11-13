// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import org.junit.Assert.*
import org.junit.Test
import io.github.kmmarin.tvbro.data.TvNav.Zone

class TvNavTest {
    @Test fun leftAndRightWalkTheZonesTheSameWayBack() {
        assertEquals(Zone.LABEL, TvNav.left(Zone.GRID))
        assertEquals(Zone.GROUPS, TvNav.left(Zone.LABEL))
        assertEquals(Zone.RAIL, TvNav.left(Zone.GROUPS))
        assertEquals(Zone.RAIL, TvNav.left(Zone.RAIL)) // the end, not a jump
        assertEquals(Zone.LABEL, TvNav.right(Zone.GROUPS, Zone.GROUPS))
        assertEquals(Zone.GRID, TvNav.right(Zone.LABEL, Zone.LABEL))
        for (z in Zone.entries) if (z != Zone.RAIL) assertEquals(z, TvNav.right(Zone.RAIL, z)) // back where it was
        assertEquals(Zone.LABEL, TvNav.right(Zone.RAIL, Zone.RAIL))
        // left then right returns to the same zone
        for (z in listOf(Zone.LABEL, Zone.GROUPS)) assertEquals(z, TvNav.right(TvNav.left(z), z))
    }

    @Test fun backClosesTheTopmostLayer() {
        assertEquals(TvNav.Layer.MENU, TvNav.top(menu = true, dialog = true, fullScreen = true, page = true))
        assertEquals(TvNav.Layer.DIALOG, TvNav.top(false, true, true, true))
        assertEquals(TvNav.Layer.FULL_SCREEN, TvNav.top(false, false, true, true))
        assertEquals(TvNav.Layer.PAGE, TvNav.top(false, false, false, true))
        assertEquals(TvNav.Layer.SCREEN, TvNav.top(false, false, false, false))
    }

    @Test fun enterSwitchesThePreviewOrGoesFullScreenFocusNeverDoes() {
        assertEquals(TvNav.Enter.FULL_SCREEN, TvNav.enter("a", "a", true))
        assertEquals(TvNav.Enter.FULL_SCREEN, TvNav.enter("a", "a", false))
        assertEquals(TvNav.Enter.SWITCH_PREVIEW, TvNav.enter("b", "a", true))
        assertEquals(TvNav.Enter.SWITCH_PREVIEW, TvNav.enter("b", null, true))
        assertEquals(TvNav.Enter.PLAY_FULL_SCREEN, TvNav.enter("b", "a", false))
    }

    @Test fun enteringAListFocusesTheSelectedOrPlayingItem() {
        val k = listOf("a", "b", "c")
        assertEquals(1, TvNav.entry(k, "b", "c"))
        assertEquals(2, TvNav.entry(k, "x", "c")) // focused channel not in this group: the playing one
        assertEquals(0, TvNav.entry(k, null, null))
        assertEquals(-1, TvNav.entry(emptyList(), "a", "a"))
    }
}
