// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import android.os.Process
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** [SqlDb] over the framework SQLite. */
class AndroidSql(val db: SQLiteDatabase) : SqlDb {
    private fun bind(st: SQLiteStatement, a: Array<out Any?>) {
        st.clearBindings()
        a.forEachIndexed { i, v ->
            when (v) {
                null -> st.bindNull(i + 1)
                is Long -> st.bindLong(i + 1, v); is Int -> st.bindLong(i + 1, v.toLong())
                is Double -> st.bindDouble(i + 1, v)
                else -> st.bindString(i + 1, v.toString())
            }
        }
    }
    override fun exec(sql: String, args: Array<Any?>) { if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args) }
    override fun query(sql: String, args: Array<String>, row: (SqlRow) -> Unit) {
        db.rawQuery(sql, args).use { c ->
            val r = object : SqlRow { override fun str(i: Int): String? = c.getString(i); override fun long(i: Int): Long = c.getLong(i) }
            while (c.moveToNext()) row(r)
        }
    }
    override fun prepare(sql: String): SqlStmt { val st = db.compileStatement(sql); return object : SqlStmt { override fun insert(vararg a: Any?): Long { bind(st, a); return st.executeInsert() } } }
    override fun begin() = db.beginTransaction()
    override fun commit() { db.setTransactionSuccessful(); db.endTransaction() }
}

/**
 * The on-device search index and stored catalogue (search.db). A rebuild writes search.new.db and renames it over
 * search.db under a write lock, so readers always see a complete index and a failed build changes nothing.
 */
object SearchStore {
    private val lock = ReentrantReadWriteLock()
    private var db: AndroidSql? = null
    private var file: File? = null
    private val exec = Executors.newSingleThreadExecutor { r -> Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "tvbro-index") }
    private val queued = AtomicBoolean(false)
    private val buildingState = androidx.compose.runtime.mutableStateOf(false)
    var building: Boolean get() = buildingState.value; private set(v) { buildingState.value = v }
    /** Bumped after each swap; the UI re-reads catalogue pages and re-runs a visible search. */
    val version = androidx.compose.runtime.mutableIntStateOf(0)

    private fun f(ctx: Context) = file ?: File(ctx.applicationContext.getDatabasePath("x").parentFile, "search.db").also { it.parentFile?.mkdirs(); file = it }

    @Synchronized private fun open(ctx: Context): AndroidSql? {
        db?.let { return it }
        val p = f(ctx)
        if (!p.exists()) return null
        return try { AndroidSql(SQLiteDatabase.openDatabase(p.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)).also { db = it } }
        catch (e: Exception) { Diag.log("search index unreadable (${e.javaClass.simpleName})"); null }
    }

    /** Runs [block] against the current index, or returns null when none has been built yet. */
    fun <T> use(ctx: Context, block: (SqlDb) -> T): T? = lock.read { open(ctx)?.let(block) }

    fun exists(ctx: Context) = f(ctx).exists()

    /** Drops the index (an older one may hold stream addresses); the next start or sync builds it again. */
    fun reset(ctx: Context) {
        lock.write {
            synchronized(this) { db?.db?.close(); db = null }
            val t = f(ctx)
            for (x in listOf(t, File(t.parentFile, "search.new.db"))) for (sfx in listOf("", "-journal", "-wal", "-shm")) File(x.path + sfx).delete()
        }
        version.intValue++
    }

    /** Coalesced, low-priority rebuild (one at a time; a request during a build runs once more afterwards). */
    fun requestRebuild(ctx: Context, why: String) {
        val app = ctx.applicationContext
        if (!queued.compareAndSet(false, true)) return
        exec.execute {
            queued.set(false)
            try { rebuild(app, why) } catch (e: Throwable) { Diag.log("search index build failed (${e.javaClass.simpleName}), kept the previous index") }
        }
    }

    private fun rebuild(ctx: Context, why: String) {
        building = true
        val t0 = System.nanoTime()
        val store = Store(ctx)
        val repo = Repository(ctx, gentle = true)
        val target = f(ctx)
        val tmp = File(target.parentFile, "search.new.db")
        tmp.delete(); File(tmp.path + "-journal").delete()
        try {
            val w = SQLiteDatabase.openOrCreateDatabase(tmp, null)
            try {
                w.rawQuery("PRAGMA journal_mode=OFF", null).use { it.moveToFirst() }
                w.execSQL("PRAGMA synchronous=OFF")
                val sql = AndroidSql(w)
                SearchIndex.create(sql)
                val wr = SearchIndex.Writer(sql)
                val sources = store.sources().filter { it.enabled }
                for (s in sources) {
                    try { for (c in repo.channels(s, false)) if (VodApi.isVodUrl(c.url)) wr.vod(VodApi.fromChannel(c)) else wr.channel(c.key, c.name, s.id) }
                    catch (e: Exception) { Diag.log("index: channels of a source unavailable (${Net.describe(e)})") }
                }
                for (s in sources) for (series in listOf(false, true)) {
                    try { repo.eachCatalogue(s, series) { wr.vod(it) } }
                    catch (e: Exception) { Diag.log("index: ${if (series) "series" else "movies"} unavailable (${Net.describe(e)})") }
                }
                EpgDb.get(ctx).forEachCurrent { ch, st, sp, title, sub, ep -> wr.programme(title, ch, st, sp, sub, ep) }
                wr.finish(System.currentTimeMillis())
                Diag.log("search index ($why): ${wr.counts[0]} channels, ${wr.counts[1]} movies, ${wr.counts[2]} series, ${wr.programmes} programmes (${wr.counts[3]} titles) in ${(System.nanoTime() - t0) / 1_000_000} ms")
            } finally { w.close() }
            lock.write {
                synchronized(this) { db?.db?.close(); db = null }
                if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) throw java.io.IOException("rename failed") }
            }
            version.intValue++
        } finally {
            tmp.delete()
            building = false
        }
    }
}
