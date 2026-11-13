// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import java.util.Locale

/** Saved playback state of one movie or episode. */
data class WatchState(val posMs: Long = 0, val durMs: Long = 0, val watched: Boolean = false, val updated: Long = 0) {
    /** Started, not finished: worth offering "Resume". */
    val inProgress: Boolean get() = !watched && posMs >= Watch.MIN_RESUME_MS
    val fraction: Float get() = if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
}

/** Subtitle choice remembered for a series: off, or on with a language ("" = first text track). */
data class SubPref(val on: Boolean, val lang: String = "")

/** What a series looks like for "Continue watching" and the series page. */
data class SeriesSummary(val next: Episode?, val finished: Boolean, val active: Boolean)

/** A track as the player reports it (only what the preference logic needs). */
data class TrackInfo(val lang: String?, val label: String? = null)

sealed class TrackChoice {
    /** No saved preference: leave the player's default. */
    object Default : TrackChoice()
    object Off : TrackChoice()
    data class Pick(val index: Int) : TrackChoice()
}

/** Platform-neutral rules for resume, watched marks, next episode, continue watching and per-series track choices. */
object Watch {
    /** Positions below this are not worth resuming. */
    const val MIN_RESUME_MS = 5_000L
    /** Played past this share of the duration counts as watched. */
    const val WATCHED_FRACTION = 0.90
    /** "Up next" appears this long before the end, when the duration is known and long enough. */
    const val UP_NEXT_LEAD_MS = 30_000L
    const val UP_NEXT_MIN_DURATION_MS = 5 * 60_000L
    const val UP_NEXT_COUNTDOWN_S = 10

    /** True when a play-through at [posMs] of [durMs] (or one that reached the end) counts as watched. */
    fun countsAsWatched(posMs: Long, durMs: Long, ended: Boolean = false): Boolean =
        ended || (durMs > 0 && posMs >= durMs * WATCHED_FRACTION)

    /** True when the "Up next" card should show during playback (credits). */
    fun upNextDue(posMs: Long, durMs: Long): Boolean =
        durMs >= UP_NEXT_MIN_DURATION_MS && posMs >= durMs - UP_NEXT_LEAD_MS

    /** Play order: seasons ascending, episode numbers ascending; season 0 (specials) last. */
    fun ordered(eps: List<Episode>): List<Episode> =
        eps.sortedWith(compareBy<Episode>({ if (it.season <= 0) Int.MAX_VALUE else it.season }, { it.num }))

    /** The episode after [currentId] in play order (crossing into the next season), or null at the end. */
    fun next(eps: List<Episode>, currentId: String): Episode? {
        val o = ordered(eps)
        val i = o.indexOfFirst { it.id == currentId }
        return if (i < 0) null else o.getOrNull(i + 1)
    }

    /**
     * The episode to offer for a series. With a last-played episode: that one while it is not watched, otherwise the
     * first unwatched episode after it. Without one: the first in-progress episode, otherwise the first unwatched
     * episode after the furthest watched one, otherwise the first episode. Null when nothing is left to watch.
     */
    fun resume(eps: List<Episode>, state: (Episode) -> WatchState?, lastId: String?): Episode? {
        val o = ordered(eps)
        if (o.isEmpty()) return null
        val w = { e: Episode -> state(e)?.watched == true }
        val li = if (lastId == null) -1 else o.indexOfFirst { it.id == lastId }
        if (li >= 0) {
            if (!w(o[li])) return o[li]
            return o.drop(li + 1).firstOrNull { !w(it) }
        }
        o.firstOrNull { state(it)?.inProgress == true }?.let { return it }
        val lastWatched = o.indexOfLast(w)
        if (lastWatched < 0) return o.first()
        return o.drop(lastWatched + 1).firstOrNull { !w(it) }
    }

    /** Next episode, whether the series is finished, and whether it belongs in "Continue watching". */
    fun summary(eps: List<Episode>, state: (Episode) -> WatchState?, lastId: String?): SeriesSummary {
        val n = resume(eps, state, lastId)
        val any = lastId != null || eps.any { state(it)?.let { s -> s.watched || s.inProgress } == true }
        val finished = eps.isNotEmpty() && n == null
        return SeriesSummary(n, finished, any && !finished)
    }

    /** "Continue watching" order: active entries only, most recent first. */
    fun <T> continueOrder(items: List<T>, active: (T) -> Boolean, updated: (T) -> Long): List<T> =
        items.filter(active).sortedByDescending(updated)

    // ---- per-series tracks ----

    private val bibliographic = mapOf(
        "fre" to "fra", "ger" to "deu", "dut" to "nld", "chi" to "zho", "cze" to "ces", "gre" to "ell", "per" to "fas",
        "rum" to "ron", "slo" to "slk", "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "geo" to "kat",
        "ice" to "isl", "mac" to "mkd", "mao" to "mri", "may" to "msa", "wel" to "cym", "tib" to "bod",
    )

    /** Normalises "en", "eng", "en-US", "EN_gb", "fre" and "fra" to one ISO 639-2/T code; null for none / undetermined. */
    fun langKey(code: String?): String? {
        val c = code?.trim()?.lowercase()?.split('-', '_')?.firstOrNull()?.takeIf { it.isNotEmpty() && it != "und" && it != "unknown" } ?: return null
        bibliographic[c]?.let { return it }
        if (c.length == 3) return c
        return try { Locale(c).isO3Language.ifEmpty { c } } catch (e: Exception) { c }
    }

    fun sameLang(a: String?, b: String?): Boolean { val x = langKey(a); return x != null && x == langKey(b) }

    /** Subtitle track to select for a saved series preference. */
    fun pickText(pref: SubPref?, tracks: List<TrackInfo>): TrackChoice {
        if (pref == null) return TrackChoice.Default
        if (!pref.on) return TrackChoice.Off
        if (tracks.isEmpty()) return TrackChoice.Default
        val i = if (pref.lang.isBlank()) -1 else tracks.indexOfFirst { sameLang(it.lang, pref.lang) }
        return TrackChoice.Pick(if (i >= 0) i else 0)
    }

    /** Audio track matching the saved language, or null to keep the default. */
    fun pickAudio(lang: String?, tracks: List<TrackInfo>): Int? {
        if (lang.isNullOrBlank()) return null
        return tracks.indexOfFirst { sameLang(it.lang, lang) }.takeIf { it >= 0 }
    }

    /** "12:31" or "1:02:05" for a resume label. */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** "S2 E4" (or "E4" without a season). */
    fun code(e: Episode): String = if (e.season > 0) "S${e.season} E${e.num}" else "E${e.num}"
}
