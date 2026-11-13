// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class SyncKind(val label: String) { CHANNELS("Channels"), GUIDE("Guide"), CATALOGUE("Movies and series") }

/** Outcome of the last syncs of one kind for one source. Times are epoch ms. */
@Serializable
data class SyncRec(val lastOk: Long = 0, val lastErr: Long = 0, val err: String = "", val failures: Int = 0, val tookMs: Long = 0, val items: Int = 0)

/**
 * Sync bookkeeping shown in Settings > Sync: last success, item count, duration, last error (the previous data is
 * always kept) and the live progress of a running job. Failures schedule a retry after 1, 5, then 15 minutes.
 */
object SyncStatus {
    val recs = mutableStateMapOf<String, SyncRec>()
    val running = mutableStateMapOf<String, String>()
    private val started = HashMap<String, Long>()
    @Volatile private var app: Context? = null
    private val json = Json { ignoreUnknownKeys = true }

    private fun key(src: String, k: SyncKind) = "$src|${k.name}"
    private fun sp(ctx: Context) = ctx.getSharedPreferences("sync", Context.MODE_PRIVATE)

    @Synchronized fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        val all = sp(ctx).all
        for ((k, v) in all) try { recs[k] = json.decodeFromString<SyncRec>(v as String) } catch (e: Exception) { }
    }

    fun get(src: String, k: SyncKind): SyncRec = recs[key(src, k)] ?: SyncRec()
    fun progress(src: String, k: SyncKind): String? = running[key(src, k)]

    private fun save(k: String, r: SyncRec) { recs[k] = r; app?.let { sp(it).edit().putString(k, json.encodeToString(r)).apply() } }

    fun start(src: String, k: SyncKind) { val kk = key(src, k); synchronized(started) { started[kk] = System.currentTimeMillis() }; running[kk] = "starting" }
    fun step(src: String, k: SyncKind, text: String) { running[key(src, k)] = text }

    fun ok(src: String, k: SyncKind, items: Int) {
        val kk = key(src, k)
        val t0 = synchronized(started) { started.remove(kk) } ?: System.currentTimeMillis()
        running.remove(kk)
        save(kk, get(src, k).copy(lastOk = System.currentTimeMillis(), failures = 0, tookMs = System.currentTimeMillis() - t0, items = items))
    }

    fun fail(src: String, k: SyncKind, e: Throwable) {
        val kk = key(src, k)
        synchronized(started) { started.remove(kk) }
        running.remove(kk)
        val r = get(src, k).let { it.copy(lastErr = System.currentTimeMillis(), err = Net.describe(e), failures = it.failures + 1) }
        save(kk, r)
        val delay = SyncRules.retryDelayMin(r.failures)
        Diag.log("${k.label} sync failed (${r.err}), kept the previous data; retry in $delay min")
        app?.let { try { Refresh.scheduleRetry(it, delay) } catch (x: Exception) { } }
    }

    fun forget(src: String) { for (k in SyncKind.values()) { recs.remove(key(src, k)); app?.let { sp(it).edit().remove(key(src, k)).apply() } } }

    fun due(src: String, k: SyncKind, intervalH: Int): Boolean = get(src, k).let { SyncRules.due(it.lastOk, it.lastErr, it.failures, intervalH, System.currentTimeMillis()) }
}
