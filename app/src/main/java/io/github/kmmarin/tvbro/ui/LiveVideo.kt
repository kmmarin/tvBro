// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.kmmarin.tvbro.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import io.github.kmmarin.tvbro.*
import io.github.kmmarin.tvbro.data.Net
import kotlin.math.roundToInt

/**
 * The one live player of the whole process. A second activity instance (split screen / multi-window on some devices
 * starts one) has its own model; with a player per model both played a stream at once (user 2026-11-12: two streams,
 * over the provider's connection limit). Every model uses this one; the last model to go releases it.
 */
object LivePlayer {
    var exo: ExoPlayer? = null
    /** Models alive (each AppModel counts itself in and out). */
    var users = 0
    /** Players built so far in this process (the debug log shows it: more than one live stream must never exist). */
    var built = 0
}

/** Live connections open right now (the live player's data sources between open and close). */
object LiveConns {
    @Volatile var open = 0
        private set
    fun inc() = synchronized(this) { open++ }
    fun dec() = synchronized(this) { open-- }
}

/** Counts the live player's open connections (LiveConns). */
private class CountingFactory(private val f: androidx.media3.datasource.DataSource.Factory) : androidx.media3.datasource.DataSource.Factory {
    override fun createDataSource(): androidx.media3.datasource.DataSource = Counting(f.createDataSource())
}

private class Counting(private val d: androidx.media3.datasource.DataSource) : androidx.media3.datasource.DataSource by d {
    private var counted = false
    override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
        if (!counted) { counted = true; LiveConns.inc() }
        return d.open(dataSpec)
    }
    override fun close() {
        try { d.close() } finally { if (counted) { counted = false; LiveConns.dec() } }
    }
    override fun getResponseHeaders(): Map<String, List<String>> = d.responseHeaders
}

/**
 * Loads a live address into the shared player. A provider usually allows one connection per account: the old stream
 * is stopped and its connection closed before the new one opens (zapping opened the next channel while the old
 * connection was still open, two at once for a moment). Waits at most 3 s for a connection that does not close.
 */
suspend fun liveLoad(exo: ExoPlayer, url: String) {
    if (LiveConns.open > 0) {
        exo.stop()
        kotlinx.coroutines.withTimeoutOrNull(3000) { while (LiveConns.open > 0) kotlinx.coroutines.delay(20) }
    }
    exo.setMediaItem(androidx.media3.common.MediaItem.fromUri(url)); exo.prepare(); exo.playWhenReady = true
}

/** The one live-TV player, shared by full screen and the TV preview (and by every activity instance). */
@Composable
fun rememberLiveExo(m: AppModel): ExoPlayer {
    val ctx = LocalContext.current.applicationContext
    return remember {
        m.liveExo ?: LivePlayer.exo?.also { m.liveExo = it } ?: run {
            LivePlayer.built++
            val ms = m.bufferSec * 1000
            ExoPlayer.Builder(ctx, delayFactory(ctx, m.delayProc))
                // media audio with audio focus: the sound comes back after another app (or the movie player) had it
                .setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), true,
                )
                .setMediaSourceFactory(DefaultMediaSourceFactory(CountingFactory(OkHttpDataSource.Factory(Net.client))))
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(ms.coerceAtLeast(2500), ms.coerceAtLeast(2500), 1200, 3000)
                        .setTargetBufferBytes(32 * 1024 * 1024)
                        .setPrioritizeTimeOverSizeThresholds(false)
                        .build(),
                ).build().also { m.liveExo = it; LivePlayer.exo = it }
        }
    }
}

/**
 * Live video is on screen full size, or at the TV preview spot (preview on, not blocked by the stream limit). The
 * preview spot's position is not needed: until it is laid out the video stays where it was.
 */
fun liveVisible(m: AppModel): Boolean = m.foreground && liveShown(m)

private fun liveShown(m: AppModel): Boolean =
    m.screen == Screen.PLAYER || (m.screen == Screen.LIST && m.section == Section.TV && m.previewOn && !m.streamsFull()) ||
        // the full guide opened from full screen: the sound goes on (no picture) and Back returns without a re-buffer
        (m.screen == Screen.GUIDE && m.liveUrl != null)

/**
 * The live video surface, drawn between the screens and the player overlays. Going back from full screen it shrinks
 * into the preview spot (and grows from it when a channel opens full screen); the same player and surface keep
 * running, so there is no re-buffer and the sound never stops. Off screen the player stops.
 */
@Composable
fun LiveVideo(m: AppModel) {
    val exo = rememberLiveExo(m)
    val full = m.screen == Screen.PLAYER
    // In the background (Home, another app on top) the live stream stops; back in front it starts again where it is
    // shown (liveGen restarts the preview / full-screen players' effects).
    val life = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(life) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> m.foreground = false
                androidx.lifecycle.Lifecycle.Event.ON_START -> if (!m.foreground) { m.foreground = true; m.liveGen++ }
                else -> {}
            }
        }
        life.addObserver(o)
        onDispose { life.removeObserver(o) }
    }
    val vis = liveVisible(m)
    // Sound whenever the picture shows (user rule 2026-11-12): full screen always, the preview unless the user muted
    // it in Settings. Set here, the one place that knows which surface shows: the preview started muted and stayed
    // muted after another section; full screen -> preview kept whatever full screen had set.
    val shownSurface = if (full || (vis && m.screen == Screen.GUIDE)) 2 else if (vis) 1 else 0
    LaunchedEffect(shownSurface, m.previewMuted) { if (shownSurface > 0) exo.volume = if (shownSurface == 1 && m.previewMuted) 0f else 1f }
    // Stop only when the video stays hidden: a screen change that passes through a hidden frame (or a preview not
    // laid out yet) must not cut picture and sound.
    LaunchedEffect(vis) { if (!vis) { kotlinx.coroutines.delay(500); exo.stop(); exo.clearMediaItems(); m.liveUrl = null; m.liveKey = null } }
    var shown by remember { mutableStateOf(false) }
    // drawn full screen, or at the preview spot (with a channel chosen); leaving full screen before the preview spot is
    // laid out the video stays full size until it is, then shrinks into it: the surface is never taken down
    val draw = full || (vis && m.screen == Screen.LIST && m.current != null && (m.previewRect != null || shown))
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        val target = if (full) Rect(0f, 0f, w, h) else m.previewRect ?: Rect(0f, 0f, w, h)
        val anim = remember { Animatable(target, Rect.VectorConverter) }
        LaunchedEffect(target, draw) {
            if (!draw) { shown = false; return@LaunchedEffect }
            if (!shown) { anim.snapTo(target); shown = true } else anim.animateTo(target, tween(380))
        }
        if (draw) {
            val r = anim.value
            val d = LocalDensity.current
            Box(
                Modifier.offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                    .size(with(d) { r.width.coerceAtLeast(1f).toDp() }, with(d) { r.height.coerceAtLeast(1f).toDp() })
                    .background(Color.Black)
                    // touch: tapping the small preview opens it full screen (not focusable: the remote never lands here)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { if (m.screen == Screen.LIST) m.current?.let { m.play(it) } })
                    },
            ) {
                AndroidView(
                    factory = { c -> PlayerView(c).apply { useController = false; player = exo; m.livePv = this; applyCaptions(this, m) } },
                    update = { it.keepScreenOn = full },
                    modifier = if (full && m.aspectMode >= 3) Modifier.align(Alignment.Center).aspectRatio(if (m.aspectMode == 3) 16f / 9f else 4f / 3f, matchHeightConstraintsFirst = true) else Modifier.fillMaxSize(),
                )
            }
        }
    }
}
