// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.InputStream

// Models for the public player_api.php JSON. Providers mix numbers and strings, so everything
// is decoded leniently as strings.
@Serializable
data class XtreamCategory(
    @SerialName("category_id") val categoryId: String = "",
    @SerialName("category_name") val categoryName: String = "",
)

@Serializable
data class XtreamLiveStream(
    val num: String? = null,
    val name: String = "",
    @SerialName("stream_id") val streamId: String = "",
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("epg_channel_id") val epgChannelId: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("tv_archive") val tvArchive: String? = null,
    @SerialName("tv_archive_duration") val tvArchiveDuration: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
object Xtream {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** A server saved without a scheme (older versions) is plain http, as it always was; new ones go through [pickServer]. */
    fun normalizeServer(s: String): String {
        var t = s.trim().trimEnd('/')
        if (!hasScheme(t)) t = "http://$t"
        return t
    }

    fun hasScheme(s: String): Boolean = s.trim().let { it.startsWith("http://", true) || it.startsWith("https://", true) }

    /**
     * Addresses to try for a server as typed: with a scheme only that one; without one https first, then http
     * (Xtream puts the login in every address, so https is worth a try before falling back).
     */
    fun serverCandidates(s: String): List<String> {
        val t = s.trim().trimEnd('/')
        if (t.isEmpty()) return emptyList()
        return if (hasScheme(t)) listOf(t) else listOf("https://$t", "http://$t")
    }

    /**
     * The server address to save: the first of [serverCandidates] for which [reachable] is true (the caller checks
     * that an HTTP answer came back, whatever its status), else the last one (plain http). Remembered by saving it.
     */
    fun pickServer(s: String, reachable: (String) -> Boolean): String {
        val c = serverCandidates(s)
        if (c.size <= 1) return c.firstOrNull() ?: ""
        return c.firstOrNull { reachable(it) } ?: c.last()
    }

    /** The login travels unencrypted (http, or no scheme = http). For the "unencrypted connection" hint. */
    fun isCleartext(server: String): Boolean = !normalizeServer(server).startsWith("https://", true)

    /** player_api.php without an action: the account check, used to probe a server. */
    fun authUrl(server: String, user: String, pass: String): String {
        val e = { v: String -> java.net.URLEncoder.encode(v, "UTF-8") }
        return "${normalizeServer(server)}/player_api.php?username=${e(user)}&password=${e(pass)}"
    }

    fun apiUrl(server: String, user: String, pass: String, action: String): String {
        val e = { v: String -> java.net.URLEncoder.encode(v, "UTF-8") }
        return "${normalizeServer(server)}/player_api.php?username=${e(user)}&password=${e(pass)}&action=$action"
    }

    fun xmltvUrl(server: String, user: String, pass: String): String {
        val e = { v: String -> java.net.URLEncoder.encode(v, "UTF-8") }
        return "${normalizeServer(server)}/xmltv.php?username=${e(user)}&password=${e(pass)}"
    }

    fun categories(i: InputStream): List<XtreamCategory> = try {
        json.decodeFromStream<List<XtreamCategory>>(i)
    } catch (e: Exception) { emptyList() }

    fun liveStreams(i: InputStream): List<XtreamLiveStream> = try {
        json.decodeFromStream<List<XtreamLiveStream>>(i)
    } catch (e: Exception) { emptyList() }

    fun toChannels(
        sourceId: String, server: String, user: String, pass: String,
        cats: List<XtreamCategory>, streams: List<XtreamLiveStream>,
    ): List<Channel> {
        val base = normalizeServer(server)
        val catMap = cats.associate { it.categoryId to it.categoryName }
        val order = cats.withIndex().associate { it.value.categoryId to it.index }
        return streams.withIndex().sortedWith(compareBy({ order[it.value.categoryId ?: ""] ?: Int.MAX_VALUE }, { it.index })).map { it.value }.map { s ->
            val archive = s.tvArchive == "1"
            Channel(
                key = "$sourceId|${s.streamId}",
                sourceId = sourceId,
                name = s.name.trim().ifBlank { "Channel ${s.streamId}" },
                logo = s.streamIcon?.takeIf { it.isNotBlank() },
                group = catMap[s.categoryId ?: ""]?.takeIf { it.isNotBlank() } ?: "Other",
                url = "$base/live/$user/$pass/${s.streamId}.ts",
                tvgId = s.epgChannelId?.takeIf { it.isNotBlank() },
                number = s.num?.toIntOrNull() ?: 0,
                catchup = if (archive) Catchup("xc", "$base/timeshift/$user/$pass/{duration}/{start}/${s.streamId}.ts") else null,
                catchupDays = if (archive) s.tvArchiveDuration?.toIntOrNull() ?: 3 else 0,
            )
        }
    }
}
