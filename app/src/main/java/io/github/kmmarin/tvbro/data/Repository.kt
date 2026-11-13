// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import android.content.Context
import android.util.Xml
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.json.decodeFromStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun request(url: String): Request = Request.Builder().url(url).header("User-Agent", "tvBro/0.1").build()

    /** Message safe to show: never includes URLs (they may contain credentials). */
    fun describe(e: Throwable): String =
        if (e is SafeIOException || (e is IOException && e.message?.startsWith("HTTP ") == true)) e.message!! else e.javaClass.simpleName
}

/** Reads at most [max] bytes from [inp], then fails with a [SafeIOException]. */
class Capped(inp: InputStream, private val max: Long) : java.io.FilterInputStream(inp) {
    private var n = 0L
    private fun count(k: Int): Int { if (k > 0) { n += k; if (n > max) throw SafeIOException("data too large (over ${max / 1_000_000} MB)") }; return k }
    override fun read(): Int = super.read().also { if (it >= 0) count(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = count(super.read(b, off, len))
}

/** An error whose message never contains a URL or credential, so it can be shown and logged as is. */
class SafeIOException(msg: String) : IOException(msg)

/** Downloads playlists / guides into filesDir/cache and parses them from disk. */
class Repository(ctx: Context, private val gentle: Boolean = false) {
    private companion object {
        const val MIN_FREE = 150_000_000L
        /** Version of the at-rest format: 1 = sealed cache files, no stream addresses in the search index or snapshots. */
        const val AT_REST = 1
        @Volatile var migrated = false
        var migrating = false
        val migrateLock = Any()
    }

    /**
     * Once per install, on the first start of this version: the plaintext cache files of older versions are sealed
     * in place (no download needed) and the plaintext copies removed, the search index is dropped (rebuilt without
     * stream addresses) and the VOD snapshots lose their addresses.
     */
    private fun migrate() {
        if (migrated) return
        synchronized(migrateLock) {
            if (migrated || migrating) return // (re-entered from the migration's own file access)
            migrating = true
            try { migrateLocked() } finally { migrating = false }
        }
    }

    private fun migrateLocked() {
        val sp = appCtx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (sp.getInt("atRest", 0) < AT_REST) {
            try {
                val (sealed, deleted) = sd.migrate()
                SearchStore.reset(appCtx)
                Store(appCtx).stripSnapUrls()
                sp.edit().putInt("atRest", AT_REST).commit()
                Diag.log("storage: $sealed cache files encrypted, $deleted removed, search index to rebuild")
            } catch (e: Exception) { Diag.log("storage migration failed (${e.javaClass.simpleName}), will retry"); return }
        }
        migrated = true
    }
    private val appCtx = ctx.applicationContext
    private val dir = File(ctx.filesDir, "cache").apply { mkdirs() }
    /**
     * Every file here is sealed (AES-256-GCM, Keystore-wrapped key): playlists and catalogues carry the provider login.
     * The first file access (always off the main thread) runs the one-time [migrate].
     */
    private val sd = SealedDir(dir) { CacheKey.get(appCtx).also { migrate() } }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    /** Size caps per download kind: guides can be large, playlists and catalogues far less so. */
    private fun maxBytes(what: String): Long = if (what.contains("xmltv", true) || what.contains("guide", true) || what.contains("epg", true)) 1_000_000_000L else 300_000_000L

    /** Downloads to a temp file, validates, then swaps atomically; on any failure the previous file is kept. */
    private fun download(url: String, dest: File, what: String = "GET", validate: ((File) -> Boolean)? = null, progress: ((Long) -> Unit)? = null) {
        val t0 = System.nanoTime()
        val tmp = File(dest.path + ".tmp")
        try {
            Net.client.newCall(Net.request(url)).execute().use { r ->
                if (!r.isSuccessful) { Diag.log("$what: http ${r.code} in ${ms(t0)} ms"); throw IOException("HTTP ${r.code}") }
                r.body!!.byteStream().use { i ->
                    sd.output(tmp).use { o ->
                        val buf = ByteArray(if (gentle) 16 * 1024 else 64 * 1024)
                        var c = 0
                        var n = 0L
                        while (true) {
                            val k = i.read(buf)
                            if (k < 0) break
                            o.write(buf, 0, k)
                            n += k
                            // a runaway or hostile response must not fill the disk (old data is kept, see catch)
                            if (n > maxBytes(what)) throw SafeIOException("response too large (over ${maxBytes(what) / 1_000_000} MB)")
                            if (n % (8L shl 20) < k && dir.usableSpace < MIN_FREE) throw SafeIOException("not enough free space")
                            if (progress != null && ++c % 64 == 0) progress(n)
                            if (gentle && ++c % 32 == 0) Thread.sleep(2)
                        }
                    }
                }
                Diag.log("$what: http ${r.code}, ${tmp.length()} bytes, ${ms(t0)} ms")
            }
            if (validate != null && !validate(tmp)) { Diag.log("$what: rejected (empty or invalid), keeping previous data"); throw SafeIOException("empty or invalid response") }
        } catch (e: IOException) {
            tmp.delete()
            if (e !is SafeIOException && e.message?.startsWith("HTTP") != true) Diag.log("$what: failed (${e.javaClass.simpleName})")
            throw e
        }
        dest.delete()
        if (!tmp.renameTo(dest)) throw IOException("rename failed")
    }

    private fun head(f: File): String = sd.head(f)
    private fun looksJsonList(f: File) = f.length() > 5 && head(f).trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("[")
    private fun looksM3u(f: File) = head(f).let { it.contains("#EXTM3U") || it.contains("#EXTINF") }

    /** Keep-old-data rule: a downloaded channel list is accepted only when it parses to at least one channel. */
    private fun m3uHasChannels(f: File) = looksM3u(f) && SyncRules.accept(sd.input(f).bufferedReader().use { M3uParser.parse(it.lineSequence(), "x").channels.size })
    private fun xtreamHasStreams(f: File) = looksJsonList(f) && SyncRules.accept(try { sd.input(f).use { Xtream.liveStreams(it).size } } catch (e: Exception) { 0 })
    private fun catalogueHasItems(series: Boolean) = { f: File -> looksJsonList(f) && SyncRules.accept(sd.input(f).use { VodApi.eachVod(it, series, "x", emptyList(), "check") { } }) }

    private fun mb(n: Long) = "%.1f MB".format(n / 1048576.0)

    /** Downloads the live channel list (validated, atomic swap; the old list stays on failure) and records the outcome. */
    private fun syncChannels(s: Source) {
        val k = SyncKind.CHANNELS
        SyncStatus.start(s.id, k)
        val prog = { n: Long -> SyncStatus.step(s.id, k, "downloading ${mb(n)}") }
        try {
            if (s.isXtream) {
                try { download(Xtream.apiUrl(s.server, s.user, s.pass, "get_live_categories"), File(dir, "${s.id}.cats.json"), "live categories") } catch (e: Exception) { }
                download(Xtream.apiUrl(s.server, s.user, s.pass, "get_live_streams"), File(dir, "${s.id}.streams.json"), "live streams", ::xtreamHasStreams, prog)
            } else download(s.url, File(dir, "${s.id}.m3u"), "m3u playlist", ::m3uHasChannels, prog)
        } catch (e: Exception) { SyncStatus.fail(s.id, k, e); throw e }
    }
    private fun stale(f: File, hours: Int) = !f.exists() || System.currentTimeMillis() - f.lastModified() > hours * 3600_000L

    /** Cache first: only downloads when forced (refresh) or when there is no cache yet (first run). */
    fun channels(s: Source, force: Boolean): List<Channel> {
        val t0 = System.nanoTime()
        val main = File(dir, if (s.isXtream) "${s.id}.streams.json" else "${s.id}.m3u")
        var synced = false
        if (force || !sd.exists(main)) {
            try { syncChannels(s); synced = true } catch (e: Exception) { if (!sd.exists(main)) throw e }
        }
        val out = if (s.isXtream) {
            val cats = File(dir, "${s.id}.cats.json")
            val c = if (sd.exists(cats)) sd.input(cats).use { Xtream.categories(it) } else emptyList()
            val l = sd.input(main).use { Xtream.liveStreams(it) }
            Xtream.toChannels(s.id, s.server, s.user, s.pass, c, l)
        } else {
            val res = sd.input(main).bufferedReader().use { M3uParser.parse(it.lineSequence(), s.id) }
            sd.writeText(File(dir, "${s.id}.epgurl"), res.epgUrls.joinToString(","))
            res.channels
        }
        if (synced) SyncStatus.ok(s.id, SyncKind.CHANNELS, out.size)
        Diag.log("channels: ${out.size} (cache ${if (force) "refreshed" else "first"}) ${ms(t0)} ms")
        return out
    }

    /** True when stored channels exist for [s] (shown immediately at start, before any network). */
    fun hasChannels(s: Source) = sd.exists(File(dir, if (s.isXtream) "${s.id}.streams.json" else "${s.id}.m3u"))
    private fun epgUrls(s: Source): List<String> = when {
        s.isXtream -> listOf(Xtream.xmltvUrl(s.server, s.user, s.pass))
        s.epgUrl.isNotBlank() -> s.epgUrl.split(Regex("[,\\s]+")).filter { it.isNotBlank() }
        else -> File(dir, "${s.id}.epgurl").takeIf { sd.exists(it) }?.let { sd.readText(it) }?.split(',')?.filter { it.isNotBlank() }?.take(1) ?: emptyList()
    }

    fun epgIds(s: Source): List<Pair<String, String>> {
        val f = File(dir, "${s.id}.epgids.txt")
        if (!sd.exists(f)) return emptyList()
        return sd.readText(f).lines().mapNotNull { l -> l.split('\t').let { if (it.size >= 2) it[0] to it[1] else null } }
    }
    fun epgStale(s: Source, hours: Int): Boolean = EpgDb.get(appCtx).meta(s.id)?.let { System.currentTimeMillis() - it.updated > hours * 3600_000L } ?: true
    /** Downloads the channel list into the cache (validated, atomic swap) without parsing it. */
    fun refreshChannelFiles(s: Source) {
        syncChannels(s)
        val n = try { channels(s, false).size } catch (e: Exception) { 0 }
        SyncStatus.ok(s.id, SyncKind.CHANNELS, n)
    }

    /** Whole movie and series catalogues of an Xtream source into the cache (validated, atomic swap per file). */
    fun syncCatalogue(s: Source) {
        if (!s.isXtream) return
        val k = SyncKind.CATALOGUE
        SyncStatus.start(s.id, k)
        try {
            var total = 0
            for (series in listOf(false, true)) {
                val pre = if (series) "series" else "vod"
                val what = if (series) "series" else "movies"
                SyncStatus.step(s.id, k, "downloading $what")
                try { download(Xtream.apiUrl(s.server, s.user, s.pass, "get_${pre}_categories"), File(dir, "${s.id}.${pre}cats.json"), "$pre categories", ::looksJsonList) } catch (e: Exception) { }
                download(Xtream.apiUrl(s.server, s.user, s.pass, if (series) "get_series" else "get_vod_streams"), File(dir, "${s.id}.$pre.all.json"), "$pre catalogue",
                    catalogueHasItems(series)) { n -> SyncStatus.step(s.id, k, "downloading $what ${mb(n)}") }
                total += sd.input(File(dir, "${s.id}.$pre.all.json")).use { VodApi.eachVod(it, series, s.id, emptyList(), "count") { } }
            }
            SyncStatus.ok(s.id, k, total)
        } catch (e: Exception) { SyncStatus.fail(s.id, k, e); throw e }
    }

    /** Streams the stored catalogue (no network); nothing when it was never synced. */
    fun eachCatalogue(s: Source, series: Boolean, sink: (VodItem) -> Unit) {
        if (!s.isXtream) return
        val pre = if (series) "series" else "vod"
        val f = File(dir, "${s.id}.$pre.all.json")
        if (!sd.exists(f)) return
        val cf = File(dir, "${s.id}.${pre}cats.json")
        val cats = if (sd.exists(cf)) try { sd.input(cf).use { VodApi.parseCategories(it, "$pre categories") } } catch (e: Exception) { emptyList() } else emptyList()
        sd.input(f).use { VodApi.eachVod(it, series, s.id, cats, "$pre index", sink) }
    }

    fun refreshEpg(s: Source, channels: List<Channel>, lowMem: Boolean = false): Boolean {
        val urls = epgUrls(s)
        if (urls.isEmpty()) return false
        val t0 = System.nanoTime()
        val k = SyncKind.GUIDE
        SyncStatus.start(s.id, k)
        val wanted = HashSet<String>()
        for (c in channels) { c.tvgId?.let { wanted += it.lowercase() }; wanted += c.name.lowercase() }
        val now = System.currentTimeMillis() / 1000
        val pastDays = (channels.maxOfOrNull { it.catchupDays } ?: 0).coerceIn(1, 14) // longest catch-up window, default 1 day
        val db = EpgDb.get(appCtx)
        val gen = db.beginRefresh(s.id)
        val batch = ArrayList<Programme>(500)
        val ids = HashMap<String, List<String>>()
        var minS = Long.MAX_VALUE
        var maxS = 0L
        var total = 0
        var ok = false
        var err: Exception? = null
        try {
            for (url in urls) {
                val tmp = File(dir, "${s.id}.epg.tmp")
                try {
                    download(url, tmp, "xmltv") { n -> SyncStatus.step(s.id, k, "downloading ${mb(n)}") }
                    SyncStatus.step(s.id, k, "reading")
                    val raw = BufferedInputStream(sd.input(tmp), 32 * 1024)
                    raw.mark(2)
                    val b0 = raw.read(); val b1 = raw.read()
                    raw.reset()
                    // inflated guides are capped too (a gzip bomb would otherwise run for ever)
                    val inp: InputStream = if (b0 == 0x1f && b1 == 0x8b) Capped(GZIPInputStream(raw, 32 * 1024), 2_000_000_000L) else raw
                    inp.use {
                        val p = Xml.newPullParser()
                        p.setInput(it, null)
                        ids.putAll(XmltvParser.parse(p, { ch, names ->
                            val l = ch.lowercase()
                            if (l in wanted) "${s.id}|$l"
                            else names?.firstNotNullOfOrNull { n -> n.lowercase().takeIf { x -> x in wanted } }?.let { "${s.id}|$it" }
                        }, now - pastDays * 86400L, now + 7 * 86400L) { pr ->
                            val dd = pr.desc
                            val q = if (dd != null && dd.length > 160) pr.copy(desc = dd.take(160)) else pr
                            batch += q; total++
                            if (total % 5000 == 0) SyncStatus.step(s.id, k, "saving · $total programmes")
                            if (q.start < minS) minS = q.start
                            if (q.stop > maxS) maxS = q.stop
                            if (batch.size >= 500) {
                                db.insertBatch(s.id, gen, batch); batch.clear()
                                if (gentle) { Thread.sleep(3); Refresh.sample() }
                            }
                        })
                    }
                    ok = true
                } catch (e: Exception) { err = e } finally { tmp.delete() }
            }
            if (!ok) throw err ?: IOException("no guide")
            if (batch.isNotEmpty()) { db.insertBatch(s.id, gen, batch); batch.clear() }
            // keep-old-data rule: an empty guide (or one that matched none of the channels) never replaces a stored one
            if (!SyncRules.accept(total)) throw SafeIOException("guide has no programmes for these channels")
            db.commit(s.id, gen, if (minS == Long.MAX_VALUE) 0L else minS, maxS)
        } catch (e: Exception) { db.discard(s.id, gen); SyncStatus.fail(s.id, k, e); throw e }
        SyncStatus.ok(s.id, k, total)
        sd.output(File(dir, "${s.id}.epgids.txt")).bufferedWriter().use { w ->
            for ((id, n) in ids) w.write("${clean(id)}\t${clean(n.firstOrNull() ?: id)}\n")
        }
        Diag.log("guide: $total programmes stored for a source in ${(System.nanoTime() - t0) / 1_000_000} ms")
        return true
    }
    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    fun removeSourceFiles(id: String) {
        dir.listFiles { f -> f.name.startsWith("$id.") }?.forEach { it.delete() }
        try { EpgDb.get(appCtx).deleteSource(id) } catch (e: Exception) { }
    }

    fun fetch(url: String): String =
        Net.client.newCall(Net.request(url)).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body!!.string()
        }


    fun epgShortOnData(s: Source): Boolean {
        val m = EpgDb.get(appCtx).meta(s.id) ?: return true
        return m.maxStop - System.currentTimeMillis() / 1000 < 6 * 3600
    }
    private fun fetchCached(f: File, url: String, what: String, force: Boolean, staleHours: Int) {
        if (!sd.exists(f) || force) {
            try { download(url, f, what, ::looksJsonList) } catch (e: Exception) { if (!sd.exists(f)) throw e }
        } else if (stale(f, staleHours)) {
            Thread { try { download(url, f, "$what (background refresh)", ::looksJsonList) } catch (e: Exception) { } }.start()
        }
    }

    fun vodCategories(s: Source, series: Boolean, force: Boolean = false): List<XtreamCategory> {
        if (!s.isXtream) return emptyList()
        val pre = if (series) "series" else "vod"
        val f = File(dir, "${s.id}.${pre}cats.json")
        fetchCached(f, Xtream.apiUrl(s.server, s.user, s.pass, "get_${pre}_categories"), "$pre categories", force, 24)
        return if (sd.exists(f)) sd.input(f).use { VodApi.parseCategories(it, "$pre categories") } else emptyList()
    }

    /** One category (light path) or, with [catId] null, the whole catalogue (capped by [limit]). Cache first, refreshed in the background. */
    fun vodItems(s: Source, series: Boolean, catId: String?, force: Boolean = false, limit: Int = 30_000): List<VodItem> {
        if (!s.isXtream) return emptyList()
        val pre = if (series) "series" else "vod"
        val what = "$pre items cat=${catId ?: "all"}"
        val cats = vodCategories(s, series)
        val f = File(dir, "${s.id}.$pre.${catId ?: "all"}.json")
        val url = Xtream.apiUrl(s.server, s.user, s.pass, if (series) "get_series" else "get_vod_streams") + (catId?.let { "&category_id=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
        fetchCached(f, url, what, force, 24)
        return sd.input(f).use { if (series) VodApi.parseSeriesList(it, s.id, cats, limit, what) else VodApi.parseMovies(it, s.id, cats, limit, what) }
    }

    /** Series / movie details, cached on disk for 7 days (stale copy used if the refresh fails). */
    fun info(s: Source, series: Boolean, id: String): String {
        val f = File(dir, "${s.id}.info.${if (series) "s" else "m"}.${id.filter { it.isLetterOrDigit() }}.json")
        if (sd.exists(f) && !stale(f, 24 * 7)) return sd.readText(f)
        try { download(Xtream.apiUrl(s.server, s.user, s.pass, (if (series) "get_series_info&series_id=" else "get_vod_info&vod_id=") + java.net.URLEncoder.encode(id, "UTF-8")), f, "details") }
        catch (e: Exception) { if (!sd.exists(f)) throw e }
        return sd.readText(f)
    }
    /** Stored guide range and size for Settings. */
    fun epgInfo(s: Source): String {
        val db = EpgDb.get(appCtx)
        val m = db.meta(s.id) ?: return "no guide stored"
        val f = object { fun format(d: java.util.Date) = TimeFmt.dayTime(d.time, "d MMM") }
        return f.format(java.util.Date(m.minStart * 1000)) + " to " + f.format(java.util.Date(m.maxStop * 1000)) + ", database %.1f MB (all sources)".format(db.sizeBytes() / 1048576.0)
    }
}