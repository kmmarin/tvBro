// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.kmmarin.tvbro.ui

import android.view.KeyEvent as AKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import io.github.kmmarin.tvbro.*
import io.github.kmmarin.tvbro.data.Net

@Composable
fun PlayerScreen(m: AppModel) {
    val ch = m.current
    if (ch == null) { m.screen = Screen.LIST; return }
    val ctx = LocalContext.current
    val now = rememberNow()

    // the shared live player: the video itself is drawn by LiveVideo (under this screen's overlays)
    val exo = rememberLiveExo(m)
    // (volume: LiveVideo, full screen always with sound)
    var status by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var altIdx by remember { mutableIntStateOf(0) }
    val alts = remember(ch.key, m.channels) { m.alternates(ch) }
    val altsNow = rememberUpdatedState(alts)
    LaunchedEffect(ch.key) { altIdx = 0 }
    val attempts = remember { intArrayOf(0) }

    DisposableEffect(exo) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                attempts[0]++
                status = "Reconnecting (${attempts[0]})..."
                if (attempts[0] % 3 == 0 && altsNow.value.isNotEmpty()) { altIdx++; status = "Trying alternate stream..." }
                reload++
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) { attempts[0] = 0; status = "" }
                else if (state == Player.STATE_BUFFERING && status.isEmpty()) status = "Buffering..."
                // a live stream never ends: the provider closed the connection, connect again (catch-up may end)
                else if (state == Player.STATE_ENDED && m.catchup == null) { attempts[0]++; status = "Reconnecting (${attempts[0]})..."; reload++ }
            }
        }
        io.github.kmmarin.tvbro.data.Busy.playing = true
        exo.addListener(l)
        exo.setVideoChangeFrameRateStrategy(if (m.matchFps) androidx.media3.common.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS else androidx.media3.common.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        onDispose { io.github.kmmarin.tvbro.data.Busy.playing = false; exo.removeListener(l) }
    }

    val url = m.catchup?.url ?: if (altIdx == 0 || alts.isEmpty()) ch.url else alts[(altIdx - 1) % alts.size].url
    LaunchedEffect(url) { attempts[0] = 0; status = "" }
    LaunchedEffect(url, reload, m.liveGen) {
        val a = attempts[0]
        if (a > 0) delay(minOf(1000L shl (a - 1).coerceAtMost(4), 15_000L))
        // already playing this channel (opened from the preview): keep going, no re-buffer
        if (a == 0 && m.liveUrl == url && exo.playbackState != Player.STATE_IDLE) { exo.playWhenReady = true; m.liveKey = ch.key; return@LaunchedEffect }
        m.liveUrl = url; m.liveKey = ch.key
        liveLoad(exo, url)
    }

    var overlay by remember { mutableStateOf(true) }
    var tech by remember { mutableStateOf("") }
    var bar by remember { mutableStateOf(false) }
    LaunchedEffect(ch.key) { m.applyDelayFor(ch.key) }
    LaunchedEffect(overlay) { while (overlay) { tech = techInfo(exo); delay(1000) } }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(ch.key, tick, m.catchup) { overlay = true; delay(5000); overlay = false }

    var digits by remember { mutableStateOf("") }
    LaunchedEffect(digits) {
        if (digits.isNotEmpty()) {
            delay(1800)
            val n = digits.toIntOrNull()
            if (n != null && !m.zapNumber(n)) m.toast("No channel $n")
            digits = ""
        }
    }

    var recMenu by remember { mutableStateOf<List<Pair<String, () -> Unit>>?>(null) }
    BackHandler { if (bar) bar = false else if (recMenu != null) recMenu = null else m.leavePlayer() }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    LaunchedEffect(recMenu, bar) { if (recMenu == null && !bar) runCatching { fr.requestFocus() } }

    // Touch screens (no remote, maybe no Back button): a tap shows / hides the player bar, a swipe down leaves full
    // screen into the preview like Back (same animation, picture and sound go on). Remote keys are not affected.
    val swipeOut = with(androidx.compose.ui.platform.LocalDensity.current) { 120.dp.toPx() }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { if (recMenu == null) bar = !bar }) }
            .pointerInput(Unit) {
                var dy = 0f
                detectVerticalDragGestures(onDragStart = { dy = 0f }, onDragEnd = { if (dy > swipeOut && recMenu == null) { bar = false; m.leavePlayer() } }) { _, d -> dy += d }
            }
            .focusRequester(fr).countFocus().focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = e.nativeKeyEvent.keyCode
                if (recMenu != null || bar) return@onPreviewKeyEvent false
                when {
                    e.key == Key.ProgramRed || e.key == Key.R -> {
                        val a = m.activeRec(ch)
                        recMenu = if (a != null) listOf("Stop recording" to { m.stopRec(a); recMenu = null })
                        else {
                            val cur = m.epg.nowNext(ch, now).first
                            listOfNotNull(
                                "Record until stopped" to { m.recordNow(ch, 0); recMenu = null },
                                "Record 30 minutes" to { m.recordNow(ch, 30); recMenu = null },
                                "Record 1 hour" to { m.recordNow(ch, 60); recMenu = null },
                                "Record 2 hours" to { m.recordNow(ch, 120); recMenu = null },
                                cur?.let { "Record until end of programme" to { m.recordNow(ch, 0, it.stop * 1000 + 60_000); recMenu = null } },
                            )
                        }
                        true
                    }
                    code in AKeyEvent.KEYCODE_0..AKeyEvent.KEYCODE_9 -> { if (digits.length < 4) digits += (code - AKeyEvent.KEYCODE_0); true }
                    code in AKeyEvent.KEYCODE_NUMPAD_0..AKeyEvent.KEYCODE_NUMPAD_9 -> { if (digits.length < 4) digits += (code - AKeyEvent.KEYCODE_NUMPAD_0); true }
                    e.key == Key.DirectionUp || e.key == Key.ChannelUp -> { m.zap(1); true }
                    e.key == Key.ChannelDown -> { m.zap(-1); true }
                    e.key == Key.DirectionDown || e.key == Key.Menu -> { bar = true; true }
                    e.key == Key.DirectionLeft -> { m.leavePlayer(); true }
                    e.key == Key.DirectionRight -> { m.screen = Screen.GUIDE; true }
                    e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.ButtonA -> {
                        if (overlay) overlay = false else tick++
                        true
                    }
                    e.key == Key.MediaPlayPause -> { exo.playWhenReady = !exo.playWhenReady; true }
                    else -> false
                }
            },
    ) {
        if (bar) QuickBar(m, exo, m.livePv, true) { bar = false }
        if (status.isNotEmpty()) {
            Text(status, color = TextMain, fontSize = 18.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(24.dp).background(Color(0xAA000000)).padding(10.dp))
        }
        if (digits.isNotEmpty()) {
            Text(digits, color = Accent, fontSize = 48.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(32.dp).background(Color(0xAA000000)).padding(12.dp))
        }
        recMenu?.let { rows ->
            val mfr = remember { FocusRequester() }
            LaunchedEffect(Unit) { delay(100); runCatching { mfr.requestFocus() } }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.align(Alignment.Center).width(420.dp).background(Color(0xEE0E1116)).padding(16.dp)) {
                itemsIndexed(rows) { i, r ->
                    Text(r.first, color = TextMain, fontSize = 18.sp,
                        modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(mfr) else Modifier).tvFocus { r.second() }.padding(10.dp))
                }
            }
        }
        if (overlay) {
            val (cur, next) = m.epg.nowNext(ch, now)
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xDD0E1116)).padding(24.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Logo(ch.logo, ch.name, 56.dp)
                    Spacer(Modifier.width(16.dp))
                    Text("${ch.number}  ${ch.name}", color = TextMain, fontSize = 26.sp)
                    m.catchup?.let { Text("   Catch-up: ${it.title}", color = Accent, fontSize = 18.sp) }
                }
                if (m.catchup == null) {
                    Spacer(Modifier.height(10.dp))
                    if (cur != null) {
                        Text("Now  ${hhmm(cur.start)}-${hhmm(cur.stop)}  ${cur.title}", color = TextMain, fontSize = 18.sp)
                        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(6.dp).background(Surface)) {
                            val f = ((now - cur.start).toFloat() / (cur.stop - cur.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                            Box(Modifier.fillMaxWidth(f).height(6.dp).background(Accent))
                        }
                    } else Text("No guide information", color = TextDim, fontSize = 16.sp)
                    Text(if (m.activeRec(ch) != null) "REC  (red: stop)" else "Red button: record", color = TextDim, fontSize = 13.sp)
                    if (tech.isNotEmpty()) Text(tech, color = Accent, fontSize = 14.sp)
                    if (next != null) Text("Next ${hhmm(next.start)}-${hhmm(next.stop)}  ${next.title}", color = TextDim, fontSize = 16.sp)
                }
            }
        }
    }
}
