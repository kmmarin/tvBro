// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.InputStream
import java.util.concurrent.ConcurrentLinkedDeque

/** Diagnostics: logs under tag "tvbro" and keeps the last lines for Settings. Never URLs, credentials or bodies. */
object Diag {
    private val lines = ConcurrentLinkedDeque<String>()
    /** Platform log sink (Android: logcat tag "tvbro"; desktop: stderr). */
    @Volatile var sink: (String) -> Unit = {}
    fun log(msg: String) {
        try { sink(msg) } catch (e: Throwable) { }
        val t = java.util.Calendar.getInstance()
        lines.add("%02d:%02d:%02d %s".format(t.get(java.util.Calendar.HOUR_OF_DAY), t.get(java.util.Calendar.MINUTE), t.get(java.util.Calendar.SECOND), msg))
        while (lines.size > 80) lines.pollFirst()
    }
    fun snapshot(): List<String> = lines.toList()
}

/** A movie or a series in the VOD catalogue. [url] is set for direct (M3U) items. */
@Serializable
data class VodItem(
    val key: String, val sourceId: String, val kind: String, val id: String, val name: String,
    val poster: String?, val category: String, val rating: String?, val ext: String, val url: String? = null,
)

data class Episode(val id: String, val season: Int, val num: Int, val title: String, val ext: String, val plot: String?, val duration: String?, val image: String? = null)

data class VodDetail(
    val plot: String?, val year: String?, val rating: String?, val cast: String?, val genre: String?,
    val trailer: String?, val duration: String?, val episodes: List<Episode> = emptyList(),
    val director: String? = null, val ext: String? = null, val backdrop: String? = null,
)

object VodFmt {
    /** "01:32:00", "42:15" or plain seconds to seconds; 0 if unknown. */
    fun seconds(d: String?): Long {
        val s = d?.trim() ?: return 0
        if (s.isEmpty()) return 0
        return try {
            if (s.contains(':')) s.split(':').fold(0L) { a, x -> a * 60 + x.trim().toDouble().toLong() } else s.toDouble().toLong()
        } catch (e: NumberFormatException) { 0 }
    }

    fun fmt(sec: Long): String = if (sec >= 3600) "${sec / 3600}h ${(sec % 3600) / 60}m" else "${sec / 60}m"
    fun runtime(d: String?): String? = seconds(d).takeIf { it > 0 }?.let { fmt(it) }

    /** Strips the series name, year and "SxxEyy - " prefixes that providers put in episode titles. */
    fun cleanTitle(series: String, title: String): String {
        var t = title.trim()
        if (series.isNotBlank() && t.startsWith(series, true)) t = t.substring(series.length)
        t = t.replace(Regex("\\(\\d{4}\\)"), " ")
        t = t.replace(Regex("^[\\s\\-–:._|]*[Ss]\\d{1,2}\\s*[Ee]\\d{1,3}[\\s\\-–:._|]*"), "")
        t = t.replace(Regex("^[\\s\\-–:._|]+"), "").replace(Regex("[\\s\\-–:._|]+$"), "").replace(Regex("\\s+"), " ")
        return t.ifBlank { title.trim() }
    }
}

/** Real providers send every field as string, number, "" or null; these helpers accept all of them. */
internal fun JsonElement?.str(): String? = when (this) {
    null -> null
    is JsonNull -> null
    is JsonPrimitive -> content.trim().takeIf { it.isNotEmpty() && it != "null" }
    else -> null
}

internal fun JsonObject.s(vararg keys: String): String? {
    for (k in keys) this[k].str()?.let { return it }
    return null
}

@OptIn(ExperimentalSerializationApi::class)
object VodApi {
    fun isVodUrl(u: String) = u.contains("/movie/") || u.contains("/series/")

    fun fromChannel(c: Channel) = VodItem("v|" + c.key, c.sourceId, "movie", c.key, c.name, c.logo, c.group, null, "", c.url)

    fun movieUrl(server: String, u: String, p: String, id: String, ext: String) = "${Xtream.normalizeServer(server)}/movie/$u/$p/$id.$ext"
    fun episodeUrl(server: String, u: String, p: String, id: String, ext: String) = "${Xtream.normalizeServer(server)}/series/$u/$p/$id.$ext"

    /** Streams the top-level elements of a JSON list (or a map of id to object); tolerant of a BOM and error objects. */
    private fun elements(input: InputStream, what: String): Iterator<JsonElement> {
        val b = input.buffered()
        b.mark(1 shl 16)
        var c = b.read()
        var n = 0
        while ((c == 32 || c == 9 || c == 10 || c == 13 || c == 0xEF || c == 0xBB || c == 0xBF) && n++ < 1000) c = b.read()
        b.reset()
        b.skip(n.toLong())
        if (c == '['.code) {
            return Xtream.json.decodeToSequence(b, JsonElement.serializer(), DecodeSequenceMode.ARRAY_WRAPPED).iterator()
        }
        val root = Xtream.json.parseToJsonElement(b.readBytes().decodeToString())
        if (root is JsonObject) {
            val items = root.values.filterIsInstance<JsonObject>().filter { it.containsKey("name") || it.containsKey("category_id") || it.containsKey("stream_id") || it.containsKey("series_id") }
            if (items.isEmpty()) Diag.log("$what: response is an object without items (keys=${root.keys.take(5).joinToString(",")})")
            return items.iterator()
        }
        Diag.log("$what: unexpected JSON root")
        return emptyList<JsonElement>().iterator()
    }

    /** Maps each item independently; one bad item never fails the list. [map] throws IllegalArgumentException("missing <field>") to skip. */
    private fun <T> collect(input: InputStream, what: String, limit: Int, map: (JsonObject) -> T): List<T> {
        val out = ArrayList<T>()
        each(input, what, limit, map) { out += it }
        return out
    }

    /** Streams mapped items to [sink] without holding the list; returns the number delivered. */
    private fun <T> each(input: InputStream, what: String, limit: Int, map: (JsonObject) -> T, sink: (T) -> Unit): Int {
        var count = 0
        var idx = 0
        var bad = 0
        val it = try { elements(input, what) } catch (e: Exception) { Diag.log("$what: unreadable response (${e.javaClass.simpleName})"); return 0 }
        while (true) {
            val el = try { if (!it.hasNext()) break; it.next() } catch (e: Exception) { Diag.log("$what: stream error after item $idx (${e.javaClass.simpleName}), keeping $idx items"); break }
            idx++
            val o = el as? JsonObject
            if (o == null) { bad++; if (bad <= 5) Diag.log("$what: item $idx is not an object"); continue }
            val v = try { map(o) } catch (e: Exception) { bad++; if (bad <= 5) Diag.log("$what: item $idx skipped (${e.message ?: e.javaClass.simpleName})"); continue }
            sink(v); count++
            if (count >= limit) { Diag.log("$what: stopped at limit $limit"); break }
        }
        Diag.log("$what: parsed $count, skipped $bad")
        return count
    }

    private fun req(o: JsonObject, vararg keys: String) = o.s(*keys) ?: throw IllegalArgumentException("missing ${keys[0]}")

    fun parseCategories(input: InputStream, what: String = "categories"): List<XtreamCategory> =
        collect(input, what, 100_000) { o ->
            val id = req(o, "category_id", "id")
            XtreamCategory(id, o.s("category_name", "name") ?: id)
        }

    private fun movie(o: JsonObject, sourceId: String, cm: Map<String, String>): VodItem {
        val id = req(o, "stream_id", "id")
        return VodItem("m|$sourceId|$id", sourceId, "movie", id, o.s("name", "title") ?: "Movie $id",
            o.s("stream_icon", "cover", "movie_image"), cm[o.s("category_id") ?: ""] ?: "Other",
            o.s("rating", "rating_5based"), o.s("container_extension") ?: "mp4")
    }

    private fun series(o: JsonObject, sourceId: String, cm: Map<String, String>): VodItem {
        val id = req(o, "series_id", "id")
        return VodItem("s|$sourceId|$id", sourceId, "series", id, o.s("name", "title") ?: "Series $id",
            o.s("cover", "stream_icon"), cm[o.s("category_id") ?: ""] ?: "Other", o.s("rating", "rating_5based"), "")
    }

    fun parseMovies(input: InputStream, sourceId: String, cats: List<XtreamCategory>, limit: Int = 30_000, what: String = "movies"): List<VodItem> {
        val cm = cats.associate { it.categoryId to it.categoryName }
        return collect(input, what, limit) { movie(it, sourceId, cm) }
    }

    fun parseSeriesList(input: InputStream, sourceId: String, cats: List<XtreamCategory>, limit: Int = 30_000, what: String = "series"): List<VodItem> {
        val cm = cats.associate { it.categoryId to it.categoryName }
        return collect(input, what, limit) { series(it, sourceId, cm) }
    }

    /** Whole catalogue, streamed item by item (for the on-disk catalogue / search index). */
    fun eachVod(input: InputStream, series: Boolean, sourceId: String, cats: List<XtreamCategory>, what: String, sink: (VodItem) -> Unit): Int {
        val cm = cats.associate { it.categoryId to it.categoryName }
        return each(input, what, Int.MAX_VALUE, { if (series) series(it, sourceId, cm) else movie(it, sourceId, cm) }, sink)
    }

    private fun obj(e: JsonElement?): JsonObject = (e as? JsonObject) ?: JsonObject(emptyMap())

    private fun detail(i: JsonObject, eps: List<Episode>, ext: String?) = VodDetail(
        plot = i.s("plot", "description"), year = i.s("releasedate", "releaseDate", "release_date", "year")?.take(4),
        rating = i.s("rating", "rating_5based"), cast = i.s("cast", "actors"), genre = i.s("genre"),
        trailer = i.s("youtube_trailer", "trailer")?.let { if (it.startsWith("http")) it else "https://www.youtube.com/watch?v=$it" },
        duration = i.s("duration") ?: i.s("duration_secs"), episodes = eps, director = i.s("director"), ext = ext,
        backdrop = ((i["backdrop_path"] as? JsonArray)?.firstOrNull().str()) ?: i.s("backdrop_path", "cover_big", "movie_image"),
    )

    fun parseVodInfo(text: String): VodDetail {
        val root = Xtream.json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())
        return detail(obj(root["info"]), emptyList(), obj(root["movie_data"]).s("container_extension"))
    }

    private fun episode(o: JsonObject, season: Int): Episode? {
        val id = o.s("id") ?: return null
        val inf = obj(o["info"])
        return Episode(id, o.s("season")?.toDoubleOrNull()?.toInt() ?: season, o.s("episode_num")?.toDoubleOrNull()?.toInt() ?: 0,
            o.s("title") ?: "Episode", o.s("container_extension") ?: "mp4", inf.s("plot"), inf.s("duration", "duration_secs"), inf.s("movie_image", "cover_big"))
    }

    /** `episodes` is normally an object keyed by season, but some panels send an array (of seasons or of episodes). */
    fun parseSeriesInfo(text: String): VodDetail {
        val root = Xtream.json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())
        val eps = ArrayList<Episode>()
        when (val e = root["episodes"]) {
            is JsonObject -> e.forEach { (season, arr) ->
                val sn = season.toIntOrNull() ?: 0
                when (arr) {
                    is JsonArray -> arr.forEach { x -> (x as? JsonObject)?.let { episode(it, sn)?.let(eps::add) } }
                    is JsonObject -> episode(arr, sn)?.let(eps::add)
                    else -> {}
                }
            }
            is JsonArray -> e.forEachIndexed { i, x ->
                when (x) {
                    is JsonArray -> x.forEach { y -> (y as? JsonObject)?.let { episode(it, i + 1)?.let(eps::add) } }
                    is JsonObject -> episode(x, 1)?.let(eps::add)
                    else -> {}
                }
            }
            else -> {}
        }
        eps.sortWith(compareBy({ it.season }, { it.num }))
        return detail(obj(root["info"]), eps, null)
    }
}
