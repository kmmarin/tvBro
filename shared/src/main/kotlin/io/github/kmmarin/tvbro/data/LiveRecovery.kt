// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

/**
 * Live TV that errors or stalls: reconnect with back-off (1, 3, 6 s), then try the channel's alternate sources one by
 * one (the same channel listed again, see [AltIndex]), then rest 30 s and start over from the channel itself.
 * Pure policy (unit-tested); the player drives it and shows a small corner notice, never a dialog.
 * Same rules as the web app (livefix.js).
 */
class LiveRecovery(val origin: String) {
    sealed class Step {
        /** Reconnect [key] after [delayMs] (attempt [attempt] of [of]). */
        data class Retry(val key: String, val delayMs: Long, val attempt: Int, val of: Int) : Step()
        /** The alternates are not known yet: call [setAlts], then [failed] again. */
        object NeedAlts : Step()
        /** Switch to the alternate [alt] after [delayMs]. */
        data class Alt(val alt: Channel, val delayMs: Long) : Step()
        /** Every source failed: wait [delayMs], then the channel itself again. */
        data class Rest(val key: String, val delayMs: Long) : Step()
    }

    /** The source tried now (the channel's key, or an alternate's). */
    var key: String = origin
        private set
    /** The alternate playing now (null: the channel itself). */
    var alt: Channel? = null
        private set
    private var tries = 0
    private var alts: List<Channel>? = null
    private var altIdx = -1
    var rounds = 0
        private set

    /** The source playing now failed (error, end of stream or stall): what to do next. */
    fun failed(): Step {
        val limit = if (key == origin) RETRY_DELAYS.size else ALT_TRIES
        if (tries < limit) {
            val d = RETRY_DELAYS[tries.coerceAtMost(RETRY_DELAYS.size - 1)]
            tries++
            return Step.Retry(key, d, tries, limit)
        }
        val l = alts ?: return Step.NeedAlts
        if (altIdx + 1 < l.size) {
            altIdx++; alt = l[altIdx]; key = l[altIdx].key; tries = 0
            return Step.Alt(l[altIdx], 300)
        }
        rounds++; key = origin; alt = null; altIdx = -1; tries = 0
        return Step.Rest(origin, REST_MS)
    }

    fun setAlts(l: List<Channel>) { alts = l.filter { it.key != origin }.distinctBy { it.key } }

    /** Playing fine again (on whatever source): later trouble starts with reconnects again. */
    fun healthy() { tries = 0; rounds = 0 }

    companion object {
        val RETRY_DELAYS = longArrayOf(1000, 3000, 6000).toList()
        /** An alternate gets one reconnect of its own. */
        const val ALT_TRIES = 1
        /** No progress this long while playing (or starting) = stalled. */
        const val STALL_MS = 15_000L
        /** Playing this long without trouble counts as recovered. */
        const val HEALTHY_MS = 20_000L
        /** Every source failed: wait this long, then start over. */
        const val REST_MS = 30_000L

        /** The short corner notice for a step. */
        fun notice(s: Step, name: String): String = when (s) {
            is Step.Retry -> "Reconnecting… (${s.attempt}/${s.of})"
            is Step.Alt -> "Trying another source: ${s.alt.name}"
            is Step.Rest -> "${name.ifBlank { "This channel" }} is not available · trying again in ${s.delayMs / 1000} s"
            Step.NeedAlts -> ""
        }
    }
}

/**
 * Watches a live stream's position for a stall. Feed [tick] about once a second with the playback position (null
 * while nothing plays yet) and whether the viewer paused it. Returns [Event.STALL] once per stall (call [reset] after
 * restarting the stream) and [Event.HEALTHY] once after [healthyMs] of steady playback.
 */
class StallWatch(private val stallMs: Long = LiveRecovery.STALL_MS, private val healthyMs: Long = LiveRecovery.HEALTHY_MS) {
    enum class Event { NONE, STALL, HEALTHY }
    private var lastPos: Double? = null
    private var lastMove = -1L
    private var steadySince = 0L
    private var fired = false
    private var healthyFired = false

    fun reset(now: Long) { lastPos = null; lastMove = now; steadySince = 0; fired = false; healthyFired = false }

    fun tick(pos: Double?, paused: Boolean, now: Long): Event {
        if (lastMove < 0) lastMove = now
        if (paused && !fired) { lastMove = now; steadySince = 0; return Event.NONE }
        if (pos != null && pos != lastPos) {
            val first = lastPos == null
            lastPos = pos; lastMove = now
            if (first) return Event.NONE // the first position seen: playback starts moving from here
            if (steadySince == 0L) steadySince = now
            if (!healthyFired && now - steadySince >= healthyMs) { healthyFired = true; return Event.HEALTHY }
            return Event.NONE
        }
        steadySince = 0
        if (!fired && now - lastMove >= stallMs) { fired = true; return Event.STALL }
        return Event.NONE
    }
}
