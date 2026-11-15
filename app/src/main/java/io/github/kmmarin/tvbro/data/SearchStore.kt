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
 *
 * Row SEARCH of [CoreSwitch]: on the core, the rebuild and every query go through the core's SQLite (never Android's
 * for this file in the same process). Shadow (debug builds): Kotlin's index is used; the core builds its own copy from
 * the same rows (search.shadow.db) and every query is compared on it afterwards ([CoreShadow.search]).
 */
object SearchStore {
    private val lock = ReentrantReadWriteLock()
    private var db: SearchReader? = null
    private var shadowDb: SearchReader? = null
    private var file: File? = null
    private val onCore get() = CoreSwitch.core(CoreSwitch.Row.SEARCH)
    private val shadow get() = CoreSwitch.shadow(CoreSwitch.Row.SEARCH)
    private val exec = Executors.newSingleThreadExecutor { r -> Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "tvbro-index") }
    private val queued = AtomicBoolean(false)
    private val buildingState = androidx.compose.runtime.mutableStateOf(false)
    var building: Boolean get() = buildingState.value; private set(v) { buildingState.value = v }
    /** Bumped after each swap; the UI re-reads catalogue pages and re-runs a visible search. */
    val version = androidx.compose.runtime.mutableIntStateOf(0)

    private fun f(ctx: Context) = file ?: File(ctx.applicationContext.getDatabasePath("x").parentFile, "search.db").also { it.parentFile?.mkdirs(); file = it }

    private fun shadowFile(ctx: Context) = File(f(ctx).parentFile, "search.shadow.db")

    private fun openFile(p: File, core: Boolean): SearchReader =
        if (core) CoreSearch(p.path)
        else SQLiteDatabase.openDatabase(p.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).let { d -> SqlSearch(AndroidSql(d)) { d.close() } }

    @Synchronized private fun open(ctx: Context): SearchReader? {
        db?.let { return it }
        val p = f(ctx)
        if (!p.exists()) return null
        return try { openFile(p, onCore).also { db = it } }
        catch (e: Exception) { Diag.log("search index unreadable (${e.javaClass.simpleName})"); null }
    }

    @Synchronized private fun openShadow(ctx: Context): SearchReader? {
        shadowDb?.let { return it }
        val p = shadowFile(ctx)
        if (!p.exists()) return null
        return try { CoreSearch(p.path).also { shadowDb = it } } catch (e: Exception) { CoreShadow.log("search: core shadow index unreadable (${e.javaClass.simpleName})"); null }
    }

    @Synchronized private fun closeAll() {
        try { db?.close() } catch (e: Exception) { }
        try { shadowDb?.close() } catch (e: Exception) { }
        db = null; shadowDb = null
    }

    /** Runs [block] against the current index, or returns null when none has been built yet. */
    fun <T> use(ctx: Context, block: (SearchReader) -> T): T? = lock.read { open(ctx)?.let(block) }

    /** A query on the current index; in shadow mode the core's copy answers the same query afterwards. */
    fun search(ctx: Context, q: String, pastFrom: Long, perSection: Int = 30): SearchResult? {
        val t0 = System.nanoTime()
        val r = use(ctx) { it.search(q, pastFrom, perSection) } ?: return null
        if (shadow) {
            val km = (System.nanoTime() - t0) / 1_000_000
            val app = ctx.applicationContext
            CoreShadow.search(r, km) { lock.read { openShadow(app)?.search(q, pastFrom, perSection) } }
        }
        return r
    }

    fun exists(ctx: Context) = f(ctx).exists()

    /** Drops the index (an older one may hold stream addresses); the next start or sync builds it again. */
    fun reset(ctx: Context) {
        lock.write {
            closeAll()
            val t = f(ctx)
            for (x in listOf(t, File(t.parentFile, "search.new.db"), shadowFile(ctx), File(t.parentFile, "search.shadow.new.db")))
                for (sfx in listOf("", "-journal", "-wal", "-shm")) File(x.path + sfx).delete()
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
        val core = onCore; val sh = !core && shadow
        val shTarget = shadowFile(ctx)
        val shTmp = File(target.parentFile, "search.shadow.new.db")
        for (x in listOf(tmp, shTmp)) { x.delete(); File(x.path + "-journal").delete() }
        var android: SQLiteDatabase? = null
        var coreSink: CoreIndexSink? = null
        try {
            val kotlinSink = if (core) null else {
                val w = SQLiteDatabase.openOrCreateDatabase(tmp, null).also { android = it }
                w.rawQuery("PRAGMA journal_mode=OFF", null).use { it.moveToFirst() }
                w.execSQL("PRAGMA synchronous=OFF")
                val sql = AndroidSql(w)
                SearchIndex.create(sql)
                KotlinIndexSink(sql)
            }
            if (core) coreSink = CoreIndexSink(tmp.path)
            else if (sh) coreSink = try { CoreIndexSink(shTmp.path, tolerant = true) } catch (e: Exception) { CoreShadow.log("search index: core failed to start (${e.javaClass.simpleName})"); null }
            val sinks = listOfNotNull<IndexSink>(kotlinSink, coreSink)
            val sources = store.sources().filter { it.enabled }
            for (s in sources) {
                try { for (c in repo.channels(s, false)) if (VodApi.isVodUrl(c.url)) { val v = VodApi.fromChannel(c); sinks.forEach { it.vod(v) } } else sinks.forEach { it.channel(c.key, c.name, s.id) } }
                catch (e: Exception) { Diag.log("index: channels of a source unavailable (${Net.describe(e)})") }
            }
            for (s in sources) for (series in listOf(false, true)) {
                try { repo.eachCatalogue(s, series) { v -> sinks.forEach { it.vod(v) } } }
                catch (e: Exception) { Diag.log("index: ${if (series) "series" else "movies"} unavailable (${Net.describe(e)})") }
            }
            val guide = EpgDb.get(ctx)
            // on the core alone it reads the core's guide store itself; with two sinks both get the same rows in the same order
            if (!(kotlinSink == null && coreSink!!.guide(guide))) guide.forEachCurrent { ch, st, sp, title, sub, ep -> for (x in sinks) x.programme(title, ch, st, sp, sub, ep) }
            val now = System.currentTimeMillis()
            val counts = (kotlinSink ?: coreSink!!).finish(now)
            val coreCounts = if (sh) coreSink?.let { s -> s.finish(now).takeIf { s.failure == null } } else null
            val coreFailure = coreSink?.failure ?: "not started"
            val total = (System.nanoTime() - t0) / 1_000_000
            val coreMs = (coreSink?.nanos ?: 0L) / 1_000_000
            val c = counts.split(',')
            Diag.log("search index ($why${if (core) ", core" else ""}): ${c[0]} channels, ${c[1]} movies, ${c[2]} series, ${c[4]} programmes (${c[3]} titles) in $total ms")
            android?.close(); android = null
            lock.write {
                closeAll()
                if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) throw java.io.IOException("rename failed") }
                if (sh && coreCounts != null) { if (!shTmp.renameTo(shTarget)) { shTarget.delete(); shTmp.renameTo(shTarget) } }
                else shTarget.delete() // never compare against an index of older data
            }
            coreSink = null
            version.intValue++
            if (sh) {
                val fail = coreCounts == null
                if (fail) CoreShadow.log("search index: core failed while building ($coreFailure), kotlin $counts")
                else {
                    val km = total - coreMs
                    try { CoreShadow.log(lock.read { val k = open(ctx); val s = openShadow(ctx); if (k == null || s == null) "search index: an index is missing" else CoreShadow.compareIndex(k, s, km, coreMs) }) }
                    catch (e: Exception) { CoreShadow.log("search index: compare failed (${e.javaClass.simpleName})") }
                }
            }
        } finally {
            try { android?.close() } catch (e: Exception) { }
            try { coreSink?.abort() } catch (e: Exception) { }
            tmp.delete(); shTmp.delete()
            building = false
        }
    }
}
