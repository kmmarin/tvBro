// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import org.junit.Assert.*
import org.junit.Test

class SeriesRecTest {
    private val rule = SeriesRule("r1", "s|bbc1", "BBC One", "Doctor Who", 0)
    private fun p(t: String, start: Long, len: Long = 3600) = Programme("s|bbc1", start, start + len, t, null)

    @Test fun titleMatching() {
        assertTrue(SeriesRec.matches(rule, p("Doctor Who", 0)))
        assertTrue(SeriesRec.matches(rule, p("DOCTOR WHO", 0)))
        assertTrue(SeriesRec.matches(rule, p("Doctor Who (R)", 0)))
        assertTrue(SeriesRec.matches(SeriesRule("x", "c", "c", "Café Society", 0), p("Cafe Society", 0)))
        assertFalse(SeriesRec.matches(rule, p("Doctor Who Confidential", 0)))
        assertFalse(SeriesRec.matches(rule, p("News", 0)))
    }

    @Test fun dueAirings() {
        val now = 100_000L
        val progs = listOf(
            p("Doctor Who", now - 7200),          // finished
            p("Doctor Who", now - 600),           // on now: still recordable
            p("News", now + 3000),
            p("Doctor Who", now + 86_400),        // tomorrow
            p("Doctor Who", now + 86_400 * 3),    // beyond 48 h: later
            p("Doctor Who", now + 90_000),        // already scheduled
        )
        val d = SeriesRec.due(rule, progs, setOf(now + 90_000), now)
        assertEquals(listOf(now - 600, now + 86_400), d.map { it.start })
    }
}
