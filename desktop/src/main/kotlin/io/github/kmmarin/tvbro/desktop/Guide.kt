// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.kmmarin.tvbro.data.*
import io.github.kmmarin.tvbro.data.TvNav.Zone

private val LABEL_W = 210.dp
private val ROW_H = 60.dp
/** [GuideCtl.pending] start value for the channel name. */
private const val LABEL = Long.MIN_VALUE
/** [GuideCtl.pending] start value for the "No guide data" cell. */
private const val NO_DATA = Long.MIN_VALUE + 1

@Composable
private fun Logo(url: String?) = NetImage(url, Modifier.size(48.dp, 30.dp).clip(RoundedCornerShape(4.dp)))

@Composable
fun Empty(app: AppState) = Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
    T(if (app.busy) "Loading…" else if (app.sources.isEmpty()) "No sources yet" else "Nothing here", 20)
    if (app.sources.isEmpty()) Btn("Add a source") { app.tab = Tab.SETTINGS; app.settingsPage = 1 }
}

private fun nowSec() = System.currentTimeMillis() / 1000

/**
 * Focus moves inside the grid, decided by the shared [GuideWindow] rules (never into the past, Left at the window start
 * goes to the channel name). The time window lives in [AppState] so it survives leaving the screen.
 */
private class GuideCtl(val app: AppState) {
    /** Channel key and programme start (or [LABEL] / [NO_DATA]) that should take the focus once composed. */
    var pending by mutableStateOf<Pair<String, Long>?>(null)
    fun setWin(w: Long) {
        val now = nowSec()
        val n = GuideWindow.clampWin(w, now)
        app.winStart = n; app.following = n == GuideWindow.initialStart(now)
    }
    fun left(ch: Channel, p: Programme) {
        val m = GuideWindow.leftOf(app.epg.programmes(ch), p, app.winStart, nowSec())
        setWin(m.win); pending = ch.key to (m.prog?.start ?: LABEL)
    }
    fun right(ch: Channel, p: Programme) {
        val m = GuideWindow.rightOf(app.epg.programmes(ch), p, app.winStart, nowSec())
        setWin(m.win); pending = ch.key to (m.prog?.start ?: p.start)
    }
    /** Right from a channel name: the programme on now, the window back at now. */
    fun fromLabel(ch: Channel) {
        val m = GuideWindow.rightOfLabel(app.epg.programmes(ch), nowSec())
        setWin(m.win); pending = ch.key to (m.prog?.start ?: NO_DATA)
    }
}

@Composable
fun TvScreen(app: AppState) {
    if (app.channels.isEmpty()) return Empty(app)
    val now = app.now
    val list = app.filtered
    val g = remember(app) { GuideCtl(app) }
    val scope = rememberCoroutineScope()
    // Follow the clock; a window time has left behind snaps to now, and an ended focused programme hands the
    // focus to the one on now (the focused block never disappears under the focus).
    LaunchedEffect(now) {
        val n = GuideWindow.onTick(app.winStart, app.following, now)
        if (n != app.winStart) g.setWin(n)
        val ch = app.focusCh; val fp = app.focusProg
        if (app.tvZone == Zone.GRID && ch != null && fp != null && fp.stop <= app.winStart)
            g.pending = ch.key to (GuideWindow.afterEnd(app.epg.programmes(ch), fp, now)?.start ?: LABEL)
    }
    val winStart = app.winStart
    // nothing focused yet (first start): the channel watched last, in the preview and focused
    val last = remember(list) { list.firstOrNull { it.key == app.settings.lastChannel } }
    val focus = app.focusCh?.let { f -> list.firstOrNull { it.key == f.key } ?: f } ?: app.playing?.live ?: last ?: list.firstOrNull()

    // The preview plays the current channel and never follows focus. Nothing played yet: the last channel starts
    // (a locked one waits for Enter and the PIN).
    LaunchedEffect(Unit) {
        if (app.playing == null && !app.full && app.settings.previewOn)
            (app.shown.firstOrNull { it.key == app.settings.lastChannel } ?: focus)?.let { app.preview(it) }
    }

    val keys = list.map { it.key }
    val targetKey = keys.getOrNull(TvNav.entry(keys, focus?.key, app.playing?.live?.key))
    val labelFr = remember { FocusRequester() }
    val groupFr = remember { FocusRequester() }
    val groupState = rememberLazyListState()
    val listState = rememberLazyListState(app.tvScroll[app.group]?.first ?: 0, app.tvScroll[app.group]?.second ?: 0)
    DisposableEffect(app.group) { val gr = app.group; onDispose { app.tvScroll[gr] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset } }

    /** Left from a channel row: the groups column, on the selected group. */
    fun toGroups() { scope.launch {
        val i = app.groups.indexOf(app.group)
        if (i >= 0 && groupState.layoutInfo.visibleItemsInfo.none { it.index == i }) groupState.scrollToItem((i - 2).coerceAtLeast(0))
        if (!groupFr.settle()) app.railFr.settle()
    } }
    // Right from the side menu: back to the zone (and item) the TV screen had focused last.
    DisposableEffect(labelFr) {
        val ret = {
            when (TvNav.right(Zone.RAIL, app.tvZone)) {
                Zone.GROUPS -> toGroups()
                Zone.GRID -> { val c = app.focusCh; val p = app.focusProg; if (c != null && targetKey == c.key) g.pending = c.key to (p?.start ?: NO_DATA) else scope.launch { labelFr.settle() } }
                else -> scope.launch { labelFr.settle() }
            }
            true
        }
        app.railReturn = ret
        onDispose { if (app.railReturn === ret) app.railReturn = null; app.previewRect = null }
    }
    // Entering (from full screen, the side menu, another section): same group and scroll, the focused or playing
    // channel visible and focused. [AppState.focusTick] re-runs it when full screen shrinks back into the preview.
    LaunchedEffect(app.focusTick) {
        val idx = keys.indexOf(targetKey)
        if (idx >= 0) {
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == idx && it.offset >= 0 }) listState.scrollToItem((idx - 2).coerceAtLeast(0))
            // back on the screen: the zone it had (leaving full screen sets the channel name)
            val c = app.focusCh; val p = app.focusProg
            when {
                app.tvZone == Zone.GROUPS -> { toGroups(); return@LaunchedEffect }
                app.tvZone == Zone.GRID && c != null && c.key == targetKey && p != null -> { g.pending = c.key to p.start; return@LaunchedEffect }
            }
            if (labelFr.settle()) return@LaunchedEffect
        }
        groupFr.settle()
    }

    Row(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.width(180.dp).fillMaxHeight(), state = groupState) {
            items(app.groups) { gr ->
                val sel = gr == app.group
                Box(Modifier.fillMaxWidth()
                    .onKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) false
                        else if (e.key == Key.DirectionRight) { scope.launch { labelFr.settle() }; true }
                        else if (e.key == Key.DirectionLeft) { scope.launch { app.railFr.settle() }; true }
                        else false
                    }
                    .tvItem(onFocus = { app.tvZone = Zone.GROUPS }, onMenu = { app.groupMenu(gr) }, requester = if (sel) groupFr else null) { app.selectGroup(gr) }.padding(8.dp)) {
                    T(app.groupLabel(gr), 14, if (sel) Theme.accent else Theme.text, bold = sel)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Header(app, focus, now)
            Ruler(winStart, now)
            if (list.isEmpty()) T(if (app.busy) "Loading…" else "No channels in this group.", 14, Theme.dim, modifier = Modifier.padding(16.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(list, key = { it.key }) { ch -> GuideRow(app, g, ch, now, winStart, if (ch.key == targetKey) labelFr else null, ::toGroups) }
                }
                // the "now" line, on the same scale as the blocks
                Box(Modifier.padding(start = LABEL_W).fillMaxSize()) {
                    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
                        val dpm = maxWidth.value / GuideWindow.WINDOW_MIN
                        if (now in winStart until winStart + GuideWindow.WINDOW_SEC) Box(Modifier.offset(GuideWindow.tickX(now, winStart, dpm).dp, 0.dp).width(2.dp).fillMaxHeight().background(Theme.accent.copy(alpha = 0.8f)))
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(app: AppState, focus: Channel?, now: Long) {
    Row(Modifier.fillMaxWidth().height(176.dp).padding(bottom = 8.dp)) {
        // the shared player is drawn over this spot (VideoHost)
        Box(Modifier.size(288.dp, 162.dp).background(androidx.compose.ui.graphics.Color.Black).onGloballyPositioned { app.previewRect = it.boundsInRoot() }) {
            if (app.playing == null) T(if (app.settings.previewOn) "…" else "Preview off", 13, Theme.dim, modifier = Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.width(14.dp))
        // the info panel describes the focused item; the preview keeps the current channel
        val prog = app.focusProg?.takeIf { focus != null } ?: focus?.let { app.epg.nowNext(it, now).first }
        Column(Modifier.weight(1f)) {
            T(focus?.let { (if (it.number > 0) "${it.number}  " else "") + it.name } ?: "No channel", 15, Theme.accent)
            T(prog?.title ?: "No guide data", 22, modifier = Modifier.padding(top = 3.dp))
            if (prog != null) {
                EpisodeNum.line(prog.ep, prog.sub)?.let { T(it, 15, Theme.text.copy(alpha = 0.85f)) }
                val st = ProgTime.classify(focus, prog.start, prog.stop, now)
                T(TimeFmt.time(prog.start) + " – " + TimeFmt.time(prog.stop) + when (st) {
                    ProgState.NOW -> "   ${ProgTime.minutesLeft(prog.stop, now)} min left"
                    ProgState.PAST_CATCHUP -> "   catch-up ⟲"
                    ProgState.PAST -> "   (finished)"
                    ProgState.FUTURE -> "   " + TimeFmt.dayTime(prog.start * 1000, "EEE").substringBefore(' ')
                }, 14, Theme.dim, modifier = Modifier.padding(top = 3.dp))
                if (st == ProgState.NOW) ProgressLine(ProgTime.progress(prog.start, prog.stop, now), Modifier.fillMaxWidth().padding(top = 5.dp))
                prog.desc?.let { T(it, 12, Theme.dim, maxLines = 4, modifier = Modifier.padding(top = 5.dp)) }
            }
        }
        Column(Modifier.width(150.dp).padding(start = 8.dp), horizontalAlignment = Alignment.End) {
            focus?.let { T(app.groupLabel(it.group), 13, Theme.dim, maxLines = 2) }
            val isCur = focus != null && app.playing?.live?.key == focus.key && app.playing?.archive == null
            if (isCur) T("▶ Playing", 13, Theme.ok)
            if (focus != null && app.inList(focus.key)) T("★ My list", 13, Theme.accent)
            if (focus != null && app.recorder.active(focus) != null) T("● Recording", 13, Theme.live)
            T((if (isCur) "Enter: full screen" else if (app.settings.previewOn) "Enter: play here" else "Enter: watch") + " · hold Enter or right-click for options", 11, Theme.dim, maxLines = 3, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun Ruler(winStart: Long, now: Long) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.width(LABEL_W)) {
            T(java.text.SimpleDateFormat("EEE d MMM").format(java.util.Date(now * 1000)), 12, Theme.dim)
            T(TimeFmt.time(now), 20)
        }
        BoxWithConstraints(Modifier.weight(1f).height(30.dp).clipToBounds()) {
            val dpm = maxWidth.value / GuideWindow.WINDOW_MIN
            var tk = winStart
            while (tk < winStart + GuideWindow.WINDOW_SEC) {
                Row(Modifier.offset(GuideWindow.tickX(tk, winStart, dpm).dp, 0.dp).align(Alignment.BottomStart), verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.width(1.dp).height(14.dp).background(Theme.dim))
                    T(" " + TimeFmt.time(tk), 13, Theme.dim)
                }
                tk += GuideWindow.STEP_SEC
            }
        }
    }
}

@Composable
private fun GuideRow(app: AppState, g: GuideCtl, ch: Channel, now: Long, winStart: Long, entryFr: FocusRequester?, toGroups: () -> Unit) {
    val playing = app.playing?.live?.key == ch.key
    val vis = remember(ch.key, winStart, app.epg) { GuideWindow.visible(app.epg.programmes(ch), winStart) }
    val ownLabel = remember { FocusRequester() }
    val labelFr = entryFr ?: ownLabel
    val noDataFr = remember { FocusRequester() }
    val blockFr = remember(ch.key) { HashMap<Long, FocusRequester>() }
    // focus asked for by a move (Left / Right / the clock), once the target is composed
    LaunchedEffect(g.pending, vis) {
        val mine = g.pending ?: return@LaunchedEffect
        val (k, start) = mine
        if (k != ch.key) return@LaunchedEffect
        // a programme no longer in the window falls back to the channel name: the focus never gets lost
        val fr = when {
            start == LABEL -> labelFr
            vis.isEmpty() -> noDataFr
            else -> blockFr[start]?.takeIf { vis.any { it.start == start } } ?: labelFr
        }
        // asked again until the item reports the focus (a block re-laid out by the window move can drop a first
        // request); any focus in this row clears [GuideCtl.pending]
        repeat(20) {
            if (g.pending !== mine) return@LaunchedEffect
            runCatching { fr.requestFocus() }
            delay(50)
        }
        if (g.pending === mine) g.pending = null
    }
    // any item of the guide taking the focus (the target, or the user moving on) ends the request
    Row(Modifier.fillMaxWidth().height(ROW_H).padding(vertical = 1.dp)) {
        Row(Modifier.width(LABEL_W).fillMaxHeight()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) false
                else if (e.key == Key.DirectionRight) { g.fromLabel(ch); true }
                else if (e.key == Key.DirectionLeft) { toGroups(); true }
                else false
            }
            .tvItem(onFocus = { g.pending = null; app.focusCh = ch; app.focusProg = null; app.tvZone = Zone.LABEL; app.favAction = { app.toggleList(ch.key) } }, onMenu = { app.channelMenu(ch) }, requester = labelFr) { app.enterChannel(ch) }
            .padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            T(if (ch.number > 0) ch.number.toString() else "", 12, Theme.dim, modifier = Modifier.width(34.dp))
            Logo(ch.logo); Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                T((if (playing) "▶ " else "") + ch.name + (if (ch.key in app.settings.prefs.lockedCh) "  🔒" else ""), 13, if (playing) Theme.accent else Theme.text, bold = playing)
                Row {
                    if (app.inList(ch.key)) T("★ ", 11, Theme.accent)
                    if (ch.catchup != null) T("⟲ ", 11, Theme.dim)
                    if (app.recorder.active(ch) != null) T("● ", 11, Theme.live)
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            val dpm = maxWidth.value / GuideWindow.WINDOW_MIN
            if (vis.isEmpty()) Box(Modifier.fillMaxSize().padding(1.dp).background(Theme.surface)
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) false
                    else if (e.key == Key.DirectionLeft) { g.pending = ch.key to LABEL; true }
                    else e.key == Key.DirectionRight // nothing to the right; no jump to another row
                }
                .tvItem(onFocus = { g.pending = null; app.focusCh = ch; app.focusProg = null; app.tvZone = Zone.GRID }, onMenu = { app.channelMenu(ch) }, requester = noDataFr) { app.enterChannel(ch) }.padding(6.dp)) { T("No guide data", 12, Theme.dim) }
            for (p in vis) {
                val bw = GuideWindow.block(p.start, p.stop, winStart, dpm) ?: continue
                // keyed by start time: the focused block keeps its focus when the window moves
                key(p.start) { GuideBlock(app, g, ch, p, bw.first, bw.second, now, blockFr.getOrPut(p.start) { FocusRequester() }) }
            }
        }
    }
}

@Composable
private fun GuideBlock(app: AppState, g: GuideCtl, ch: Channel, p: Programme, x: Float, w: Float, now: Long, fr: FocusRequester) {
    val st = ProgTime.classify(ch, p.start, p.stop, now)
    val rec = app.recorder.scheduledFor(ch, p) != null || (st == ProgState.NOW && app.recorder.active(ch) != null)
    Box(Modifier.offset(x.dp, 0.dp).width(w.dp).fillMaxHeight().padding(vertical = 1.dp)
        .background(if (st == ProgState.NOW) Theme.surface2 else Theme.surface, RoundedCornerShape(4.dp))
        .onKeyEvent { e ->
            // Left / Right stay in the row (shared rules); Up / Down go to the spatially nearest block (Home)
            if (e.type != KeyEventType.KeyDown) false
            else if (e.key == Key.DirectionLeft) { g.left(ch, p); true }
            else if (e.key == Key.DirectionRight) { g.right(ch, p); true }
            else false
        }
        .tvItem(onFocus = { g.pending = null; app.focusCh = ch; app.focusProg = p; app.tvZone = Zone.GRID; app.favAction = { app.toggleList(ch.key) } }, onMenu = { app.programmeMenu(ch, p) }, requester = fr) { app.guidePlay(ch, p) }
        .padding(horizontal = 6.dp, vertical = 3.dp)) {
        Column {
            T((if (rec) "● " else "") + (if (app.reminderFor(ch, p) != null) "⏰ " else "") + p.title + if (st == ProgState.PAST_CATCHUP) " ⟲" else "", 12,
                if (st == ProgState.PAST) Theme.dim else Theme.text)
            T(TimeFmt.time(p.start) + (EpisodeNum.line(p.ep, p.sub)?.let { "  $it" } ?: ""), 11, Theme.dim)
        }
    }
}
