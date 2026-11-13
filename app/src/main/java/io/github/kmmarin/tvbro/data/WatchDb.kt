// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.mutableIntStateOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Per-series memory: last episode, the next one to offer, and the caption / audio choices for the whole series. */
data class SeriesRow(
    val key: String, val updated: Long = 0, val lastEp: String? = null,
    val nextEp: String? = null, val nextSeason: Int = 0, val nextNum: Int = 0, val nextTitle: String? = null,
    val finished: Boolean = false, val active: Boolean = false,
    val subs: SubPref? = null, val audioLang: String? = null,
)

/**
 * Watch state in SQLite (`state.db`): positions, durations and watched marks per movie / episode, and per-series rows.
 * Everything is cached in memory at start (a few thousand rows at most) so the UI never waits on the disk; writes are
 * applied to the cache at once and to the database on one background thread. [rev] changes on every write so
 * Compose screens recompose.
 *
 * Version 1 imports the older per-title state kept in the "vod" SharedPreferences (p_, d_, w_ keys).
 */
class WatchDb private constructor(private val ctx: Context) : SQLiteOpenHelper(ctx, "state.db", null, 1) {
    private val eps = ConcurrentHashMap<String, WatchState>()
    private val epSeries = ConcurrentHashMap<String, String>()
    private val series = ConcurrentHashMap<String, SeriesRow>()
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "tvbro-state").apply { isDaemon = true } }
    val rev = mutableIntStateOf(0)

    init { setWriteAheadLoggingEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE watch(key TEXT PRIMARY KEY, series TEXT, pos INTEGER NOT NULL DEFAULT 0, dur INTEGER NOT NULL DEFAULT 0, watched INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX watch_series ON watch(series)")
        db.execSQL("CREATE TABLE series(key TEXT PRIMARY KEY, updated INTEGER NOT NULL DEFAULT 0, last_ep TEXT, next_ep TEXT, next_season INTEGER, next_num INTEGER, next_title TEXT, " +
            "finished INTEGER NOT NULL DEFAULT 0, active INTEGER NOT NULL DEFAULT 0, subs INTEGER, sub_lang TEXT, audio_lang TEXT)")
        importPrefs(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) { }

    /** Keeps the state saved by earlier versions (SharedPreferences "vod"). The old keys stay as they were. */
    private fun importPrefs(db: SQLiteDatabase) {
        val vp = ctx.getSharedPreferences("vod", Context.MODE_PRIVATE)
        val m = HashMap<String, Triple<Long, Long, Boolean>>()
        for ((k, v) in vp.all) {
            val key = k.drop(2); val t = m[key] ?: Triple(0L, 0L, false)
            when {
                k.startsWith("p_") && v is Long -> m[key] = t.copy(first = v)
                k.startsWith("d_") && v is Long -> m[key] = t.copy(second = v)
                k.startsWith("w_") && v is Boolean -> m[key] = t.copy(third = v)
            }
        }
        val now = System.currentTimeMillis()
        for ((k, t) in m) db.insert("watch", null, ContentValues().apply {
            put("key", k); put("pos", if (t.third) 0 else t.first); put("dur", t.second); put("watched", if (t.third) 1 else 0); put("updated", now)
        })
        if (m.isNotEmpty()) Diag.log("state: imported ${m.size} watch entries from the old settings")
    }

    @Volatile private var loaded = false
    private fun ensure() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val db = readableDatabase
            db.rawQuery("SELECT key,series,pos,dur,watched,updated FROM watch", null).use {
                while (it.moveToNext()) {
                    eps[it.getString(0)] = WatchState(it.getLong(2), it.getLong(3), it.getInt(4) != 0, it.getLong(5))
                    it.getString(1)?.let { s -> epSeries[it.getString(0)] = s }
                }
            }
            db.rawQuery("SELECT key,updated,last_ep,next_ep,next_season,next_num,next_title,finished,active,subs,sub_lang,audio_lang FROM series", null).use {
                while (it.moveToNext()) series[it.getString(0)] = SeriesRow(
                    it.getString(0), it.getLong(1), it.getString(2), it.getString(3), it.getInt(4), it.getInt(5), it.getString(6),
                    it.getInt(7) != 0, it.getInt(8) != 0,
                    if (it.isNull(9)) null else SubPref(it.getInt(9) != 0, it.getString(10) ?: ""), it.getString(11),
                )
            }
            loaded = true
        }
    }

    /** Loads the cache (call once off the main thread at start). */
    fun warm() = ensure()

    fun state(key: String): WatchState? { ensure(); return eps[key] }
    fun seriesRow(key: String): SeriesRow? { ensure(); return series[key] }
    fun allSeries(): List<SeriesRow> { ensure(); return series.values.toList() }

    private fun writeEp(key: String, seriesKey: String?, s: WatchState) {
        eps[key] = s
        if (seriesKey != null) epSeries[key] = seriesKey
        val sk = seriesKey ?: epSeries[key]
        exec.execute {
            try {
                writableDatabase.insertWithOnConflict("watch", null, ContentValues().apply {
                    put("key", key); put("series", sk); put("pos", s.posMs); put("dur", s.durMs); put("watched", if (s.watched) 1 else 0); put("updated", s.updated)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            } catch (e: Exception) { Diag.log("state: write failed (${e.javaClass.simpleName})") }
        }
    }

    /** Saves a position (and duration when known). Watched titles keep their mark; their position is not stored. */
    fun setPos(key: String, ms: Long, durMs: Long = 0, seriesKey: String? = null) {
        ensure()
        val o = eps[key] ?: WatchState()
        if (o.watched) return
        writeEp(key, seriesKey, o.copy(posMs = ms.coerceAtLeast(0), durMs = if (durMs > 0) durMs else o.durMs, updated = System.currentTimeMillis()))
        rev.intValue++
    }

    fun setDur(key: String, ms: Long) {
        ensure()
        val o = eps[key] ?: WatchState()
        if (ms > 0 && o.durMs != ms) writeEp(key, null, o.copy(durMs = ms))
    }

    /** Watched = mark set and position cleared; unwatched = both cleared. */
    fun setWatched(key: String, watched: Boolean, seriesKey: String? = null) {
        ensure()
        val o = eps[key] ?: WatchState()
        writeEp(key, seriesKey, o.copy(posMs = 0, watched = watched, updated = System.currentTimeMillis()))
        rev.intValue++
    }

    fun putSeries(r: SeriesRow) {
        ensure()
        series[r.key] = r
        exec.execute {
            try {
                writableDatabase.insertWithOnConflict("series", null, ContentValues().apply {
                    put("key", r.key); put("updated", r.updated); put("last_ep", r.lastEp); put("next_ep", r.nextEp)
                    put("next_season", r.nextSeason); put("next_num", r.nextNum); put("next_title", r.nextTitle)
                    put("finished", if (r.finished) 1 else 0); put("active", if (r.active) 1 else 0)
                    if (r.subs == null) putNull("subs") else put("subs", if (r.subs.on) 1 else 0)
                    put("sub_lang", r.subs?.lang); put("audio_lang", r.audioLang)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            } catch (e: Exception) { Diag.log("state: series write failed (${e.javaClass.simpleName})") }
        }
        rev.intValue++
    }

    companion object {
        @Volatile private var inst: WatchDb? = null
        fun get(ctx: Context): WatchDb = inst ?: synchronized(this) { inst ?: WatchDb(ctx.applicationContext).also { inst = it } }
    }
}
