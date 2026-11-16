// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.kmmarin.tvbro.data.MvLayout
import io.github.kmmarin.tvbro.data.MvRules
import io.github.kmmarin.tvbro.data.MvRules.Dir

class MultiviewRulesTest {
    private fun free(m: Map<String, Int>) = { id: String -> m[id] ?: 1 }

    @Test fun eachProviderKeepsToItsOwnLimit() {
        val f = free(mapOf("a" to 3, "b" to 1))
        assertTrue(MvRules.fits(listOf("a", "a"), null, "a", f)) // third tile of a provider allowing 3
        assertFalse(MvRules.fits(listOf("a", "a", "a"), null, "a", f)) // a 4th: refused
        assertTrue(MvRules.fits(listOf("a", "a", "a"), 1, "a", f)) // changing a tile's channel: fine
        assertTrue(MvRules.fits(listOf("a", "a", "a"), null, "b", f)) // mixed providers: b has its own stream
        assertFalse(MvRules.fits(listOf("a", "b"), null, "b", f)) // ...but only one
        assertTrue(MvRules.fits(listOf("a", "b"), 1, "b", f))
        assertFalse(MvRules.fits(listOf("a", "a", "a", "b"), null, "c", free(mapOf("c" to 4)))) // 4 tiles at most
        assertEquals(listOf(1, 2, 4), MvRules.take(listOf(1, 2, 3, 4), { if (it == 4) "b" else "a" }, free(mapOf("a" to 2, "b" to 1))))
    }

    @Test fun multiviewNeedsRoomForTwo() {
        assertEquals(1, MvRules.room(listOf("a"), free(mapOf("a" to 1)))) // provider allows 1: unavailable
        assertEquals(3, MvRules.room(listOf("a", "a"), free(mapOf("a" to 3))))
        assertEquals(2, MvRules.room(listOf("a", "b"), free(mapOf("a" to 1, "b" to 1)))) // two providers, one each
        assertEquals(4, MvRules.room(listOf("a", "b"), free(mapOf("a" to 5, "b" to 5))))
        // a recording of the provider takes one of its streams
        assertEquals(2, MvRules.room(listOf("a"), { id -> io.github.kmmarin.tvbro.data.StreamRules.free(3, id, listOf("a")) }))
    }

    @Test fun layoutsFollowTheNumberOfTiles() {
        assertEquals(listOf(MvLayout.SIDE), MvRules.layoutsFor(2))
        assertEquals(listOf(MvLayout.ONE_PLUS_TWO, MvLayout.ROW3), MvRules.layoutsFor(3)) // 3 allowed: 3-tile layouts only
        assertEquals(listOf(MvLayout.GRID, MvLayout.ONE_PLUS_THREE), MvRules.layoutsFor(4))
        assertEquals(MvLayout.ONE_PLUS_TWO, MvRules.resolve(MvLayout.GRID, 3)) // never a 2x2 with an empty quarter
        assertEquals(MvLayout.ROW3, MvRules.resolve(MvLayout.ROW3, 3))
        assertEquals(MvLayout.SIDE, MvRules.resolve(MvLayout.ROW3, 2))
        assertEquals(MvLayout.ROW3, MvRules.nextLayout(MvLayout.ONE_PLUS_TWO))
        assertEquals(MvLayout.ONE_PLUS_TWO, MvRules.nextLayout(MvLayout.ROW3))
        assertNull(MvRules.nextLayout(MvLayout.SIDE))
        for (l in MvLayout.entries) assertEquals(l.tiles, MvRules.rects(l, l.tiles).size)
    }
    @Test fun layoutsCoverTheScreenWithoutOverlap() {
        for (layout in MvLayout.entries) for (n in 1..4) {
            val r = MvRules.rects(layout, n)
            assertEquals(n, r.size)
            for (a in r) assertTrue(a.x >= 0 && a.y >= 0 && a.x + a.w <= 1.0001f && a.y + a.h <= 1.0001f)
            for (i in r.indices) for (j in r.indices) if (i < j) {
                val a = r[i]; val b = r[j]
                val overlap = a.x < b.x + b.w - 0.001f && b.x < a.x + a.w - 0.001f && a.y < b.y + b.h - 0.001f && b.y < a.y + a.h - 0.001f
                assertTrue("$layout $n: tiles $i and $j overlap", !overlap)
            }
        }
        val big = MvRules.rects(MvLayout.ONE_PLUS_THREE, 4)[0]
        assertTrue(big.w > 0.6f)
    }

    @Test fun dpadMovesBetweenTiles() {
        // 2x2: 0 1 / 2 3
        assertEquals(1, MvRules.move(MvLayout.GRID, 4, 0, Dir.RIGHT))
        assertEquals(2, MvRules.move(MvLayout.GRID, 4, 0, Dir.DOWN))
        assertEquals(3, MvRules.move(MvLayout.GRID, 4, 2, Dir.RIGHT))
        assertEquals(0, MvRules.move(MvLayout.GRID, 4, 0, Dir.LEFT)) // edge: stays
        assertEquals(1, MvRules.move(MvLayout.GRID, 4, 3, Dir.UP))
        // 1+3: big 0 on the left, 1 2 3 stacked on the right
        assertEquals(2, MvRules.move(MvLayout.ONE_PLUS_THREE, 4, 0, Dir.RIGHT)) // the middle one, level with the big tile
        assertEquals(0, MvRules.move(MvLayout.ONE_PLUS_THREE, 4, 1, Dir.LEFT))
        assertEquals(2, MvRules.move(MvLayout.ONE_PLUS_THREE, 4, 1, Dir.DOWN))
        assertEquals(3, MvRules.move(MvLayout.ONE_PLUS_THREE, 4, 2, Dir.DOWN))
        assertEquals(3, MvRules.move(MvLayout.ONE_PLUS_THREE, 4, 3, Dir.DOWN))
        // two tiles side by side
        assertEquals(1, MvRules.move(MvLayout.GRID, 2, 0, Dir.RIGHT))
        assertEquals(0, MvRules.move(MvLayout.GRID, 2, 0, Dir.DOWN))
    }

    @Test fun tilesAskForSmallerVideo() {
        assertEquals(540, MvRules.maxVideoHeight(MvLayout.GRID, 4, 3))
        assertEquals(720, MvRules.maxVideoHeight(MvLayout.ONE_PLUS_THREE, 4, 0))
        assertEquals(360, MvRules.maxVideoHeight(MvLayout.ONE_PLUS_THREE, 4, 1))
        assertEquals(1080, MvRules.maxVideoHeight(MvLayout.GRID, 1, 0))
    }
}
