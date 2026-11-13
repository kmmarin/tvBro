// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.kmmarin.tvbro.data.EpgIndex
import io.github.kmmarin.tvbro.data.Programme

class EpgTidyTest {
    private fun p(a: Long, b: Long, t: String = "x") = Programme("k", a, b, t, null)
    private fun spans(l: Array<Programme>) = l.map { it.start to it.stop }

    @Test fun overlapsEndAtTheNextStart() {
        // the user's provider: 10:00-14:00 and 11:00-14:00, then 14:00-18:00 and 15:00-16:00
        val r = EpgIndex.tidy(arrayOf(p(14, 18), p(11, 14), p(10, 14), p(15, 16)))
        assertEquals(listOf(10L to 11L, 11L to 14L, 14L to 15L, 15L to 16L), spans(r))
    }

    @Test fun sameStartKeepsTheLongerOne() {
        val r = EpgIndex.tidy(arrayOf(p(10, 11, "short"), p(10, 12, "long"), p(12, 13)))
        assertEquals(listOf("long", "x"), r.map { it.title })
    }

    @Test fun cleanDataUnchanged() {
        val a = arrayOf(p(1, 2), p(2, 3), p(4, 5))
        assertEquals(spans(a), spans(EpgIndex.tidy(a)))
    }
}
