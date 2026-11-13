// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

/** Previous search keywords: newest first, de-duplicated without regard to case, capped. */
object SearchHistory {
    const val MAX = 20

    fun add(list: List<String>, query: String, max: Int = MAX): List<String> {
        val q = query.trim().replace(Regex("\\s+"), " ")
        if (q.length < 2) return list
        return (listOf(q) + list.filter { !it.equals(q, ignoreCase = true) }).take(max)
    }

    fun remove(list: List<String>, query: String): List<String> = list.filter { !it.equals(query.trim(), ignoreCase = true) }

    fun encode(list: List<String>): String = list.joinToString("\n") { it.replace('\n', ' ') }
    fun decode(s: String?): List<String> = (s ?: "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
}

/** Where a programme sits relative to now. PAST_CATCHUP: finished, and the channel's archive still covers it. */
enum class ProgState { PAST, PAST_CATCHUP, NOW, FUTURE }

object ProgTime {
    /** All times are epoch seconds. */
    fun classify(ch: Channel?, start: Long, stop: Long, now: Long): ProgState = when {
        start > now -> ProgState.FUTURE
        stop > now -> ProgState.NOW
        ch != null && CatchupUrl.supports(ch, start, now) -> ProgState.PAST_CATCHUP
        else -> ProgState.PAST
    }

    fun minutesLeft(stop: Long, now: Long): Long = ((stop - now).coerceAtLeast(0) + 59) / 60

    fun progress(start: Long, stop: Long, now: Long): Float =
        ((now - start).toFloat() / (stop - start).coerceAtLeast(1)).coerceIn(0f, 1f)
}

/** Episode codes from XMLTV `episode-num` (systems xmltv_ns and onscreen) or from a title, shown as "S2 E4". */
object EpisodeNum {
    private val onscreen = Regex("""(?i)\bS(?:eason)?\s*(\d{1,3})\s*[._x-]?\s*E(?:p(?:isode)?)?\s*(\d{1,4})\b""")
    private val epOnly = Regex("""(?i)^\s*(?:E|Ep|Ep\.|Episode)\s*(\d{1,4})\s*$""")
    private val cross = Regex("""^\s*(\d{1,3})\s*[xX]\s*(\d{1,4})\s*$""")

    fun label(season: Int?, episode: Int?): String? = when {
        season != null && episode != null -> "S$season E$episode"
        episode != null -> "E$episode"
        season != null -> "S$season"
        else -> null
    }

    /** xmltv_ns is "season.episode.part", each zero-based and optionally "n/total"; any part may be empty. */
    fun fromXmltvNs(text: String): String? {
        val parts = text.trim().split('.')
        fun num(i: Int): Int? = parts.getOrNull(i)?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it >= 0 }?.plus(1)
        return label(num(0), num(1))
    }

    fun fromOnscreen(text: String): String? {
        onscreen.find(text)?.let { return label(it.groupValues[1].toIntOrNull(), it.groupValues[2].toIntOrNull()) }
        cross.find(text)?.let { return label(it.groupValues[1].toIntOrNull(), it.groupValues[2].toIntOrNull()) }
        epOnly.find(text)?.let { return label(null, it.groupValues[1].toIntOrNull()) }
        return null
    }

    fun parse(system: String?, text: String): String? = when (system?.lowercase()) {
        "xmltv_ns" -> fromXmltvNs(text)
        null, "", "onscreen", "sxxexx" -> fromOnscreen(text)
        else -> null // dd_progid, crid and other ids are not episode numbers
    }

    /** "S2 E4 Sub-title", either part optional; null when there is neither. */
    fun line(code: String?, subTitle: String?): String? =
        listOfNotNull(code?.takeIf { it.isNotBlank() }, subTitle?.trim()?.takeIf { it.isNotEmpty() }).joinToString(" ").takeIf { it.isNotEmpty() }
}
