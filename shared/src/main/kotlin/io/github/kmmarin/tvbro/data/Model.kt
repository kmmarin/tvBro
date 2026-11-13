// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import kotlinx.serialization.Serializable

/** A playlist source: either an M3U URL (+ optional XMLTV URL) or an Xtream login. */
@Serializable
data class Source(
    val id: String,
    val name: String,
    val kind: String, // "m3u" or "xtream"
    val url: String = "",
    val epgUrl: String = "",
    val server: String = "",
    val user: String = "",
    val pass: String = "",
    val enabled: Boolean = true,
) {
    val isXtream: Boolean get() = kind == "xtream"
}

/** Catch-up description. [type] is xc, default, append, shift, flussonic... */
data class Catchup(val type: String, val template: String?)

data class Channel(
    val key: String,
    val sourceId: String,
    val name: String,
    val logo: String?,
    val group: String,
    val url: String,
    val tvgId: String?,
    val number: Int,
    val catchup: Catchup?,
    val catchupDays: Int,
    val idKey: String? = tvgId?.takeIf { it.isNotBlank() }?.let { "$sourceId|${it.lowercase()}" },
    val nameKey: String = "$sourceId|${name.lowercase()}",
)

/** Times are epoch seconds. [key] is the EPG key (source id | lowercase channel id or name). */
data class Programme(val key: String, val start: Long, val stop: Long, val title: String, val desc: String?, val sub: String? = null, val ep: String? = null)

open class EpgIndex(private val map: Map<String, Array<Programme>> = emptyMap()) {
    private val none = emptyArray<Programme>()

    open fun programmes(ch: Channel): Array<Programme> =
        (ch.idKey?.let { map[it] }) ?: map[ch.nameKey] ?: none

    fun nowNext(ch: Channel, now: Long): Pair<Programme?, Programme?> {
        val a = programmes(ch)
        if (a.isEmpty()) return null to null
        var lo = 0
        var hi = a.size - 1
        var idx = -1 // last programme with start <= now
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid].start <= now) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        val cur = if (idx >= 0 && a[idx].stop > now) a[idx] else null
        val next = a.getOrNull(idx + 1)
        return cur to next
    }

    companion object {
        val EMPTY = EpgIndex(emptyMap())

        /**
         * One channel's programmes as a timeline: sorted by start, one programme per start time, and a programme that
         * runs into the next one ends where the next one starts. Some providers send overlapping entries (10:00-14:00
         * and 11:00-14:00); drawn as they were, blocks covered each other, focus went to a hidden block and the guide
         * window jumped.
         */
        fun tidy(a: Array<Programme>): Array<Programme> {
            if (a.size < 2) return a
            val s = a.sortedWith(compareBy<Programme> { it.start }.thenByDescending { it.stop }).distinctBy { it.start }
            val out = ArrayList<Programme>(s.size)
            for (i in s.indices) {
                val p = s[i]
                val nx = s.getOrNull(i + 1)
                val q = if (nx != null && p.stop > nx.start) p.copy(stop = nx.start) else p
                if (q.stop > q.start) out += q
            }
            return out.toTypedArray()
        }
    }
}
