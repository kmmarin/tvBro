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
fun VodScreen(m: AppModel) {
    BackHandler { m.screen = Screen.LIST }
    val cats = listOf(CAT_MYLIST, CAT_RECENT, VOD_ALL) + m.vodCatNames
    val items = m.vodFiltered
    val gridFr = remember { FocusRequester() }
    Row(Modifier.fillMaxSize().background(Bg).padding(24.dp)) {
        Column(Modifier.width(260.dp).fillMaxHeight()) {
            Text(if (m.vodSeries) "Series" else "Movies", color = Accent, fontSize = 24.sp, modifier = Modifier.padding(bottom = 12.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(cats) { g ->
                    val sel = g == m.vodCat
                    Text((if (sel) "> " else "  ") + vodCatLabel(g, m.vodSeries) + if (g in m.prefs.locked) " (locked)" else "", color = if (sel) Accent else TextMain, fontSize = 17.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().focusProperties { right = if (items.isNotEmpty()) gridFr else FocusRequester.Default }.tvFocus(onLongClick = { m.vodCatMenu(g) }) { m.selectVodCat(g) }.padding(10.dp))
                }
            }
            TvButton("Back to channels", Modifier.fillMaxWidth()) { m.screen = Screen.LIST }
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            TvField("Search", m.vodQuery, { m.vodQuery = it })
            if (m.vodMsg.isNotEmpty()) Text(m.vodMsg, color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))
            LazyVerticalGrid(GridCells.Adaptive(140.dp), Modifier.weight(1f).padding(top = 8.dp)) {
                itemsIndexed(items) { idx, it ->
                    Column((if (idx == 0) Modifier.focusRequester(gridFr) else Modifier).padding(4.dp).tvFocus(onLongClick = { m.toggleFavKey(it) }) { m.openDetail(it) }.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Logo(it.poster, it.name, 120.dp)
                        Text(it.name, color = TextMain, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (m.isWatched(it.key)) Text("watched", color = TextDim, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun DetailScreen(m: AppModel) {
    BackHandler { m.screen = Screen.LIST }
    val item = m.detail ?: run { m.screen = Screen.LIST; return }
    val info = m.detailInfo
    val ctx = LocalContext.current
    var season by remember { mutableIntStateOf(-1) }
    var tick by remember { mutableIntStateOf(0) }
    Row(Modifier.fillMaxSize().background(Bg).padding(32.dp)) {
        Logo(item.poster, item.name, 200.dp)
        Spacer(Modifier.width(24.dp))
        LazyColumn(Modifier.weight(1f)) {
            item { Text(item.name, color = TextMain, fontSize = 28.sp) }
            item {
                val meta = listOfNotNull(info?.year, info?.rating?.let { "Rating $it" } ?: item.rating?.takeIf { it.isNotBlank() }?.let { "Rating $it" },
                    info?.duration, info?.genre).joinToString("  |  ")
                if (meta.isNotEmpty()) Text(meta, color = TextDim, fontSize = 15.sp, modifier = Modifier.padding(vertical = 6.dp))
            }
            if (m.detailMsg.isNotEmpty()) item { Text(m.detailMsg, color = TextDim, fontSize = 14.sp) }
            info?.plot?.let { p -> item { Text(p, color = TextMain.copy(alpha = 0.9f), fontSize = 15.sp, maxLines = 8, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 6.dp)) } }
            info?.cast?.let { c -> item { Text("Cast: $c", color = TextDim, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) } }
            if (item.kind == "movie") {
                item {
                    val pos = remember(tick) { m.store.pos(item.key) }
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton(if (pos > 5000) "Resume ${pos / 60000} min" else "Play") { m.playItem(item) }
                        TvButton(if (m.isWatched(item.key)) "Mark unwatched" else "Mark watched") { m.markWatched(item.key, !m.isWatched(item.key)); tick++ }
                        info?.trailer?.let { t ->
                            TvButton("Trailer") {
                                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(t)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                catch (e: Exception) { m.toast("No app to open the trailer") }
                            }
                        }
                    }
                }
            } else if (info != null) {
                val seasons = info.episodes.map { it.season }.distinct()
                val cur = if (season in seasons) season else seasons.firstOrNull() ?: 0
                item {
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (s in seasons) TvButton((if (s == cur) "> " else "") + "Season $s") { season = s }
                        info.trailer?.let { t ->
                            TvButton("Trailer") {
                                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(t)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                catch (e: Exception) { m.toast("No app to open the trailer") }
                            }
                        }
                    }
                }
                items(info.episodes.filter { it.season == cur }) { e ->
                    val k = "e|${item.sourceId}|${e.id}"
                    val pos = remember(tick) { m.store.pos(k) }
                    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp).tvFocus(onLongClick = { m.markWatched(k, !m.isWatched(k)); tick++ }) { m.playEpisode(item, e) }.padding(10.dp)) {
                        Text("E${e.num}  ${e.title}" + (if (m.isWatched(k)) "  (watched)" else if (pos > 5000) "  (resume ${pos / 60000} min)" else ""), color = TextMain, fontSize = 16.sp)
                        e.plot?.let { Text(it, color = TextDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
                if (info.episodes.isEmpty()) item { Text("No episodes listed.", color = TextDim) }
            }
        }
    }
}

private class TrackRow(val label: String, val action: () -> Unit)

@Composable
fun VodPlayerScreen(m: AppModel) {
    val vp = m.vodPlay ?: run { m.screen = Screen.LIST; return }
    val ctx = LocalContext.current
    val exo = remember {
        val ms = m.bufferSec * 1000
        ExoPlayer.Builder(ctx, delayFactory(ctx, m.delayProc))
            // http(s) through OkHttp, file:// (downloads, local recordings) and smb:// (recordings on a network share)
            .setMediaSourceFactory(DefaultMediaSourceFactory(io.github.kmmarin.tvbro.data.SmbShare.Factory(androidx.media3.datasource.DefaultDataSource.Factory(ctx, OkHttpDataSource.Factory(Net.client))) { m.store.smb() }))
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
    var overlay by remember { mutableStateOf(true) }
    var tech by remember { mutableStateOf("") }
    var pv by remember { mutableStateOf<PlayerView?>(null) }
    var bar by remember { mutableStateOf(false) }
    LaunchedEffect(vp.key) { m.applyDelayFor(vp.key) }
    LaunchedEffect(overlay) { while (overlay) { tech = techInfo(exo); delay(1000) } }
    var tick by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf<List<TrackRow>?>(null) }
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
    LaunchedEffect(tick) { overlay = true; delay(5000); overlay = false }
    // Up-next countdown: plays the next episode at zero.
    LaunchedEffect(upNext) {
        val n = upNext ?: return@LaunchedEffect
        if (n <= 0) { upNext = null; m.playNext(); return@LaunchedEffect }
        delay(1000); if (upNext == n) upNext = n - 1
    }

    fun cancelUpNext() { upNext = null; upNextCancelled = true; if (exo.playbackState == Player.STATE_ENDED) back() }
    BackHandler { if (upNext != null) cancelUpNext() else if (bar) bar = false else if (menu != null) menu = null else leave() }
    val fr = remember { FocusRequester() }
    val mfr = remember { FocusRequester() }
    val ufr = remember { FocusRequester() }
    val showingUp = upNext != null
    LaunchedEffect(menu, bar, showingUp) {
        if (showingUp) { delay(50); runCatching { ufr.requestFocus() } }
        else if (menu != null) { delay(100); runCatching { mfr.requestFocus() } } else runCatching { fr.requestFocus() }
    }

    fun openMenu() {
        val rows = ArrayList<TrackRow>()
        for (g in exo.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_AUDIO && g.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                val kind = if (g.type == C.TRACK_TYPE_AUDIO) "Audio" else "Subtitles"
                val name = listOfNotNull(f.label, f.language).distinct().joinToString(" ").ifBlank { "track ${i + 1}" }
                rows += TrackRow((if (g.isTrackSelected(i)) "* " else "  ") + "$kind: $name") {
                    var b = exo.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, listOf(i)))
                    if (g.type == C.TRACK_TYPE_TEXT) b = b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    exo.trackSelectionParameters = b.build(); menu = null
                    m.rememberTrack(g.type == C.TRACK_TYPE_AUDIO, f.language, true)
                }
            }
        }
        rows += TrackRow("  Subtitles: off") {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build(); menu = null
            m.rememberTrack(false, null, false)
        }
        menu = rows
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(fr).countFocus().focusable().onPreviewKeyEvent { e ->
            if (menu != null || bar || upNext != null || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionLeft -> { exo.seekTo((exo.currentPosition - 10_000).coerceAtLeast(0)); tick++; true }
                Key.DirectionRight -> { exo.seekTo(exo.currentPosition + 10_000); tick++; true }
                Key.DirectionUp, Key.Menu -> { openMenu(); true }
                Key.DirectionDown -> { bar = true; true }
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.ButtonA, Key.MediaPlayPause -> { exo.playWhenReady = !exo.playWhenReady; tick++; true }
                else -> false
            }
        },
    ) {
        AndroidView(factory = { c -> PlayerView(c).apply { useController = false; player = exo; keepScreenOn = true; pv = this; applyCaptions(this, m) } }, modifier = (if (m.aspectMode >= 3) Modifier.align(Alignment.Center).aspectRatio(if (m.aspectMode == 3) 16f / 9f else 4f / 3f, matchHeightConstraintsFirst = true) else Modifier.fillMaxSize()))
        if (bar) QuickBar(m, exo, pv, false) { bar = false }
        if (status.isNotEmpty()) Text(status, color = TextMain, fontSize = 18.sp, modifier = Modifier.align(Alignment.TopEnd).padding(24.dp).background(Color(0xAA000000)).padding(10.dp))
        if (overlay) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xDD0E1116)).padding(24.dp)) {
                Text(vp.title, color = TextMain, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(6.dp).background(Surface)) {
                    Box(Modifier.fillMaxWidth(if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f).height(6.dp).background(Accent))
                }
                if (tech.isNotEmpty()) Text(tech, color = Accent, fontSize = 14.sp)
                Text("${posMs / 60000}:%02d / ${durMs / 60000}:%02d    Left/Right seek, OK pause, Up audio and subtitles".format(posMs / 1000 % 60, durMs / 1000 % 60), color = TextDim, fontSize = 14.sp)
            }
        }
        val un = upNext
        if (un != null && nextEp != null) {
            val series = m.playingSeries()
            Column(Modifier.align(Alignment.BottomEnd).padding(32.dp).width(420.dp).background(Color(0xEE0E1116)).padding(16.dp)) {
                Text("Up next in $un s", color = Accent, fontSize = 15.sp)
                Text("${io.github.kmmarin.tvbro.data.Watch.code(nextEp)} - ${io.github.kmmarin.tvbro.data.VodFmt.cleanTitle(series?.name ?: "", nextEp.title)}", color = TextMain, fontSize = 19.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton("Play now", Modifier.focusRequester(ufr)) { upNext = null; m.playNext() }
                    TvButton("Cancel") { cancelUpNext() }
                }
            }
        }
        menu?.let { rows ->
            LazyColumn(Modifier.align(Alignment.Center).width(480.dp).background(Color(0xEE0E1116)).padding(16.dp)) {
                items(rows) { r ->
                    val first = r === rows.first()
                    Text(r.label, color = TextMain, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().then(if (first) Modifier.focusRequester(mfr) else Modifier).tvFocus { r.action() }.padding(10.dp))
                }
            }
        }
    }
}
