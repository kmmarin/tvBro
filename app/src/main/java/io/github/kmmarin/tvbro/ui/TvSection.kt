// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.kmmarin.tvbro.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.kmmarin.tvbro.*
import io.github.kmmarin.tvbro.R
import io.github.kmmarin.tvbro.data.Channel
import io.github.kmmarin.tvbro.data.Net
import io.github.kmmarin.tvbro.data.Programme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TV_LABEL = 230.dp
private val ROW_H = 46.dp

/**
 * Spot of the live preview: the shared live player (LiveVideo) is drawn over it. It plays the chosen channel
 * (m.current, set with OK), never just the focused one (user 2026-11-11: moving around must not switch channels).
 */
@Composable
private fun Preview(m: AppModel, ch: Channel?, modifier: Modifier) {
    val exo = rememberLiveExo(m)
    // (the volume is set by LiveVideo for whichever surface shows: sound with the picture)
    LaunchedEffect(ch?.key, m.previewOn, m.streamsFull(), m.liveGen) {
        if (ch == null || !m.previewOn || m.streamsFull()) return@LaunchedEffect // LiveVideo stops the player when hidden
        // already playing this channel (back from full screen, catch-up or an alternate stream included): keep picture
        // and sound exactly as they are, no re-buffer
        if (m.liveKey == ch.key && m.liveUrl != null && exo.playbackState != androidx.media3.common.Player.STATE_IDLE) { exo.playWhenReady = true; return@LaunchedEffect }
        m.liveUrl = ch.url; m.liveKey = ch.key
        liveLoad(exo, ch.url)
    }
    // a preview stream that fails (dropped connection, provider hiccup) tries again, like full screen does
    DisposableEffect(exo) {
        val l = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (m.screen == Screen.LIST && m.liveKey != null) { m.liveUrl = null; m.liveKey = null; m.previewRetry++ }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_READY && m.previewRetry > 0) m.previewRetry = 0
                // a live stream that "ends" lost its connection (the provider closed it): connect again
                if (state == androidx.media3.common.Player.STATE_ENDED && m.screen == Screen.LIST && m.liveKey != null && m.catchup == null) {
                    m.liveUrl = null; m.liveKey = null; m.previewRetry++
                }
            }
        }
        exo.addListener(l)
        onDispose { exo.removeListener(l) }
    }
    LaunchedEffect(m.previewRetry) {
        if (m.previewRetry == 0 || ch == null || m.screen != Screen.LIST || !m.previewOn || m.streamsFull()) return@LaunchedEffect
        delay(minOf(2000L * m.previewRetry, 15_000L))
        if (m.liveKey != null) return@LaunchedEffect // something else started a stream meanwhile
        m.liveUrl = ch.url; m.liveKey = ch.key
        liveLoad(exo, ch.url)
    }
    Box(modifier.background(Color.Black).onGloballyPositioned { m.previewRect = it.boundsInRoot() }) {
        if (!m.previewOn) Text("Preview off", color = TextDim, modifier = Modifier.align(Alignment.Center))
        else if (m.streamsFull()) Text("Preview paused: a recording uses the stream (limit ${m.maxStreams})", color = TextDim, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center).padding(8.dp))
    }
}

/** A clipped area whose width is exactly the visible time window; [content] receives dp per minute. */
@Composable
private fun GridArea(modifier: Modifier, content: @Composable BoxScope.(Float) -> Unit) {
    BoxWithConstraints(modifier.clipToBounds()) { content(maxWidth.value / GuideLayout.WINDOW_MIN) }
}

@Composable
fun TvSection(m: AppModel) {
    val now = rememberNow()
    val list = m.filtered
    val ctx = LocalContext.current
    // times follow Settings > General > Time format

    val t = { sec: Long -> io.github.kmmarin.tvbro.data.TimeFmt.time(sec) }
    val dateFmt = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    // The window opens at the current half hour, so nothing before it shows; Left past the start reveals earlier time.
    var winStart by remember { mutableLongStateOf(GuideLayout.initialStart(now)) }
    var follow by remember { mutableStateOf(true) } // keep up with the clock until the user moves the window
    var lastMove by remember { mutableLongStateOf(0L) }
    // Follow the clock; a window left in the past (the TV screen can stay open for hours) returns to now after 3 idle minutes.
    LaunchedEffect(now) {
        if (follow || winStart < GuideLayout.initialStart(now)) { winStart = GuideLayout.initialStart(now); follow = true }
    }
    val winEnd = winStart + GuideLayout.WINDOW_MIN * 60L
    val shift = { d: Long -> winStart = GuideLayout.shifted(winStart, d, now); follow = winStart == GuideLayout.initialStart(now); lastMove = System.currentTimeMillis() / 1000 }
    val ensure = { p: Programme ->
        val n = GuideLayout.revealNoPast(p.start, p.stop, winStart, now)
        if (n != winStart) { winStart = n; follow = n == GuideLayout.initialStart(now); lastMove = System.currentTimeMillis() / 1000 }
    }
    // Right from a channel name: the programme on now (the window jumps back to now first when it was moved away).
    val nowFocus = remember { NowFocus() }
    val toNow = { ch: Channel, nowFr: SafeFocus ->
        val cur = GuideLayout.initialStart(System.currentTimeMillis() / 1000)
        if (winStart != cur) { winStart = cur; follow = true; nowFocus.pending = ch.key; true }
        else nowFr.request()
    }
    // the selected group: where Left from the channels (and the first focus) lands, never just the nearest group,
    // since focusing a group switches the channel list to it
    val groupSel = remember { SafeFocus() }
    val groupState = rememberLazyListState()
    LaunchedEffect(m.group, m.groupList) { // also after a move: the moved group stays in view
        val gi = m.groupList.indexOf(m.group) + 1 // +1: the "TV" title row
        if (gi > 0 && groupState.layoutInfo.visibleItemsInfo.none { it.index == gi }) groupState.scrollToItem((gi - 3).coerceAtLeast(0))
        // after a move from the group menu: focus back on the moved (selected) group
        if (m.groupMoved) { m.groupMoved = false; repeat(2) { delay(200); groupSel.request() } }
    }
    // Right from the groups (and the side menu): the channel last focused in this group, else the playing channel when
    // it is in this group, else the first one.
    val chFocus = remember { SafeFocus() }
    val targetKey = (m.groupFocus[m.group]?.takeIf { k -> list.any { it.key == k } })
        ?: (m.focusCh?.key?.takeIf { k -> list.any { it.key == k } })
        ?: (m.current?.key?.takeIf { k -> list.any { it.key == k } }) ?: list.firstOrNull()?.key
    // Coming back (from full screen, the side menu, another section): same group, same scroll, the focused (or playing)
    // channel focused. The first time: the group list.
    val listState = rememberLazyListState(m.tvScroll[m.group]?.first ?: 0, m.tvScroll[m.group]?.second ?: 0)
    val scope = rememberCoroutineScope()
    var shownGroup by remember { mutableStateOf(m.group) }
    DisposableEffect(m.group) { val g = m.group; onDispose { m.tvScroll[g] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset } }
    // another group in the list: its own remembered scroll (not the previous group's)
    LaunchedEffect(m.group) {
        if (shownGroup == m.group) return@LaunchedEffect
        shownGroup = m.group
        val s = m.tvScroll[m.group]
        runCatching { listState.scrollToItem(s?.first ?: 0, s?.second ?: 0) }
    }
    /** Focus into the channel list at [targetKey] (scrolled into view first when needed). */
    val enterChannels: () -> Unit = {
        if (!chFocus.request()) scope.launch {
            val idx = list.indexOfFirst { it.key == targetKey }
            if (idx >= 0) runCatching { listState.scrollToItem((idx - 2).coerceAtLeast(0)) }
            repeat(15) { if (chFocus.request()) return@launch; delay(40) }
        }
    }
    /** Left from a channel: its own (selected) group, scrolled into view first when needed. */
    val toGroups: () -> Boolean = {
        if (!groupSel.request()) scope.launch {
            val gi = m.groupList.indexOf(m.group) + 1
            if (gi > 0) runCatching { groupState.scrollToItem((gi - 3).coerceAtLeast(0)) }
            repeat(15) { if (groupSel.request()) return@launch; delay(40) }
        }
        true
    }
    DisposableEffect(chFocus) { m.railRight = chFocus; onDispose { if (m.railRight === chFocus) m.railRight = null } }
    LaunchedEffect(m.channels.isNotEmpty()) {
        if (m.channels.isEmpty()) return@LaunchedEffect
        val k = m.focusCh?.key
        val idx = if (k == null) -1 else m.filtered.indexOfFirst { it.key == k }
        if (idx >= 0) {
            val vis = listState.layoutInfo.visibleItemsInfo
            if (vis.none { it.index == idx && it.offset >= 0 }) listState.scrollToItem((idx - 2).coerceAtLeast(0))
            repeat(20) { if (chFocus.request()) return@LaunchedEffect; delay(50) }
        }
        delay(250); groupSel.request()
    }
    LaunchedEffect(m.group, m.groupReady) { if (m.groupReady) m.store.lastGroup = m.group }
    val focus = m.focusCh ?: m.current ?: list.firstOrNull()
    // the number column fits the longest number in the list (5-6 digit numbers were cut: "10904" showed as "1090")
    val numW = remember(list) { numberWidth(list.maxOfOrNull { it.number } ?: 0) }

    Row(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.width(190.dp).fillMaxHeight().padding(8.dp), state = groupState) {
            item { Text("TV", color = Accent, fontSize = 22.sp, modifier = Modifier.padding(start = 10.dp, bottom = 6.dp)) }
            items(m.groupList, key = { it }) { g -> // keyed: a moved group keeps its focus
                val sel = g == m.group
                Text((if (sel) "> " else "  ") + m.groupLabel(g), color = if (sel) Accent else TextMain, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().safeFocus(if (sel) groupSel else null)
                        .onPreviewKeyEvent { e ->
                            // Right: always into this group's channels (never stuck in the column)
                            if (e.type != KeyEventType.KeyDown) false
                            else when (e.key) {
                                Key.DirectionRight -> { if (list.isNotEmpty()) enterChannels(); true }
                                Key.DirectionLeft -> m.railSel?.request() == true // the side menu at the TV icon
                                else -> false
                            }
                        }
                        .onFocusChanged { if (it.isFocused) { m.favAction = { m.toggleFavGroup(g) }; if (g !in m.prefs.locked || g in m.unlocked) m.group = g } }
                        .tvFocus(onLongClick = { m.openGroupMenu(g) }) { m.selectGroup(g) }.padding(10.dp))
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            // header: preview on the left; the programme under the cursor (or the current one) on the right
            Row(Modifier.fillMaxWidth().height(180.dp).padding(8.dp)) {
                Preview(m, m.current, Modifier.size(280.dp, 158.dp))
                Spacer(Modifier.width(14.dp))
                val prog = m.focusProg ?: focus?.let { m.epg.nowNext(it, now).first }
                Column(Modifier.weight(1f)) {
                    Text(focus?.let { "${it.number}  ${it.name}" } ?: "No channel", color = Accent, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(prog?.title ?: "No guide data", color = TextMain, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                    if (prog != null) {
                        io.github.kmmarin.tvbro.data.EpisodeNum.line(prog.ep, prog.sub)?.let { Text(it, color = TextMain.copy(alpha = 0.85f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        val live = prog.start <= now && prog.stop > now
                        Text(t(prog.start) + " - " + t(prog.stop) + if (live) "   ${(prog.stop - now) / 60} min left" else if (prog.stop <= now) "   (finished)" else "", color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 3.dp))
                        if (live) Box(Modifier.fillMaxWidth().padding(top = 5.dp).height(5.dp).background(Surface)) {
                            Box(Modifier.fillMaxWidth(((now - prog.start).toFloat() / (prog.stop - prog.start).coerceAtLeast(1)).coerceIn(0f, 1f)).height(5.dp).background(Accent))
                        }
                        prog.desc?.let { Text(it, color = TextDim, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp)) }
                    }
                }
                Column(Modifier.width(150.dp).padding(start = 8.dp), horizontalAlignment = Alignment.End) {
                    focus?.let { Text(m.groupLabel(it.group), color = TextDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    if (focus != null && focus.key in m.favourites) Text("* My list", color = Accent, fontSize = 13.sp)
                }
            }
            // time ruler on the same scale as the blocks
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.width(TV_LABEL).padding(start = 8.dp)) {
                    Text(dateFmt.format(Date(now * 1000)), color = TextDim, fontSize = 12.sp)
                    Text(t(now), color = TextMain, fontSize = 20.sp)
                }
                GridArea(Modifier.weight(1f).height(34.dp)) { dpm ->
                    var tk = winStart
                    while (tk <= winEnd) {
                        Row(Modifier.offset(GuideLayout.tickX(tk, winStart, dpm).dp, 0.dp).align(Alignment.BottomStart), verticalAlignment = Alignment.Bottom) {
                            Box(Modifier.width(1.dp).height(16.dp).background(TextDim))
                            Text(" " + t(tk), color = TextDim, fontSize = 13.sp, maxLines = 1, softWrap = false)
                        }
                        tk += 1800
                    }
                }
            }
            if (list.isEmpty()) Text(if (m.loading.isNotEmpty()) m.loading else "No channels in this group.", color = TextDim, modifier = Modifier.padding(16.dp))
            Box(Modifier.weight(1f).fillMaxWidth().padding(end = 8.dp)) {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    itemsIndexed(list) { i, ch -> TvRow(m, ch, now, winStart, winEnd, shift, ensure, t, if (ch.key == targetKey) chFocus else null, nowFocus, toNow, toGroups, numW) }
                }
                Box(Modifier.padding(start = TV_LABEL).fillMaxSize()) {
                    GridArea(Modifier.fillMaxSize()) { dpm ->
                        if (now in winStart..winEnd) Box(Modifier.offset(GuideLayout.tickX(now, winStart, dpm).dp, 0.dp).width(2.dp).fillMaxHeight().background(Accent.copy(alpha = 0.85f)))
                    }
                }
            }
        }
    }
}

@Composable
private fun TvRow(
    m: AppModel, ch: Channel, now: Long, winStart: Long, winEnd: Long, shift: (Long) -> Unit, ensure: (Programme) -> Unit,
    t: (Long) -> String, labelFocus: SafeFocus?, nowFocus: NowFocus, toNow: (Channel, SafeFocus) -> Boolean, toGroups: () -> Boolean,
    numW: androidx.compose.ui.unit.Dp,
) {
    val rowFocused = m.focusCh?.key == ch.key
    val playing = m.current?.key == ch.key
    val nowFr = remember { SafeFocus() }
    // this row's channel name: where Left from the first block in the window goes (never into the past)
    val labelFr = remember { FocusRequester() }
    val toLabel = { runCatching { labelFr.requestFocus() }; true }
    val rowFocus = { p: Programme? -> m.focusCh = ch; m.focusProg = p; m.favAction = { m.toggleFav(ch) }; m.groupFocus[m.group] = ch.key }
    LaunchedEffect(nowFocus.pending, nowFr.isAttached) { if (nowFocus.pending == ch.key && nowFr.request()) nowFocus.pending = null }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.safeFocus(labelFocus).focusRequester(labelFr).width(TV_LABEL).height(ROW_H).padding(end = 4.dp)
                // Right: this row's programme on now (never a programme in another row)
                // Left: this channel's group (never another one)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) false
                    else when (e.key) { Key.DirectionRight -> { toNow(ch, nowFr); true }; Key.DirectionLeft -> toGroups(); else -> false }
                }
                .onFocusChanged { if (it.isFocused) rowFocus(null) }
                .tvFocus(onLongClick = { m.openChannelMenu(ch) }) { m.previewOrPlay(ch) }.padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(ch.number.toString(), color = TextDim, fontSize = 13.sp, modifier = Modifier.width(numW), maxLines = 1, softWrap = false)
            Logo(ch.logo, ch.name, 30.dp)
            Spacer(Modifier.width(6.dp))
            Text((if (playing) "> " else "") + (if (ch.key in m.favourites) "* " else "") + ch.name, color = if (playing) Accent else TextMain, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (ch.catchup != null) Image(painterResource(R.drawable.ic_catchup), null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(TextDim))
        }
        GridArea(Modifier.weight(1f).height(ROW_H)) { dpm ->
            val progs = m.epg.programmes(ch)
            val total = dpm * GuideLayout.WINDOW_MIN
            val idle = if (rowFocused) Color(0xFF1B222C) else Color(0xFF12171E)
            // "No guide data" blocks take focus too (OK: the channel), so Up/Down never skip a row or jump far away
            val noData: @Composable (Float, Float, SafeFocus?) -> Unit = { x, w, fr ->
                Box(Modifier.offset(x.dp, 0.dp).width(w.dp).height(ROW_H).safeFocus(fr).background(idle)
                    .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft && x < 1f) toLabel() else false }
                    .onFocusChanged { if (it.isFocused) rowFocus(null) }
                    .tvFocus(onLongClick = { m.openChannelMenu(ch) }) { m.previewOrPlay(ch) }
                    .padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                    Text("No guide data", color = TextDim, fontSize = 13.sp, maxLines = 1)
                }
            }
            val inWindow = progs.any { it.stop > winStart && it.start < winEnd }
            if (!inWindow) {
                noData(0f, total - 2f, nowFr)
            } else {
                var cursor = winStart
                // the programme that Right from the channel name lands on: on now, else the next one in the window
                val nowP = progs.firstOrNull { it.stop > now && it.stop > winStart && it.start < winEnd }
                for (p in progs) {
                    val b = GuideLayout.block(p.start, p.stop, winStart, winEnd, dpm) ?: continue
                    val s = maxOf(p.start, winStart)
                    if (s > cursor + 120) GuideLayout.block(cursor, s, winStart, winEnd, dpm)?.let { g ->
                        if (g.second > 60f) key("gap$cursor") { noData(g.first, g.second, null) }
                    }
                    key(p.start) {
                        val live = p.start <= now && p.stop > now
                        val past = p.stop <= now
                        Column(
                            Modifier.offset(b.first.dp, 0.dp).width(b.second.dp).height(ROW_H).clipToBounds()
                                .safeFocus(if (p === nowP) nowFr else null)
                                .background(if (live) Surface else idle)
                                .onPreviewKeyEvent { e ->
                                    if (e.type != KeyEventType.KeyDown) false
                                    else when (e.key) {
                                        Key.DirectionRight -> { val nx = progs.firstOrNull { it.start >= p.stop }; if (nx != null && nx.start >= winEnd) { shift(1800); true } else false }
                                        // Left from the first block in the window: the window moves back only when that stays in the present,
                                        // else focus goes to the channel name (the past is never shown)
                                        Key.DirectionLeft -> {
                                            val pv = progs.lastOrNull { it.stop <= p.start }
                                            // (only when that brings the previous programme into view: a long programme that started in
                                            // the past went on scrolling the window back 30 min per press while focus stayed on it)
                                            if (pv != null && pv.stop <= winStart && pv.stop > GuideLayout.initialStart(now) && GuideLayout.shifted(winStart, -1800, now) != winStart) { shift(-1800); true }
                                            else if (p.start <= winStart + 120 || pv == null || pv.stop <= winStart) {
                                                // the channel name, with the window back at now (where Right from the name goes again)
                                                if (winStart != GuideLayout.initialStart(now)) shift(-48 * 3600L)
                                                toLabel()
                                            } else false
                                        }
                                        else -> false
                                    }
                                }
                                .onFocusChanged { if (it.isFocused) { rowFocus(p); ensure(p) } }
                                .tvFocus(onLongClick = { m.progMenu = ch to p }) { if (p.stop > now) m.previewOrPlay(ch) else m.guidePlay(ch, p) }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text((if (p.start < winStart) "< " else "") + p.title, color = if (past) TextDim else TextMain, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Clip, softWrap = false)
                            if (b.second > 90f) Text(t(p.start), color = TextDim, fontSize = 11.sp, maxLines = 1, softWrap = false)
                        }
                    }
                    cursor = minOf(p.stop, winEnd)
                }
                if (winEnd > cursor + 120) GuideLayout.block(cursor, winEnd, winStart, winEnd, dpm)?.let { g ->
                    if (g.second > 60f) key("gapEnd") { noData(g.first, g.second, null) }
                }
            }
        }
    }
}

/** Width of a 13 sp channel-number column for numbers up to [max] (at least the old 34 dp). */
fun numberWidth(max: Int): androidx.compose.ui.unit.Dp = (max.coerceAtLeast(1).toString().length * 8 + 4).coerceAtLeast(34).dp

/** Channel whose on-now programme should take focus once it is composed (after the window moved back to now). */
class NowFocus { var pending by mutableStateOf<String?>(null) }
