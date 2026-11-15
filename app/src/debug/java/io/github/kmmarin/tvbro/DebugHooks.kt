// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import android.util.Log
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Test hooks, compiled into DEBUG builds only (the release source set has a no-op copy). Driven with system properties
 * from adb, so no saved setting is ever changed:
 *  - `adb shell setprop debug.tvbro.noautoplay 1`: the next start resumes nothing (no channel, no full screen), so the
 *    UI can be inspected with uiautomator (it cannot dump while video plays on some devices);
 *  - `adb shell setprop debug.tvbro.stop <any new value>`: stops live playback now (full screen closes, preview off);
 *  - `adb shell setprop debug.tvbro.core rust`: the Rust core for every switched call site from the next start
 *    (see [io.github.kmmarin.tvbro.data.CoreSwitch]); `shadow` (every row, or `m3u=shadow,search=shadow`) keeps Kotlin in
 *    charge and compares the core afterwards, summary in `files/core-shadow.log`;
 *  - `adb shell setprop debug.tvbro.epgbench kotlin|core`: one guide store benchmark run at the next start ([EpgBench]);
 *  - `adb shell setprop debug.tvbro.scroll centre|edge`: the list scrolling mode from the next start (not saved);
 *  - logcat tag `tvbro-dbg`: screen / section / playing channel / player state on every change, and every new media
 *    item (a re-buffer) of the live player. Stream addresses are never logged.
 */
object DebugHooks {
    private const val TAG = "tvbro-dbg"

    private fun prop(k: String): String = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, k) as? String ?: ""
    } catch (e: Throwable) { "" }

    fun noAutoplay(): Boolean = prop("debug.tvbro.noautoplay") == "1"

    /**
     * `setprop debug.tvbro.startprof 1`: samples the main thread's stack every 50 ms for the first 5 s after start and
     * logs (tag tvbro-prof) the app frames seen most, to find what holds up the first frame.
     */
    fun startProfile() {
        if (prop("debug.tvbro.startprof") != "1") return
        val mainT = android.os.Looper.getMainLooper().thread
        Thread({
            val counts = HashMap<String, Int>()
            val t0 = android.os.SystemClock.uptimeMillis()
            var n = 0
            while (android.os.SystemClock.uptimeMillis() - t0 < 5000) {
                val st = mainT.stackTrace
                val idle = st.firstOrNull()?.let { it.methodName == "nativePollOnce" } == true
                if (!idle) {
                    n++
                    val frames = st.filter { it.className.startsWith("io.github.kmmarin.tvbro") }.take(3).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                    val top = st.take(2).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
                    val k = "$top || $frames"
                    counts[k] = (counts[k] ?: 0) + 1
                    Log.i("tvbro-prof", "+${android.os.SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis()} $k")
                }
                Thread.sleep(50)
            }
            for ((k, v) in counts.entries.sortedByDescending { it.value }.take(25)) Log.i("tvbro-prof", "TOP $v x50ms $k")
        }, "tvbro-prof").start()
    }

    /** `debug.tvbro.core`: Rust core rollout override for the next start ("rust", "kotlin" or "m3u=rust,pin=kotlin"). */
    fun coreProp(): String = prop("debug.tvbro.core")

    // ---- Speed log (logcat tag `tvbro-perf`): key press to the end of the next drawn frame, and when each screen's
    // content appears (ms after the last key). Measures what the user sees, including keys queued behind slow work.
    private const val PERF = "tvbro-perf"
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastKeyAt = 0L
    private var lastKey = ""

    /** Runs [f] after the next frame has been laid out and drawn on the main thread. */
    private fun afterFrame(f: () -> Unit) { android.view.Choreographer.getInstance().postFrameCallback { main.post(f) } }

    /** Called first in MainActivity.dispatchKeyEvent. */
    fun key(e: android.view.KeyEvent) {
        if (e.action != android.view.KeyEvent.ACTION_DOWN) return
        val t0 = e.eventTime
        val name = android.view.KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")
        lastKeyAt = t0; lastKey = name
        afterFrame { Log.i(PERF, "key $name lat=${android.os.SystemClock.uptimeMillis() - t0}") }
    }

    /** What the current screen shows (counts only, no titles or queries). */
    private fun content(m: AppModel): String = when {
        m.screen == Screen.DETAIL -> "detail info=${m.detailInfo != null} eps=${m.detailInfo?.episodes?.size ?: 0} msg=${m.detailMsg.isNotEmpty()}"
        m.screen == Screen.SETTINGS -> "settings page=${m.settingsSec}"
        m.screen != Screen.LIST -> "screen ${m.screen}"
        m.section == Section.MOVIES || m.section == Section.SHOWS ->
            "browse ${m.section} cats=${m.vodCatNames.size} items=${m.vodFiltered.size} msg=${m.vodMsg.isNotEmpty()} peek=${m.peek != null}"
        m.section == Section.SEARCH -> "search q=${m.searchQ.length} results=${m.searchView?.let { if (it.q == m.searchQ) it.movies.size + it.series.size + it.channels.size + it.progs.size else -1 } ?: -1}"
        m.section == Section.MYLIST -> "mylist my=${m.mySnaps.size} hist=${m.histSnaps.size}"
        else -> "section ${m.section}"
    }

    /**
     * `adb shell setprop debug.tvbro.walk "<keys> #<id>"`: plays D-pad keys into tvBro's own window (testenv/perfwalk.py),
     * much faster than one `adb shell input` per key. Tokens: U D L R C(OK) B(Back) with a count (D5), g<ms> the pause
     * after each key (default 500), W<ms> a wait, T<text> typed. Logs "WALK done <id>" (tvbro-perf) at the end.
     * Injection only reaches this app's windows: with another app in front it stops (SecurityException).
     */
    private fun walk(script: String) {
        val id = script.substringAfterLast('#', "").trim()
        Thread({
            val ins = android.app.Instrumentation()
            var gap = 500L
            fun key(code: Int) {
                val t = android.os.SystemClock.uptimeMillis()
                ins.sendKeySync(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, code, 0))
                ins.sendKeySync(android.view.KeyEvent(t, android.os.SystemClock.uptimeMillis(), android.view.KeyEvent.ACTION_UP, code, 0))
            }
            try {
                for (tok in script.substringBeforeLast('#').trim().split(' ').filter { it.isNotEmpty() }) {
                    val n = tok.drop(1)
                    when (tok[0]) {
                        'g' -> gap = n.toLong()
                        'W' -> Thread.sleep(n.toLong())
                        'T' -> for (ch in n) { ins.sendStringSync(ch.toString()); Thread.sleep(250) }
                        else -> {
                            val code = when (tok[0]) {
                                'U' -> android.view.KeyEvent.KEYCODE_DPAD_UP; 'D' -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
                                'L' -> android.view.KeyEvent.KEYCODE_DPAD_LEFT; 'R' -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                                'C' -> android.view.KeyEvent.KEYCODE_DPAD_CENTER; 'B' -> android.view.KeyEvent.KEYCODE_BACK
                                else -> throw IllegalArgumentException("unknown token $tok")
                            }
                            repeat(n.toIntOrNull() ?: 1) { key(code); Thread.sleep(gap) }
                        }
                    }
                }
                Log.i(PERF, "WALK done $id")
            } catch (e: Exception) { Log.i(PERF, "WALK failed $id: ${e.javaClass.simpleName}") }
        }, "tvbro-walk").start()
    }

    fun attach(m: AppModel) {
        Log.i(TAG, "debug hooks on, noautoplay=${noAutoplay()}")
        io.github.kmmarin.tvbro.ui.Lat.noPrefetch = prop("debug.tvbro.noprefetch") == "1"
        // `setprop debug.tvbro.scroll centre|edge`: the scrolling mode for this run (the saved setting stays)
        when (prop("debug.tvbro.scroll")) { "centre" -> io.github.kmmarin.tvbro.ui.FocusScroll.centred = true; "edge" -> io.github.kmmarin.tvbro.ui.FocusScroll.centred = false }
        m.viewModelScope.launch {
            var seen = prop("debug.tvbro.walk")
            while (true) { delay(150); val w = prop("debug.tvbro.walk"); if (w != seen) { seen = w; if (w.isNotEmpty()) walk(w) } }
        }
        m.viewModelScope.launch {
            androidx.compose.runtime.snapshotFlow { content(m) }.collect { s ->
                afterFrame { Log.i(PERF, "content $s +${android.os.SystemClock.uptimeMillis() - lastKeyAt} after $lastKey") }
            }
        }
        // `adb shell setprop debug.tvbro.show <prog|chmenu|prompt|reminder|update|none>`: opens that pop-up at once, with
        // the first channel that has a programme ahead (screenshots of every pop-up without navigating to it); test data
        // only, nothing is saved (the prompt's OK does nothing, the reminder is not stored)
        m.viewModelScope.launch {
            var seen = prop("debug.tvbro.show")
            while (true) {
                delay(200)
                val s = prop("debug.tvbro.show")
                if (s == seen) continue
                seen = s
                val now = System.currentTimeMillis() / 1000
                val ch = m.shown.firstOrNull { m.epg.nowNext(it, now).second != null } ?: m.shown.firstOrNull()
                Log.i(TAG, "show $s")
                when (s) {
                    "prog" -> ch?.let { c -> m.epg.nowNext(c, now).second?.let { p -> m.progMenu = c to p } }
                    "chmenu" -> ch?.let { m.openChannelMenu(it) }
                    "prompt" -> m.prompt = io.github.kmmarin.tvbro.ui.PromptState("Channel name", ch?.name ?: "Example", false) { }
                    "reminder" -> ch?.let { c -> m.dueReminder = io.github.kmmarin.tvbro.data.Reminder("debug", c.key, c.name, m.epg.nowNext(c, now).second?.title ?: "Example programme", System.currentTimeMillis()) }
                    "update" -> io.github.kmmarin.tvbro.update.Updater.debugOffer(io.github.kmmarin.tvbro.update.ReleaseInfo("9.9.9", "v9.9.9", "tvbro.apk", "https://example.invalid/tvbro.apk", 0, null,
                        "Example release notes for a screenshot.\n- One player overlay\n- Faster rows", "https://example.invalid"))
                    "none" -> { m.progMenu = null; m.menu = null; m.prompt = null; m.dueReminder = null; io.github.kmmarin.tvbro.update.Updater.later() }
                }
            }
        }
        prop("debug.tvbro.epgbench").takeIf { it == "kotlin" || it == "core" }?.let { which ->
            val app = m.getApplication<android.app.Application>()
            Thread({ EpgBench.run(app, which) }, "tvbro-bench").start()
        }
        m.viewModelScope.launch {
            var stopSeen = prop("debug.tvbro.stop")
            var last = ""
            var watched: Player? = null
            while (true) {
                delay(400)
                val s = prop("debug.tvbro.stop")
                if (s != stopSeen) { stopSeen = s; if (s.isNotEmpty()) stop(m) }
                val exo = m.liveExo
                if (exo != null && exo !== watched) { watched = exo; exo.addListener(listener) }
                val st = "screen=${m.screen} section=${m.section} current=${m.current?.name} focus=${m.focusCh?.name} " +
                    "player=${exo?.let { "state=${it.playbackState} playing=${it.isPlaying} vol=${it.volume}" }} loaded=${m.liveKey != null} fg=${m.foreground} liveplayers=${io.github.kmmarin.tvbro.ui.LivePlayer.built} models=${io.github.kmmarin.tvbro.ui.LivePlayer.users} conns=${io.github.kmmarin.tvbro.ui.LiveConns.open}"
                if (st != last) { last = st; Log.i(TAG, st) }
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { Log.i(TAG, "player: new media item (reason $reason)") }
        override fun onPlaybackStateChanged(state: Int) { Log.i(TAG, "player: state $state") }
        override fun onIsPlayingChanged(isPlaying: Boolean) { Log.i(TAG, "player: playing=$isPlaying") }
        // text tracks of what plays (captions check): mime, caption channel, language, selected
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            val l = tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }.flatMap { g ->
                (0 until g.length).map { i -> g.getTrackFormat(i).let { f -> "${f.sampleMimeType}/cc${f.accessibilityChannel}/${f.language}${if (g.isTrackSelected(i)) "*" else ""}" } }
            }
            Log.i(TAG, "player: text tracks ${l.size}: ${l.joinToString()}")
        }
    }

    private fun stop(m: AppModel) {
        Log.i(TAG, "stop requested")
        if (m.screen == Screen.PLAYER) m.leavePlayer()
        else if (m.screen == Screen.VODPLAY) m.screen = Screen.LIST
        m.current = null
        m.liveExo?.let { it.stop(); it.clearMediaItems() }
        m.liveUrl = null; m.liveKey = null
    }
}
