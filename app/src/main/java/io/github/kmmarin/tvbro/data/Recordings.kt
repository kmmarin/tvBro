// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import io.github.kmmarin.tvbro.RecReceiver
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** status: scheduled, recording, done, failed. endMs == 0 means "until stopped". */
@Serializable
data class Rec(
    val id: String, val title: String, val channel: String, val url: String,
    val startMs: Long, val endMs: Long, val path: String = "", val status: String = "scheduled", val note: String = "",
    /** Channel key (parental lock on playback); empty for recordings of older versions (matched by name). */
    val chKey: String = "",
)

object RecRepo {
    var items by mutableStateOf<List<Rec>>(emptyList())
        private set
    val progress = mutableStateMapOf<String, Long>()
    private var store: Store? = null

    @Synchronized fun init(ctx: Context) {
        if (store == null) { store = Store(ctx.applicationContext); items = store!!.recordings(); DlRepo.attach(store!!) }
    }
    fun get(id: String) = items.firstOrNull { it.id == id }
    @Synchronized fun put(r: Rec) { items = items.filter { it.id != r.id } + r; store?.saveRecordings(items) }
    @Synchronized fun remove(id: String) { items = items.filter { it.id != id }; progress.remove(id); store?.saveRecordings(items) }

    // ---- storage ----
    fun dirs(ctx: Context): List<File> {
        val l = ctx.getExternalFilesDirs(null).filterNotNull().filter { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
        return l.ifEmpty { listOf(ctx.filesDir) }
    }
    fun dirLabel(ctx: Context, f: File) = if (f == dirs(ctx).first()) "Internal" else "USB / SD (${f.path.substringAfter("/storage/").substringBefore('/')})"
    fun currentDir(ctx: Context, pref: String): File = (dirs(ctx).firstOrNull { it.path == pref } ?: dirs(ctx).first()).let { File(it, "recordings").apply { mkdirs() } }
    fun freeMb(dir: File): Long = try { StatFs(dir.path).availableBytes / (1024 * 1024) } catch (e: Exception) { 0 }

    fun newFile(dir: File, title: String, startMs: Long): File {
        val safe = title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(' ', '_').take(40).ifEmpty { "rec" }
        return File(dir, safe + "_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(startMs)) + ".ts")
    }

    // ---- alarms ----
    private fun pi(ctx: Context, id: String) = PendingIntent.getBroadcast(
        ctx, id.hashCode(), Intent(ctx, RecReceiver::class.java).setAction("io.github.kmmarin.tvbro.RUN").putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(ctx: Context, r: Rec) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val at = maxOf(r.startMs, System.currentTimeMillis() + 1500)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx, r.id))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx, r.id))
    }

    fun cancelAlarm(ctx: Context, id: String) { ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, id)) }

    fun rescheduleAll(ctx: Context) {
        init(ctx)
        val now = System.currentTimeMillis()
        for (r in items) if (r.status == "scheduled" && (r.endMs == 0L || r.endMs > now)) schedule(ctx, r)
        else if (r.status == "scheduled" || r.status == "recording") put(r.copy(status = "failed", note = "interrupted"))
    }
}

/** Copies a live stream to one file. MPEG-TS bytes are copied as is; HLS (media playlists, unencrypted MPEG-TS segments) is concatenated. */
object Recorder {
    suspend fun run(rec: Rec, file: File, minFreeMb: Long, onBytes: (Long) -> Unit): String {
        var total = file.length()
        var attempt = 0
        val ctx = currentCoroutineContext()
        fun active() = ctx.isActive && (rec.endMs == 0L || System.currentTimeMillis() < rec.endMs)
        while (active()) {
            try {
                val before = total
                FileOutputStream(file, true).use { out ->
                    val sink = { b: ByteArray, n: Int ->
                        out.write(b, 0, n); total += n; onBytes(total)
                        if (total % (32L * 1024 * 1024) < n && RecRepo.freeMb(file.parentFile!!) < minFreeMb / 2) throw java.io.IOException("DISK")
                    }
                    if (rec.url.substringBefore('?').endsWith(".m3u8")) hls(rec.url, ::active, sink) else ts(rec.url, ::active, sink)
                }
                if (total > before) attempt = 0
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (e.message == "DISK") return "disk full"; if (e.message?.startsWith("FATAL") == true) return e.message!!.removePrefix("FATAL ") }
            if (!active()) break
            attempt++
            delay(minOf(1000L shl minOf(attempt, 4), 15_000L)) // reconnect, keep appending to the same file
        }
        return ""
    }

    private suspend fun ts(url: String, active: () -> Boolean, sink: (ByteArray, Int) -> Unit) {
        val call = Net.client.newCall(Net.request(url))
        val h = currentCoroutineContext()[Job]!!.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                val buf = ByteArray(64 * 1024)
                r.body!!.byteStream().use { i ->
                    while (active()) { val n = i.read(buf); if (n < 0) break; sink(buf, n) }
                }
            }
        } finally { h.dispose() }
    }

    private fun resolve(base: String, ref: String): String = try { URI(base).resolve(ref.trim()).toString() } catch (e: Exception) { ref.trim() }

    private suspend fun hls(url: String, active: () -> Boolean, sink: (ByteArray, Int) -> Unit) {
        var pl = url
        val seen = LinkedHashSet<String>()
        var first = true
        val buf = ByteArray(64 * 1024)
        while (active()) {
            val text = Net.client.newCall(Net.request(pl)).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}"); r.body!!.string()
            }
            val lines = text.lines().map { it.trim() }
            if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) { // master playlist: pick the highest bandwidth
                var best = -1L; var uri: String? = null
                for ((i, l) in lines.withIndex()) if (l.startsWith("#EXT-X-STREAM-INF")) {
                    val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                    val u = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                    if (u != null && bw > best) { best = bw; uri = u }
                }
                pl = resolve(pl, uri ?: throw java.io.IOException("no variant")); continue
            }
            if (lines.any { it.startsWith("#EXT-X-KEY") && !it.contains("METHOD=NONE") }) throw java.io.IOException("FATAL encrypted HLS is not supported")
            val ended = lines.any { it.startsWith("#EXT-X-ENDLIST") }
            val target = lines.firstOrNull { it.startsWith("#EXT-X-TARGETDURATION:") }?.substringAfter(':')?.trim()?.toLongOrNull() ?: 6
            var segs = lines.filter { it.isNotEmpty() && !it.startsWith("#") }.map { resolve(pl, it) }
            if (first && !ended) segs = segs.takeLast(3)
            first = false
            for (s in segs) {
                if (!active()) return
                if (!seen.add(s)) continue
                val call = Net.client.newCall(Net.request(s))
                val h = currentCoroutineContext()[Job]!!.invokeOnCompletion { call.cancel() }
                try {
                    call.execute().use { r ->
                        if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                        r.body!!.byteStream().use { i -> while (true) { val n = i.read(buf); if (n < 0) break; sink(buf, n) } }
                    }
                } finally { h.dispose() }
                if (seen.size > 400) seen.remove(seen.first())
            }
            if (ended) return
            delay(target * 500)
        }
    }
}

@Serializable
data class Reminder(val id: String, val chKey: String, val channel: String, val title: String, val startMs: Long)

/** Programme reminders: an alarm one minute before start posts a notification that opens the channel. */
object Reminders {
    /** True while tvBro is in front: the app shows its own pop-up instead of a notification. */
    @Volatile var appVisible = false
    var items by mutableStateOf<List<Reminder>>(emptyList())
        private set

    private fun sp(ctx: Context) = ctx.getSharedPreferences("reminders", Context.MODE_PRIVATE)
    private fun save(ctx: Context) = sp(ctx).edit().putString("l", kotlinx.serialization.json.Json.encodeToString(items)).apply()
    private fun pi(ctx: Context, id: String) = PendingIntent.getBroadcast(
        ctx, ("r" + id).hashCode(), Intent(ctx, RecReceiver::class.java).setAction("io.github.kmmarin.tvbro.REMIND").putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun init(ctx: Context) {
        items = try { kotlinx.serialization.json.Json.decodeFromString(sp(ctx).getString("l", "[]") ?: "[]") } catch (e: Exception) { emptyList() }
    }

    fun add(ctx: Context, r: Reminder) {
        items = items.filter { it.id != r.id } + r; save(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val at = maxOf(r.startMs - 60_000, System.currentTimeMillis() + 1000)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx, r.id))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx, r.id))
    }

    fun remove(ctx: Context, id: String) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, id))
        items = items.filter { it.id != id }; save(ctx)
    }

    fun rescheduleAll(ctx: Context) {
        init(ctx)
        val now = System.currentTimeMillis()
        for (r in items.toList()) if (r.startMs > now) add(ctx, r) else remove(ctx, r.id)
    }
}
/** "Record series" rules (all airings of a title on a channel); the app schedules new airings from the guide. */
object SeriesRules {
    var items by mutableStateOf<List<SeriesRule>>(emptyList())
        private set
    private fun sp(ctx: Context) = ctx.getSharedPreferences("seriesrules", Context.MODE_PRIVATE)
    fun init(ctx: Context) {
        items = try { kotlinx.serialization.json.Json.decodeFromString(sp(ctx).getString("l", "[]") ?: "[]") } catch (e: Exception) { emptyList() }
    }
    fun add(ctx: Context, r: SeriesRule) { items = items.filter { it.id != r.id } + r; sp(ctx).edit().putString("l", kotlinx.serialization.json.Json.encodeToString(items)).apply() }
    fun remove(ctx: Context, id: String) { items = items.filter { it.id != id }; sp(ctx).edit().putString("l", kotlinx.serialization.json.Json.encodeToString(items)).apply() }
}

/** A download of a movie or episode. status: queued, downloading, paused, done, failed. */
@Serializable
data class Dl(
    val id: String, val key: String, val title: String, val url: String, val path: String,
    val status: String = "queued", val note: String = "", val total: Long = 0,
    /** Film / show category (parental lock on playback); empty for downloads of older versions. */
    val cat: String = "",
)

object DlRepo {
    var items by mutableStateOf<List<Dl>>(emptyList())
        private set
    val progress = mutableStateMapOf<String, Long>()
    private var store: Store? = null

    @Synchronized fun attach(s: Store) {
        if (store != null) return
        store = s
        items = s.downloads().map { if (it.status == "downloading") it.copy(status = "paused", note = "interrupted, press OK to resume") else it }
    }
    fun get(id: String) = items.firstOrNull { it.id == id }
    @Synchronized fun put(d: Dl) { items = if (items.any { it.id == d.id }) items.map { if (it.id == d.id) d else it } else items + d; store?.saveDownloads(items) }
    @Synchronized fun remove(id: String) { items = items.filter { it.id != id }; progress.remove(id); store?.saveDownloads(items) }
    fun has(key: String): Boolean = items.any { it.key == key && it.status == "done" }
    /** file:// URL of a finished local copy, or null. */
    fun localFor(key: String): String? = items.firstOrNull { it.key == key && it.status == "done" && File(it.path).exists() }?.let { "file://" + it.path }
}

/** Resumable plain-HTTP download (Range). Returns "" on success or an error text; throws IOException for retryable drops. */
object Downloader {
    suspend fun run(d: Dl, minFreeMb: Long, onProgress: (Long, Long) -> Unit): String {
        val file = File(d.path)
        var have = if (file.exists()) file.length() else 0L
        val rb = Net.request(d.url).newBuilder()
        if (have > 0) rb.header("Range", "bytes=$have-")
        val call = Net.client.newCall(rb.build())
        val h = currentCoroutineContext()[Job]!!.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { r ->
                if (r.code == 416) return ""
                if (!r.isSuccessful) return "HTTP ${r.code}"
                val ct = r.header("Content-Type") ?: ""
                if (ct.contains("mpegurl", true) || d.url.substringBefore('?').endsWith(".m3u8")) return "streaming (HLS) titles cannot be downloaded"
                val partial = r.code == 206
                if (!partial) have = 0
                val len = r.body!!.contentLength()
                val total = if (len > 0) have + len else -1L
                val dir = file.parentFile!!
                if (total > 0 && RecRepo.freeMb(dir) < (total - have) / (1024 * 1024) + minFreeMb) return "not enough free space"
                var written = have
                FileOutputStream(file, partial && have > 0).use { out ->
                    val buf = ByteArray(64 * 1024)
                    r.body!!.byteStream().use { i ->
                        while (currentCoroutineContext().isActive) {
                            val n = i.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); written += n
                            onProgress(written, total)
                            if (written % (32L * 1024 * 1024) < n && RecRepo.freeMb(dir) < minFreeMb / 2) return "not enough free space"
                        }
                    }
                }
                if (total > 0 && written < total) throw java.io.IOException("short")
            }
        } finally { h.dispose() }
        return ""
    }
}