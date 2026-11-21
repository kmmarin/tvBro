// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One shared time scale for the guide ruler and programme blocks. All times are epoch seconds. */
object GuideLayout {
    const val WINDOW_MIN = 180 // 3 h visible
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

/**
 * The guide's "now" marker (TV screen grid and 24 h guide): the programme on now shows how much of it has aired
 * inside its own block, with the app's one [ProgressLine] under the title, where other blocks show their start time
 * (nothing runs down the page; user 2026-11-21). The share is of the whole programme, the same as the header's bar,
 * also for a block cut at the window's start ("<").
 */
@Composable
fun GuideNowBar(start: Long, stop: Long, now: Long) =
    ProgressLine(io.github.kmmarin.tvbro.data.ProgTime.progress(start, stop, now), Modifier.padding(top = 6.dp))
