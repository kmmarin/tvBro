// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import android.content.Context
import android.util.Log
import android.util.Xml
import io.github.kmmarin.tvbro.data.Capped
import io.github.kmmarin.tvbro.data.CoreGuideDb
import io.github.kmmarin.tvbro.data.CoreShadow
import io.github.kmmarin.tvbro.data.EpgDb
import io.github.kmmarin.tvbro.data.Programme
import io.github.kmmarin.tvbro.data.XmltvParser
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream

/**
 * Guide store benchmark, DEBUG builds only (docs/RUST_CORE.md, row EPG): `adb shell setprop debug.tvbro.epgbench
 * kotlin` (or `core`), then a fresh start of the app runs one guide refresh into a separate database
 * (`bench-kotlin.db` / `bench-core.db`, never `epg.db`) from `files/bench/guide.xml.gz`, with the wanted channel ids
 * in `files/bench/wanted.txt` (one per line) and the time window in `files/bench/window.txt` ("minStop maxStart").
 * The Kotlin run is Repository.refreshEpg's Kotlin path (pull parser, 500-row batches into [EpgDb]); the core run is
 * the row-EPG path ([CoreGuideDb.loadGuide]). One run per process, so the process peak (VmHWM) belongs to it.
 * Result: logcat tag `tvbro-dbg` and `files/bench/result.log`.
 */
object EpgBench {
    private const val SRC = "bench"

    private fun status(k: String): Long = try {
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("$k:") }?.filter { it.isDigit() }?.toLong()?.div(1024) ?: -1
    } catch (e: Exception) { -1 }

    fun run(ctx: Context, which: String) {
        val dir = File(ctx.filesDir, "bench")
        val input = File(dir, "guide.xml.gz")
        if (!input.exists()) { out(dir, "epgbench $which: no ${input.name} in files/bench"); return }
        val wanted = File(dir, "wanted.txt").readLines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toHashSet()
        val (minStop, maxStart) = File(dir, "window.txt").readText().trim().split(Regex("\\s+")).map { it.toLong() }
        val name = "bench-$which.db"
        val dbf = ctx.getDatabasePath(name)
        dbf.parentFile?.mkdirs()
        for (x in listOf(dbf, File(dbf.path + "-wal"), File(dbf.path + "-shm"))) x.delete()
        val wal = File(dbf.path + "-wal")
        val rt = Runtime.getRuntime()
        System.gc()
        val rss0 = status("VmRSS")
        val heap0 = rt.totalMemory() - rt.freeMemory()
        val native0 = android.os.Debug.getNativeHeapAllocatedSize()
        val heapPeak = AtomicLong(heap0); val nativePeak = AtomicLong(native0); val walPeak = AtomicLong(0)
        val running = AtomicBoolean(true)
        val sampler = Thread {
            while (running.get()) {
                heapPeak.accumulateAndGet(rt.totalMemory() - rt.freeMemory()) { a, b -> maxOf(a, b) }
                nativePeak.accumulateAndGet(android.os.Debug.getNativeHeapAllocatedSize()) { a, b -> maxOf(a, b) }
                walPeak.accumulateAndGet(wal.length()) { a, b -> maxOf(a, b) }
                Thread.sleep(20)
            }
        }.apply { isDaemon = true; start() }
        val t0 = System.nanoTime()
        var rows = 0L
        val digest: String
        try {
            if (which == "core") {
                val db = CoreGuideDb(dbf)
                val gen = db.beginRefresh(SRC)
                val r = input.inputStream().use { db.loadGuide(SRC, gen, it, wanted, minStop, maxStart) { } }
                if (r.error != null) throw IllegalStateException(r.error)
                db.commit(SRC, gen, r.minStart, r.maxStop)
                walPeak.accumulateAndGet(db.walPeak()) { a, b -> maxOf(a, b) }
                rows = r.programmes.toLong()
                digest = db.digest(SRC).let { d -> "rows=${d.rows} row=%016x fields=%s".format(d.row.toLong(), d.fields.joinToString(",") { "%016x".format(it.toLong()) }) }
            } else {
                val db = EpgDb(ctx, name)
                val gen = db.beginRefresh(SRC)
                val batch = ArrayList<Programme>(500)
                var minS = Long.MAX_VALUE; var maxS = 0L
                val raw = BufferedInputStream(input.inputStream(), 32 * 1024)
                raw.mark(2); val b0 = raw.read(); val b1 = raw.read(); raw.reset()
                val inp: InputStream = if (b0 == 0x1f && b1 == 0x8b) Capped(GZIPInputStream(raw, 32 * 1024), 2_000_000_000L) else raw
                inp.use {
                    val p = Xml.newPullParser()
                    p.setInput(it, null)
                    XmltvParser.parse(p, { ch, names ->
                        val l = ch.lowercase()
                        if (l in wanted) "$SRC|$l" else names?.firstNotNullOfOrNull { n -> n.lowercase().takeIf { x -> x in wanted } }?.let { "$SRC|$it" }
                    }, minStop, maxStart) { pr ->
                        val dd = pr.desc
                        val q = if (dd != null && dd.length > 160) pr.copy(desc = dd.take(160)) else pr
                        batch += q; rows++
                        if (q.start < minS) minS = q.start
                        if (q.stop > maxS) maxS = q.stop
                        if (batch.size >= 500) { db.insertBatch(SRC, gen, batch); batch.clear() }
                    }
                }
                if (batch.isNotEmpty()) db.insertBatch(SRC, gen, batch)
                db.commit(SRC, gen, minS, maxS)
                val d = CoreShadow.Digest()
                db.readableDatabase.rawQuery("SELECT ch,start,stop,title,desc,sub,ep FROM prog WHERE src=? AND gen=(SELECT gen FROM meta WHERE src=?)", arrayOf(SRC, SRC)).use { c ->
                    while (c.moveToNext()) d.add(Programme(c.getString(0), c.getLong(1), c.getLong(2), c.getString(3) ?: "", c.getString(4), c.getString(5), c.getString(6)))
                }
                db.close()
                digest = "rows=${d.rows} row=%016x fields=%s".format(d.row, d.fields.joinToString(",") { "%016x".format(it) })
            }
        } catch (e: Throwable) {
            running.set(false)
            out(dir, "epgbench $which: failed (${e.javaClass.simpleName}: ${e.message})"); return
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        running.set(false); sampler.join()
        out(dir, "epgbench $which: $rows programmes parsed in $ms ms (parse + store + commit); peak RSS ${status("VmHWM")} MB (RSS at start $rss0 MB); " +
            "Java heap peak +${(heapPeak.get() - heap0) / 1_048_576} MB; native heap peak +${(nativePeak.get() - native0) / 1_048_576} MB; " +
            "log peak ${walPeak.get() / 1_048_576} MB, after commit ${wal.length()} B; db ${dbf.length() / 1_048_576} MB; ABI ${android.os.Build.SUPPORTED_ABIS.joinToString("/")}; digest $digest")
    }

    private fun out(dir: File, line: String) {
        Log.i("tvbro-dbg", line)
        try { File(dir, "result.log").appendText(line + "\n") } catch (e: Exception) { }
    }
}
