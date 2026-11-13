// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import java.text.Normalizer

/** A channel of a playlist's guide (XMLTV `<channel id>` and its first display name). */
data class GuideChannel(val id: String, val name: String)

/**
 * Manual channel-to-guide mapping: playlist channel key -> XMLTV channel id of the same playlist's guide, or [NONE]
 * (no guide: a wrong automatic match). Laid over the automatic match (tvg-id, then the name); the guide, now / next,
 * search and series recording read the mapped channel. Provider data is never changed, so a sync keeps every mapping.
 */
object GuideMap {
    const val NONE = "__none"

    /** [c] as the guide sees it with [id] (null / blank: automatic). */
    fun apply(c: Channel, id: String?): Channel = when {
        id.isNullOrBlank() -> c
        id == NONE -> c.copy(idKey = "${c.sourceId}|$NONE", nameKey = "${c.sourceId}|$NONE")
        else -> c.copy(idKey = "${c.sourceId}|${id.trim().lowercase()}")
    }

    private val MARKS = Regex("\\p{M}+")
    private val SEP = Regex("[^a-z0-9+]+")
    private fun fold(s: String) = SEP.replace(MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFKD), "").lowercase(), " ").trim()

    /**
     * Guide channels for the picker, searched by name or id: every word of [q] must start a word of the name or id
     * (diacritics and punctuation ignored); exact name matches first, then names starting with the query, then the
     * rest in the guide's order. Empty [q]: the channels closest to [hint] (the channel's own name) first.
     */
    fun search(all: List<GuideChannel>, q: String, hint: String = "", limit: Int = 200): List<GuideChannel> {
        val query = fold(q)
        if (query.isEmpty()) {
            val h = fold(Alternates.normName(hint)).split(' ').filter { it.isNotEmpty() }
            if (h.isEmpty()) return all.take(limit)
            val near = all.filter { g -> val w = words(g); h.all { t -> w.any { it.startsWith(t) } } }
            return (near + all.filter { it !in near }).take(limit)
        }
        val qs = query.split(' ')
        return all.asSequence().mapNotNull { g ->
            val w = words(g)
            if (!qs.all { t -> w.any { it.startsWith(t) } }) null
            else g to when { fold(g.name) == query -> 0; fold(g.name).startsWith(query) -> 1; else -> 2 }
        }.sortedBy { it.second }.map { it.first }.take(limit).toList()
    }

    private fun words(g: GuideChannel) = (fold(g.name) + " " + fold(g.id)).split(' ').filter { it.isNotEmpty() }
}
