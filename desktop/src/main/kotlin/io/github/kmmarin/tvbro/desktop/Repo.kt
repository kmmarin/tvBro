// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import okhttp3.OkHttpClient
import okhttp3.Request
import org.kxml2.io.KXmlParser
import io.github.kmmarin.tvbro.data.*
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object Net {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(true).build()

    /**
     * Downloads [url] (inflating a gzip body) into [to] through [sink] (the cache's encrypting writer), atomically.
     * Never logs the URL (it carries credentials).
     */
    fun download(url: String, to: File, sink: (File) -> OutputStream) {
        val tmp = File(to.parentFile, to.name + ".part")
        val local = File(url.removePrefix("file://"))
        if (!url.startsWith("http") && local.isFile) { // local playlist / guide file
            sink(tmp).use { out -> local.inputStream().use { it.copyTo(out) } }
            Perms.replace(tmp, to)
            return
        }
        val req = Request.Builder().url(url).header("User-Agent", "tvBro/0.1").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("empty body")
            var input: InputStream = body.byteStream().buffered()
            input.mark(4)
            val gz = input.read() == 0x1f && input.read() == 0x8b
            input.reset()
            if (gz) input = GZIPInputStream(input)
            try { sink(tmp).use { out -> input.use { it.copyTo(out) } } } catch (e: Exception) { tmp.delete(); throw e }
            Perms.replace(tmp, to)
        }
    }

    fun text(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "tvBro/0.1").build()
        http.newCall(req).execute().use { r -> if (!r.isSuccessful) throw IOException("HTTP ${r.code}"); return r.body?.string() ?: "" }
    }

    /** Any HTTP answer (whatever the status) came back from [url]: the scheme and the TLS handshake work. */
    fun reachable(url: String): Boolean = try {
        http.newBuilder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).followRedirects(false).build()
            .newCall(Request.Builder().url(url).header("User-Agent", "tvBro/0.1").build()).execute().use { true }
    } catch (e: Exception) { false }

    fun describe(e: Throwable) = when (e) {
        is java.net.UnknownHostException -> "server not found"
        is java.net.SocketTimeoutException -> "timed out"
        is java.net.ConnectException -> "connection refused"
        else -> e.message?.takeIf { !it.contains("://") } ?: e.javaClass.simpleName
    }
}

/**
 * The key of the cache files: random, kept encrypted like the logins ([Secrets] "cachekey": DPAPI / keyring / key
 * file). A key that cannot be read any more only costs a fresh download.
 */
object CacheKey {
    @Volatile private var k: ByteArray? = null
    fun get(): ByteArray = k ?: synchronized(this) {
        k ?: run {
            val old = try { Secrets.read("cachekey")?.takeIf { it.size == 32 } } catch (e: Exception) { Diag.log("cache key unreadable: new key, the cache is downloaded again"); null }
            old ?: ByteArray(32).also { java.security.SecureRandom().nextBytes(it); Secrets.write("cachekey", it) }
        }.also { k = it }
    }
}

/**
 * Cache-first file store: everything is downloaded into gzip files and parsed from disk. The files are encrypted
 * ([Sealed], AES-256-GCM): playlists and catalogues hold stream addresses with the provider login.
 */
class Cache(private val src: Source, private val key: () -> ByteArray = CacheKey::get, root: File = Paths.cache) {
    private val dir = Perms.dir(File(root, hash(src.id)))
    init { dir.listFiles { f -> f.name.endsWith(".gz") }?.forEach { it.delete() } } // plaintext cache of older versions
    fun file(name: String) = File(dir, "$name.sgz")
    /** Encrypting, compressing writer for a new file (owner-only). */
    fun writer(f: File): OutputStream = GZIPOutputStream(SealedOutputStream(Perms.newOutput(f), key()), 1 shl 16)
    /** Usable: present and written with the current key. */
    fun has(name: String): Boolean = file(name).let { it.exists() && Sealed.isSealedWith(it, key()) }
    fun open(name: String): InputStream? = file(name).takeIf { has(name) }?.let { GZIPInputStream(SealedInputStream(it.inputStream().buffered(1 shl 16), key()), 1 shl 16) }
    fun ageHours(name: String): Double = file(name).let { if (!has(name)) Double.MAX_VALUE else (System.currentTimeMillis() - it.lastModified()) / 3.6e6 }
    fun download(name: String, url: String) = Net.download(url, file(name), ::writer)
    fun clear() = dir.listFiles()?.forEach { it.delete() }

    companion object {
        fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }
}

data class Catalogue(val channels: List<Channel>, val epgUrls: List<String>)

/** All network and parsing for one source. */
class SourceRepo(val src: Source) {
    val cache = Cache(src)
    private fun api(action: String) = Xtream.apiUrl(src.server, src.user, src.pass, action)

    private fun ensure(name: String, url: String, maxAgeH: Int, force: Boolean) {
        if (force || cache.ageHours(name) > maxAgeH) {
            try { cache.download(name, url) } catch (e: Exception) {
                if (!cache.has(name)) throw e
                Diag.log("${src.name}: $name refresh failed (${Net.describe(e)}), using cache")
            }
        }
    }

    /** Live channels. Cache-first: only downloads when older than [maxAgeH] (or [force]). */
    fun channels(maxAgeH: Int, force: Boolean): Catalogue {
        if (src.isXtream) {
            ensure("live_cats", api("get_live_categories"), maxAgeH, force)
            ensure("live", api("get_live_streams"), maxAgeH, force)
            val cats = cache.open("live_cats")!!.use { VodApi.parseCategories(it, "live categories") }
            val streams = cache.open("live")!!.use { Xtream.liveStreams(it) }
            return Catalogue(Xtream.toChannels(src.id, src.server, src.user, src.pass, cats, streams), listOf(Xtream.xmltvUrl(src.server, src.user, src.pass)))
        }
        ensure("m3u", src.url, maxAgeH, force)
        val r = cache.open("m3u")!!.bufferedReader().useLines { M3uParser.parse(it, src.id) }
        val live = r.channels.filter { !VodApi.isVodUrl(it.url) }
        return Catalogue(live, listOfNotNull(src.epgUrl.takeIf { it.isNotBlank() }) + r.epgUrls)
    }

    /** M3U items whose URL looks like VOD, as movies. */
    fun m3uVod(): List<VodItem> = cache.open("m3u")?.bufferedReader()?.useLines { M3uParser.parse(it, src.id) }
        ?.channels?.filter { VodApi.isVodUrl(it.url) }?.map { VodApi.fromChannel(it) } ?: emptyList()

    /**
     * Parses the XMLTV guide into [sink] for the window 3 days back to 2 days ahead. Returns the guide's channels
     * (XMLTV id -> display names), for the guide-channel picker.
     */
    fun epg(urls: List<String>, channels: List<Channel>, maxAgeH: Int, force: Boolean, sink: (Programme) -> Unit): Map<String, List<String>> {
        val ids = channels.mapNotNullTo(HashSet()) { it.idKey }
        val names = channels.mapTo(HashSet()) { it.nameKey }
        val now = System.currentTimeMillis() / 1000
        val guide = LinkedHashMap<String, List<String>>()
        urls.take(3).forEachIndexed { i, u ->
            val name = "epg$i"
            try { ensure(name, u, maxAgeH, force) } catch (e: Exception) { Diag.log("${src.name}: guide download failed (${Net.describe(e)})"); return@forEachIndexed }
            try {
                cache.open(name)!!.use { input ->
                    val p = KXmlParser()
                    p.setInput(XmlGuard.check(input), null) // no entity declarations (billion laughs / XXE)
                    guide += XmltvParser.parse(p, { ch, dn ->
                        val k = "${src.id}|${ch.lowercase()}"
                        if (k in ids) k else dn?.firstNotNullOfOrNull { n -> "${src.id}|${n.lowercase()}".takeIf { it in names } }
                            ?: k.takeIf { it in names }
                    }, now - 3 * 86400, now + 2 * 86400, sink)
                }
            } catch (e: Exception) { Diag.log("${src.name}: guide parse stopped (${e.javaClass.simpleName})") }
        }
        return guide
    }

    fun movies(maxAgeH: Int, force: Boolean): List<VodItem> {
        if (!src.isXtream) return m3uVod()
        ensure("vod_cats", api("get_vod_categories"), maxAgeH, force)
        ensure("vod", api("get_vod_streams"), maxAgeH, force)
        val cats = cache.open("vod_cats")!!.use { VodApi.parseCategories(it, "movie categories") }
        return cache.open("vod")!!.use { VodApi.parseMovies(it, src.id, cats, 200_000) }
    }

    fun series(maxAgeH: Int, force: Boolean): List<VodItem> {
        if (!src.isXtream) return emptyList()
        ensure("series_cats", api("get_series_categories"), maxAgeH, force)
        ensure("series", api("get_series"), maxAgeH, force)
        val cats = cache.open("series_cats")!!.use { VodApi.parseCategories(it, "series categories") }
        return cache.open("series")!!.use { VodApi.parseSeriesList(it, src.id, cats, 200_000) }
    }

    fun detail(item: VodItem): VodDetail = when {
        !src.isXtream -> VodDetail(null, null, null, null, null, null, null)
        item.kind == "series" -> VodApi.parseSeriesInfo(Net.text(api("get_series_info") + "&series_id=" + item.id))
        else -> VodApi.parseVodInfo(Net.text(api("get_vod_info") + "&vod_id=" + item.id))
    }

    /**
     * Address of a movie. Saved copies of playlist VOD (search index, "Continue watching") carry no address (it holds
     * the login): it comes from the decrypted playlist cache again.
     */
    fun movieUrl(item: VodItem, d: VodDetail?): String = item.url
        ?: if (!src.isXtream) m3uVod().firstOrNull { it.key == item.key }?.url ?: throw IOException("This title is no longer in the playlist")
        else VodApi.movieUrl(src.server, src.user, src.pass, item.id, d?.ext ?: item.ext)
    fun episodeUrl(e: Episode) = VodApi.episodeUrl(src.server, src.user, src.pass, e.id, e.ext)
}
