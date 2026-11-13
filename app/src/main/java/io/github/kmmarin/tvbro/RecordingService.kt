// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.kmmarin.tvbro.data.Net
import io.github.kmmarin.tvbro.data.Dl
import io.github.kmmarin.tvbro.data.DlRepo
import io.github.kmmarin.tvbro.data.Downloader
import io.github.kmmarin.tvbro.data.RecRepo
import io.github.kmmarin.tvbro.data.Recorder
import io.github.kmmarin.tvbro.data.Store
import java.io.File

/** Foreground service that records one or more streams; survives leaving the app. */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    private fun notif(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rec", "Recording", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "rec").setSmallIcon(applicationInfo.icon)
            .setContentTitle("tvBro is recording").setContentText("${jobs.size} active recording(s)").build()
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notif())
        RecRepo.init(this)
        val id = i?.getStringExtra("id")
        when (i?.action) {
            "start" -> id?.let { start(it) }
            "stop" -> id?.let { jobs[it]?.cancel() }
            "dl" -> kickDl()
            "dlpause" -> id?.let { if (dlCurrent == it) { dlJob?.cancel(); dlJob = null }; kickDl() }
            "dlcancel" -> id?.let {
                if (dlCurrent == it) { dlJob?.cancel(); dlJob = null }
                DlRepo.get(it)?.let { d -> java.io.File(d.path).delete() }
                DlRepo.remove(it); kickDl()
            }
        }
        if (jobs.isEmpty() && dlJob?.isActive != true) stopSelf()
        return START_NOT_STICKY
    }

    private fun start(id: String) {
        val rec = RecRepo.get(id) ?: return
        if (jobs[id]?.isActive == true) return
        val store = Store(this)
        val pref = store.recStorage
        val toShare = pref == io.github.kmmarin.tvbro.data.SmbShare.STORAGE_KEY
        // A network share is written after the recording ends: the stream goes to local storage first (robust to network drops).
        val dir = RecRepo.currentDir(this, pref)
        val usbMissing = !toShare && pref.isNotBlank() && RecRepo.dirs(this).none { it.path == pref }
        if (RecRepo.freeMb(dir) < store.recMinFreeMb) {
            RecRepo.put(rec.copy(status = "failed", note = "not enough free space")); return
        }
        val file = if (rec.path.isNotEmpty() && !rec.path.startsWith("smb:")) File(rec.path) else RecRepo.newFile(dir, rec.title, System.currentTimeMillis())
        var cur = rec.copy(status = "recording", path = file.path, note = if (usbMissing) "USB drive not found, recording to internal storage" else "")
        RecRepo.put(cur)
        if (wake == null) wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvbro:rec").apply { acquire(12 * 3600_000L) }
        jobs[id] = scope.launch {
            var note = ""
            try {
                note = Recorder.run(cur, file, store.recMinFreeMb) { RecRepo.progress[id] = it }
            } catch (e: Exception) { if (e !is kotlinx.coroutines.CancellationException) note = Net.describe(e) }
            var path = file.path
            if (toShare && file.length() > 0) {
                val cfg = store.smb()
                RecRepo.put(RecRepo.get(id)!!.copy(status = "recording", note = "copying to ${cfg.label}"))
                try { path = withContext(kotlinx.coroutines.NonCancellable) { io.github.kmmarin.tvbro.data.SmbShare.upload(cfg, file) } }
                catch (e: Exception) {
                    io.github.kmmarin.tvbro.data.Diag.log("recording: network share unavailable (${io.github.kmmarin.tvbro.data.SmbShare.describe(e)}), kept locally")
                    if (note.isEmpty()) note = "network share unavailable (${io.github.kmmarin.tvbro.data.SmbShare.describe(e)}), kept on this device"
                }
            }
            val ok = note.isEmpty() || note.startsWith("network share") || note.startsWith("USB drive")
            RecRepo.put(RecRepo.get(id)!!.copy(status = if (ok) "done" else "failed", note = if (usbMissing && note.isEmpty()) cur.note else note, path = path))
            synchronized(jobs) { jobs.remove(id) }
            if (jobs.isEmpty() && dlJob?.isActive != true) { wake?.let { if (it.isHeld) it.release() }; wake = null; stopSelf() }
        }
    }

    private var dlJob: Job? = null
    private var dlGen = 0
    @Volatile private var dlCurrent: String? = null

    private fun metered() = try { (getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered } catch (e: Exception) { false }

    /** Runs queued downloads one after another (only on unmetered Wi-Fi/Ethernet); resumes partial files with Range. */
    private fun kickDl() {
        if (dlJob?.isActive == true) return
        if (wake == null) wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvbro:dl").apply { acquire(12 * 3600_000L) }
        val gen = ++dlGen
        dlJob = scope.launch {
            try {
                while (true) { val d = DlRepo.items.firstOrNull { it.status == "queued" } ?: break; runDl(d) }
            } finally {
                if (gen == dlGen) {
                    dlCurrent = null
                    if (jobs.isEmpty()) { wake?.let { if (it.isHeld) it.release() }; wake = null; stopSelf() }
                }
            }
        }
    }

    private suspend fun runDl(d0: Dl) {
        val id = d0.id
        dlCurrent = id
        val minFree = Store(this).recMinFreeMb
        var attempts = 0
        while (true) {
            if (metered()) {
                DlRepo.get(id)?.let { DlRepo.put(it.copy(note = "waiting for Wi-Fi or Ethernet")) }
                kotlinx.coroutines.delay(15_000)
                if (DlRepo.get(id)?.status != "queued") return
                continue
            }
            val cur = DlRepo.get(id) ?: return
            DlRepo.put(cur.copy(status = "downloading", note = ""))
            val err = try {
                Downloader.run(DlRepo.get(id)!!, minFree) { b, t ->
                    DlRepo.progress[id] = b
                    DlRepo.get(id)?.let { x -> if (t > 0 && x.total != t) DlRepo.put(x.copy(total = t)) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "io:" + Net.describe(e) }
            val now = DlRepo.get(id) ?: return
            when {
                err.isEmpty() -> { DlRepo.put(now.copy(status = "done", note = "")); return }
                err.startsWith("io:") -> {
                    if (++attempts >= 4) { DlRepo.put(now.copy(status = "paused", note = "interrupted, press OK to resume")); return }
                    kotlinx.coroutines.delay(3000L * attempts)
                }
                else -> { DlRepo.put(now.copy(status = "failed", note = err)); return }
            }
        }
    }

    override fun onDestroy() { scope.cancel(); wake?.let { if (it.isHeld) it.release() }; super.onDestroy() }
}



/** Starts scheduled recordings (alarm) and re-arms alarms after boot. */
class RecReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.action) {
            Intent.ACTION_BOOT_COMPLETED -> { RecRepo.rescheduleAll(ctx); io.github.kmmarin.tvbro.data.Reminders.rescheduleAll(ctx) }
            "io.github.kmmarin.tvbro.REMIND" -> {
                val id = i.getStringExtra("id") ?: return
                io.github.kmmarin.tvbro.data.Reminders.init(ctx)
                val r = io.github.kmmarin.tvbro.data.Reminders.items.firstOrNull { it.id == id } ?: return
                if (io.github.kmmarin.tvbro.data.Reminders.appVisible) return // the open app shows its pop-up (AppModel.dueReminder)
                val nm = ctx.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel("remind", "Programme reminders", NotificationManager.IMPORTANCE_HIGH))
                val open = android.app.PendingIntent.getActivity(ctx, id.hashCode(),
                    Intent(ctx, MainActivity::class.java).putExtra("play", r.chKey).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
                nm.notify(id.hashCode(), Notification.Builder(ctx, "remind").setSmallIcon(ctx.applicationInfo.icon)
                    .setContentTitle("Starting soon: ${r.title}").setContentText(r.channel + " (select to watch)").setContentIntent(open).setAutoCancel(true).build())
                io.github.kmmarin.tvbro.data.Reminders.remove(ctx, id)
            }
            "io.github.kmmarin.tvbro.RUN" -> {
                val id = i.getStringExtra("id") ?: return
                try { ctx.startForegroundService(Intent(ctx, RecordingService::class.java).setAction("start").putExtra("id", id)) }
                catch (e: Exception) { RecRepo.init(ctx); RecRepo.get(id)?.let { RecRepo.put(it.copy(status = "failed", note = "could not start service")) } }
            }
        }
    }
}
