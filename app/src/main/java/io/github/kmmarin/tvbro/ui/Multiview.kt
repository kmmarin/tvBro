// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.kmmarin.tvbro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.BuildConfig
import io.github.kmmarin.tvbro.data.Channel
import io.github.kmmarin.tvbro.data.MvLayout
import io.github.kmmarin.tvbro.data.MvRules

/**
 * Multiview: 2 to 4 live channels at once, each tile its own player from the live players' recipe ([buildLiveExo]:
 * same client, counted connections). The D-pad moves between tiles and the sound follows the focus; OK opens the
 * tile's options (change channel, add one, layout, full screen, remove); Channel up / down zaps the focused tile;
 * Back goes full screen on the focused tile's channel.
 *
 * Connections: a tile loads only while fewer live connections than tiles are open (the full-screen player
 * has stopped, a changed tile's old stream has closed), so the provider's limit is never passed, not even for a moment.
 * Tiles ask for video no larger than their size ([MvRules.maxVideoHeight]): the Fire TV Stick 4K decodes two HD
 * streams, four 1080p streams drop frames.
 */
@Composable
fun MultiviewScreen(m: AppModel) {
    val tiles = m.mvTiles
    if (tiles.isEmpty()) return
    val fr = remember { FocusRequester() }
    BackHandler(enabled = m.menu == null && m.prompt == null) { m.mvLeave(full = true) }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    // focus back on the screen when a menu closes
    LaunchedEffect(m.menu, m.prompt) { if (m.menu == null && m.prompt == null) runCatching { fr.requestFocus() } }
    var hint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(6000); hint = false }
    val n = tiles.size
    val layout = MvRules.resolve(m.mvLayout, n)
    // a recording that starts takes a stream: tiles past their provider's limit close
    val recs = io.github.kmmarin.tvbro.data.RecRepo.items.count { it.status == "recording" } +
        io.github.kmmarin.tvbro.data.DlRepo.items.count { it.status == "downloading" }
    LaunchedEffect(recs) { m.mvFit() }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(fr).countFocus().focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val dir = when (e.key) {
                    Key.DirectionLeft -> MvRules.Dir.LEFT; Key.DirectionRight -> MvRules.Dir.RIGHT
                    Key.DirectionUp -> MvRules.Dir.UP; Key.DirectionDown -> MvRules.Dir.DOWN
                    else -> null
                }
                when {
                    dir != null -> { m.mvFocus = MvRules.move(layout, n, m.mvFocus, dir); true }
                    e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.Menu -> { if (e.nativeKeyEvent.repeatCount == 0) tileMenu(m, m.mvFocus); true }
                    e.key == Key.ChannelUp || e.key == Key.PageUp -> { zapTile(m, m.mvFocus, -1); true }
                    e.key == Key.ChannelDown || e.key == Key.PageDown -> { zapTile(m, m.mvFocus, 1); true }
                    else -> false
                }
            },
    ) {
        val rects = MvRules.rects(layout, n)
        val wDp = maxWidth; val hDp = maxHeight
        tiles.forEachIndexed { i, c ->
            val r = rects[i]
            // keyed by slot: changing a tile's channel keeps its player (and surface), only the stream changes
            key(i) {
                Box(Modifier.offset(wDp * r.x, hDp * r.y).size(wDp * r.w, hDp * r.h).padding(3.dp)) {
                    MvTile(m, i, c, focused = i == m.mvFocus, maxH = MvRules.maxVideoHeight(layout, n, i))
                }
            }
        }
        if (hint) {
            Text("OK or hold: options  ·  Tap: sound  ·  Channel up / down: change  ·  Back: full screen", color = TextMain, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
    if (BuildConfig.DEBUG) MvStats(m)
}

/** The players of the open tiles (slot -> player), for the stats log and the tests. */
internal object MvPlayers { val bySlot = HashMap<Int, ExoPlayer>() }

@Composable
private fun MvTile(m: AppModel, slot: Int, c: Channel, focused: Boolean, maxH: Int) {
    val ctx = LocalContext.current.applicationContext
    val exo = remember { buildLiveExo(ctx, m, tile = true) }
    DisposableEffect(Unit) {
        MvPlayers.bySlot[slot] = exo
        onDispose { MvPlayers.bySlot.remove(slot); exo.release() }
    }
    // in the background (Home, another app) the tiles stop; back in front liveGen starts them again
    LaunchedEffect(m.foreground) { if (!m.foreground) { exo.stop(); exo.clearMediaItems() } }
    // sound only on the focused tile
    LaunchedEffect(focused) { exo.volume = if (focused) 1f else 0f }
    LaunchedEffect(maxH) {
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setMaxVideoSize(Int.MAX_VALUE, maxH).build()
    }
    var failed by remember(c.key) { mutableStateOf(false) }
    LaunchedEffect(c.key, m.liveGen) {
        if (!m.foreground) return@LaunchedEffect
        val url = c.url
        if (exo.playbackState != androidx.media3.common.Player.STATE_IDLE || exo.mediaItemCount > 0) { exo.stop(); exo.clearMediaItems() }
        // never more connections than allowed: wait for the full-screen player's or this tile's old stream to close
        // (every tile is one allowed stream: more open connections than tiles means an old one is still closing)
        kotlinx.coroutines.withTimeoutOrNull(4000) { while (LiveConns.open >= m.mvTiles.size) kotlinx.coroutines.delay(30) }
        if (LiveConns.open >= m.mvTiles.size) { failed = true; io.github.kmmarin.tvbro.data.Diag.log("multiview: tile $slot not started, ${LiveConns.open} connections open (${m.mvTiles.size} tiles)"); return@LaunchedEffect }
        exo.setMediaItem(MediaItem.fromUri(url)); exo.prepare(); exo.playWhenReady = true
    }
    DisposableEffect(exo) {
        val l = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { failed = true; io.github.kmmarin.tvbro.data.Diag.log("multiview: tile $slot stopped (${error.errorCodeName}: ${error.cause?.javaClass?.simpleName})") }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) failed = false }
        }
        exo.addListener(l)
        onDispose { exo.removeListener(l) }
    }
    val shape = RoundedCornerShape(6.dp)
    Box(Modifier.fillMaxSize().background(Color.Black, shape).border(if (focused) 3.dp else 1.dp, if (focused) Accent else Color(0x33FFFFFF), shape).padding(3.dp)) {
        AndroidView(factory = { k -> PlayerView(k).apply { useController = false; player = exo; keepScreenOn = true } }, modifier = Modifier.fillMaxSize())
        val now = System.currentTimeMillis() / 1000
        val prog = remember(c.key, now / 60) { m.epg.nowNext(c, now).first?.title }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 4.dp)) {
            // the sound mark is the app's speaker icon (a "♪" character drew as an empty box on the Peloton's font)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (focused) androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(io.github.kmmarin.tvbro.R.drawable.ic_pm_audio),
                    "Sound", Modifier.padding(end = 6.dp).size(16.dp), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Accent))
                Text(c.name, color = TextMain, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (prog != null) Text(prog, color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (failed) Text("Not playing", color = TextDim, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
        // touch: a tap selects the tile (its sound comes on), a long press opens its options (like OK on the remote).
        // A layer over the video, so the player view never takes the touch.
        Box(Modifier.matchParentSize().pointerInput(slot) {
            detectTapGestures(onTap = { m.mvFocus = slot }, onLongPress = { m.mvFocus = slot; tileMenu(m, slot) })
        })
    }
}

/** The channels a tile can switch to: the tile's channel's group as the TV screen lists it. */
private fun groupOf(m: AppModel, c: Channel): List<Channel> = m.shown.filter { it.group == c.group }.ifEmpty { m.shown }

private fun zapTile(m: AppModel, i: Int, step: Int) {
    val c = m.mvTiles.getOrNull(i) ?: return
    val l = groupOf(m, c).filter { x -> m.mvTiles.none { it.key == x.key } || x.key == c.key }
    if (l.size < 2) return
    val at = l.indexOfFirst { it.key == c.key }.coerceAtLeast(0)
    m.mvSet(i, l[(at + step + l.size) % l.size])
}

/** A channel list around [around] (at most 200 rows, so a big group opens at once). */
private fun pickChannel(m: AppModel, title: String, around: Channel, onPick: (Channel) -> Unit) {
    val all = groupOf(m, around)
    val at = all.indexOfFirst { it.key == around.key }.coerceAtLeast(0)
    val from = (at - 100).coerceAtLeast(0)
    val l = all.subList(from, (from + 200).coerceAtMost(all.size))
    m.menu = MenuState(title, l.map { c -> c.name to { onPick(c) } }, focus = l.indexOfFirst { it.key == around.key })
}

private fun tileMenu(m: AppModel, i: Int) {
    val c = m.mvTiles.getOrNull(i) ?: return
    val items = ArrayList<Pair<String, () -> Unit>>()
    items += "Full screen" to { m.mvFocus = i; m.mvLeave(full = true) }
    items += "Change channel..." to { pickChannel(m, "Tile ${i + 1}: ${c.group}", c) { m.mvSet(i, it) } }
    // a new tile only within the provider's limit (else the row says why, naming the provider and its limit)
    if (m.mvFits(c, null)) items += "Add a channel..." to { pickChannel(m, "Add to multiview", c) { m.mvSet(m.mvTiles.size, it) } }
    else items += ("Add a channel: " + if (m.mvTiles.size >= MvRules.MAX_TILES) "${MvRules.MAX_TILES} at most" else "${m.limitText(c.sourceId)}, all in use") to { m.toast(m.mvRefusal(c)) }
    val layout = MvRules.resolve(m.mvLayout, m.mvTiles.size)
    MvRules.nextLayout(layout)?.let { nx -> items += "Layout: ${nx.label}" to { m.mvLayout = nx } }
    if (m.mvTiles.size >= 3 && i != 0) items += "Make this the main tile" to { val t = m.mvTiles[i]; m.mvTiles[i] = m.mvTiles[0]; m.mvTiles[0] = t; m.mvFocus = 0 }
    if (m.mvTiles.size > 1) items += "Remove from multiview" to { m.mvRemove(i) }
    items += "Close multiview" to { m.mvLeave(full = false) }
    m.menu = MenuState(c.name, items)
}

/** Debug builds: every 5 s (logcat tag tvbro-mv) the tiles' video size, frames shown / dropped, connections and memory. */
@Composable
private fun MvStats(m: AppModel) {
    LaunchedEffect(Unit) {
        val t0 = android.os.SystemClock.uptimeMillis()
        while (true) {
            kotlinx.coroutines.delay(5000)
            val mi = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
            val tiles = MvPlayers.bySlot.entries.sortedBy { it.key }.joinToString(" ") { (i, p) ->
                val f = p.videoFormat; val d = p.videoDecoderCounters
                "t$i=${f?.width ?: 0}x${f?.height ?: 0}/st${p.playbackState}/r${d?.renderedOutputBufferCount ?: 0}/d${d?.droppedBufferCount ?: 0}/v${p.volume.toInt()}"
            }
            android.util.Log.i("tvbro-mv", "+${(android.os.SystemClock.uptimeMillis() - t0) / 1000}s n=${m.mvTiles.size} layout=${m.mvLayout} limits=${m.mvTiles.map { it.sourceId }.distinct().map { m.streamLimit(it) }} conns=${LiveConns.open} pssKB=${mi.totalPss} javaKB=${mi.dalvikPss} nativeKB=${mi.nativePss} $tiles")
        }
    }
}
