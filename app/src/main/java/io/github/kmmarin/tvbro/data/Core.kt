// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import android.content.Context
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.Response
import io.github.kmmarin.tvbro.BuildConfig
import io.github.kmmarin.tvbro.core.CoreException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * Rollout switch for the call sites that moved to the Rust core (docs/RUST_CORE.md, phase 2). Every row keeps its
 * Kotlin implementation; this picks which one runs, once per process (the HTTP client and the guide store are built
 * once).
 *
 * Modes: [Mode.KOTLIN], [Mode.CORE], and in debug builds [Mode.SHADOW]: Kotlin stays in charge and the core runs
 * afterwards on the same input (the downloaded file, the index rows, the query, the PIN, the request); only a summary
 * is kept ([CoreShadow]).
 *
 * Default per build: `BuildConfig.CORE_DEFAULT` (`-Ptvbro.core=rust|kotlin`, default kotlin). Debug builds only:
 * Settings > About > Developer stores an override per row, and `adb shell setprop debug.tvbro.core rust` (or
 * `shadow`, or `m3u=rust,epg=shadow,pin=kotlin`) overrides both for the next start. If the native library cannot be
 * loaded every row runs Kotlin for the process and the reason is shown in Settings > About.
 */
object CoreSwitch {
    enum class Row(val id: String, val label: String, val canShadow: Boolean) {
        URL_GUARD("urlguard", "URL guard", true), PIN("pin", "PIN hashing", true), M3U("m3u", "M3U parsing", true),
        XTREAM("xtream", "Xtream live parsing", true), EPG("epg", "Guide: XMLTV + gzip, epg.db", true),
        SEARCH("search", "Search index: search.db", true),
    }

    enum class Mode(val id: String, val label: String) { KOTLIN("kotlin", "Kotlin"), CORE("rust", "Rust"), SHADOW("shadow", "Shadow (Kotlin, core compared)") }

    private const val PREFS = "core"
    /**
     * Rows that run on the core by default (`-Ptvbro.core=default`): those whose comparison was identical in the host
     * parity tests and in shadow runs on a device (docs/RUST_CORE.md, phase 2 step 3). The others stay on Kotlin.
     * 2026-11-13: all six (Fire TV Stick 4K shadow run with a real Xtream provider and an M3U source: no difference).
     */
    internal val CORE_BY_DEFAULT: Set<Row> = Row.entries.toSet()

    internal fun defaults(build: String = BuildConfig.CORE_DEFAULT): MutableMap<Row, Mode> = Row.entries.associateWith {
        when (build) { "rust" -> Mode.CORE; "kotlin" -> Mode.KOTLIN; else -> if (it in CORE_BY_DEFAULT) Mode.CORE else Mode.KOTLIN }
    }.toMutableMap()
    @Volatile private var modes: Map<Row, Mode> = defaults()

    /** Reads the overrides (debug builds). Called first thing in [io.github.kmmarin.tvbro.TvBroApp.onCreate]. */
    fun init(ctx: Context) {
        val m = defaults()
        if (BuildConfig.DEBUG) {
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            for (r in Row.entries) sp.getString(r.id, null)?.let { v -> parse(v, r, true)?.let { m[r] = it } }
            applyProp(io.github.kmmarin.tvbro.DebugHooks.coreProp(), m, true)
            CoreShadow.logFile = File(ctx.filesDir, CoreShadow.LOG)
        }
        modes = m
        val on = Row.entries.filter { m[it] == Mode.CORE }
        val sh = Row.entries.filter { m[it] == Mode.SHADOW }
        // (off the main thread: status() loads the core library, ~0.1-0.3 s on a Fire TV, which held up the first frame;
        // the first parse / guide call waits for the same lazy load if it comes first)
        if (on.isNotEmpty() || sh.isNotEmpty()) Thread({
            Core.available
            Diag.log("rust core: ${on.joinToString { it.id }.ifEmpty { "nothing" }} on${if (sh.isEmpty()) "" else ", shadow: " + sh.joinToString { it.id }}, ${status()}")
        }, "tvbro-core-load").start()
    }

    /** "rust" / "kotlin" / "shadow"; shadow only for rows that support it, and only in debug builds. */
    internal fun parse(v: String, r: Row, debug: Boolean): Mode? = when (v.trim().lowercase()) {
        "rust", "core" -> Mode.CORE
        "kotlin" -> Mode.KOTLIN
        "shadow" -> if (debug && r.canShadow) Mode.SHADOW else null
        else -> null
    }

    /** "rust" / "kotlin" / "shadow" for every row (shadow: the rows that support it), or "m3u=rust,epg=shadow". */
    internal fun applyProp(p: String, m: MutableMap<Row, Mode>, debug: Boolean) {
        val v = p.trim().lowercase()
        if (v.isEmpty()) return
        if (!v.contains('=')) { for (r in Row.entries) parse(v, r, debug)?.let { m[r] = it }; return }
        for (part in v.split(',')) {
            val (k, x) = part.split('=').map { it.trim() }.takeIf { it.size == 2 } ?: continue
            val r = Row.entries.firstOrNull { it.id == k } ?: continue
            parse(x, r, debug)?.let { m[r] = it }
        }
    }

    /** True when [r] runs on the Rust core in this process. */
    fun core(r: Row): Boolean = modes[r] == Mode.CORE && Core.available

    /** True when [r] runs Kotlin and the core is compared after it (debug builds). */
    fun shadow(r: Row): Boolean = modes[r] == Mode.SHADOW && Core.available

    fun mode(r: Row): Mode = modes[r] ?: Mode.KOTLIN

    /** Debug builds: stores the override for the next start. */
    fun store(ctx: Context, r: Row, mode: Mode) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(r.id, mode.id).apply()
    }

    fun stored(ctx: Context, r: Row): Mode? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(r.id, null)?.let { parse(it, r, BuildConfig.DEBUG) }

    fun status(): String = when {
        modes.values.none { it != Mode.KOTLIN } && !Core.tried -> "not loaded (every row on Kotlin)"
        Core.available -> "loaded, core ${Core.version}"
        else -> "failed to load (${Core.loadError}): every row on Kotlin"
    }
}

/** The Rust core's functions as the app uses them (generated bindings in `io.github.kmmarin.tvbro.core`). */
object Core {
    /** Android sources may be on the home network (a tvheadend box, a NAS): private ranges are allowed. */
    const val ALLOW_LAN = true

    @Volatile var tried = false; private set
    @Volatile var loadError: String? = null; private set
    var version = ""; private set

    /** Loads `libtvbro_ffi.so` on first use; any failure (missing ABI, link error) leaves the core off. */
    val available: Boolean by lazy {
        tried = true
        try { version = io.github.kmmarin.tvbro.core.coreVersion(); true }
        catch (t: Throwable) { loadError = t.javaClass.simpleName; Diag.log("rust core failed to load (${t.javaClass.simpleName}), using Kotlin"); false }
    }

    private fun channel(c: io.github.kmmarin.tvbro.core.FfiChannel) = Channel(
        key = c.key, sourceId = c.sourceId, name = c.name, logo = c.logo, group = c.group, url = c.url, tvgId = c.tvgId,
        number = c.number, catchup = c.catchupKind?.let { Catchup(it, c.catchupTemplate) }, catchupDays = c.catchupDays,
    )

    fun m3u(data: ByteArray, sourceId: String): M3uResult =
        io.github.kmmarin.tvbro.core.parseM3uBytes(data, sourceId).let { r -> M3uResult(r.channels.map(::channel), r.epgUrls) }

    fun xtreamLive(cats: ByteArray, streams: ByteArray, sourceId: String, server: String, user: String, pass: String): List<Channel> =
        io.github.kmmarin.tvbro.core.parseXtreamLive(cats, streams, sourceId, server, user, pass).map(::channel)

    /** Download check: streams of a complete list, 0 when it is cut off or not JSON (Kotlin's strict decoder rule). */
    fun xtreamCountStrict(streams: ByteArray): Int = io.github.kmmarin.tvbro.core.xtreamLiveCountStrict(streams).toInt()

    /** Null when allowed, else the reason (never contains the URL). No DNS: host names are checked by [GuardDns]. */
    fun urlRefusal(url: String): String? = try { io.github.kmmarin.tvbro.core.checkUrlStatic(url, ALLOW_LAN); null }
        catch (e: CoreException.Blocked) { e.reason } catch (e: CoreException) { "bad url" }

    fun addrRefusal(addrs: List<String>): String? = try { io.github.kmmarin.tvbro.core.checkAddrs(addrs, ALLOW_LAN); null }
        catch (e: CoreException.Blocked) { e.reason } catch (e: CoreException) { "bad address" }

    fun pinHash(pin: String): String = io.github.kmmarin.tvbro.core.pinHash(pin)
    fun pinVerify(pin: String, stored: String): Boolean = io.github.kmmarin.tvbro.core.pinVerify(pin, stored).ok
    fun pinNeedsRehash(stored: String): Boolean = io.github.kmmarin.tvbro.core.pinNeedsRehash(stored)

    /** Shadow mode: the digest of the programmes the core would store from [inp] (nothing is written). */
    fun guideDigest(inp: InputStream, sourceId: String, wanted: Collection<String>, minStop: Long, maxStart: Long): io.github.kmmarin.tvbro.core.FfiDigest =
        io.github.kmmarin.tvbro.core.guideDigest(Feed(inp), sourceId, wanted.toList(), minStop, maxStart)
}

/** A (decrypted) guide stream handed to the core in chunks; read errors reach the core without file names or URLs. */
class Feed(private val inp: InputStream, private val onProgress: (Long) -> Unit = {}) : io.github.kmmarin.tvbro.core.GuideFeed {
    override fun read(max: UInt): ByteArray {
        val b = ByteArray(max.toInt().coerceIn(1, 1 shl 20))
        var off = 0
        try {
            while (off < b.size) { val k = inp.read(b, off, b.size - off); if (k < 0) break; off += k }
        } catch (e: IOException) { throw CoreException.Other(if (e is SafeIOException) e.message ?: "read failed" else "read failed (${e.javaClass.simpleName})") }
        return if (off == b.size) b else b.copyOf(off)
    }
    override fun progress(programmes: ULong) = onProgress(programmes.toLong())
}

/**
 * Shadow mode (debug builds, opt-in per row): Kotlin's result is used; the core runs on the same input afterwards and
 * only a summary is kept: counts, the first differing position and the names of the fields that differ, timings.
 * Never names, URLs or credentials. Written to `files/core-shadow.log` (`adb shell run-as io.github.kmmarin.tvbro cat
 * files/core-shadow.log`) and to the diagnostics.
 */
object CoreShadow {
    const val LOG = "core-shadow.log"
    @Volatile var logFile: File? = null
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "tvbro-shadow").apply { priority = Thread.MIN_PRIORITY; isDaemon = true } }

    private val channelFields = listOf<Pair<String, (Channel) -> Any?>>(
        "key" to { it.key }, "source" to { it.sourceId }, "name" to { it.name }, "logo" to { it.logo }, "group" to { it.group },
        "url" to { it.url }, "tvg-id" to { it.tvgId }, "number" to { it.number }, "catchup" to { it.catchup?.type },
        "catchup-template" to { it.catchup?.template }, "catchup-days" to { it.catchupDays },
    )

    /** Summary of two channel lists: counts, how many positions differ, the first one and its differing fields. */
    fun compareChannels(what: String, kotlin: List<Channel>, core: List<Channel>, kotlinMs: Long, coreMs: Long): String {
        var differ = 0
        var first = -1
        var fields = ""
        for (i in 0 until maxOf(kotlin.size, core.size)) {
            val a = kotlin.getOrNull(i); val b = core.getOrNull(i)
            val f = if (a == null || b == null) listOf("missing") else channelFields.filter { (_, g) -> g(a) != g(b) }.map { it.first }
            if (f.isNotEmpty()) { differ++; if (first < 0) { first = i; fields = f.joinToString(",") } }
        }
        val res = if (differ == 0) "identical" else "$differ differ, first at $first: $fields"
        return "$what: kotlin ${kotlin.size} / core ${core.size} channels, $res; kotlin $kotlinMs ms, core $coreMs ms"
    }

    /** Runs [core] on the shadow thread and logs the comparison with Kotlin's [kotlin] result. */
    fun channels(what: String, kotlin: List<Channel>, kotlinMs: Long, core: () -> List<Channel>) {
        exec.execute {
            try {
                val t0 = System.nanoTime()
                val c = core()
                log(compareChannels(what, kotlin, c, kotlinMs, (System.nanoTime() - t0) / 1_000_000))
            } catch (e: Throwable) { log("$what: core failed (${e.javaClass.simpleName})") }
        }
    }

    /** Download check (keep-old-data rule) of both sides: accepted or refused the same way? */
    fun check(what: String, kotlinCount: Int, coreCount: Int) {
        val k = SyncRules.accept(kotlinCount); val c = SyncRules.accept(coreCount)
        log("$what check: kotlin ${if (k) "accepts" else "refuses"} ($kotlinCount), core ${if (c) "accepts" else "refuses"} ($coreCount)${if (k == c) "" else " DIFFERENT"}")
    }

    /** Programme rows as the core's `epgload::Digest`: per field and per row, wrapping sums of FNV-1a 64 hashes. */
    class Digest {
        var rows = 0L; private set
        val fields = LongArray(7)
        var row = 0L; private set

        fun add(p: Programme) {
            val f = longArrayOf(hs(p.key), hi(p.start), hi(p.stop), hs(p.title), hs(p.desc), hs(p.sub), hs(p.ep))
            val b = ByteArray(56)
            for (i in 0 until 7) {
                fields[i] += f[i]
                for (k in 0 until 8) b[i * 8 + k] = (f[i] ushr (8 * k)).toByte()
            }
            row += fnv(b); rows++
        }

        /** Field names whose digests differ from the core's (or "rows" when only counts / pairing differ). */
        fun differing(c: io.github.kmmarin.tvbro.core.FfiDigest): List<String> {
            val v = (0 until 7).filter { fields[it] != c.fields[it].toLong() }.map { FIELDS[it] }
            return if (v.isEmpty() && (rows != c.rows.toLong() || row != c.row.toLong())) listOf("rows") else v
        }

        companion object {
            val FIELDS = listOf("key", "start", "stop", "title", "desc", "sub", "ep")
            private const val OFFSET = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            private const val PRIME = 0x100000001b3L
            private val N = byteArrayOf('n'.code.toByte())
            private val S = byteArrayOf('s'.code.toByte())
            private val I = byteArrayOf('i'.code.toByte())
            fun fnv(vararg parts: ByteArray): Long {
                var h = OFFSET
                for (p in parts) for (x in p) { h = h xor (x.toLong() and 0xff); h *= PRIME }
                return h
            }
            private fun hs(s: String?): Long = if (s == null) fnv(N) else fnv(S, s.toByteArray(Charsets.UTF_8))
            private fun hi(v: Long): Long = fnv(I, v.toString().toByteArray(Charsets.UTF_8))
        }
    }

    fun guide(kotlin: Digest, kotlinMs: Long, core: io.github.kmmarin.tvbro.core.FfiDigest, coreMs: Long) {
        val d = kotlin.differing(core)
        val res = when {
            core.error != null -> "core error (${core.error})"
            d.isEmpty() -> "identical"
            else -> "differ in: " + d.joinToString(",")
        }
        log("guide: kotlin ${kotlin.rows} / core ${core.rows} programmes, $res; kotlin $kotlinMs ms (parse + store), core $coreMs ms (parse)")
    }

    // ---- URL guard (Kotlin: no guard, everything allowed) ----
    private val guardChecks = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private val guardRefused = java.util.concurrent.atomic.AtomicLong()
    private val guardTotal = java.util.concurrent.atomic.AtomicLong()

    /** One check of the core's guard on traffic Kotlin allowed; [refusal] is the core's reason (never a URL) or null. */
    fun urlGuard(what: String, refusal: String?) {
        guardChecks.getOrPut(what) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
        val n = guardTotal.incrementAndGet()
        val r = if (refusal != null) guardRefused.incrementAndGet() else guardRefused.get()
        if (refusal != null && r <= 20) log("urlguard: core would refuse a $what (${refusal}), kotlin allows it DIFFERENT")
        if (n == 1L || n % 100 == 0L) log("urlguard: ${guardSummary()}")
    }

    fun guardSummary(): String {
        val c = guardChecks.entries.sortedBy { it.key }.joinToString(", ") { "${it.value.get()} ${it.key}" }
        val r = guardRefused.get()
        return "$c checked; kotlin allows all, core would refuse $r${if (r == 0L) ", identical" else ""}"
    }

    // ---- PIN ----
    fun pinVerify(kotlinOk: Boolean, kotlinMs: Long, core: () -> Boolean) {
        exec.execute {
            try {
                val t0 = System.nanoTime(); val c = core(); val cm = (System.nanoTime() - t0) / 1_000_000
                log("pin verify: kotlin ${if (kotlinOk) "right" else "wrong"} / core ${if (c) "right" else "wrong"}, ${if (c == kotlinOk) "identical" else "DIFFERENT"}; kotlin $kotlinMs ms, core $cm ms")
            } catch (e: Throwable) { log("pin verify: core failed (${e.javaClass.simpleName})") }
        }
    }

    /** [core] gives (core verifies Kotlin's new hash, Kotlin verifies a core hash of the same PIN). */
    fun pinHash(kotlinMs: Long, core: () -> Pair<Boolean, Boolean>) {
        exec.execute {
            try {
                val t0 = System.nanoTime(); val (a, b) = core(); val cm = (System.nanoTime() - t0) / 1_000_000
                log("pin hash: core reads kotlin's hash: ${if (a) "yes" else "no"}, kotlin reads core's hash: ${if (b) "yes" else "no"}, ${if (a && b) "identical" else "DIFFERENT"}; kotlin hash $kotlinMs ms, core verify + hash $cm ms")
            } catch (e: Throwable) { log("pin hash: core failed (${e.javaClass.simpleName})") }
        }
    }

    // ---- search ----
    private val hitFields = listOf("kind", "key", "name", "img", "src", "xid", "cat", "rating", "ext", "url")
    private fun hitRow(h: SearchHit): List<Any?> = listOf(h.kind, h.key, h.name, h.img, h.src, h.xid, h.cat, h.rating, h.ext, h.url)
    private val progFields = listOf("title", "ch", "start", "stop", "sub", "ep")
    private fun progRow(h: ProgHit): List<Any?> = listOf(h.title, h.ch, h.start, h.stop, h.sub, h.ep)

    /** Positions that differ and the first one with its differing field names, or null when identical. */
    internal fun diffRows(what: String, kotlin: List<List<Any?>>, core: List<List<Any?>>, fields: List<String>): String? {
        var differ = 0; var first = -1; var names = ""
        for (i in 0 until maxOf(kotlin.size, core.size)) {
            val a = kotlin.getOrNull(i); val b = core.getOrNull(i)
            val f = if (a == null || b == null) listOf("missing") else fields.indices.filter { a[it] != b[it] }.map { fields[it] }
            if (f.isNotEmpty()) { differ++; if (first < 0) { first = i; names = f.joinToString(",") } }
        }
        return if (differ == 0) null else "$what: $differ differ, first at $first: $names"
    }

    /** Summary of two hit lists of one query: per-section counts, then what differs (positions and field names). */
    fun compareSearch(kotlin: SearchResult, core: SearchResult, kotlinMs: Long, coreMs: Long): String {
        fun n(r: SearchResult) = "${r.channels.size}/${r.movies.size}/${r.series.size}/${r.programmes.size}"
        val d = listOfNotNull(
            diffRows("channels", kotlin.channels.map(::hitRow), core.channels.map(::hitRow), hitFields),
            diffRows("movies", kotlin.movies.map(::hitRow), core.movies.map(::hitRow), hitFields),
            diffRows("series", kotlin.series.map(::hitRow), core.series.map(::hitRow), hitFields),
            diffRows("programmes", kotlin.programmes.map(::progRow), core.programmes.map(::progRow), progFields),
        )
        return "search: kotlin ${n(kotlin)} / core ${n(core)} hits (channels/movies/series/programmes), ${if (d.isEmpty()) "identical" else d.joinToString("; ")}; kotlin $kotlinMs ms, core $coreMs ms"
    }

    /** Runs the query on the core's shadow index and logs the comparison (the query text is not logged). */
    fun search(kotlin: SearchResult, kotlinMs: Long, core: () -> SearchResult?) {
        exec.execute {
            try {
                val t0 = System.nanoTime()
                val c = core() ?: run { log("search: no core shadow index yet"); return@execute }
                log(compareSearch(kotlin, c, kotlinMs, (System.nanoTime() - t0) / 1_000_000))
            } catch (e: Throwable) { log("search: core failed (${e.javaClass.simpleName})") }
        }
    }

    /** After a shadow rebuild: the stored counts, the catalogue categories and first pages of both indexes. */
    fun compareIndex(kotlin: SearchReader, core: SearchReader, kotlinMs: Long, coreMs: Long): String {
        val d = ArrayList<String>()
        val kc = kotlin.meta("counts"); val cc = core.meta("counts")
        if (kc != cc) d += "counts"
        for ((k, label) in listOf(SearchIndex.MOVIE to "movies", SearchIndex.SERIES to "series")) {
            if (kotlin.count(k) != core.count(k)) d += "$label count"
            if (kotlin.categories(k) != core.categories(k)) d += "$label categories"
            val kp = kotlin.page(k, null, 0, 500); val cp = core.page(k, null, 0, 500)
            if (kp != cp) d += "$label pages"
        }
        return "search index: kotlin $kc / core $cc (channels,movies,series,titles,programmes), ${if (d.isEmpty()) "identical" else "differ in: " + d.joinToString(",")}; kotlin $kotlinMs ms, core $coreMs ms (build)"
    }

    @Synchronized fun log(line: String) {
        Diag.log("shadow $line")
        val f = logFile ?: return
        try {
            if (f.length() > 256 * 1024) f.writeText(f.readText().takeLast(128 * 1024).substringAfter('\n'))
            val t = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
            f.appendText("$t $line\n")
        } catch (e: IOException) { }
    }
}

/** The PIN functions behind the switch (the Kotlin [Pin] reads every hash the core writes, so switching back is safe). */
object Pins {
    private val shadow get() = CoreSwitch.shadow(CoreSwitch.Row.PIN)

    /** Shadow: Kotlin's hash is stored; the core checks that it reads it, and that Kotlin reads a core hash. */
    fun hash(pin: String): String {
        if (CoreSwitch.core(CoreSwitch.Row.PIN)) return Core.pinHash(pin)
        val t0 = System.nanoTime()
        val h = Pin.hash(pin)
        if (shadow) { val km = (System.nanoTime() - t0) / 1_000_000; CoreShadow.pinHash(km) { Core.pinVerify(pin, h) to Core.pinHash(pin).let { c -> Pin.verify(pin, c) } } }
        return h
    }

    /** Shadow: Kotlin decides; the core verifies the same PIN against the same stored hash afterwards. */
    fun verify(pin: String, stored: String): Boolean {
        if (CoreSwitch.core(CoreSwitch.Row.PIN)) return Core.pinVerify(pin, stored)
        val t0 = System.nanoTime()
        val ok = Pin.verify(pin, stored)
        if (shadow) { val km = (System.nanoTime() - t0) / 1_000_000; CoreShadow.pinVerify(ok, km) { Core.pinVerify(pin, stored) } }
        return ok
    }
    /** Core only: after a right PIN, an old-format (legacy SHA-256) or weaker hash is replaced. Kotlin keeps the old rule (on the next PIN change). */
    fun needsRehash(stored: String): Boolean = CoreSwitch.core(CoreSwitch.Row.PIN) && Core.pinNeedsRehash(stored)
}

/**
 * The URL guard on the shared HTTP client (row URL_GUARD): every request and every redirect hop passes the core's
 * URL rules (http/https, ports 80 / 443 / 1024+, IP-literal hosts), and every host name is resolved here and all of
 * its addresses checked before OkHttp connects to exactly those addresses (no second lookup, no DNS rebinding).
 * Loopback, link-local (incl. 169.254.169.254), multicast and reserved addresses are refused; LAN addresses allowed.
 */
object Guard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        Core.urlRefusal(chain.request().url.toString())?.let { throw SafeIOException("blocked: $it") }
        return chain.proceed(chain.request())
    }

    val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val a = Dns.SYSTEM.lookup(hostname)
            Core.addrRefusal(a.map { it.hostAddress ?: "" })?.let { throw SafeIOException("blocked: $it") }
            return a
        }
    }

    /**
     * Shadow mode of the row (debug builds): Kotlin has no guard, so every request is allowed as before; the core's
     * checks run on the same requests, hops and lookups and only what they would have refused is counted.
     */
    class Shadow(private val what: String) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            CoreShadow.urlGuard(what, try { Core.urlRefusal(chain.request().url.toString()) } catch (e: Throwable) { "core failed (${e.javaClass.simpleName})" })
            return chain.proceed(chain.request())
        }
    }

    val shadowDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val a = Dns.SYSTEM.lookup(hostname)
            CoreShadow.urlGuard("lookup", try { Core.addrRefusal(a.map { it.hostAddress ?: "" }) } catch (e: Throwable) { "core failed (${e.javaClass.simpleName})" })
            return a
        }
    }
}
