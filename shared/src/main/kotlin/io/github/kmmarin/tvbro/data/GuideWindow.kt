// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

/**
 * One time scale for a guide grid (ruler, programme blocks, "now" line), platform-neutral. Times are epoch seconds.
 *
 * The grid never shows the past: the window starts at the current half hour and only goes forward, a window time
 * has left behind snaps to now, and Left at the window start moves focus to the channel name (never scrolls back,
 * never traps). Catch-up of earlier programmes is reached from the programme / channel menus ([pastCatchup]).
 */
object GuideWindow {
    const val WINDOW_MIN = 150
    const val WINDOW_SEC = WINDOW_MIN * 60L
    const val STEP_SEC = 1800L
    /** How far ahead of now the window may go (what the guide stores). */
    const val AHEAD_SEC = 30 * 3600L

    fun floor30(t: Long): Long = Math.floorDiv(t, 1800L) * 1800L

    /** The window start for "now": 05:00 at 05:17. */
    fun initialStart(now: Long): Long = floor30(now)

    /** A window start kept inside [current half hour, now + [AHEAD_SEC]]: one behind now snaps to now. */
    fun clampWin(winStart: Long, now: Long): Long = winStart.coerceIn(floor30(now), maxOf(floor30(now), floor30(now + AHEAD_SEC)))

    /** Window start after focus lands on a programme: moves only as far as needed, never before now. */
    fun reveal(pStart: Long, pStop: Long, winStart: Long, now: Long): Long {
        val winEnd = winStart + WINDOW_SEC
        val w = when {
            pStop <= winStart || pStart >= winEnd -> floor30(pStart)
            pStart >= winStart && pStop > winEnd -> minOf(floor30(pStart), floor30(pStop - WINDOW_SEC + 1799))
            else -> winStart
        }
        return clampWin(w, now)
    }

    /** Window start after a manual step of [delta] seconds (never into the past). */
    fun shifted(winStart: Long, delta: Long, now: Long): Long = clampWin(winStart + delta, now)

    /** Where the window should be on a clock tick: following the clock it moves with it; behind now it snaps to now. */
    fun onTick(winStart: Long, following: Boolean, now: Long): Long = if (following) initialStart(now) else clampWin(winStart, now)

    /** x offset and width (in [unitsPerMin] units) of a programme clipped to the window, or null when outside it. */
    fun block(start: Long, stop: Long, winStart: Long, unitsPerMin: Float, gap: Float = 2f): Pair<Float, Float>? {
        val s = maxOf(start, winStart)
        val e = minOf(stop, winStart + WINDOW_SEC)
        if (e <= s) return null
        return ((s - winStart) / 60f * unitsPerMin) to (((e - s) / 60f * unitsPerMin) - gap).coerceAtLeast(1f)
    }

    fun tickX(t: Long, winStart: Long, unitsPerMin: Float): Float = (t - winStart) / 60f * unitsPerMin

    /** Programmes of one channel visible in the window, in order. */
    fun visible(progs: Array<Programme>, winStart: Long): List<Programme> {
        val winEnd = winStart + WINDOW_SEC
        return progs.filter { it.stop > winStart && it.start < winEnd }
    }

    /** The programme to focus for "Right from the channel name": on now, else the first visible one. */
    fun nowTarget(visible: List<Programme>, now: Long): Programme? =
        visible.firstOrNull { it.start <= now && it.stop > now } ?: visible.firstOrNull { it.start > now } ?: visible.lastOrNull()

    /** A focus move in the grid: focus [prog] with the window at [win]; [prog] null = the channel name. */
    data class Move(val prog: Programme?, val win: Long)

    private fun index(progs: Array<Programme>, p: Programme): Int = progs.indexOfFirst { it.start == p.start && it.stop == p.stop }

    /**
     * Left from [focus]: the previous programme while it is visible (or the window can go back towards now to show
     * it); at the window start, which is never before the current half hour, the channel name.
     */
    fun leftOf(progs: Array<Programme>, focus: Programme?, winStart: Long, now: Long): Move {
        val lo = floor30(now)
        if (progs.isEmpty() || focus == null) return Move(null, winStart)
        val i = index(progs, focus)
        val pv = if (i >= 0) progs.getOrNull(i - 1) else progs.lastOrNull { it.stop <= focus.start }
        if (pv != null && pv.stop > winStart) return Move(pv, winStart)
        if (winStart > lo) {
            if (pv != null) { val w = clampWin(minOf(floor30(pv.start), winStart - STEP_SEC), now); if (pv.stop > w) return Move(pv, w) }
            // the previous one is in the past (or there is none): the window steps back towards now, the focus stays
            return Move(focus, clampWin(winStart - STEP_SEC, now))
        }
        return Move(null, winStart)
    }

    /** Right from [focus]: the next programme (the window moves forward as far as needed), else a 30-minute step. */
    fun rightOf(progs: Array<Programme>, focus: Programme, winStart: Long, now: Long): Move {
        val i = index(progs, focus)
        val nx = if (i >= 0) progs.getOrNull(i + 1) else progs.firstOrNull { it.start >= focus.stop }
        val winEnd = winStart + WINDOW_SEC
        return when {
            nx == null || nx.start >= winEnd -> Move(focus, shifted(winStart, STEP_SEC, now))
            else -> Move(nx, reveal(nx.start, nx.stop, winStart, now))
        }
    }

    /** Right from the channel name: the programme on now (or the first visible one), with the window back at now. */
    fun rightOfLabel(progs: Array<Programme>, now: Long): Move {
        val win = initialStart(now)
        return Move(nowTarget(visible(progs, win), now), win)
    }

    /** The programme to keep focused once [focus] has ended (the window moved on): the one on now. */
    fun afterEnd(progs: Array<Programme>, focus: Programme?, now: Long): Programme? =
        if (focus == null || focus.stop > floor30(now)) focus else progs.firstOrNull { it.start <= now && it.stop > now } ?: progs.firstOrNull { it.start > now }

    /** Earlier programmes still in the channel's archive, newest first (the grid shows none of them). */
    fun pastCatchup(ch: Channel, progs: Array<Programme>, now: Long, max: Int = 80): List<Programme> =
        progs.filter { ProgTime.classify(ch, it.start, it.stop, now) == ProgState.PAST_CATCHUP }.sortedByDescending { it.start }.take(max)
}
