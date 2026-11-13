// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.work.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object Busy { @Volatile var playing = false }

object Refresh {
    val running = AtomicBoolean(false)
    @Volatile var fgBusy = false      // a user-started refresh is in progress
    @Volatile var peakMb = 0L

    fun sample() {
        val rt = Runtime.getRuntime()
        val mb = (rt.totalMemory() - rt.freeMemory()) / 1048576
        if (mb > peakMb) peakMb = mb
    }

    /** Background work is gentle: skip while the screen is on and another app is in front. */
    fun mayRunNow(ctx: Context): Boolean {
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (pm == null || !pm.isInteractive) return true
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return true
        val me = am.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() } ?: return false
        return me.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    fun schedule(ctx: Context, hours: Int) {
        val every = maxOf(1, hours / 2).toLong()
        val req = PeriodicWorkRequestBuilder<RefreshWorker>(every, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("tvbro-refresh", ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** One extra run after a failed sync (backoff 1, 5, 15 min; see [SyncRules.retryDelayMin]). */
    fun scheduleRetry(ctx: Context, minutes: Int) {
        val req = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setInitialDelay(minutes.toLong().coerceAtLeast(1), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("tvbro-retry", ExistingWorkPolicy.REPLACE, req)
    }
}

/**
 * Background refresh of channels and guide on a schedule (Wi-Fi/Ethernet only). It is gentle: one run at a time,
 * one low-priority thread, small buffers with pauses, deferred while another app is in front, skipped while playing.
 * Old data is kept on any failure.
 */
class RefreshWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        if (Busy.playing) { Diag.log("background refresh: skipped, TV is playing"); return Result.success() }
        if (Refresh.fgBusy) { Diag.log("background refresh: skipped, a manual refresh is running"); return Result.success() }
        if (!Refresh.mayRunNow(app)) { Diag.log("background refresh: deferred, another app is in front"); return Result.retry() }
        if (!Refresh.running.compareAndSet(false, true)) return Result.success()
        val exec = Executors.newSingleThreadExecutor { r -> Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "tvbro-refresh") }
        val t0 = SystemClock.elapsedRealtime()
        Refresh.peakMb = 0
        try {
            withContext(exec.asCoroutineDispatcher()) {
                val store = Store(app)
                val repo = Repository(app, gentle = true)
                SyncStatus.init(app)
                var changed = false
                for (s in store.sources().filter { it.enabled }) {
                    if (Busy.playing || Refresh.fgBusy) { Diag.log("background refresh: stopped early (device busy)"); break }
                    // each kind on its own schedule; a failed kind is retried after its backoff, the old data stays
                    try {
                        if (SyncStatus.due(s.id, SyncKind.CHANNELS, store.chHours)) { repo.refreshChannelFiles(s); store.setLastRefresh(s.id, System.currentTimeMillis()); changed = true }
                    } catch (e: Exception) { }
                    try {
                        if (repo.epgStale(s, store.epgHours) || repo.epgShortOnData(s) || SyncStatus.get(s.id, SyncKind.GUIDE).failures > 0 && SyncStatus.due(s.id, SyncKind.GUIDE, store.epgHours)) {
                            val ch = repo.channels(s, false)
                            Refresh.sample()
                            if (repo.refreshEpg(s, ch, lowMem = true)) changed = true
                        }
                    } catch (e: Exception) { }
                    try {
                        if (s.isXtream && SyncStatus.due(s.id, SyncKind.CATALOGUE, store.vodHours)) { repo.syncCatalogue(s); changed = true }
                    } catch (e: Exception) { }
                    Refresh.sample()
                    Thread.sleep(50)
                }
                if (changed || !SearchStore.exists(app)) SearchStore.requestRebuild(app, "background sync")
            }
        } finally {
            exec.shutdown()
            Refresh.running.set(false)
            Diag.log("background refresh: ${(SystemClock.elapsedRealtime() - t0) / 1000.0} s, peak heap ${Refresh.peakMb} MB")
        }
        return Result.success()
    }
}