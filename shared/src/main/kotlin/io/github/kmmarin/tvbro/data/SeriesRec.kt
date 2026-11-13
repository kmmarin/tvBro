// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import kotlinx.serialization.Serializable

/** "Record series": every airing of [title] on one channel, scheduled from the guide as new airings appear. */
@Serializable
data class SeriesRule(val id: String, val chKey: String, val channel: String, val title: String, val created: Long)

object SeriesRec {
    /** Same programme title, ignoring case, accents, punctuation and a trailing "(R)" / "New:" style marker. */
    fun key(title: String): String =
        // Fold already lowercases, drops accents and turns punctuation into single spaces ("Who (R)" -> "who r")
        Fold.text(title).replace(Regex("^(new|nouveau|neu) "), "").replace(Regex(" (r|repeat|new)$"), "").trim()

    fun matches(rule: SeriesRule, p: Programme): Boolean = key(p.title) == key(rule.title)

    /**
     * Airings of [rule] in [progs] (one channel's guide) that should be scheduled now: not finished, starting within
     * [aheadSec], and not already scheduled ([scheduledStarts] = start times, epoch seconds, already recorded or queued).
     */
    fun due(rule: SeriesRule, progs: List<Programme>, scheduledStarts: Set<Long>, nowSec: Long, aheadSec: Long = 48 * 3600L): List<Programme> =
        progs.filter { it.stop > nowSec && it.start < nowSec + aheadSec && it.start !in scheduledStarts && matches(rule, it) }
            .distinctBy { it.start }.sortedBy { it.start }
}
