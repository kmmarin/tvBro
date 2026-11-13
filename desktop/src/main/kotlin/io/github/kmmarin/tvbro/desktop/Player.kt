// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.sun.jna.Native
import kotlinx.coroutines.*
import io.github.kmmarin.tvbro.data.*
import java.awt.Canvas
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

/** Native child surface mpv draws into; mpv is attached once it has a window id and detached before it goes. */
private class VideoCanvas(val onReady: (Long) -> Unit, val onGone: () -> Unit, val onMouse: (MouseEvent) -> Unit) : Canvas() {
    init {
        background = java.awt.Color.BLACK; isFocusable = false
        addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) = onMouse(e) })
    }
    override fun addNotify() { super.addNotify(); onReady(Native.getComponentID(this)) }
    override fun removeNotify() { onGone(); super.removeNotify() }
}

/**
 * The video, at one stable place in the window tree so the same mpv keeps playing when it moves between full screen
 * and the TV screen's preview spot (no re-buffering). Off-screen while a preview spot is not on screen.
 */
@Composable
fun VideoHost(app: AppState) = BoxWithConstraints(Modifier.fillMaxSize()) {
    val req = app.playing ?: return@BoxWithConstraints
    val d = LocalDensity.current
    val r = app.previewRect
    val whole = with(d) { Rect(0f, 0f, maxWidth.toPx(), maxHeight.toPx()) }
    val off = Rect(-64f, -64f, -48f, -48f)
    val onTv = app.tab == Tab.TV && app.detail == null
    // where the video belongs; null while the TV screen is still laying out its preview spot (keep the last place)
    val target: Rect? = when {
        app.full -> whole
        onTv && r != null -> r
        onTv -> null
        else -> off
    }
    val box = remember { Animatable(target ?: off, Rect.VectorConverter) }
    LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        // full screen <-> preview: a short, smooth move of the same surface (the player is never reloaded);
        // window resizes and off-screen moves are immediate
        val between = (box.targetValue == whole && t == r) || (box.targetValue == r && t == whole)
        if (between && box.value != off) box.animateTo(t, tween(320, easing = FastOutSlowInEasing)) else box.snapTo(t)
    }
    val b = box.value
    val mod = Modifier.offset { IntOffset(b.left.roundToInt(), b.top.roundToInt()) }
        .size(with(d) { b.width.coerceAtLeast(1f).toDp() }, with(d) { b.height.coerceAtLeast(1f).toDp() })
    val lib = MpvLoader.lib
    if (lib == null) {
        if (app.full) Box(Modifier.fillMaxSize().background(Color.Black)) {
            Text("Video library missing (libmpv is not included in tvBro).\n${MpvLoader.error ?: ""}\nSettings > Playback > Video library shows how to add it (see also INSTALL.md).\nPress Esc to go back.\n\n${req.title}",
                color = Color.White, modifier = Modifier.align(Alignment.Center).padding(32.dp))
        }
        return@BoxWithConstraints
    }
    SwingPanel(background = Color.Black, modifier = mod, factory = {
        VideoCanvas(onReady = { wid -> app.player.attach(lib, wid) }, onGone = { app.player.detach() }, onMouse = { e -> app.runOnUi { app.player.mouse(e) } })
    })
}

class PlayerCtl(private val app: AppState, private val scope: CoroutineScope) {
    private var mpv: Mpv? = null
    private var loadedUrl: String? = null
    private var req: PlayReq? = null
    var info by mutableStateOf(false)
    var menu by mutableStateOf(false)
    var sel by mutableStateOf(0)
    var aspect by mutableStateOf(0)
    var sleepMin by mutableStateOf(0)
    private var sleepJob: Job? = null
    private var ticker: Job? = null
    /** Bottom bar / quick settings hide 5 s after the last input. */
    private var lastInput = 0L
    private var barShown = false
    // Up next (episodes)
    private var upNextAt = 0L
    private var upNextCancelled = false
    private var advancing = false
    private var lastSave = 0L
    private var digits = ""
    private var digitJob: Job? = null
    val aspects = listOf("Auto" to "-1", "16:9" to "16:9", "4:3" to "4:3", "2.35:1" to "2.35:1", "Fill" to "-1")
    val items = listOf("Audio", "Subtitles", "Video quality", "Audio delay", "Aspect", "Sleep timer", "Record", "Stream info")

    fun attach(lib: MpvLib, wid: Long) {
        try {
            val m = Mpv(lib, wid, app.settings.hwdec, onLoaded = { app.runOnUi { onLoaded() } }) { why -> app.runOnUi { onEnd(why) } }
            m.set("audio-delay", "%.3f".format(java.util.Locale.US, app.settings.audioDelayMs / 1000.0))
            applyCaptionStyle(m)
            mpv = m; loadedUrl = null
            app.playing?.let { load(it) }
            if (ticker?.isActive != true) ticker = scope.launch { while (true) { delay(500); tick() } }
        } catch (e: Throwable) { Diag.log("mpv start failed: ${e.message}"); app.toast("Video engine failed: ${e.message}") }
    }

    fun detach() { saveResume(); ticker?.cancel(); mpv?.destroy(); mpv = null; loadedUrl = null }

    private fun applyCaptionStyle(m: Mpv) {
        m.set("sub-font-size", listOf("40", "55", "72")[app.settings.captionSize.coerceIn(0, 2)])
        when (app.settings.captionBg) {
            1 -> { m.set("sub-border-style", "outline-and-shadow"); m.set("sub-back-color", "#99000000") }
            2 -> { m.set("sub-border-style", "opaque-box"); m.set("sub-back-color", "#E0000000") }
            else -> { m.set("sub-border-style", "outline-and-shadow"); m.set("sub-back-color", "#00000000") }
        }
    }

    /** Plays [r]; the same address already playing (preview to full screen) is kept without reloading. */
    fun load(r: PlayReq) {
        val m = mpv
        if (r.url != loadedUrl) {
            saveResume()
            req = r
            if (m != null) { if (start(m, r)) loadedUrl = r.url }
            upNextAt = 0; upNextCancelled = false; advancing = false
            startRecovery(r)
        } else req = r
        muted = null
        if (app.full) { showBar(); osd(r.title) }
    }

    /** Plays [r] in [m]; an address that may not be played ([PlayUrl]) is refused with a notice instead. */
    private fun start(m: Mpv, r: PlayReq): Boolean = try { m.play(r.url, r.startSec, trustedLocal = r.recording != null); true }
        catch (e: IllegalArgumentException) { Diag.log("${r.title}: address refused"); app.toast(e.message ?: "Not played"); false }

    fun close() { saveResume(); stopRecovery(); sleepMin = 0; sleepJob?.cancel(); menu = false; info = false; upNextAt = 0; req = null; loadedUrl = null; mpv?.cmd("stop") }

    // ---- live TV that errors or stalls: reconnect, then the channel's alternate sources, then rest (shared LiveRecovery) ----
    private var recovery: LiveRecovery? = null
    private val stall = StallWatch()
    private var recoverJob: Job? = null
    private var noticeJob: Job? = null
    /** Small corner notice ("Reconnecting… (2/3)", "Trying another source: …"); drawn by mpv in full screen and the preview. */
    var notice by mutableStateOf("")
        private set
    /** The alternate source playing instead of the channel (the channel shown stays the one asked for). */
    var altPlaying by mutableStateOf<Channel?>(null)
        private set

    private fun startRecovery(r: PlayReq) {
        stopRecovery()
        val ch = r.live
        if (ch != null && r.archive == null && r.recording == null) recovery = LiveRecovery(ch.key)
        stall.reset(System.currentTimeMillis())
    }

    private fun stopRecovery() { recoverJob?.cancel(); noticeJob?.cancel(); recovery = null; altPlaying = null; setNotice("") }

    private fun setNotice(s: String, clearAfterMs: Long = 0) {
        noticeJob?.cancel()
        notice = s; mpv?.let { redraw(it) }
        if (clearAfterMs > 0) noticeJob = scope.launch { delay(clearAfterMs); notice = ""; mpv?.let { redraw(it) } }
    }

    /** The live source playing now failed (error, end of stream or a stall). */
    private fun liveFailed(why: String) {
        val rec = recovery ?: return
        val r = req ?: return
        val ch = r.live ?: return
        if (recoverJob?.isActive == true) return
        var step = rec.failed()
        if (step === LiveRecovery.Step.NeedAlts) { rec.setAlts(app.alternates(ch)); step = rec.failed() }
        Diag.log("${ch.name}: live $why, ${step.javaClass.simpleName}")
        setNotice(LiveRecovery.notice(step, ch.name))
        val (url, wait) = when (step) {
            is LiveRecovery.Step.Retry -> (rec.alt?.url ?: r.url) to step.delayMs
            is LiveRecovery.Step.Alt -> step.alt.url to step.delayMs
            is LiveRecovery.Step.Rest -> r.url to step.delayMs
            LiveRecovery.Step.NeedAlts -> return
        }
        recoverJob = scope.launch {
            delay(wait)
            if (recovery !== rec || req !== r) return@launch
            if (step is LiveRecovery.Step.Rest) setNotice("Reconnecting… (${ch.name})")
            altPlaying = rec.alt
            mpv?.let { m -> try { m.play(url) } catch (e: IllegalArgumentException) { setNotice(e.message ?: "Not played") } }
            stall.reset(System.currentTimeMillis())
        }
    }

    private fun liveHealthy() {
        val rec = recovery ?: return
        val was = notice.isNotEmpty()
        rec.healthy()
        val alt = rec.alt
        if (alt != null) setNotice("Playing from another source: ${alt.name}", 6000) else if (was) setNotice("", 0)
    }

    private fun pos(): Long? = mpv?.num("time-pos")?.let { (it * 1000).toLong() }
    private fun dur(): Long = mpv?.num("duration")?.let { (it * 1000).toLong() } ?: 0

    fun saveResume(ended: Boolean = false) {
        val r = req ?: return
        val k = r.resumeKey ?: return
        val p = pos() ?: if (ended) 0 else return
        val done = app.watch.setPos(k, p, dur(), r.series?.key, ended)
        if (r.isEpisode) {
            if (done) app.watch.updateSeries(r.series!!, r.episodes, lastId = r.episode!!.id, touch = true)
        }
        lastSave = System.currentTimeMillis()
    }

    private fun onLoaded() {
        val r = req ?: return
        val m = mpv ?: return
        if (!r.isEpisode) return
        // captions and audio remembered for this series
        val row = app.watch.series(r.series!!.key) ?: return
        val tr = m.tracks()
        val subs = tr.filter { it.type == "sub" }; val auds = tr.filter { it.type == "audio" }
        when (val c = Watch.pickText(row.subPref, subs.map { TrackInfo(it.lang, it.title) })) {
            TrackChoice.Off -> m.set("sid", "no")
            is TrackChoice.Pick -> subs.getOrNull(c.index)?.let { m.set("sid", it.id) }
            TrackChoice.Default -> {}
        }
        Watch.pickAudio(row.audioLang, auds.map { TrackInfo(it.lang, it.title) })?.let { i -> auds.getOrNull(i)?.let { m.set("aid", it.id) } }
    }

    private fun onEnd(why: String) {
        val r = req ?: return
        // live: never a dialog; reconnect, then another source (the corner notice says what happens)
        if (recovery != null) return liveFailed(if (why.startsWith("error")) "error" else "ended")
        if (why.startsWith("error")) { osd("Playback failed (${why.removePrefix("error: ")})"); app.toast("Playback failed: ${r.title}"); return }
        if (r.live != null && r.archive == null) return
        saveResume(ended = true)
        val next = r.nextEpisode()
        if (r.isEpisode && next != null && app.settings.autoplayNext && !upNextCancelled) return playNext()
        app.stopPlayback()
    }

    fun playNext() {
        val r = req ?: return
        val n = r.nextEpisode() ?: return
        if (advancing) return
        advancing = true
        saveResume(ended = true)
        app.playEpisode(r.series!!, r.episodes, n)
    }

    fun playPrev() { val r = req ?: return; val p = r.prevEpisode() ?: return osd("First episode"); saveResume(); app.playEpisode(r.series!!, r.episodes, p) }

    private var muted: Boolean? = null

    private fun tick() {
        val r = req ?: return
        val m = mpv ?: return
        val t = System.currentTimeMillis()
        val mute = !app.full && app.settings.previewMuted && !app.docked // the video that left full screen keeps its sound
        if (mute != muted) { muted = mute; m.set("mute", if (mute) "yes" else "no") }
        if (r.resumeKey != null && t - lastSave > 10_000 && app.full) saveResume()
        if (recovery != null && recoverJob?.isActive != true) when (stall.tick(m.num("time-pos"), m.get("pause") == "yes", t)) {
            StallWatch.Event.STALL -> liveFailed("stalled")
            StallWatch.Event.HEALTHY -> liveHealthy()
            StallWatch.Event.NONE -> {}
        }
        // Up next: during the credits, a card and a countdown; then the next episode plays
        if (r.isEpisode && app.full && app.settings.autoplayNext && !upNextCancelled && r.nextEpisode() != null) {
            val p = pos() ?: 0; val d = dur()
            if (Watch.upNextDue(p, d)) { if (upNextAt == 0L) { upNextAt = t; lastInput = t } }
            else upNextAt = 0
            if (upNextAt > 0 && t - upNextAt >= Watch.UP_NEXT_COUNTDOWN_S * 1000L) { upNextAt = 0; playNext(); return }
        }
        if ((barShown || menu) && t - lastInput > 5000) { barShown = false; menu = false }
        redraw(m)
    }

    fun showBar() { barShown = true; lastInput = System.currentTimeMillis(); mpv?.let { redraw(it) } }

    fun osd(s: String) { mpv?.cmd("show-text", s.replace("\n", " "), "2500") }

    private fun recordingNow(): Rec? = req?.live?.let { app.recorder.active(it) }

    private fun toggleRec() {
        val r = req ?: return
        val ch = r.live ?: return osd("Only live channels can be recorded")
        if (r.archive != null) return osd("Catch-up is not recorded")
        val a = app.recorder.active(ch)
        if (a != null) { app.recorder.stop(a); osd("Recording saved: ${a.title}") } else app.recordMenu(ch)
    }

    private fun cycleTrack(type: String, off: Boolean) {
        val m = mpv ?: return
        val t = m.tracks().filter { it.type == type }
        val opts = (if (off) listOf("no") else emptyList()) + t.map { it.id }
        if (opts.isEmpty()) return osd("No ${if (type == "sub") "subtitles" else "audio tracks"}")
        val cur = t.firstOrNull { it.selected }?.id ?: "no"
        val nx = opts[(opts.indexOf(cur) + 1) % opts.size]
        m.set(if (type == "sub") "sid" else "aid", nx)
        // remembered for the series (applied to its later episodes)
        val sk = req?.takeIf { it.isEpisode }?.series?.key ?: return
        val lang = t.firstOrNull { it.id == nx }?.lang
        if (type == "sub") app.watch.rememberSubs(sk, nx != "no", lang) else app.watch.rememberAudio(sk, lang)
    }

    private fun change(i: Int, d: Int) {
        val m = mpv ?: return
        when (items[i]) {
            "Audio" -> cycleTrack("audio", false)
            "Subtitles" -> cycleTrack("sub", true)
            "Video quality" -> {
                // HLS / DASH variants are separate video tracks in mpv; "auto" lets mpv pick the best one
                val v = m.tracks().filter { it.type == "video" }
                if (v.size < 2) return osd("Only one video quality")
                val opts = listOf("auto") + v.sortedByDescending { it.h * 10_000_000L + it.bitrate }.map { it.id }
                val cur = m.get("vid")?.takeIf { it in opts } ?: "auto"
                m.set("vid", opts[(opts.indexOf(cur) + d + opts.size) % opts.size])
            }
            "Audio delay" -> {
                val ms = (app.settings.audioDelayMs + d * 50).coerceIn(-5000, 5000)
                app.update { it.copy(audioDelayMs = ms) }
                m.set("audio-delay", "%.3f".format(java.util.Locale.US, ms / 1000.0))
            }
            "Aspect" -> {
                aspect = (aspect + d + aspects.size) % aspects.size
                m.set("video-aspect-override", aspects[aspect].second)
                m.set("panscan", if (aspects[aspect].first == "Fill") "1.0" else "0.0")
            }
            "Sleep timer" -> {
                val steps = listOf(0, 15, 30, 60, 90, 120)
                sleepMin = steps[(steps.indexOf(sleepMin) + d + steps.size) % steps.size]
                sleepJob?.cancel()
                if (sleepMin > 0) sleepJob = scope.launch { delay(sleepMin * 60_000L); app.stopPlayback() }
            }
            "Record" -> toggleRec()
            "Stream info" -> info = !info
        }
    }

    private fun value(i: Int): String {
        val m = mpv
        fun label(type: String, none: String) = m?.tracks()?.firstOrNull { it.type == type && it.selected }
            ?.let { listOfNotNull(it.lang?.uppercase(), it.title.takeIf { t -> t.isNotBlank() }).joinToString(" ") }?.ifBlank { "#" } ?: none
        return when (items[i]) {
            "Audio" -> label("audio", "—")
            "Subtitles" -> label("sub", "Off")
            "Video quality" -> m?.tracks()?.filter { it.type == "video" }?.let { v ->
                val s = v.firstOrNull { it.selected }
                if (v.size < 2) "Single" else (if (m.get("vid") == "auto") "Auto · " else "") + (s?.let { if (it.h > 0) "${it.h}p" else "#${it.id}" } ?: "Auto") + (s?.bitrate?.takeIf { it > 0 }?.let { " ${it / 1000} kb/s" } ?: "")
            } ?: "Auto"
            "Audio delay" -> "${app.settings.audioDelayMs} ms"
            "Aspect" -> aspects[aspect].first
            "Sleep timer" -> if (sleepMin == 0) "Off" else "$sleepMin min"
            "Record" -> if (recordingNow() != null) "On" else "Off"
            else -> if (info) "On" else "Off"
        }
    }

    private fun infoText(m: Mpv): String {
        fun g(k: String) = m.get(k)
        val w = g("video-params/w"); val h = g("video-params/h")
        val fps = (g("estimated-vf-fps") ?: g("container-fps"))?.toDoubleOrNull()
        val vbr = g("video-bitrate")?.toDoubleOrNull(); val abr = g("audio-bitrate")?.toDoubleOrNull()
        return listOf(
            "Video: ${if (w != null) "${w}×$h" else "—"}${fps?.let { " @ %.2f fps".format(it) } ?: ""}  ${g("video-codec") ?: ""}",
            "Audio: ${g("audio-codec-name") ?: "—"}  ${g("audio-params/channel-count") ?: ""} ch  ${g("audio-params/samplerate") ?: ""} Hz",
            "Bitrate: video ${vbr?.let { "%.1f Mb/s".format(it / 1e6) } ?: "—"}, audio ${abr?.let { "${(it / 1000).toInt()} kb/s" } ?: "—"}",
            "Decoder: ${g("hwdec-current")?.takeIf { it.isNotBlank() && it != "no" } ?: "software"}   Cache: ${g("demuxer-cache-duration")?.toDoubleOrNull()?.toInt() ?: 0} s",
        ).joinToString("\\N")
    }

    private fun bar(len: Int, f: Float): String { val n = (f.coerceIn(0f, 1f) * len).toInt(); return "{\\1c&H33CCFF&}" + "█".repeat(n) + "{\\1c&H555555&}" + "█".repeat(len - n) + "{\\1c&HFFFFFF&}" }

    private fun barText(m: Mpv): String {
        val r = req ?: return ""
        val nowS = System.currentTimeMillis() / 1000
        val lines = ArrayList<String>()
        val rec = if (recordingNow() != null) "   {\\1c&H5F5AFF&}● REC{\\1c&HFFFFFF&}" else ""
        lines += "{\\fs30\\b1}${esc(r.title)}{\\b0}$rec{\\fs22}     ${TimeFmt.time(nowS)}"
        val ch = r.live
        if (ch != null && r.archive == null) {
            val (cur, next) = app.epg.nowNext(ch, nowS)
            if (cur != null) {
                lines += "${TimeFmt.time(cur.start)} – ${TimeFmt.time(cur.stop)}   ${esc(cur.title)}" + (EpisodeNum.line(cur.ep, cur.sub)?.let { "  ·  " + esc(it) } ?: "") + "   ${ProgTime.minutesLeft(cur.stop, nowS)} min left"
                lines += bar(60, ProgTime.progress(cur.start, cur.stop, nowS))
            } else lines += "No guide data"
            altPlaying?.let { lines += "{\\1c&H33CCFF&}Alternate source: ${esc(it.name)}{\\1c&HFFFFFF&}" }
            next?.let { lines += "{\\1c&HAAAAAA&}Next ${TimeFmt.time(it.start)}  ${esc(it.title)}{\\1c&HFFFFFF&}" }
            lines += "{\\fs18\\1c&HAAAAAA&}↑↓ channel · Enter quick settings · I info · R record · Esc back to the guide"
        } else {
            val p = pos() ?: 0; val d = dur()
            lines += "${Watch.clock(p)} / ${if (d > 0) Watch.clock(d) else "--:--"}" + (if (m.get("pause") == "yes") "   ❚❚ paused" else "")
            if (d > 0) lines += bar(60, p.toFloat() / d)
            lines += "{\\fs18\\1c&HAAAAAA&}←→ 10 s · PgUp PgDn 1 min" + (if (r.isEpisode) " · N next / B previous episode" else "") + " · Space pause · Esc back"
        }
        return "{\\an1\\fs22\\bord2\\shad0\\1c&HFFFFFF&\\3c&H000000&\\pos(32,690)}" + lines.joinToString("\\N")
    }

    private fun upNextText(): String? {
        val r = req ?: return null
        if (upNextAt == 0L) return null
        val n = r.nextEpisode() ?: return null
        val left = (Watch.UP_NEXT_COUNTDOWN_S - (System.currentTimeMillis() - upNextAt) / 1000).coerceAtLeast(0)
        return "{\\an3\\fs24\\bord2\\shad0\\1c&HFFFFFF&\\3c&H000000&\\pos(1248,560)}{\\1c&H33CCFF&}Up next{\\1c&HFFFFFF&}\\N{\\fs30\\b1}${Watch.code(n)} · ${esc(VodFmt.cleanTitle(r.series!!.name, n.title))}{\\b0}\\N" +
            "Playing in $left s{\\fs20\\1c&HAAAAAA&}\\NEnter play now · Esc cancel"
    }

    fun redraw(m0: Mpv? = mpv) {
        val m = m0 ?: return
        // the reconnect notice: a small label in the top right corner, also in the TV screen's preview
        m.overlay(5, notice.takeIf { it.isNotEmpty() }?.let { "{\\an9\\fs${if (app.full) 22 else 40}\\bord2\\shad0\\1c&HFFFFFF&\\3c&H000000&\\pos(1260,16)}" + esc(it) })
        if (!app.full) { m.overlay(1, null); m.overlay(2, null); m.overlay(3, null); m.overlay(4, null); return }
        val style = "{\\an7\\fs26\\bord2\\shad0\\1c&HFFFFFF&\\3c&H000000&\\pos(24,24)}"
        m.overlay(1, if (info) style + "{\\b1}${esc(req?.title ?: "")}{\\b0}\\N" + infoText(m) else null)
        m.overlay(2, if (!menu) null else {
            val rows = items.indices.joinToString("\\N") { i ->
                val c = if (i == sel) "{\\1c&H33CCFF&}▶ " else "{\\1c&HFFFFFF&}   "
                c + items[i] + ":  " + esc(value(i))
            }
            "{\\an9\\fs26\\bord2\\shad0\\3c&H000000&\\pos(1256,24)}{\\b1}Quick settings{\\b0}\\N$rows\\N{\\fs20\\1c&HAAAAAA&}↑↓ choose  ←→ change  Esc close"
        })
        m.overlay(3, if (barShown || menu) barText(m) else null)
        m.overlay(4, upNextText())
    }

    private fun esc(s: String) = s.replace("\\", "/").replace("{", "(").replace("}", ")").replace("\n", " ")

    fun mouse(e: MouseEvent) {
        if (!app.full) { if (e.button == MouseEvent.BUTTON1) app.playing?.live?.let { app.playChannel(it) }; return }
        when {
            e.button == MouseEvent.BUTTON3 -> { menu = !menu; sel = 0; showBar() }
            e.clickCount >= 2 -> app.fullscreen = !app.fullscreen
            else -> showBar()
        }
    }

    /** TV-remote style keys while the player fills the window. Returns true when handled. */
    fun key(e: KeyEvent): Boolean {
        val m = mpv
        val r = req
        val live = r?.live != null && r.archive == null
        lastInput = System.currentTimeMillis()
        if (upNextAt > 0) when (e.keyCode) {
            KeyEvent.VK_ENTER -> { upNextAt = 0; playNext(); return true }
            KeyEvent.VK_ESCAPE, KeyEvent.VK_BACK_SPACE -> { upNextAt = 0; upNextCancelled = true; m?.let { redraw(it) }; return true }
        }
        if (live && e.keyCode in KeyEvent.VK_0..KeyEvent.VK_9 || live && e.keyCode in KeyEvent.VK_NUMPAD0..KeyEvent.VK_NUMPAD9) {
            val dgt = if (e.keyCode >= KeyEvent.VK_NUMPAD0) e.keyCode - KeyEvent.VK_NUMPAD0 else e.keyCode - KeyEvent.VK_0
            digits = (digits + dgt).takeLast(4); osd("Channel $digits")
            digitJob?.cancel(); digitJob = scope.launch { delay(1500); val n = digits.toIntOrNull(); digits = ""; if (n != null && !app.zapNumber(n)) osd("No channel $n") }
            return true
        }
        when (e.keyCode) {
            KeyEvent.VK_ESCAPE, KeyEvent.VK_BACK_SPACE -> if (menu) menu = false else if (info) info = false else { saveResume(); app.leavePlayer() }
            KeyEvent.VK_ENTER -> if (menu) change(sel, 1) else { menu = true; sel = 0 }
            KeyEvent.VK_UP -> if (menu) sel = (sel + items.size - 1) % items.size else if (live) app.zap(-1) else showBar()
            KeyEvent.VK_DOWN -> if (menu) sel = (sel + 1) % items.size else if (live) app.zap(1) else showBar()
            KeyEvent.VK_PAGE_UP -> if (live) app.zap(-1) else m?.cmd("seek", "60")
            KeyEvent.VK_PAGE_DOWN -> if (live) app.zap(1) else m?.cmd("seek", "-60")
            KeyEvent.VK_LEFT -> if (menu) change(sel, -1) else m?.cmd("seek", "-10")
            KeyEvent.VK_RIGHT -> if (menu) change(sel, 1) else m?.cmd("seek", "10")
            KeyEvent.VK_SPACE, KeyEvent.VK_P -> m?.cmd("cycle", "pause")
            KeyEvent.VK_I -> info = !info
            KeyEvent.VK_M, KeyEvent.VK_CONTEXT_MENU -> { menu = !menu; sel = 0 }
            KeyEvent.VK_R -> toggleRec()
            KeyEvent.VK_S -> cycleTrack("sub", true)
            KeyEvent.VK_A -> cycleTrack("audio", false)
            KeyEvent.VK_N -> if (r?.isEpisode == true) { if (r.nextEpisode() != null) playNext() else osd("Last episode") }
            KeyEvent.VK_B -> if (r?.isEpisode == true) playPrev()
            KeyEvent.VK_W -> app.dueReminder?.let { app.reminderDone(it, true) }
            KeyEvent.VK_Y -> r?.live?.let { app.toggleList(it.key) }
            KeyEvent.VK_F, KeyEvent.VK_F11 -> app.fullscreen = !app.fullscreen
            KeyEvent.VK_OPEN_BRACKET -> change(items.indexOf("Audio delay"), -1)
            KeyEvent.VK_CLOSE_BRACKET -> change(items.indexOf("Audio delay"), 1)
            else -> return false
        }
        if (app.full) showBar()
        return true
    }
}
