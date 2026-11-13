// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class TimeFmtTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val t1630 = 1_762_878_600L // 2025-11-11 16:30 UTC

    @Test fun twelveAndTwentyFour() {
        assertEquals("4:30 PM", TimeFmt.time(t1630, false, utc))
        assertEquals("16:30", TimeFmt.time(t1630, true, utc))
        assertEquals("12:05 AM", TimeFmt.time(t1630 - 16 * 3600 - 25 * 60, false, utc))
        assertEquals("12:00 PM", TimeFmt.time(t1630 - 4 * 3600 - 30 * 60, false, utc))
        assertEquals("Tue 11 Nov 4:30 PM", TimeFmt.dayTime(t1630 * 1000, h24 = false, tz = utc, locale = Locale.UK))
    }

    @Test fun modes() {
        assertFalse(TimeFmt.use24(TimeFmt.H12, true))
        assertTrue(TimeFmt.use24(TimeFmt.H24, false))
        assertTrue(TimeFmt.use24(TimeFmt.SYSTEM, true))
        assertFalse(TimeFmt.use24(TimeFmt.SYSTEM, false))
        assertEquals(TimeFmt.H12, TimeFmt.mode) // 12-hour is the default
    }
}
