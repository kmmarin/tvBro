// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.mutableIntStateOf
import java.util.concurrent.Executors

/**
 * The guide store as the app uses it: Android's SQLite ([EpgDb]) or, with row EPG of [CoreSwitch] on, the Rust
 * core's ([CoreGuideDb]). One process uses one of them for `epg.db`, never both: two SQLite builds in one process do
 * not see each other's POSIX locks, so they must not share a database file (sqlite.org/howtocorrupt.html, 2.2.1).
 */
interface GuideDb {
    fun meta(src: String): EpgDb.Meta?
    /** Starts a refresh: drops leftovers of failed runs and returns the new generation number. */
    fun beginRefresh(src: String): Int
    fun insertBatch(src: String, gen: Int, rows: List<Programme>)
    fun commit(src: String, gen: Int, minStart: Long, maxStop: Long)
    fun discard(src: String, gen: Int)
    fun deleteSource(src: String)
    /** Folds the write-ahead log back and truncates it (unless [force], only once it is large). */
    fun checkpoint(force: Boolean = true)
    fun load(src: String, key: String, from: Long, to: Long): Array<Programme>
    /**
     * Programmes of several guide [keys] of [src] overlapping [from, to), ordered by key then start, in one query.
     * Only programmes that start at most [MAX_PROGRAMME] seconds before [from] are found (keeps the index range short).
     */
    fun loadMany(src: String, keys: List<String>, from: Long, to: Long): List<Programme>
    fun search(q: String, now: Long, limit: Int): List<Pair<String, Programme>>
    fun forEachCurrent(sink: (ch: String, start: Long, stop: Long, title: String, sub: String?, ep: String?) -> Unit)
    fun desc(ch: String, start: Long): String?
    fun count(src: String): Int
    fun sizeBytes(): Long

    companion object {
        /** Longest programme [loadMany] finds when it started before the range: 2 days (the core's MAX_PROGRAMME). */
        const val MAX_PROGRAMME = 2 * 86400L
    }
}

/**
 * On-disk guide store. Programmes are streamed in batches into a new "generation" and become visible only when the
 * refresh is committed (atomic swap), so a failed refresh keeps the old data. Queries are small and indexed by
 * (source, generation, channel key, start); nothing holds the full guide in memory.
 */
class EpgDb internal constructor(ctx: Context, name: String = "epg.db") : SQLiteOpenHelper(ctx, name, null, 2), GuideDb {
    class Meta(val gen: Int, val minStart: Long, val maxStop: Long, val updated: Long)

    init { setWriteAheadLoggingEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE prog(src TEXT NOT NULL, gen INTEGER NOT NULL, ch TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT, desc TEXT, sub TEXT, ep TEXT)")
        db.execSQL("CREATE UNIQUE INDEX prog_u ON prog(src, gen, ch, start)")
        db.execSQL("CREATE INDEX prog_t ON prog(src, gen, stop)")
        db.execSQL("CREATE TABLE meta(src TEXT PRIMARY KEY, gen INTEGER, minStart INTEGER, maxStop INTEGER, updated INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) { db.execSQL("ALTER TABLE prog ADD COLUMN sub TEXT"); db.execSQL("ALTER TABLE prog ADD COLUMN ep TEXT") }
    }

    override fun meta(src: String): Meta? = readableDatabase.rawQuery("SELECT gen,minStart,maxStop,updated FROM meta WHERE src=?", arrayOf(src)).use {
        if (it.moveToFirst()) Meta(it.getInt(0), it.getLong(1), it.getLong(2), it.getLong(3)) else null
    }

    /** Starts a refresh: drops leftovers of failed runs and returns the new generation number. */
    override fun beginRefresh(src: String): Int {
        val cur = meta(src)?.gen ?: 0
        deleteChunked("src=? AND gen<>?", arrayOf(src, cur.toString()))
        return cur + 1
    }

    override fun insertBatch(src: String, gen: Int, rows: List<Programme>) {
        val w = writableDatabase
        w.beginTransaction()
        try {
            val st = w.compileStatement("INSERT OR IGNORE INTO prog(src,gen,ch,start,stop,title,desc,sub,ep) VALUES(?,?,?,?,?,?,?,?,?)")
            for (p in rows) {
                st.clearBindings()
                st.bindString(1, src); st.bindLong(2, gen.toLong()); st.bindString(3, p.key)
                st.bindLong(4, p.start); st.bindLong(5, p.stop); st.bindString(6, p.title)
                if (p.desc != null) st.bindString(7, p.desc)
                if (p.sub != null) st.bindString(8, p.sub)
                if (p.ep != null) st.bindString(9, p.ep)
                st.executeInsert()
            }
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
        if (++batches % 40 == 0) checkpoint(force = false)
    }
    private var batches = 0

    override fun commit(src: String, gen: Int, minStart: Long, maxStop: Long) {
        val w = writableDatabase
        w.beginTransaction()
        try {
            w.execSQL("INSERT OR REPLACE INTO meta(src,gen,minStart,maxStop,updated) VALUES(?,?,?,?,?)",
                arrayOf<Any>(src, gen, minStart, maxStop, System.currentTimeMillis()))
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
        // the swap above is atomic (readers follow meta.gen); the old generation goes afterwards, in small steps
        deleteChunked("src=? AND gen<>?", arrayOf(src, gen.toString()))
    }

    override fun discard(src: String, gen: Int) = deleteChunked("src=? AND gen=?", arrayOf(src, gen.toString()))
    override fun deleteSource(src: String) { writableDatabase.delete("meta", "src=?", arrayOf(src)); deleteChunked("src=?", arrayOf(src)) }

    /**
     * Deletes matching programmes 5000 rows per transaction with checkpoints in between. One transaction deleting a
     * whole generation (440,000 programmes on the Fire TV) spilled the page cache over and over and wrote 1.6 GB of log
     * before it could commit, filling the device again (2026-11-12).
     */
    private fun deleteChunked(where: String, args: Array<String>) {
        val w = writableDatabase
        var n = 0
        while (true) {
            val del = w.compileStatement("DELETE FROM prog WHERE rowid IN (SELECT rowid FROM prog WHERE $where LIMIT 5000)").use { st ->
                args.forEachIndexed { i, a -> st.bindString(i + 1, a) }
                st.executeUpdateDelete()
            }
            if (del <= 0) break
            if (++n % 10 == 0) checkpoint(force = false)
        }
        checkpoint()
    }

    private val walFile = java.io.File(ctx.getDatabasePath(name).path + "-wal")

    /**
     * Folds the write-ahead log back into the database and truncates it. Without this the log only grew: a guide refresh
     * rewrites hundreds of MB, readers kept the log from being reset, and on a Fire TV with a large provider it reached
     * 1.7 GB and filled the device (2026-11-12). Called after every refresh step that changes much, and while
     * inserting once the log passes 64 MB. Fails quietly while a reader is busy; the next call tries again.
     */
    override fun checkpoint(force: Boolean) {
        if (!force && walFile.length() < WAL_MAX) return
        try {
            writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c ->
                if (c.moveToFirst() && c.getInt(0) != 0) Diag.log("guide database: checkpoint busy, log ${walFile.length() / 1048576} MB")
            }
        } catch (e: Exception) { Diag.log("guide database: checkpoint failed (${e.javaClass.simpleName})") }
    }

    override fun load(src: String, key: String, from: Long, to: Long): Array<Programme> {
        val out = ArrayList<Programme>()
        readableDatabase.rawQuery(
            "SELECT start,stop,title,desc,sub,ep FROM prog WHERE src=? AND gen=(SELECT gen FROM meta WHERE src=?) AND ch=? AND stop>? AND start<? ORDER BY start",
            arrayOf(src, src, key, from.toString(), to.toString()),
        ).use { while (it.moveToNext()) out += Programme(key, it.getLong(0), it.getLong(1), it.getString(2) ?: "", it.getString(3), it.getString(4), it.getString(5)) }
        return out.toTypedArray()
    }

    override fun loadMany(src: String, keys: List<String>, from: Long, to: Long): List<Programme> {
        if (keys.isEmpty()) return emptyList()
        val out = ArrayList<Programme>()
        val args = ArrayList<String>(keys.size + 5)
        args += src; args += src; args += keys
        args += (from - GuideDb.MAX_PROGRAMME).toString(); args += to.toString(); args += from.toString()
        readableDatabase.rawQuery(
            "SELECT ch,start,stop,title,desc,sub,ep FROM prog WHERE src=? AND gen=(SELECT gen FROM meta WHERE src=?) AND ch IN (" +
                keys.joinToString(",") { "?" } + ") AND start>=? AND start<? AND +stop>? ORDER BY ch,start",
            args.toTypedArray(),
        ).use { while (it.moveToNext()) out += Programme(it.getString(0), it.getLong(1), it.getLong(2), it.getString(3) ?: "", it.getString(4), it.getString(5), it.getString(6)) }
        return out
    }

    /** Title search over current and upcoming programmes of all sources (runs off the main thread). */
    override fun search(q: String, now: Long, limit: Int): List<Pair<String, Programme>> {
        val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val out = ArrayList<Pair<String, Programme>>()
        readableDatabase.rawQuery(
            "SELECT p.ch,p.start,p.stop,p.title,p.desc FROM prog p JOIN meta m ON m.src=p.src AND m.gen=p.gen WHERE p.stop>? AND p.title LIKE ? ESCAPE '\\' ORDER BY p.start LIMIT ?",
            arrayOf(now.toString(), like, limit.toString()),
        ).use { while (it.moveToNext()) out += it.getString(0) to Programme(it.getString(0), it.getLong(1), it.getLong(2), it.getString(3) ?: "", it.getString(4)) }
        return out
    }

    /** Streams every current programme (all sources) without descriptions, for the search index. */
    override fun forEachCurrent(sink: (ch: String, start: Long, stop: Long, title: String, sub: String?, ep: String?) -> Unit) {
        readableDatabase.rawQuery("SELECT p.ch,p.start,p.stop,p.title,p.sub,p.ep FROM prog p JOIN meta m ON m.src=p.src AND m.gen=p.gen", null).use {
            while (it.moveToNext()) sink(it.getString(0), it.getLong(1), it.getLong(2), it.getString(3) ?: "", it.getString(4), it.getString(5))
        }
    }

    /** Description of one programme (the search details card loads it on focus). */
    override fun desc(ch: String, start: Long): String? = readableDatabase.rawQuery(
        "SELECT p.desc FROM prog p JOIN meta m ON m.src=p.src AND m.gen=p.gen WHERE p.src=? AND p.ch=? AND p.start=? LIMIT 1",
        arrayOf(ch.substringBefore('|'), ch, start.toString()),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    override fun count(src: String): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM prog WHERE src=? AND gen=(SELECT gen FROM meta WHERE src=?)", arrayOf(src, src)).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    override fun sizeBytes(): Long = try { java.io.File(readableDatabase.path).length() } catch (e: Exception) { 0L }

    companion object {
        private const val WAL_MAX = 32L * 1024 * 1024
        @Volatile private var inst: GuideDb? = null
        /** The process's guide store: the core's with row EPG on, else Android's (decided once per process). */
        fun get(ctx: Context): GuideDb = inst ?: synchronized(this) { inst ?: open(ctx.applicationContext).also { inst = it } }

        /**
         * Opens the guide store. A log left far too large by older versions is not folded back (that needs as much free
         * space again, which a full device does not have, and minutes of writing): the guide is only a cache, so the
         * files go and the next refresh downloads the guide again.
         */
        private fun open(ctx: Context): GuideDb {
            val f = ctx.getDatabasePath("epg.db")
            dropHugeLog(f)
            if (CoreSwitch.core(CoreSwitch.Row.EPG)) {
                try { return CoreGuideDb(f).also { Diag.log("guide database: on the Rust core") } }
                catch (e: Exception) { Diag.log("guide database: core store failed to open (${e.javaClass.simpleName}), using Android's") }
            }
            return EpgDb(ctx).also { it.checkpoint() }
        }

        internal fun dropHugeLog(f: java.io.File) {
            val wal = java.io.File(f.path + "-wal")
            if (wal.length() > 256L * 1024 * 1024) {
                Diag.log("guide database: log of ${wal.length() / 1048576} MB, guide cache rebuilt")
                for (x in listOf(f, wal, java.io.File(f.path + "-shm"), java.io.File(f.path + "-journal"))) x.delete()
            }
        }
    }
}

/**
 * The guide store on the Rust core (row EPG): same file, schema and rules as [EpgDb] (generations, 5000-row chunked
 * deletes, log checkpoints and truncation), written and read by the core's bundled SQLite only. A refresh streams the
 * downloaded guide into the core ([loadGuide]: gzip limits, XMLTV parsing, batches); the UI's queries go through a
 * second core connection, so they run while a refresh writes.
 */
class CoreGuideDb(private val file: java.io.File) : GuideDb {
    private val db = io.github.kmmarin.tvbro.core.CoreEpgDb.open(file.path)
    /** The core object (the search index rebuild reads the current programmes from it directly). */
    internal val store get() = db

    private fun prog(p: io.github.kmmarin.tvbro.core.FfiProgramme) = Programme(p.key, p.start, p.stop, p.title, p.desc, p.sub, p.ep)

    override fun meta(src: String): EpgDb.Meta? = db.meta(src)?.let { EpgDb.Meta(it.generation.toInt(), it.minStart, it.maxStop, it.updated) }
    override fun beginRefresh(src: String): Int = db.beginRefresh(src).toInt()
    override fun insertBatch(src: String, gen: Int, rows: List<Programme>) =
        db.insertBatch(src, gen.toLong(), rows.map { io.github.kmmarin.tvbro.core.FfiProgramme(it.key, it.start, it.stop, it.title, it.desc, it.sub, it.ep) })
    override fun commit(src: String, gen: Int, minStart: Long, maxStop: Long) = db.commit(src, gen.toLong(), minStart, maxStop, System.currentTimeMillis())
    override fun discard(src: String, gen: Int) = db.discard(src, gen.toLong())
    override fun deleteSource(src: String) = db.deleteSource(src)
    override fun checkpoint(force: Boolean) { if (!db.checkpoint(force)) Diag.log("guide database: checkpoint busy, log ${java.io.File(file.path + "-wal").length() / 1048576} MB") }
    override fun load(src: String, key: String, from: Long, to: Long): Array<Programme> = db.load(src, key, from, to).map(::prog).toTypedArray()
    override fun loadMany(src: String, keys: List<String>, from: Long, to: Long): List<Programme> = db.loadMany(src, keys, from, to).map(::prog)
    override fun search(q: String, now: Long, limit: Int): List<Pair<String, Programme>> =
        db.search(q, now, limit.toLong()).map { it.key to Programme(it.key, it.start, it.stop, it.title, it.desc) }
    override fun forEachCurrent(sink: (ch: String, start: Long, stop: Long, title: String, sub: String?, ep: String?) -> Unit) {
        var after = 0L
        while (true) {
            val page = db.currentPage(after, 2000)
            if (page.isEmpty()) return
            for (r in page) sink(r.ch, r.start, r.stop, r.title, r.sub, r.ep)
            after = page.last().rowid
        }
    }
    override fun desc(ch: String, start: Long): String? = db.desc(ch, start)
    override fun count(src: String): Int = db.count(src).toInt()
    override fun sizeBytes(): Long = file.length()
    fun walPeak(): Long = db.walPeak().toLong()
    fun digest(src: String) = db.digest(src)

    /** Parses one downloaded guide (plain or gzip) into generation [gen]; see `CoreEpgDb.load_guide`. */
    fun loadGuide(src: String, gen: Int, inp: java.io.InputStream, wanted: Collection<String>, minStop: Long, maxStart: Long, progress: (Long) -> Unit) =
        db.loadGuide(src, gen.toLong(), Feed(inp, progress), wanted.toList(), minStop, maxStart)
}

/**
 * Guide view for the UI, loaded in windows: programmes are kept per (channel, 3-hour chunk) in a bounded LRU cache and
 * loaded on one low-priority thread with one query per chunk for many channels at once (the UI never touches the
 * database).
 *
 * Two ways in:
 *  - the TV screen says what it shows with [want] (visible rows, focused row and its neighbours first, for the
 *    visible time window); a new call replaces the old demand, so rows that scrolled past or the previous group are
 *    dropped from the queue. Rows read the cache with [peek] and [listen] for their own channel only: data arriving
 *    for one row recomposes that row, not the grid. [hold] drops the demand while a key is held (rows flying past are
 *    not loaded).
 *  - [programmes] (now / next for the player, lists, the old guide): the chunks around now, loaded on request; readers
 *    recompose when such a request completes (one shared counter, never bumped by [want] loads).
 */
class DbEpg(private val db: GuideDb, private val shiftSec: Long) : EpgIndex() {
    private data class Key(val ch: String, val chunk: Long)

    private val lock = Any()
    private val cache = object : LinkedHashMap<Key, Array<Programme>>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Array<Programme>>?) = size > MAX_ENTRIES
    }
    /** Channel key -> the guide key that had its programmes (tvg-id or name); worker thread only. */
    private val resolved = HashMap<String, String>()
    private var want: List<Channel> = emptyList()
    private var wantC0 = 0L
    private var wantC1 = -1L
    /** [programmes] requests not loaded yet, oldest first. */
    private val extra = LinkedHashMap<Key, Channel>()
    private var running = false
    private val version = mutableIntStateOf(0)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** Rows listening for their channel's data (main thread only). */
    private val listeners = HashMap<String, ArrayList<() -> Unit>>()
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "tvbro-epg").apply { priority = Thread.NORM_PRIORITY - 1; isDaemon = true } }

    /** Programmes of [ch] overlapping [from, to) (display time) that are in the cache; never loads. */
    fun peek(ch: Channel, from: Long, to: Long): Array<Programme> {
        if (to <= from) return NONE
        val c0 = chunkOf(from)
        val c1 = chunkOf(to - 1)
        val parts = ArrayList<Array<Programme>>(3)
        synchronized(lock) { for (c in c0..c1) cache[Key(ch.key, c)]?.let { if (it.isNotEmpty()) parts += it } }
        return when (parts.size) {
            0 -> NONE
            1 -> parts[0]
            else -> EpgIndex.tidy(parts.flatMap { it.asList() }.toTypedArray()) // a programme across a chunk edge is in both
        }
    }

    /** Whether every chunk of [from, to) is cached for [ch]. */
    fun has(ch: Channel, from: Long, to: Long): Boolean = synchronized(lock) { (chunkOf(from)..chunkOf(to - 1)).all { cache.containsKey(Key(ch.key, it)) } }

    /** Calls [l] on the main thread whenever data for [chKey] arrives. */
    fun listen(chKey: String, l: () -> Unit) { listeners.getOrPut(chKey) { ArrayList(1) }.add(l) }
    fun unlisten(chKey: String, l: () -> Unit) { listeners[chKey]?.let { it.remove(l); if (it.isEmpty()) listeners.remove(chKey) } }

    /**
     * What the screen needs now: [chs] in priority order for display times [from, to). Replaces the previous demand
     * (work queued for rows no longer wanted is dropped; the query already running finishes).
     */
    fun want(chs: List<Channel>, from: Long, to: Long) {
        synchronized(lock) { want = chs; wantC0 = chunkOf(from); wantC1 = chunkOf(to - 1) }
        kick()
    }

    /** Drops the screen's demand (a key is held: rows flying past are not loaded), the prefetch too. */
    fun hold() = synchronized(lock) { want = emptyList(); pre = emptyList() }

    /** Rows of the groups next to the current one (visible window), loaded last: see [prefetch]. */
    private var pre: List<Channel> = emptyList()
    private var preC0 = 0L
    private var preC1 = -1L

    /**
     * Low priority: [chs] (the visible rows of the two groups above and below the current one) for [from, to), loaded
     * only when nothing the screen shows or asked for is waiting, and never while a key is held, so moving to a
     * neighbouring group shows its guide at once. Replaces the previous prefetch; the cache stays bounded (LRU).
     */
    fun prefetch(chs: List<Channel>, from: Long, to: Long) {
        synchronized(lock) { pre = chs; preC0 = chunkOf(from); preC1 = chunkOf(to - 1) }
        kick()
    }

    override fun programmes(ch: Channel): Array<Programme> {
        version.intValue // subscribe: recompose when a requested channel arrives
        val now = System.currentTimeMillis() / 1000
        return request(ch, now - CHUNK, now + CHUNK)
    }

    /** [ch]'s programmes in [from, to) from the cache, loading missing chunks (readers recompose on arrival). */
    fun programmes(ch: Channel, from: Long, to: Long): Array<Programme> {
        version.intValue
        return request(ch, from, to)
    }

    private fun request(ch: Channel, from: Long, to: Long): Array<Programme> {
        var missing = false
        synchronized(lock) {
            for (c in chunkOf(from)..chunkOf(to - 1)) {
                val k = Key(ch.key, c)
                if (!cache.containsKey(k) && !extra.containsKey(k)) {
                    extra[k] = ch; missing = true
                    if (extra.size > MAX_EXTRA) extra.remove(extra.keys.first())
                }
            }
        }
        if (missing) kick()
        return peek(ch, from, to)
    }

    private fun kick() {
        val start = synchronized(lock) { if (running) false else { running = true; true } }
        if (start) exec.execute(::drain)
    }

    private class Job(val chunk: Long, val chs: List<Channel>, val legacy: Boolean)

    /** Next batch: the screen's rows first (in their order), then [programmes] requests. Under [lock]. */
    private fun nextJob(): Job? {
        val w = want
        if (w.isNotEmpty() && wantC1 >= wantC0) {
            var chunk = Long.MIN_VALUE
            val batch = ArrayList<Channel>()
            val seen = HashSet<String>()
            for (ch in w) {
                if (!seen.add(ch.key)) continue
                if (chunk == Long.MIN_VALUE) {
                    for (c in wantC0..wantC1) if (!cache.containsKey(Key(ch.key, c))) { chunk = c; break }
                    if (chunk == Long.MIN_VALUE) continue
                }
                if (!cache.containsKey(Key(ch.key, chunk))) { batch += ch; if (batch.size >= BATCH) break }
            }
            if (batch.isNotEmpty()) return Job(chunk, batch, false)
        }
        if (extra.isEmpty()) return prefetchJob()
        val chunk = extra.keys.first().chunk
        val batch = ArrayList<Channel>()
        val it = extra.entries.iterator()
        while (it.hasNext() && batch.size < BATCH) {
            val e = it.next()
            if (e.key.chunk != chunk) continue
            it.remove()
            if (!cache.containsKey(e.key)) batch += e.value
        }
        return if (batch.isEmpty()) nextJob() else Job(chunk, batch, true)
    }

    /** The next prefetch batch (nothing while a key is held). Under [lock]. */
    private fun prefetchJob(): Job? {
        if (pre.isEmpty() || preC1 < preC0 || io.github.kmmarin.tvbro.ui.KeyHold.busy()) return null
        for (c in preC0..preC1) {
            val batch = pre.filter { !cache.containsKey(Key(it.key, c)) }.distinctBy { it.key }.take(BATCH)
            if (batch.isNotEmpty()) return Job(c, batch, false)
        }
        pre = emptyList()
        return null
    }

    private fun drain() {
        while (true) {
            val job = synchronized(lock) { nextJob() ?: run { running = false; null } } ?: break
            val t0 = System.nanoTime()
            val got = try { fetch(job.chunk, job.chs) } catch (e: Exception) { job.chs.associate { it.key to NONE } }
            if (io.github.kmmarin.tvbro.ui.Lat.enabled) android.util.Log.i("tvbro-lat", "load ${job.chs.size} rows chunk ${job.chunk - chunkOf(System.currentTimeMillis() / 1000)}${if (job.legacy) " (request)" else ""} ${(System.nanoTime() - t0) / 1_000_000} ms")
            synchronized(lock) { for ((k, v) in got) cache[Key(k, job.chunk)] = v }
            val keys = job.chs.map { it.key }
            main.post {
                for (k in keys) listeners[k]?.let { ls -> for (l in ls.toTypedArray()) l() }
                if (job.legacy) version.intValue++
            }
        }
        io.github.kmmarin.tvbro.ui.Lat.data()
    }

    /** One chunk of [chs]: by each channel's tvg-id key, then by name for those without rows (as before, per channel). */
    private fun fetch(chunk: Long, chs: List<Channel>): Map<String, Array<Programme>> {
        val from = chunk * CHUNK - shiftSec
        val to = from + CHUNK
        val out = HashMap<String, Array<Programme>>(chs.size * 2)
        val first = HashMap<String, ArrayList<Channel>>()
        for (c in chs) first.getOrPut(resolved[c.key] ?: c.idKey ?: c.nameKey) { ArrayList(1) } += c
        val rows = query(first.keys, from, to)
        val retry = HashMap<String, ArrayList<Channel>>()
        for ((k, cs) in first) for (c in cs) {
            val r = rows[k]
            when {
                r != null -> { out[c.key] = finish(r); resolved.putIfAbsent(c.key, k) }
                k == c.idKey && resolved[c.key] == null && c.nameKey != k -> retry.getOrPut(c.nameKey) { ArrayList(1) } += c
                else -> out[c.key] = NONE
            }
        }
        if (retry.isNotEmpty()) {
            val r2 = query(retry.keys, from, to)
            for ((k, cs) in retry) for (c in cs) {
                val r = r2[k]
                out[c.key] = if (r != null) { resolved[c.key] = k; finish(r) } else NONE
            }
        }
        return out
    }

    private fun query(keys: Collection<String>, from: Long, to: Long): Map<String, List<Programme>> {
        val out = HashMap<String, ArrayList<Programme>>()
        for ((src, ks) in keys.groupBy { it.substringBefore('|') }) for (part in ks.chunked(200)) {
            for (p in db.loadMany(src, part, from, to)) out.getOrPut(p.key) { ArrayList() } += p
        }
        return out
    }

    private fun finish(l: List<Programme>): Array<Programme> {
        var r = l.toTypedArray()
        if (shiftSec != 0L) r = Array(r.size) { r[it].copy(start = r[it].start + shiftSec, stop = r[it].stop + shiftSec) }
        return EpgIndex.tidy(r) // overlapping entries from the provider: one clean timeline
    }

    companion object {
        /** Cache granularity: programmes are loaded and kept per channel and 3-hour chunk of display time. */
        const val CHUNK = 3 * 3600L
        /** (channel, chunk) entries kept (a few programmes each). */
        const val MAX_ENTRIES = 3000
        /** Channels per query. */
        const val BATCH = 40
        private const val MAX_EXTRA = 5000
        private val NONE = emptyArray<Programme>()
        fun chunkOf(t: Long): Long = Math.floorDiv(t, CHUNK)
        /** Chunk-aligned extent [start, end) of display time covering [from, to). */
        fun extent(from: Long, to: Long): Pair<Long, Long> = chunkOf(from) * CHUNK to (chunkOf(to - 1) + 1) * CHUNK
    }
}
