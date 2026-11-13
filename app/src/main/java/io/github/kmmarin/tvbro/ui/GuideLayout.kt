// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui

/** One shared time scale for the guide ruler, programme blocks and the "now" line. All times are epoch seconds. */
object GuideLayout {
    const val WINDOW_MIN = 150
    private const val WINDOW_SEC = WINDOW_MIN * 60L

    fun floor30(t: Long): Long = Math.floorDiv(t, 1800L) * 1800L

    /** The guide opens at the current half hour (05:00 at 05:17): the past is hidden until the user scrolls there. */
    fun initialStart(now: Long): Long = floor30(now)

    /**
     * Window start after focus lands on a programme. A programme that is at least partly visible never pulls the
     * window back into the past; only one entirely outside it (or one that starts inside and ends after it) moves it.
     */
    fun reveal(pStart: Long, pStop: Long, winStart: Long): Long {
        val winEnd = winStart + WINDOW_SEC
        return when {
            pStop <= winStart || pStart >= winEnd -> floor30(pStart)
            pStart >= winStart && pStop > winEnd -> minOf(floor30(pStart), floor30(pStop - WINDOW_SEC + 1799))
            else -> winStart
        }
    }

    /** Manual 30-minute steps go from the current half hour (never into the past; user 2026-11-11) to 30 h ahead. */
    fun shifted(winStart: Long, delta: Long, now: Long): Long =
        (winStart + delta).coerceIn(floor30(now), floor30(now + 30 * 3600))

    /** [reveal], never before the current half hour. */
    fun revealNoPast(pStart: Long, pStop: Long, winStart: Long, now: Long): Long =
        maxOf(reveal(pStart, pStop, winStart), floor30(now))

    /** x offset and width (dp units) of a programme clipped to the window [winStart, winEnd], or null when outside. */
    fun block(start: Long, stop: Long, winStart: Long, winEnd: Long, dpm: Float, gap: Float = 2f): Pair<Float, Float>? {
        val s = maxOf(start, winStart)
        val e = minOf(stop, winEnd)
        if (e <= s) return null
        return ((s - winStart) / 60f * dpm) to (((e - s) / 60f * dpm) - gap).coerceAtLeast(1f)
    }

    fun tickX(t: Long, winStart: Long, dpm: Float): Float = (t - winStart) / 60f * dpm
}
