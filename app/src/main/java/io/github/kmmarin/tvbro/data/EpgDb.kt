// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.mutableIntStateOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * On-disk guide store. Programmes are streamed in batches into a new "generation" and become visible only when the
 * refresh is committed (atomic swap), so a failed refresh keeps the old data. Queries are small and indexed by
 * (source, generation, channel key, start); nothing holds the full guide in memory.
 */
class EpgDb private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "epg.db", null, 2) {
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

    fun meta(src: String): Meta? = readableDatabase.rawQuery("SELECT gen,minStart,maxStop,updated FROM meta WHERE src=?", arrayOf(src)).use {
        if (it.moveToFirst()) Meta(it.getInt(0), it.getLong(1), it.getLong(2), it.getLong(3)) else null
    }

    /** Starts a refresh: drops leftovers of failed runs and returns the new generation number. */
    fun beginRefresh(src: String): Int {
        val cur = meta(src)?.gen ?: 0
        deleteChunked("src=? AND gen<>?", arrayOf(src, cur.toString()))
        return cur + 1
    }

    fun insertBatch(src: String, gen: Int, rows: List<Programme>) {
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

    fun commit(src: String, gen: Int, minStart: Long, maxStop: Long) {
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

    fun discard(src: String, gen: Int) = deleteChunked("src=? AND gen=?", arrayOf(src, gen.toString()))
    fun deleteSource(src: String) { writableDatabase.delete("meta", "src=?", arrayOf(src)); deleteChunked("src=?", arrayOf(src)) }

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

    private val walFile = java.io.File(ctx.getDatabasePath("epg.db").path + "-wal")

    /**
     * Folds the write-ahead log back into the database and truncates it. Without this the log only grew: a guide refresh
     * rewrites hundreds of MB, readers kept the log from being reset, and on a Fire TV with a large provider it reached
     * 1.7 GB and filled the device (2026-11-12). Called after every refresh step that changes much, and while
     * inserting once the log passes 64 MB. Fails quietly while a reader is busy; the next call tries again.
     */
    fun checkpoint(force: Boolean = true) {
        if (!force && walFile.length() < WAL_MAX) return
        try {
            writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c ->
                if (c.moveToFirst() && c.getInt(0) != 0) Diag.log("guide database: checkpoint busy, log ${walFile.length() / 1048576} MB")
            }
        } catch (e: Exception) { Diag.log("guide database: checkpoint failed (${e.javaClass.simpleName})") }
    }

    fun load(src: String, key: String, from: Long, to: Long): Array<Programme> {
        val out = ArrayList<Programme>()
        readableDatabase.rawQuery(
            "SELECT start,stop,title,desc,sub,ep FROM prog WHERE src=? AND gen=(SELECT gen FROM meta WHERE src=?) AND ch=? AND stop>? AND start<? ORDER BY start",
            arrayOf(src, src, key, from.toString(), to.toString()),
        ).use { while (it.moveToNext()) out += Programme(key, it.getLong(0), it.getLong(1), it.getString(2) ?: "", it.getString(3), it.getString(4), it.getString(5)) }
        return out.toTypedArray()
    }

    /** Title search over current and upcoming programmes of all sources (runs off the main thread). */
    fun search(q: String, now: Long, limit: Int): List<Pair<String, Programme>> {
        val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val out = ArrayList<Pair<String, Programme>>()
        readableDatabase.rawQuery(
            "SELECT p.ch,p.start,p.stop,p.title,p.desc FROM prog p JOIN meta m ON m.src=p.src AND m.gen=p.gen WHERE p.stop>? AND p.title LIKE ? ESCAPE '\\' ORDER BY p.start LIMIT ?",
            arrayOf(now.toString(), like, limit.toString()),
        ).use { while (it.moveToNext()) out += it.getString(0) to Programme(it.getString(0), it.getLong(1), it.getLong(2), it.getString(3) ?: "", it.getString(4)) }
        return out
    }

    /** Streams every current programme (all sources) without descriptions, for the search index. */
    fun forEachCurrent(sink: (ch: String, start: Long, stop: Long, title: String, sub: String?, ep: String?) -> Unit) {
        readableDatabase.rawQuery("SELECT p.ch,p.start,p.stop,p.title,p.sub,p.ep FROM prog p JOIN meta m ON m.src=p.src AND m.gen=p.gen", null).use {
            while (it.moveToNext()) sink(it.getString(0), it.getLong(1), it.getLong(2), it.getString(3) ?: "", it.getString(4), it.getString(5))
        }
    }

    /** Description of one programme (the search details card loads it on focus). */
    fun desc(ch: String, start: Long): String? = readableDatabase.rawQuery(
        "SELECT p.desc FROM prog p JOIN meta m ON m.src=p.src AND m.gen=p.gen WHERE p.src=? AND p.ch=? AND p.start=? LIMIT 1",
        arrayOf(ch.substringBefore('|'), ch, start.toString()),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun count(src: String): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM prog WHERE src=? AND gen=(SELECT gen FROM meta WHERE src=?)", arrayOf(src, src)).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun sizeBytes(): Long = try { java.io.File(readableDatabase.path).length() } catch (e: Exception) { 0L }

    companion object {
        private const val WAL_MAX = 32L * 1024 * 1024
        @Volatile private var inst: EpgDb? = null
        fun get(ctx: Context): EpgDb = inst ?: synchronized(this) { inst ?: open(ctx.applicationContext).also { inst = it } }

        /**
         * Opens the guide store. A log left far too large by older versions is not folded back (that needs as much free
         * space again, which a full device does not have, and minutes of writing): the guide is only a cache, so the
         * files go and the next refresh downloads the guide again.
         */
        private fun open(ctx: Context): EpgDb {
            val f = ctx.getDatabasePath("epg.db")
            val wal = java.io.File(f.path + "-wal")
            if (wal.length() > 256L * 1024 * 1024) {
                Diag.log("guide database: log of ${wal.length() / 1048576} MB, guide cache rebuilt")
                for (x in listOf(f, wal, java.io.File(f.path + "-shm"), java.io.File(f.path + "-journal"))) x.delete()
            }
            return EpgDb(ctx).also { it.checkpoint() }
        }
    }
}

/**
 * Guide view for the UI: per-channel arrays are loaded on demand from the database on one low-priority thread and
 * kept in a small cache (the UI never touches the database). The caller recomposes when a channel's data arrives.
 */
class DbEpg(private val db: EpgDb, private val shiftSec: Long) : EpgIndex() {
    private val cache = ConcurrentHashMap<String, Array<Programme>>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val version = mutableIntStateOf(0)
    @Volatile private var born = System.currentTimeMillis()
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "tvbro-epg").apply { priority = Thread.NORM_PRIORITY - 1; isDaemon = true } }

    override fun programmes(ch: Channel): Array<Programme> {
        version.intValue // subscribe: recompose when data arrives
        if (System.currentTimeMillis() - born > 30 * 60_000L) { cache.clear(); born = System.currentTimeMillis() }
        cache[ch.key]?.let { return it }
        if (pending.add(ch.key)) exec.execute {
            try {
                val now = System.currentTimeMillis() / 1000
                val from = now - 8 * 3600 - shiftSec
                val to = now + 32 * 3600 - shiftSec
                var r = ch.idKey?.let { db.load(it.substringBefore('|'), it, from, to) } ?: emptyArray()
                if (r.isEmpty()) r = db.load(ch.nameKey.substringBefore('|'), ch.nameKey, from, to)
                if (shiftSec != 0L) r = Array(r.size) { r[it].copy(start = r[it].start + shiftSec, stop = r[it].stop + shiftSec) }
                r = EpgIndex.tidy(r) // overlapping entries from the provider: one clean timeline
                if (cache.size > 400) cache.clear()
                cache[ch.key] = r
            } catch (e: Exception) {
                cache[ch.key] = emptyArray()
            } finally {
                pending.remove(ch.key)
                version.intValue++
            }
        }
        return emptyArray()
    }
}
