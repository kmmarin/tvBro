// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import io.github.kmmarin.tvbro.*
import io.github.kmmarin.tvbro.data.Channel
import io.github.kmmarin.tvbro.data.DbEpg
import io.github.kmmarin.tvbro.data.Programme

private const val DP_PER_MIN = 5
private val LABEL_W = 200.dp
/** Rows loaded beyond the visible ones, above and below. */
private const val PREFETCH = 4
/** Guide time loaded beyond the visible part of the strip on both sides (seconds). */
private const val MARGIN = 1800L

/**
 * The 24 h guide (Right from full screen). Loads like the TV screen: only the visible rows (focused one and its
 * neighbours first) for the visible part of the strip plus a margin, nothing while a key is held; each row listens for
 * its own channel's data, so arriving programmes recompose that row only. The header is its own composable: a focus
 * move redraws it and the two cells, not the grid.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GuideScreen(m: AppModel) {
    GuideBody(m) // (the rows scroll the FocusScroll way, the time strip its own: GuideIntoView)
}

/**
 * The time strip (sideways, whatever Settings > Appearance > Scrolling says: centring programmes would shift the time
 * line on every key): the least scroll, in the same frame, and a block already showing a good part of itself (a third
 * of the screen, or all of it) does not scroll the strip: Up/Down onto a programme that began before the visible part
 * moved the whole guide back in time, row after row while a key was held.
 */
@OptIn(ExperimentalFoundationApi::class)
private val GuideIntoView = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    @Suppress("OVERRIDE_DEPRECATION")
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> = androidx.compose.animation.core.snap()
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val shown = minOf(offset + size, containerSize) - maxOf(offset, 0f)
        return if (shown >= minOf(size, containerSize / 3f) - 1f) 0f else FocusScroll.edge(offset, size, containerSize)
    }
}

@Composable
private fun GuideBody(m: AppModel) {
    BackHandler { m.screen = if (m.current != null) Screen.PLAYER else Screen.LIST }
    val now = rememberNow()
    val gridStart = (now / 1800) * 1800   // no past (user 2026-11-11): catch-up stays in the programme menu
    val gridEnd = gridStart + 24 * 3600
    val totalDp = (((gridEnd - gridStart) / 60).toInt() * DP_PER_MIN).dp
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(Unit) {
        // opens at the current half hour: the past stays off-screen until the user moves Left into it
        scroll.scrollTo(with(density) { (((GuideLayout.initialStart(now) - gridStart) / 60).toInt() * DP_PER_MIN).dp.toPx().toInt() })
    }
    val list = m.filtered
    val sel = remember { mutableStateOf<Pair<Channel, Programme?>?>(null) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(list.isNotEmpty()) { if (list.isNotEmpty()) { delay(300); runCatching { fr.requestFocus() } } }
    // opens at the playing channel (else the focused one), its programme on now focused
    val startIdx = remember { (m.current ?: m.focusCh)?.key?.let { k -> list.indexOfFirst { it.key == k } }?.coerceAtLeast(0) ?: 0 }
    val rows = androidx.compose.foundation.lazy.rememberLazyListState(FocusScroll.first(startIdx, 2, 7))
    val uniqueKeys = remember(list) { list.mapTo(HashSet(list.size * 2)) { it.key }.size == list.size }

    // What to load: the visible rows (the focused one and its neighbours first, then a few beyond) for the visible part
    // of the strip plus a margin, in whole cache chunks. Re-sent when rows, focus or the strip's chunks change; while a
    // key is held nothing is loaded and the queue is replaced (rows flown past are never loaded after the fact).
    val epg = m.epg
    val visSec = ((LocalConfiguration.current.screenWidthDp - LABEL_W.value.toInt()).coerceAtLeast(300) / DP_PER_MIN) * 60L
    if (epg is DbEpg) LaunchedEffect(epg, list, gridStart) {
        val pxPerSec = with(density) { DP_PER_MIN.dp.toPx() } / 60f
        val rowOf = HashMap<String, Int>(list.size * 2).also { mp -> list.forEachIndexed { i, c -> mp.putIfAbsent(c.key, i) } }
        snapshotFlow {
            val vis = rows.layoutInfo.visibleItemsInfo
            val a = vis.firstOrNull()?.index ?: rows.firstVisibleItemIndex
            val b = vis.lastOrNull()?.index ?: a
            val from = gridStart + (scroll.value / pxPerSec).toLong()
            val (c0, c1) = DbEpg.extent(from - MARGIN, minOf(from + visSec + MARGIN, gridEnd))
            listOf(a.toLong(), b.toLong(), (sel.value?.first?.key?.let { rowOf[it] } ?: -1).toLong(), c0, c1)
        }.distinctUntilChanged().collectLatest { (a, b, f, c0, c1) ->
            while (KeyHold.busy()) { epg.hold(); delay(40) }
            val order = ArrayList<Channel>(((b - a) + 2 * PREFETCH + 3).toInt().coerceAtLeast(4))
            fun add(i: Long) { list.getOrNull(i.toInt())?.let { order += it } }
            if (f in (a - PREFETCH)..(b + PREFETCH)) { add(f); add(f - 1); add(f + 1) }
            for (i in a..b) add(i)
            for (i in 1..PREFETCH) { add(b + i); add(a - i) }
            epg.want(order, maxOf(c0, gridStart), minOf(c1, gridEnd))
        }
    }
    // Focus must never fall out of the guide: when the focused block goes away (a "No guide data" placeholder replaced
    // by arriving programmes), its row puts focus back within two frames.
    val focusBack = remember { HashMap<String, () -> Boolean>() }
    LaunchedEffect(Unit) {
        snapshotFlow { FocusMemory.anyFocus }.collect { any ->
            if (any) return@collect
            withFrameNanos { }; withFrameNanos { }
            if (Lat.enabled) android.util.Log.i("tvbro-lat", "guide24 focus lost: still=${!FocusMemory.anyFocus}")
            if (!FocusMemory.anyFocus) sel.value?.first?.key?.let { focusBack[it] }?.invoke()
        }
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        GuideHeader(m, sel, now)
        Row {
            Spacer(Modifier.width(LABEL_W))
            Box(Modifier.weight(1f).horizontalScroll(scroll)) {
                Row(Modifier.width(totalDp)) {
                    var t = gridStart
                    while (t < gridEnd) {
                        Text(hhmm(t), color = TextDim, fontSize = 14.sp, modifier = Modifier.width((30 * DP_PER_MIN).dp))
                        t += 1800
                    }
                }
            }
        }
        val startKey = list.getOrNull(startIdx)?.key
        // Rows compose only the blocks around the visible part of the strip (half an hour before, the screen and three
        // hours after: Right finds the next block), in half-hour steps: all 24 h of blocks made each row entering the
        // list cost tens of ms on the stick, inside the key's own frame.
        val pxPerSec = with(density) { DP_PER_MIN.dp.toPx() } / 60f
        val shown = remember(gridStart, visSec) {
            derivedStateOf {
                val v = gridStart + ((scroll.value / pxPerSec).toLong() / 1800) * 1800
                (v - 1800).coerceAtLeast(gridStart) to (v + visSec + 3 * 3600).coerceAtMost(gridEnd)
            }
        }
        LazyColumn(Modifier.weight(1f), state = rows) {
            items(list, key = if (uniqueKeys) { c -> c.key } else null, contentType = { 0 }) { ch ->
                GuideRow(m, ch, ch.key == startKey, now, gridStart, gridEnd, totalDp, scroll, fr, sel, focusBack, shown)
            }
        }
    }
}

/** The focused channel / programme (own scope: a focus move recomposes only this, not the grid). */
@Composable
private fun GuideHeader(m: AppModel, sel: State<Pair<Channel, Programme?>?>, now: Long) {
    Column(Modifier.fillMaxWidth().height(116.dp)) {
        val s = sel.value
        if (s != null) {
            val p = s.second
            Text(s.first.name + (p?.let { "  -  ${it.title}  (${hhmm(it.start)}-${hhmm(it.stop)})" } ?: ""), color = TextMain, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(p?.let { io.github.kmmarin.tvbro.data.EpisodeNum.line(it.ep, it.sub) }, p?.desc).joinToString("  ·  "), color = TextDim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (p != null && p.stop <= now) {
                Text(if (s.first.catchup != null) "OK: watch catch-up" else "No catch-up for this channel", color = Accent, fontSize = 13.sp)
            } else Text("OK: watch    Long-press OK: details, record, remind", color = Accent, fontSize = 13.sp)
        } else Text("TV guide", color = TextMain, fontSize = 20.sp)
        TvButton("Search guide") { m.searchKeyboard = true; m.screen = Screen.GUIDE_SEARCH }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GuideRow(
    m: AppModel, ch: Channel, first: Boolean, now: Long, gridStart: Long, gridEnd: Long, totalDp: Dp, scroll: ScrollState,
    fr: FocusRequester, sel: MutableState<Pair<Channel, Programme?>?>, focusBack: HashMap<String, () -> Boolean>,
    shown: State<Pair<Long, Long>>,
) {
    // the channel name takes focus: Left from the first programme lands here (the guide never scrolls into the past);
    // OK plays the channel, long-press opens its menu (catch-up, record...)
    val labelFr = remember { FocusRequester() }
    val toLabel = { runCatching { labelFr.requestFocus() }; true }
    // this row's blocks by start time: where focus goes back to when the focused block went away
    val cells = remember { HashMap<Long, FocusRequester>() }
    DisposableEffect(ch.key) {
        val back = {
            val p = sel.value?.second
            (p != null && cells[p.start]?.let { runCatching { it.requestFocus() }.isSuccess } == true) ||
                // else the block on now (or at the shown part's start), else the first one, else the name
                (cells.keys.filter { it <= maxOf(now, shown.value.first) }.maxOrNull() ?: cells.keys.minOrNull())?.let { k -> cells[k]?.let { runCatching { it.requestFocus() }.isSuccess } } == true || toLabel()
        }
        focusBack[ch.key] = back
        onDispose { if (focusBack[ch.key] === back) focusBack.remove(ch.key) }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(LABEL_W).height(52.dp).padding(end = 6.dp).focusRequester(labelFr)
            .onFocusChanged { if (it.isFocused) sel.value = ch to null }
            .tvFocus(onLongClick = { m.openChannelMenu(ch) }) { m.play(ch) }.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Logo(ch.logo, ch.name, 32.dp)
            Spacer(Modifier.width(6.dp))
            Text((if (m.showNumbers) "${ch.number} " else "") + ch.name, color = TextMain, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides GuideIntoView) {
        Box(Modifier.weight(1f).horizontalScroll(scroll)) {
            Row(Modifier.width(totalDp)) {
                // the loaded part of the 24 h strip (the screen says what to load; this row only listens for its data)
                val progs = rowProgrammes(m.epg, ch, gridStart, gridEnd)
                val (lo, hi) = shown.value
                // first focus: the programme on now (or the next one), never a finished one
                val focusP = if (first) progs.firstOrNull { it.stop > now && it.stop > gridStart } else null
                var cursor = gridStart
                var any = false
                for (p in progs) {
                    val s = maxOf(p.start, cursor)
                    val e = minOf(p.stop, gridEnd)
                    if (e <= s || e <= lo || s >= hi) continue // (blocks outside the shown part: the spacer covers them)
                    if (s > cursor) Spacer(Modifier.width((((s - cursor) / 60).toInt() * DP_PER_MIN).dp))
                    val w = (((e - s) / 60).toInt() * DP_PER_MIN - 2).coerceAtLeast(4).dp
                    key(p.start) {
                        Cell(m, ch, p, p.title, w, now, p === focusP, fr, if (s <= gridStart) toLabel else null, cells) { sel.value = ch to p }
                    }
                    Spacer(Modifier.width(2.dp))
                    cursor = e
                    any = true
                }
                if (!any) {
                    // nothing (loaded) in the shown part: one placeholder over it, so Up/Down never skip the row
                    if (lo > gridStart) Spacer(Modifier.width((((lo - gridStart) / 60).toInt() * DP_PER_MIN).dp))
                    key("none") {
                        Cell(m, ch, null, "No guide data", (((hi - lo) / 60).toInt() * DP_PER_MIN - 2).coerceAtLeast(4).dp, now, first, fr, if (lo <= gridStart) toLabel else null, null) { sel.value = ch to null } }
                }
            }
        }
        }
    }
}

@Composable
private fun Cell(
    m: AppModel, ch: Channel, p: Programme?, title: String, width: Dp,
    now: Long, requester: Boolean, fr: FocusRequester, toLabel: (() -> Boolean)?, cells: HashMap<Long, FocusRequester>?, onFocus: () -> Unit,
) {
    val live = p != null && p.start <= now && p.stop > now
    val past = p != null && p.stop <= now
    val cellFr = remember { FocusRequester() }
    if (cells != null && p != null) DisposableEffect(p.start) { cells[p.start] = cellFr; onDispose { if (cells[p.start] === cellFr) cells.remove(p.start) } }
    Column(
        Modifier.width(width).height(52.dp)
            .requesterIf(requester, fr)
            .focusRequester(cellFr)
            // the first block in the row: Left goes to the channel name
            .onPreviewKeyEvent { e -> toLabel != null && e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft && toLabel() }
            .onFocusChanged { if (it.isFocused) onFocus() }
            .tvFocus(onLongClick = { if (p != null) m.progMenu = ch to p else m.toast("No programme info") }) { m.guidePlay(ch, p) }
            .background(if (live) Surface else Bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = if (past) TextDim else TextMain, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (p != null) Text(hhmm(p.start), color = TextDim, fontSize = 11.sp, maxLines = 1)
    }
}
