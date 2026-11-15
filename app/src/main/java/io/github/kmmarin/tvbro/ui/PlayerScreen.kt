// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.kmmarin.tvbro.ui

import android.view.KeyEvent as AKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import io.github.kmmarin.tvbro.*

/** Full-screen live TV and catch-up: the shared live player (drawn by LiveVideo underneath) and the shared overlay. */
@Composable
fun PlayerScreen(m: AppModel) {
    val ch = m.current
    if (ch == null) { m.screen = Screen.LIST; return }

    // the shared live player: the video itself is drawn by LiveVideo (under this screen's overlays)
    val exo = rememberLiveExo(m)
    // (volume: LiveVideo, full screen always with sound)
    // A stream that does not play: said in the overlay's banner (kept up meanwhile, see PlayerOverlayHost), with the
    // channel, the reason and the try; after MAX_TRIES it stops: OK tries again, Up/Down another channel, Back the
    // guide. (It said "Buffering..." then "Reconnecting (n)..." on black, forever; TiviMate: reason + "Retrying (1/5)".)
    var status by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var altIdx by remember { mutableIntStateOf(0) }
    val alts = remember(ch.key, m.channels) { m.alternates(ch) }
    val altsNow = rememberUpdatedState(alts)
    LaunchedEffect(ch.key) { altIdx = 0 }
    val attempts = remember { intArrayOf(0) }
    var ready by remember { mutableStateOf(false) }

    DisposableEffect(exo) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                attempts[0]++
                ready = false
                val why = StreamProblem.reason(error)
                if (attempts[0] >= StreamProblem.MAX_TRIES) { failed = true; status = StreamProblem.gaveUp(why); return }
                status = StreamProblem.retrying(why, attempts[0] + 1)
                if (attempts[0] % 3 == 0 && altsNow.value.isNotEmpty()) { altIdx++; status = "$why: trying this channel's other stream..." }
                reload++
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) { attempts[0] = 0; status = ""; failed = false; ready = true }
                // a live stream never ends: the provider closed the connection, connect again (catch-up may end)
                else if (state == Player.STATE_ENDED && m.catchup == null) { attempts[0]++; ready = false; status = StreamProblem.retrying("The provider closed the stream", attempts[0] + 1); reload++ }
            }
        }
        io.github.kmmarin.tvbro.data.Busy.playing = true
        exo.addListener(l)
        exo.setVideoChangeFrameRateStrategy(if (m.matchFps) androidx.media3.common.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS else androidx.media3.common.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        onDispose { io.github.kmmarin.tvbro.data.Busy.playing = false; exo.removeListener(l) }
    }

    val url = m.catchup?.url ?: if (altIdx == 0 || alts.isEmpty()) ch.url else alts[(altIdx - 1) % alts.size].url
    LaunchedEffect(url) { attempts[0] = 0; status = ""; failed = false; ready = false }
    // no picture yet: after a moment the banner says so (a channel off air can keep the connection open for a minute
    // before the stream fails)
    LaunchedEffect(url, reload) {
        // (playing already counts: full screen goes on with the preview's stream, no state change to see)
        fun noPicture() = !ready && exo.playbackState != Player.STATE_READY
        delay(4000)
        if (noPicture() && status.isEmpty()) status = "Connecting to ${ch.name}..."
        delay(8000)
        if (noPicture() && status.startsWith("Connecting")) status = "Still no picture from the provider: the channel may be off air"
        if (!noPicture() && status.startsWith("Connecting")) status = ""
    }
    LaunchedEffect(url, reload, m.liveGen) {
        if (failed) return@LaunchedEffect
        val a = attempts[0]
        if (a > 0) delay(minOf(1000L shl (a - 1).coerceAtMost(4), 15_000L))
        // already playing this channel (opened from the preview): keep going, no re-buffer
        if (a == 0 && m.liveUrl == url && exo.playbackState != Player.STATE_IDLE) { exo.playWhenReady = true; m.liveKey = ch.key; return@LaunchedEffect }
        m.liveUrl = url; m.liveKey = ch.key
        liveLoad(exo, url)
    }
    LaunchedEffect(ch.key) { m.applyDelayFor(ch.key) }
    // watched for 3 s: into the quick-access row's recent channels (zapping past a channel does not count)
    LaunchedEffect(ch.key) { delay(3000); m.noteWatched(ch) }

    var digits by remember { mutableStateOf("") }
    LaunchedEffect(digits) {
        if (digits.isNotEmpty()) {
            delay(1800)
            val n = digits.toIntOrNull()
            if (n != null && !m.zapNumber(n)) m.toast("No channel $n")
            digits = ""
        }
    }

    val kind = if (m.catchup != null) PlayKind.CATCHUP else PlayKind.LIVE
    PlayerOverlayHost(m, exo, m.livePv, kind, contentKey = ch.key to m.catchup, onLeave = { m.leavePlayer() },
        problem = status.ifEmpty { null },
        onRetry = if (failed) ({ failed = false; attempts[0] = 0; status = "Connecting to ${ch.name}..."; reload++ }) else null,
        extraKeys = { e ->
            val code = e.nativeKeyEvent.keyCode
            when {
                e.key == Key.ProgramRed || e.key == Key.R -> { pickRecord(m, ch); true }
                code in AKeyEvent.KEYCODE_0..AKeyEvent.KEYCODE_9 -> { if (digits.length < 4) digits += (code - AKeyEvent.KEYCODE_0); true }
                code in AKeyEvent.KEYCODE_NUMPAD_0..AKeyEvent.KEYCODE_NUMPAD_9 -> { if (digits.length < 4) digits += (code - AKeyEvent.KEYCODE_NUMPAD_0); true }
                else -> false
            }
        },
    ) {
        if (digits.isNotEmpty()) {
            Text(digits, color = Accent, fontSize = 48.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(32.dp).background(Color(0xAA000000)).padding(12.dp))
        }
    }
}
