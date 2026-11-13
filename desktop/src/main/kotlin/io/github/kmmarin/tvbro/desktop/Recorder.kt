// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import io.github.kmmarin.tvbro.data.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

/**
 * One recording. Times are epoch ms; [endMs] 0 = until stopped. [status]: scheduled, recording, done, failed.
 * [file] is a local path or an smb:// address; [note] explains a fallback or a failure.
 * [url] is only read from files of older versions: the stream address carries the provider login, so it is never
 * saved; the recorder takes it from the channel ([chKey]) when the recording starts.
 */
@Serializable
data class Rec(
    val id: String, val title: String, val channel: String, val chKey: String, val url: String = "",
    val startMs: Long, val endMs: Long, val status: String = "scheduled", val file: String = "", val note: String = "",
    val seriesId: String? = null, val bytes: Long = 0,
    /** Further files when the stream dropped and was reconnected (in order, after [file]). */
    val parts: List<String> = emptyList(),
)

/** Pure scheduling rules (unit-tested). */
object RecPlan {
    /** Scheduled recordings that should be running at [now]. */
    fun toStart(recs: List<Rec>, now: Long): List<Rec> = recs.filter { it.status == "scheduled" && it.startMs <= now && (it.endMs == 0L || it.endMs > now) }
    /** Scheduled recordings whose whole slot passed while tvBro was not running. */
    fun missed(recs: List<Rec>, now: Long): List<Rec> = recs.filter { it.status == "scheduled" && it.endMs in 1..now }
    /** Running recordings that reached their end. */
    fun toStop(recs: List<Rec>, now: Long): List<Rec> = recs.filter { it.status == "recording" && it.endMs in 1..now }

    /** After a restart nothing is running: a "recording" item keeps what it wrote (done) or failed. */
    fun recover(r: Rec, fileBytes: Long): Rec = if (r.status != "recording") r
        else if (fileBytes > 0) r.copy(status = "done", bytes = fileBytes, note = "Stopped early: tvBro was closed")
        else r.copy(status = "failed", note = "tvBro was closed before anything was written")

    /** Start times of [progs] already covered by a (not failed) recording of channel [chKey]. */
    fun covered(recs: List<Rec>, chKey: String, progs: List<Programme>): Set<Long> =
        progs.filter { p -> recs.any { it.chKey == chKey && it.status != "failed" && it.startMs < p.stop * 1000 && (it.endMs == 0L || it.endMs > p.start * 1000) } }
            .map { it.start }.toSet()

    /** What recordings.json holds: everything but the stream address. */
    fun forDisk(recs: List<Rec>): List<Rec> = recs.map { if (it.url.isEmpty()) it else it.copy(url = "") }

    /** Stream address of [r] at start: the channel's current one (an older file's saved one as the fallback); null = wait or fail. */
    fun streamUrl(r: Rec, channels: List<Channel>): String? = channels.firstOrNull { it.key == r.chKey }?.url ?: r.url.ifEmpty { null }

    /** "Title 2026-11-11 16-30.ts" with characters every file system accepts. */
    fun fileName(title: String, startMs: Long): String =
        title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").trim().take(60).ifBlank { "Recording" } + SimpleDateFormat(" yyyy-MM-dd HH-mm").format(Date(startMs)) + ".ts"
}

/**
 * Recordings and series rules (recordings.json, series.json). Recordings run in their own headless mpv
 * (`stream-record`, no decoding), so they continue while something else plays; they run while tvBro runs.
 */
class Recorder(private val app: AppState) {
    private val recFile = File(Paths.config, "recordings.json")
    private val ruleFile = File(Paths.config, "series.json")
    var items by mutableStateOf(load(recFile, Rec.serializer()).map { RecPlan.recover(it, it.file.takeIf { f -> !f.startsWith("smb://") }?.let { f -> File(f).length() } ?: 0) })
        private set
    var rules by mutableStateOf(load(ruleFile, SeriesRule.serializer()))
        private set
    private val running = HashMap<String, Mpv>()

    private fun <T> load(f: File, s: kotlinx.serialization.KSerializer<T>): List<T> = try {
        if (f.exists()) Json2.json.decodeFromString(ListSerializer(s), f.readText()) else emptyList()
    } catch (e: Exception) { Diag.log("${f.name} unreadable (${e.javaClass.simpleName})"); emptyList() }

    private fun saveRecs() { val l = items; try { atomicWrite(recFile, Json2.json.encodeToString(ListSerializer(Rec.serializer()), RecPlan.forDisk(l)).toByteArray()) } catch (e: Exception) { Diag.log("recordings save failed") } }
    private fun saveRules() { val l = rules; try { atomicWrite(ruleFile, Json2.json.encodeToString(ListSerializer(SeriesRule.serializer()), l).toByteArray()) } catch (e: Exception) { Diag.log("series rules save failed") } }

    private fun put(r: Rec) { items = items.filter { it.id != r.id } + r; saveRecs() }
    private fun update(id: String, f: (Rec) -> Rec) { items.firstOrNull { it.id == id }?.let { put(f(it)) } }

    fun active(c: Channel): Rec? = items.firstOrNull { it.status == "recording" && it.chKey == c.key }
    fun scheduledFor(c: Channel, p: Programme): Rec? = items.firstOrNull { it.chKey == c.key && it.status == "scheduled" && it.startMs < p.stop * 1000 && it.endMs > p.start * 1000 }
    fun ruleFor(c: Channel, p: Programme): SeriesRule? = rules.firstOrNull { it.chKey == c.key && SeriesRec.matches(it, p) }

    /** Record now: [minutes] 0 = until stopped, or until [untilMs]. */
    fun recordNow(c: Channel, minutes: Int, untilMs: Long = 0, title: String? = null) {
        if (active(c) != null) return app.toast("Already recording ${c.name}")
        val now = System.currentTimeMillis()
        val end = if (untilMs > 0) untilMs else if (minutes > 0) now + minutes * 60_000L else 0
        put(Rec(UUID.randomUUID().toString().take(8), title ?: c.name, c.name, c.key, startMs = now, endMs = end))
        tick()
        app.toast("Recording ${title ?: c.name}")
    }

    /** Schedules a programme with the padding from Settings; one already on air starts now. */
    fun recordProgramme(c: Channel, p: Programme, seriesId: String? = null, quiet: Boolean = false) {
        val now = System.currentTimeMillis()
        val end = p.stop * 1000 + app.settings.recPadEndMin * 60_000L
        if (end < now) { if (!quiet) app.toast("That programme has ended"); return }
        if (scheduledFor(c, p) != null) { if (!quiet) app.toast("Already scheduled"); return }
        val start = maxOf(p.start * 1000 - app.settings.recPadStartMin * 60_000L, now)
        put(Rec(UUID.randomUUID().toString().take(8), p.title.ifBlank { c.name }, c.name, c.key, startMs = start, endMs = end, seriesId = seriesId))
        tick()
        if (!quiet) app.toast(if (start <= now) "Recording ${p.title}" else "Scheduled: ${p.title} at ${TimeFmt.time(start / 1000)}")
    }

    fun stop(r: Rec) {
        if (r.status == "scheduled") { items = items.filter { it.id != r.id }; saveRecs(); app.toast("Cancelled: ${r.title}"); return }
        finish(r.id, "")
    }

    fun delete(r: Rec) {
        if (r.status == "recording") finish(r.id, "")
        val cur = items.firstOrNull { it.id == r.id } ?: r
        for (f in listOf(cur.file) + cur.parts) if (f.startsWith("smb://")) SmbShare.delete(app.settings.smb, f) else if (f.isNotBlank()) File(f).delete()
        items = items.filter { it.id != r.id }; saveRecs()
    }

    // ---- Record series ----
    fun recordSeries(c: Channel, p: Programme) {
        if (ruleFor(c, p) != null) return app.toast("Already recording this series")
        rules = rules + SeriesRule(UUID.randomUUID().toString().take(8), c.key, c.name, p.title, System.currentTimeMillis()); saveRules()
        app.toast("Series recording: every \"${p.title}\" on ${c.name}")
        scheduleSeries()
    }

    /** Backup restore: the rules of the backup replace the current ones. */
    fun restoreRules(l: List<SeriesRule>) { rules = l; saveRules(); scheduleSeries() }

    fun cancelSeries(r: SeriesRule) {
        rules = rules.filter { it.id != r.id }; saveRules()
        // scheduled (not started) airings of this rule go too; finished and running recordings stay
        items = items.filter { !(it.status == "scheduled" && it.chKey == r.chKey && SeriesRec.key(it.title) == SeriesRec.key(r.title)) }; saveRecs()
        app.toast("Series recording cancelled")
    }

    /** Books the airings of every rule in the next 48 h that are not booked yet (at guide load and every 30 min). */
    fun scheduleSeries() {
        if (rules.isEmpty()) return
        val now = System.currentTimeMillis() / 1000
        var n = 0
        for (r in rules) {
            val c = app.channels.firstOrNull { it.key == r.chKey } ?: continue
            val progs = app.epg.programmes(c).filter { it.stop > now - 6 * 3600 && it.start < now + 48 * 3600 }
            val due = SeriesRec.due(r, progs, RecPlan.covered(items, c.key, progs), now)
            for (p in due) { recordProgramme(c, p, r.id, quiet = true); n++ }
        }
        if (n > 0) Diag.log("series recording: scheduled $n airing(s)")
    }

    // ---- engine ----
    /** Starts and stops recordings on time; called every few seconds and after every change. */
    fun tick() {
        val now = System.currentTimeMillis()
        for (r in RecPlan.missed(items, now)) update(r.id) { it.copy(status = "failed", note = "Missed: tvBro was not running") }
        for (r in RecPlan.toStop(items, now)) finish(r.id, "")
        for (r in RecPlan.toStart(items, now)) start(r)
        // a recorder whose stream ended early (network drop) is restarted into a new file until the end time
        for (r in items.filter { it.status == "recording" && it.id !in running }) start(r, resume = true)
    }

    private val restarts = HashMap<String, Int>()

    private fun start(r: Rec, resume: Boolean = false) {
        if (resume) {
            val n = (restarts[r.id] ?: 0) + 1; restarts[r.id] = n
            if (n > 12) return finish(r.id, "Stopped early: the stream kept failing")
        }
        fun fail(why: String) { if (resume) finish(r.id, why) else update(r.id) { it.copy(status = "failed", note = why) } }
        val url = RecPlan.streamUrl(r, app.channels) ?: run {
            if (app.channels.isEmpty()) return // the playlist is still loading: the next tick tries again
            return fail("The channel is no longer in the playlist")
        }
        if (!PlayUrl.allowed(url)) return fail(PlayUrl.refusal(url))
        val lib = MpvLoader.lib ?: run { update(r.id) { it.copy(status = "failed", note = "Video library (libmpv) missing: see Settings > Playback") }; return }
        val dir = RecTarget.dir(app.settings).apply { mkdirs() }
        val f = File(dir, RecPlan.fileName(r.title, if (resume) System.currentTimeMillis() else r.startMs))
        try {
            val m = Mpv(lib, 0, "no", recorder = true) { why ->
                // stream ended or failed: drop the instance; tick() restarts it (into a new part) while the slot lasts
                app.runOnUi { running.remove(r.id)?.let { x -> Thread { x.destroy() }.start() }; Diag.log("recording ${r.id}: stream ended ($why)") }
            }
            m.set("stream-record", f.path)
            m.play(url)
            running[r.id] = m
            update(r.id) {
                if (resume) it.copy(parts = it.parts + f.path, note = "The stream dropped and was reconnected (${it.parts.size + 2} parts)")
                else it.copy(status = "recording", file = f.path)
            }
        } catch (e: Throwable) {
            update(r.id) { it.copy(status = "failed", note = "Could not start (${e.message ?: e.javaClass.simpleName})") }
        }
    }

    /** Stops the recorder and files the result: empty parts dropped, copied to the share when that is the storage. */
    private fun finish(id: String, note: String) {
        running.remove(id)?.let { m -> m.set("stream-record", ""); m.destroy() }
        restarts.remove(id)
        val r = items.firstOrNull { it.id == id } ?: return
        val files = (listOf(r.file) + r.parts).filter { it.isNotBlank() && !it.startsWith("smb://") }.map(::File)
        files.filter { it.isFile && it.length() == 0L }.forEach { it.delete() }
        val kept = files.filter { it.isFile && it.length() > 0 }
        val size = kept.sumOf { it.length() }
        if (size == 0L) { update(id) { it.copy(status = "failed", note = note.ifBlank { "Nothing was received from the stream" }) }; return }
        update(id) {
            it.copy(status = "done", file = kept.first().path, parts = kept.drop(1).map { f -> f.path }, bytes = size, note = note.ifBlank { it.note },
                endMs = if (it.endMs == 0L || it.endMs > System.currentTimeMillis()) System.currentTimeMillis() else it.endMs)
        }
        val s = app.settings
        if (s.storage == "smb" && kept.all { it.parentFile?.canonicalPath == Paths.recStaging.canonicalPath }) {
            app.io {
                val urls = ArrayList<String>()
                val err = try { for (f in kept) urls += SmbShare.upload(s.smb, f); null } catch (e: Exception) { e }
                app.runOnUi {
                    if (err == null) update(id) { it.copy(file = urls.first(), parts = urls.drop(1), note = it.note.takeIf { n -> n.startsWith("The stream") } ?: "") }
                    else {
                        // keep what was not copied: move it from staging into the local recordings folder, with a note
                        val dir = File(s.recDir.ifBlank { Paths.recordings.path }).apply { mkdirs() }
                        val local = kept.drop(urls.size).map { f -> val k = File(dir, f.name); if (f.renameTo(k)) k else run { f.copyTo(k, true); f.delete(); k } }
                        val all = urls + local.map { it.path }
                        update(id) { it.copy(file = all.first(), parts = all.drop(1), note = "Share unreachable (${SmbShare.describe(err)}): kept on this computer") }
                    }
                }
            }
        }
    }
    /** Stops every recorder (app exit); the files keep what was written. */
    fun shutdown() { for (id in running.keys.toList()) finish(id, "Stopped early: tvBro was closed") }

    /** Address mpv plays for a finished recording. */
    fun playUrl(r: Rec): String = if (r.file.startsWith("smb://")) { SmbBridge.cfg = { app.settings.smb }; SmbBridge.url(r.file) } else r.file
}
