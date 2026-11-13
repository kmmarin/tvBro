// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Clock times for every screen (guide, programme times, clock, overlays, recordings, reminders, search): 12-hour
 * ("4:30 PM", the default), 24-hour ("16:30") or the system setting. Apps set [mode] from their settings and
 * [system24] from the platform.
 */
object TimeFmt {
    const val H12 = 0
    const val H24 = 1
    const val SYSTEM = 2
    val names = listOf("12-hour (4:30 PM)", "24-hour (16:30)", "System")

    @Volatile var mode: Int = H12
    @Volatile var system24: Boolean = false

    fun use24(m: Int = mode, sys24: Boolean = system24): Boolean = when (m) { H24 -> true; SYSTEM -> sys24; else -> false }

    /** "4:30 PM" / "16:30" for epoch seconds. */
    fun time(sec: Long, h24: Boolean = use24(), tz: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(tz).apply { timeInMillis = sec * 1000 }
        val h = c.get(Calendar.HOUR_OF_DAY); val mi = c.get(Calendar.MINUTE)
        if (h24) return "%02d:%02d".format(h, mi)
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "%d:%02d %s".format(h12, mi, if (h < 12) "AM" else "PM")
    }

    /** "Wed 11 Nov 4:30 PM" (or with [dayPattern]) for epoch milliseconds. */
    fun dayTime(ms: Long, dayPattern: String = "EEE d MMM", h24: Boolean = use24(), tz: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): String {
        val d = SimpleDateFormat(dayPattern, locale).apply { timeZone = tz }.format(Date(ms))
        return d + " " + time(ms / 1000, h24, tz)
    }
}
