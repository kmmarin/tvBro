// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import java.text.Normalizer

/**
 * Alternate sources of a live channel: the same channel listed again by the playlist (or by another playlist), e.g.
 * "BBC One HD" / "BBC One FHD" / "BBC One (backup)", or another entry with the same tvg-id. The player tries them
 * when a channel stays down after its reconnects ([LiveRecovery]).
 *
 * Matching is deliberately conservative (same rules as the web app's alternates.py): the same tvg-id, or the same
 * name once quality / backup words and punctuation are dropped. Country prefixes ("UK:") and timeshift marks ("+1")
 * are kept, so "UK: Fox" never stands in for "US: Fox" and "Film4 +1" never for "Film4".
 */
object Alternates {
    /** Words that tell copies of one channel apart without changing what it shows. */
    val QUALITY = setOf(
        "hd", "fhd", "uhd", "sd", "hq", "lq", "4k", "8k", "hevc", "h265", "h264", "avc", "1080", "1080p", "1080i",
        "720", "720p", "576p", "2160p", "50fps", "60fps", "25fps", "fps", "backup", "bkp", "alt", "alternative", "raw",
        "source", "server", "feed", "link", "mirror", "low", "high", "fullhd", "ultra",
    )
    const val MAX_ALTS = 6
    private val TOKEN = Regex("\\+?[a-z0-9]+")
    private val BRACKET = Regex("[(\\[{]([^)\\]}]*)[)\\]}]")
    private val MARKS = Regex("\\p{M}+")

    private fun fold(s: String) = MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFKD), "").lowercase()
    private fun lone(t: String) = t.length == 1 && t[0].isDigit()

    /** The comparable form of a channel name ("" when nothing is left to compare). */
    fun normName(name: String): String {
        // "(backup)", "[FHD]", "(1080p 50fps)" go; "(Wales)" stays
        val s = BRACKET.replace(fold(name)) { m ->
            val inner = TOKEN.findAll(m.groupValues[1]).map { it.value }.toList()
            if (inner.isNotEmpty() && inner.all { it in QUALITY || lone(it) }) " " else " " + m.groupValues[1] + " "
        }
        val raw = TOKEN.findAll(s).map { it.value }.toList()
        val toks = raw.filter { it !in QUALITY }.toMutableList()
        // a trailing lone digit after a quality word ("HD 2", "Backup 1") numbers copies of the same channel
        val endsWithQuality = raw.size >= 2 && raw[raw.size - 2] in QUALITY
        while (toks.size > 1 && lone(toks.last()) && endsWithQuality) toks.removeAt(toks.size - 1)
        return toks.joinToString(" ")
    }

    fun normId(tvgId: String?): String = tvgId.orEmpty().trim().lowercase()
}

/** name / tvg-id -> channels, over every enabled playlist (build again when the channel list changes). */
class AltIndex(channels: List<Channel>) {
    private val byId = HashMap<String, MutableList<Channel>>()
    private val byName = HashMap<String, MutableList<Channel>>()
    private val byKey = HashMap<String, Channel>()

    init {
        for (c in channels) {
            byKey[c.key] = c
            Alternates.normId(c.tvgId).takeIf { it.isNotEmpty() }?.let { byId.getOrPut(it) { ArrayList() }.add(c) }
            Alternates.normName(c.name).takeIf { it.isNotEmpty() }?.let { byName.getOrPut(it) { ArrayList() }.add(c) }
        }
    }

    /**
     * Other channels showing the same thing as [key]: the same tvg-id first, then the same name; the channel's own
     * playlist before the others (same login, same server, usually). [usable] leaves out hidden / locked ones.
     */
    fun alternates(key: String, usable: (Channel) -> Boolean = { true }, limit: Int = Alternates.MAX_ALTS): List<Channel> {
        val c = byKey[key] ?: return emptyList()
        val out = ArrayList<Channel>()
        val id = Alternates.normId(c.tvgId); val name = Alternates.normName(c.name)
        for (bucket in listOf(if (id.isNotEmpty()) byId[id].orEmpty() else emptyList(), if (name.isNotEmpty()) byName[name].orEmpty() else emptyList())) {
            val (same, other) = bucket.partition { it.sourceId == c.sourceId }
            for (k in same + other) if (k.key != key && out.none { it.key == k.key } && k.url != c.url && usable(k)) out += k
        }
        return out.take(limit)
    }
}
