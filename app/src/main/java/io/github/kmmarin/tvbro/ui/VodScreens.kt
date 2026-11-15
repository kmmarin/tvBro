// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.kmmarin.tvbro.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import io.github.kmmarin.tvbro.*
import io.github.kmmarin.tvbro.data.Net

@Composable
fun VodPlayerScreen(m: AppModel) {
    val vp = m.vodPlay ?: run { m.screen = Screen.LIST; return }
    val ctx = LocalContext.current
    val exo = remember {
        val ms = m.bufferSec * 1000
        ExoPlayer.Builder(ctx, delayFactory(ctx, m.delayProc))
            // http(s) through OkHttp, file:// (downloads, local recordings) and smb:// (recordings on a network share)
            .setMediaSourceFactory(Captions.factory(io.github.kmmarin.tvbro.data.SmbShare.Factory(androidx.media3.datasource.DefaultDataSource.Factory(ctx, OkHttpDataSource.Factory(Net.client))) { m.store.smb() }))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(ms, ms * 2, 1500, 3000)
                .setTargetBufferBytes(48 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build())
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .build()
    }
    // in the background (Home, another app on top) the movie pauses; back in front it goes on
    val life = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(life) {
        var wasPlaying = false
        val o = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { wasPlaying = exo.playWhenReady; exo.playWhenReady = false }
                androidx.lifecycle.Lifecycle.Event.ON_START -> if (wasPlaying) { wasPlaying = false; exo.playWhenReady = true }
                else -> {}
            }
        }
        life.addObserver(o)
        onDispose { life.removeObserver(o) }
    }
    var pv by remember { mutableStateOf<PlayerView?>(null) }
    LaunchedEffect(vp.key) { m.applyDelayFor(vp.key) }
    var status by remember { mutableStateOf("") }
    var posMs by remember { mutableLongStateOf(0L) }
    var durMs by remember { mutableLongStateOf(0L) }
    val resumed = remember { booleanArrayOf(false) }
    // played to 90 % / the end in this session: marked watched, positions no longer saved
    val done = remember { booleanArrayOf(false) }
    val tracksApplied = remember { booleanArrayOf(false, false) }
    val isEpisode = vp.key.startsWith("e|")
    // "Up next" card: seconds left in the countdown (null = hidden); cancelled stays for this episode
    var upNext by remember { mutableStateOf<Int?>(null) }
    var upNextCancelled by remember { mutableStateOf(false) }
    val nextEp = remember(vp.key) { if (isEpisode) m.nextEpisode() else null }

    fun back() { m.screen = if (vp.key.startsWith("r|")) Screen.RECORDINGS else if (m.detail != null) Screen.DETAIL else Screen.LIST }
    fun markDone() { if (!done[0]) { done[0] = true; m.episodeDone(vp.key) } }
    fun leave() {
        val d = exo.duration
        if (d > 0) m.store.setDur(vp.key, d)
        if (io.github.kmmarin.tvbro.data.Watch.countsAsWatched(exo.currentPosition, d)) markDone() else if (!done[0]) m.savePos(vp.key, exo.currentPosition, d.coerceAtLeast(0))
        back()
    }
    fun canAutoplay() = isEpisode && nextEp != null && m.autoplayNext && !upNextCancelled
    DisposableEffect(exo) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { status = "Playback error: ${error.errorCodeName}" }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !resumed[0]) {
                    resumed[0] = true
                    val p = m.store.pos(vp.key)
                    if (p > 5000 && !m.isWatched(vp.key)) exo.seekTo(p)
                }
                if (state == Player.STATE_ENDED) {
                    markDone()
                    if (canAutoplay()) { if (upNext == null) upNext = io.github.kmmarin.tvbro.data.Watch.UP_NEXT_COUNTDOWN_S }
                    else back()
                }
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                // Per-series audio language and captions, applied once per episode as the tracks become known.
                if (!isEpisode) return
                val row = m.playingSeries()?.let { m.watch.seriesRow(it.key) } ?: return
                val groups = tracks.groups
                if (!tracksApplied[0] && groups.any { it.type == C.TRACK_TYPE_AUDIO }) {
                    tracksApplied[0] = true
                    val flat = groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { g -> (0 until g.length).map { g to it } }
                    io.github.kmmarin.tvbro.data.Watch.pickAudio(row.audioLang, flat.map { io.github.kmmarin.tvbro.data.TrackInfo(it.first.getTrackFormat(it.second).language) })?.let { i ->
                        val (g, t) = flat[i]
                        if (!g.isTrackSelected(t)) exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, listOf(t))).build()
                    }
                }
                if (!tracksApplied[1]) {
                    val flat = groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { g -> (0 until g.length).map { g to it } }
                    when (val c = io.github.kmmarin.tvbro.data.Watch.pickText(row.subs, flat.map { io.github.kmmarin.tvbro.data.TrackInfo(it.first.getTrackFormat(it.second).language, it.first.getTrackFormat(it.second).label) })) {
                        is io.github.kmmarin.tvbro.data.TrackChoice.Off -> { tracksApplied[1] = true; exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }
                        is io.github.kmmarin.tvbro.data.TrackChoice.Pick -> {
                            tracksApplied[1] = true
                            val (g, t) = flat[c.index]
                            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, listOf(t))).build()
                        }
                        else -> {} // no preference, or captions not announced yet (CEA-608 can appear later)
                    }
                }
            }
        }
        io.github.kmmarin.tvbro.data.Busy.playing = true
        exo.addListener(l)
        exo.setVideoChangeFrameRateStrategy(if (m.matchFps) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        exo.setMediaItem(MediaItem.fromUri(vp.url)); exo.prepare(); exo.playWhenReady = true
        onDispose {
            // switching episode from the bar, or leaving by any route: keep the position
            if (!done[0] && resumed[0] && exo.currentPosition > 0) m.savePos(vp.key, exo.currentPosition, exo.duration.coerceAtLeast(0))
            io.github.kmmarin.tvbro.data.Busy.playing = false; exo.removeListener(l); exo.release()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000); posMs = exo.currentPosition; durMs = exo.duration.coerceAtLeast(0)
            if (!done[0] && io.github.kmmarin.tvbro.data.Watch.countsAsWatched(posMs, durMs)) markDone()
            if (exo.isPlaying && !done[0] && (posMs / 1000) % 5 == 0L) m.savePos(vp.key, posMs, durMs)
            if (upNext == null && canAutoplay() && exo.isPlaying && io.github.kmmarin.tvbro.data.Watch.upNextDue(posMs, durMs)) upNext = io.github.kmmarin.tvbro.data.Watch.UP_NEXT_COUNTDOWN_S
        }
    }
    // Up-next countdown: plays the next episode at zero.
    LaunchedEffect(upNext) {
        val n = upNext ?: return@LaunchedEffect
        if (n <= 0) { upNext = null; m.playNext(); return@LaunchedEffect }
        delay(1000); if (upNext == n) upNext = n - 1
    }

    fun cancelUpNext() { upNext = null; upNextCancelled = true; if (exo.playbackState == Player.STATE_ENDED) back() }
    val ufr = remember { FocusRequester() }
    val showingUp = upNext != null
    LaunchedEffect(showingUp) { if (showingUp) { delay(50); runCatching { ufr.requestFocus() } } }

    val kind = when { isEpisode -> PlayKind.EPISODE; vp.key.startsWith("r|") -> PlayKind.RECORDING; else -> PlayKind.MOVIE }
    PlayerOverlayHost(m, exo, pv, kind, contentKey = vp.key, onLeave = { leave() }, modifier = Modifier.background(Color.Black), blocked = showingUp) {
        AndroidView(factory = { c -> PlayerView(c).apply { useController = false; player = exo; keepScreenOn = true; pv = this; applyCaptions(this, m) } }, modifier = (if (m.aspectMode >= 3) Modifier.align(Alignment.Center).aspectRatio(if (m.aspectMode == 3) 16f / 9f else 4f / 3f, matchHeightConstraintsFirst = true) else Modifier.fillMaxSize()))
        if (status.isNotEmpty()) Text(status, color = TextMain, fontSize = 18.sp, modifier = Modifier.align(Alignment.TopCenter).padding(24.dp).background(Color(0xAA000000)).padding(10.dp))
        // Up next: the app's pop-up as a card over the picture (after the overlay's own Back: Back cancels it)
        val un = upNext
        if (un != null && nextEp != null) {
            val series = m.playingSeries()
            Popup("upnext", "Up next in $un s", { cancelUpNext() }, width = 420.dp, align = Alignment.BottomEnd, dim = false, restoreFocus = false) {
                Text("${io.github.kmmarin.tvbro.data.Watch.code(nextEp)} - ${io.github.kmmarin.tvbro.data.VodFmt.cleanTitle(series?.name ?: "", nextEp.title)}", color = TextMain, fontSize = 19.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                PopupButtons(ufr, "Play now" to { upNext = null; m.playNext() }, "Cancel" to { cancelUpNext() })
            }
        }
    }
}
