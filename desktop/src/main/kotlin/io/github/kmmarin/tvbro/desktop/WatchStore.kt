// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import io.github.kmmarin.tvbro.data.*
import java.io.File

/** Saved position / watched mark of one movie, episode or catch-up programme. [series] = series key for episodes. */
@Serializable
data class WItem(val posMs: Long = 0, val durMs: Long = 0, val watched: Boolean = false, val updated: Long = 0, val series: String? = null) {
    fun state() = WatchState(posMs, durMs, watched, updated)
}

/** One series as "Continue watching" and the series page show it, plus its remembered caption / audio choice. */
@Serializable
data class SeriesRow(
    val key: String, val updated: Long = 0, val lastEp: String? = null,
    val nextEp: String? = null, val nextSeason: Int = 0, val nextNum: Int = 0, val nextTitle: String? = null,
    val finished: Boolean = false, val active: Boolean = false,
    val subsOn: Boolean? = null, val subsLang: String = "", val audioLang: String? = null,
) {
    val subPref: SubPref? get() = subsOn?.let { SubPref(it, subsLang) }
}

@Serializable
data class WatchData(
    val items: Map<String, WItem> = emptyMap(),
    val series: Map<String, SeriesRow> = emptyMap(),
    /** Catalogue entries of titles in progress, so "Continue watching" shows them before the catalogue loads. */
    val snaps: Map<String, VodItem> = emptyMap(),
)

/**
 * Watch state on disk (watch.json, written atomically shortly after a change). Keys: "m|src|id" movies,
 * "v|..." playlist VOD, "e|src|episodeId" episodes, "a|chKey|start" catch-up programmes; series rows by series key.
 */
class WatchStore(private val file: File, private val saveLater: (() -> Unit) -> Unit = { it() }, private val clock: () -> Long = System::currentTimeMillis) {
    var data by mutableStateOf(load())
        private set

    private fun load(): WatchData = try {
        if (!file.exists()) WatchData() else {
            val raw = Json2.json.decodeFromString(WatchData.serializer(), file.readText())
            bare(raw).also { b -> if (b !== raw) atomicWrite(file, Json2.json.encodeToString(WatchData.serializer(), b).toByteArray()) } // older versions saved addresses
        }
    }
        catch (e: Exception) { Diag.log("watch state unreadable (${e.javaClass.simpleName}), starting empty"); WatchData() }

    private fun commit(d: WatchData) {
        // keep the file bounded: the 3000 most recent items
        data = if (d.items.size > 3000) d.copy(items = d.items.entries.sortedByDescending { it.value.updated }.take(3000).associate { it.key to it.value }) else d
        saveLater { save() }
    }

    fun save() { try { file.parentFile?.mkdirs(); atomicWrite(file, Json2.json.encodeToString(WatchData.serializer(), data).toByteArray()) } catch (e: Exception) { Diag.log("watch state save failed") } }

    /** Backup restore: the backup's watch state replaces this one. */
    fun replace(d: WatchData) { commit(bare(d)) }

    fun state(key: String): WatchState? = data.items[key]?.state()
    fun item(key: String): WItem? = data.items[key]
    fun series(key: String): SeriesRow? = data.series[key]

    /** Position update during playback; at 90 % (or [ended]) the title becomes watched and its position is cleared. */
    fun setPos(key: String, posMs: Long, durMs: Long, series: String? = null, ended: Boolean = false): Boolean {
        val done = Watch.countsAsWatched(posMs, durMs, ended)
        val o = data.items[key] ?: WItem()
        val n = if (done) o.copy(posMs = 0, durMs = durMs.coerceAtLeast(o.durMs), watched = true, updated = clock(), series = series ?: o.series)
            else o.copy(posMs = posMs, durMs = if (durMs > 0) durMs else o.durMs, watched = false, updated = clock(), series = series ?: o.series)
        commit(data.copy(items = data.items + (key to n)))
        return done
    }

    fun setWatched(key: String, watched: Boolean, series: String? = null) {
        val o = data.items[key] ?: WItem()
        commit(data.copy(items = data.items + (key to o.copy(posMs = 0, watched = watched, updated = clock(), series = series ?: o.series))))
    }

    /** Several at once (a season, a whole show): one write. */
    fun setWatchedAll(keys: Collection<String>, watched: Boolean, series: String?) {
        val t = clock()
        val m = data.items.toMutableMap()
        for (k in keys) { val o = m[k] ?: WItem(); m[k] = o.copy(posMs = 0, watched = watched, updated = t, series = series ?: o.series) }
        commit(data.copy(items = m))
    }

    fun putSeries(r: SeriesRow) = commit(data.copy(series = data.series + (r.key to r)))
    fun snap(v: VodItem) { val b = bare(v); if (data.snaps[b.key] != b) commit(data.copy(snaps = data.snaps + (b.key to b))) }
    fun snapOf(key: String): VodItem? = data.snaps[key]

    /**
     * Recomputes a series row from its episode list and marks (next episode, finished, in "Continue watching").
     * [touch] moves it to the front of "Continue watching".
     */
    fun updateSeries(series: VodItem, eps: List<Episode>, lastId: String? = series(series.key)?.lastEp, touch: Boolean = true) {
        if (eps.isEmpty()) return
        val sum = Watch.summary(eps, { state(epKey(series, it)) }, lastId)
        val o = series(series.key) ?: SeriesRow(series.key)
        val n = sum.next
        val row = o.copy(
            updated = if (touch || o.updated == 0L) clock() else o.updated, lastEp = lastId,
            nextEp = n?.id, nextSeason = n?.season ?: 0, nextNum = n?.num ?: 0, nextTitle = n?.let { VodFmt.cleanTitle(series.name, it.title) },
            finished = sum.finished, active = sum.active,
        )
        commit(data.copy(series = data.series + (row.key to row), snaps = data.snaps + (series.key to bare(series))))
    }

    /** Marks [which] episodes; unmarking a whole show also forgets the last-played episode (it leaves Continue watching). */
    fun markEpisodes(series: VodItem, all: List<Episode>, which: List<Episode>, watched: Boolean) {
        setWatchedAll(which.map { epKey(series, it) }, watched, series.key)
        val wholeUnwatched = !watched && all.all { e -> which.any { it.id == e.id } }
        updateSeries(series, all, if (wholeUnwatched) null else series(series.key)?.lastEp, touch = watched)
    }

    /** Series in "Continue watching", most recent first, with their catalogue entry. */
    fun continueShows(): List<Pair<VodItem, SeriesRow>> =
        Watch.continueOrder(data.series.values.toList(), { it.active && it.nextEp != null }, { it.updated })
            .mapNotNull { r -> data.snaps[r.key]?.let { it to r } }

    /** Movies (and playlist VOD) in progress, most recent first. */
    fun continueMovies(): List<Pair<VodItem, WItem>> =
        Watch.continueOrder(data.items.entries.filter { it.key.startsWith("m|") || it.key.startsWith("v|") }, { it.value.state().inProgress }, { it.value.updated })
            .mapNotNull { e -> data.snaps[e.key]?.let { it to e.value } }

    fun rememberSubs(seriesKey: String, on: Boolean, lang: String?) {
        val o = series(seriesKey) ?: SeriesRow(seriesKey, updated = clock())
        putSeries(o.copy(subsOn = on, subsLang = lang ?: ""))
    }

    fun rememberAudio(seriesKey: String, lang: String?) {
        if (lang.isNullOrBlank()) return
        val o = series(seriesKey) ?: SeriesRow(seriesKey, updated = clock())
        putSeries(o.copy(audioLang = lang))
    }

    /** One-time import of the old settings.json resume map (seconds). */
    fun importLegacy(resume: Map<String, Long>) {
        if (resume.isEmpty()) return
        val m = data.items.toMutableMap()
        for ((k, s) in resume) if (k !in m) m[k] = WItem(posMs = s * 1000, updated = clock())
        commit(data.copy(items = m))
    }

    companion object {
        /** Saved catalogue copies keep no stream address: a playlist VOD address carries the provider login. */
        fun bare(v: VodItem): VodItem = if (v.url == null) v else v.copy(url = null)
        fun bare(d: WatchData): WatchData = if (d.snaps.values.none { it.url != null }) d else d.copy(snaps = d.snaps.mapValues { bare(it.value) })
        fun epKey(series: VodItem, e: Episode) = "e|${series.sourceId}|${e.id}"
    }
}
