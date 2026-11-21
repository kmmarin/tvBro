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
import androidx.compose.ui.draw.drawBehind
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import io.github.kmmarin.tvbro.*
import io.github.kmmarin.tvbro.R
import io.github.kmmarin.tvbro.data.Channel
import io.github.kmmarin.tvbro.data.DbEpg
import io.github.kmmarin.tvbro.data.Net
import io.github.kmmarin.tvbro.data.Programme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TV_LABEL = 170.dp // compact: the time grid gets the width
private val ROW_H = 46.dp
/** Groups that fit in the column on a 540 dp high screen (where the column opens before it is measured). */
private const val GROUPS_PER_SCREEN = 12

/**
 * Spot of the live preview: the shared live player (LiveVideo) is drawn over it. It plays the chosen channel
 * (m.current, set with OK), never just the focused one (user 2026-11-11: moving around must not switch channels).
 */
@Composable
private fun Preview(m: AppModel, ch: Channel?, modifier: Modifier) {
    val exo = rememberLiveExo(m)
    // (the volume is set by LiveVideo for whichever surface shows: sound with the picture)
    LaunchedEffect(ch?.key, m.previewOn, m.streamsFull(ch?.sourceId), m.liveGen) {
        if (ch == null || !m.previewOn || m.streamsFull(ch?.sourceId)) return@LaunchedEffect // LiveVideo stops the player when hidden
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
        if (m.previewRetry == 0 || ch == null || m.screen != Screen.LIST || !m.previewOn || m.streamsFull(ch?.sourceId)) return@LaunchedEffect
        delay(minOf(2000L * m.previewRetry, 15_000L))
        if (m.liveKey != null) return@LaunchedEffect // something else started a stream meanwhile
        m.liveUrl = ch.url; m.liveKey = ch.key
        liveLoad(exo, ch.url)
    }
    Box(modifier.background(Color.Black).onGloballyPositioned { m.previewRect = it.boundsInRoot() }) {
        if (!m.previewOn) Text("Preview off", color = TextDim, modifier = Modifier.align(Alignment.Center))
        else if (m.streamsFull(ch?.sourceId)) Text("Preview paused: " + m.limitText(ch?.sourceId ?: ""), color = TextDim, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center).padding(8.dp))
    }
}


/** The start snapshot's guide cells (channel key -> programmes), shown by rows the database has not loaded yet. */
object SnapGuide { @Volatile var progs: Map<String, Array<Programme>> = emptyMap() }

/** Start timing (logcat tag tvbro-start): process start to the first frame with channel rows, and with guide cells. */
object StartLog {
    private var rows = false
    private var guide = false
    private fun at() = android.os.SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis()
    private fun frame(what: String) {
        val t = at()
        android.view.Choreographer.getInstance().postFrameCallback { android.util.Log.i("tvbro-start", "$what: composed at $t ms, drawn by ${at()} ms after process start") }
    }
    fun rows(n: Int, snap: Boolean) { if (!rows) { rows = true; frame("first channel rows ($n, ${if (snap) "snapshot" else "loaded"})") } }
    fun guide(snap: Boolean) { if (!guide) { guide = true; frame("first guide cells (${if (snap) "snapshot" else "database"})") } }
}

/** Rows of each neighbouring group whose guide is loaded ahead (a screen's worth). */
private const val NEIGHBOUR_ROWS = 12

/** Each group's rows, kept while the channel list and settings stay the same (filtering 15 800 channels per group). */
private val neighbourRows = object {
    private var key: Any? = null
    private val map = HashMap<String, List<Channel>>()
    @Synchronized fun get(shown: List<Channel>, g: String, prefs: io.github.kmmarin.tvbro.data.Prefs, favs: Set<String>): List<Channel> {
        val k = Triple(System.identityHashCode(shown), prefs, favs)
        if (k != key) { key = k; map.clear() }
        return map.getOrPut(g) { io.github.kmmarin.tvbro.groupRows(shown, g, prefs, favs) }
    }
}

/** Rows loaded beyond the visible ones, above and below. */
private const val PREFETCH = 4
/** Guide time loaded beyond the visible window on both sides (seconds). */
private const val MARGIN = 1800L

/** Array state that only changes (and recomposes its readers) when the programmes differ. */
private val SameProgrammes = object : SnapshotMutationPolicy<Array<Programme>> {
    override fun equivalent(a: Array<Programme>, b: Array<Programme>) = a === b || a.contentEquals(b)
}

/**
 * [ch]'s programmes in [from, to) for one row: from the store's cache, updated when that channel's data arrives (only
 * this caller recomposes). Never loads by itself: the screen says what to load ([DbEpg.want]).
 */
@Composable
internal fun rowProgrammes(epg: io.github.kmmarin.tvbro.data.EpgIndex, ch: Channel, from: Long, to: Long): Array<Programme> {
    if (epg !is DbEpg) return epg.programmes(ch)
    // (until the database has this row: the start snapshot's cells, so the guide never blanks while it opens)
    val fromSnap = remember(epg, ch.key) { booleanArrayOf(false) }
    val st = remember(epg, ch.key) {
        val c = epg.peek(ch, from, to)
        val s = if (c.isEmpty()) SnapGuide.progs[ch.key] else null
        fromSnap[0] = s != null
        mutableStateOf(s ?: c, SameProgrammes)
    }
    DisposableEffect(epg, ch.key, from, to) {
        // New data is merged into what the row shows, never replaces it: dropping programmes from the front of the
        // array (the window moving into the next chunk) made Compose recreate every block of the row, the focused one
        // included, and focus fell to the side menu. A row only gains programmes while it is on screen (at most the
        // 30 h the window can move), and starts again from the cache when it comes back.
        val l = {
            val fresh = epg.peek(ch, from, to)
            // the snapshot's cells give way to the database's (same programmes: the blocks, keyed by start, stay)
            if (fromSnap[0] && (fresh.isNotEmpty() || epg.has(ch, from, to))) { fromSnap[0] = false; st.value = fresh }
            else if (!fromSnap[0]) st.value = if (st.value.isEmpty()) fresh else io.github.kmmarin.tvbro.data.EpgIndex.tidy(st.value + fresh)
        }
        epg.listen(ch.key, l)
        l() // whatever arrived between composition and now
        onDispose { epg.unlisten(ch.key, l) }
    }
    return st.value
}

/** The TV screen: groups, channel rows and their guide. Focus moves scroll its lists the [FocusScroll] way. */
@Composable
fun TvSection(m: AppModel) {
    val now = rememberNow()
    val list = m.filtered
    // times follow Settings > General > Time format

    val t = { sec: Long -> io.github.kmmarin.tvbro.data.TimeFmt.time(sec) }
    val dateFmt = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    // The window opens at the current half hour, so nothing before it shows; Left past the start reveals earlier time.
    var winStart by remember { mutableLongStateOf(GuideLayout.initialStart(now)) }
    var follow by remember { mutableStateOf(true) } // keep up with the clock until the user moves the window
    var lastMove by remember { mutableLongStateOf(0L) }
    // Follow the clock; a window left in the past (catch-up, or the TV screen left open for hours) returns to now
    // after 3 idle minutes.
    LaunchedEffect(now) {
        if (follow || (winStart < GuideLayout.initialStart(now) && now - lastMove > 180)) { winStart = GuideLayout.initialStart(now); follow = true }
    }
    val winEnd = winStart + GuideLayout.WINDOW_MIN * 60L
    val shift = { d: Long -> winStart = GuideLayout.shifted(winStart, d, now); follow = winStart == GuideLayout.initialStart(now); lastMove = System.currentTimeMillis() / 1000 }
    // [past]: a catch-up programme, which may show the past (Left into it, as in TiviMate); others never pull it back
    val ensure = { p: Programme, past: Boolean ->
        val n = if (past) GuideLayout.reveal(p.start, p.stop, winStart) else GuideLayout.revealNoPast(p.start, p.stop, winStart, now)
        if (n != winStart) { winStart = n; follow = n == GuideLayout.initialStart(now); lastMove = System.currentTimeMillis() / 1000 }
    }
    // Right from a channel name: the programme on now (the window jumps back to now first when it was moved away).
    val nowFocus = remember { NowFocus() }
    val toNow = { ch: Channel, nowFr: SafeFocus ->
        val cur = GuideLayout.initialStart(System.currentTimeMillis() / 1000)
        if (winStart != cur) { winStart = cur; follow = true; nowFocus.orLabel = false; nowFocus.pending = ch.key; true }
        else nowFr.request()
    }
    // the selected group: where Left from the channels (and the first focus) lands, never just the nearest group,
    // since focusing a group switches the channel list to it
    val groupSel = remember { SafeFocus() }
    // (opens at the selected group: Compose's first focus enters the column there, see enterAt)
    val groupState = rememberLazyListState(FocusScroll.first(m.groupList.indexOf(m.group) + 1, 3, GROUPS_PER_SCREEN))
    LaunchedEffect(m.group, m.groupList) { // also after a move: the moved group stays in view
        val gi = m.groupList.indexOf(m.group) + 1 // +1: the "TV" title row
        if (gi > 0 && groupState.layoutInfo.visibleItemsInfo.none { it.index == gi }) groupState.reveal(gi, 3)
        // after a move from the group menu: focus back on the moved (selected) group
        if (m.groupMoved) { m.groupMoved = false; repeat(2) { delay(200); groupSel.request() } }
    }
    // channel key -> row (first one), for lookups without scanning the list
    val rowOf = remember(list) { HashMap<String, Int>(list.size * 2).also { mp -> list.forEachIndexed { i, c -> mp.putIfAbsent(c.key, i) } } }
    val uniqueKeys = rowOf.size == list.size
    // Right from the groups (and the side menu): the channel last focused in this group, else the playing channel when
    // it is in this group, else the first one. A derived state: rows read it, the screen itself does not recompose
    // when focus moves.
    val chFocus = remember { SafeFocus() }
    val target = remember(list, m.group) {
        derivedStateOf {
            (m.groupFocus[m.group]?.takeIf { it in rowOf }) ?: (m.focusCh?.key?.takeIf { it in rowOf })
                ?: (m.current?.key?.takeIf { it in rowOf }) ?: list.firstOrNull()?.key
        }
    }
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
    val listNow = rememberUpdatedState(list)
    val targetNow = rememberUpdatedState(target)
    val rowOfNow = rememberUpdatedState(rowOf)
    /**
     * Focus into the channel list at the target channel's programme on now (one press from the groups to the
     * programmes, as in TiviMate; it went to the channel name first), scrolled into view first when needed; the name
     * when the row has nothing to focus.
     */
    val enterChannels: () -> Unit = {
        val key = targetNow.value.value
        val idx = key?.let { rowOfNow.value[it] } ?: -1
        if (idx < 0) chFocus.request()
        else scope.launch {
            val li = listState.layoutInfo
            if (li.visibleItemsInfo.none { it.index == idx && it.offset >= 0 && it.offset + it.size <= li.viewportEndOffset }) runCatching { listState.reveal(idx, 2) }
            nowFocus.orLabel = true; nowFocus.pending = key
        }
    }
    /** Left from a channel: its own (selected) group, scrolled into view first when needed. */
    val toGroups: () -> Boolean = {
        if (!groupSel.request()) scope.launch {
            val gi = m.groupList.indexOf(m.group) + 1
            if (gi > 0) runCatching { groupState.reveal(gi, 3) }
            repeat(15) { if (groupSel.request()) return@launch; delay(40) }
        }
        true
    }
    // Up/Down in the groups: the focus box moves at once (that frame draws only the group column), the channel list
    // follows in the next frame, or when the key settles while it is held (switching the whole list in the key's own
    // frame took 120-190 ms on the Fire TV Stick before the focus visibly moved; for every group flown past, a third
    // of a second).
    var pendingGroup by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingGroup) {
        val g = pendingGroup ?: return@LaunchedEffect
        withFrameNanos { } // (the frame with the moved focus box goes out first)
        while (KeyHold.busy()) delay(30)
        m.group = g; pendingGroup = null
    }
    DisposableEffect(listState) { val v = { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }; m.tvView = v; onDispose { if (m.tvView === v) m.tvView = null } }
    DisposableEffect(chFocus) { m.railRight = chFocus; onDispose { if (m.railRight === chFocus) m.railRight = null } }
    // Back in the guide (as in TiviMate): a window moved into the past or future comes back to now, on the focused
    // channel's programme on now; at now, the groups (Left goes into the past on catch-up channels, so the groups are
    // otherwise far from them); from the groups, the side menu (HomeBack)
    val gridFocus = remember { booleanArrayOf(false) }
    DisposableEffect(Unit) {
        val back = {
            val cur = GuideLayout.initialStart(System.currentTimeMillis() / 1000)
            if (gridFocus[0] && winStart != cur) { winStart = cur; follow = true; nowFocus.orLabel = true; nowFocus.pending = m.focusCh?.key; true }
            else if (gridFocus[0]) toGroups()
            else false
        }
        m.tvBack = back
        onDispose { if (m.tvBack === back) m.tvBack = null }
    }
    // a group sent to the top / bottom or hidden: focus goes to the group now in its place (see AppModel.focusGroupAt)
    DisposableEffect(groupSel) { val f = { groupSel.request() }; m.focusGroupSel = f; onDispose { if (m.focusGroupSel === f) m.focusGroupSel = null } }
    LaunchedEffect(m.channels.isNotEmpty()) {
        if (m.channels.isEmpty()) return@LaunchedEffect
        val k = m.focusCh?.key
        val idx = if (k == null) -1 else m.filtered.indexOfFirst { it.key == k }
        if (idx >= 0) {
            val vis = listState.layoutInfo.visibleItemsInfo
            if (vis.none { it.index == idx && it.offset >= 0 }) listState.reveal(idx, 2)
            // its programme on now, as Right from the groups does (Left, OK then plays the previous programme on a
            // catch-up channel); the channel name when the row has nothing on now
            nowFocus.orLabel = true; nowFocus.pending = k
            // (in the guide: at start Compose may have put focus on the side menu meanwhile)
            repeat(20) { delay(50); if (nowFocus.pending == null && gridFocus[0]) return@LaunchedEffect }
            nowFocus.pending = null
            repeat(20) { if (chFocus.request()) return@LaunchedEffect; delay(50) }
        }
        delay(250); groupSel.request()
    }
    LaunchedEffect(m.group, m.groupReady) { if (m.groupReady) m.store.lastGroup = m.group }
    // the number column fits the longest number in the list (5-6 digit numbers were cut: "10904" showed as "1090")
    if (list.isNotEmpty()) SideEffect { StartLog.rows(list.size, m.snapActive) }
    val numW = if (m.showNumbers) remember(list) { numberWidth(list.maxOfOrNull { it.number } ?: 0) } else 0.dp
    // What to load: the visible rows (the focused one and its neighbours first, then the rest, then a few beyond) for
    // the visible window plus a margin. Re-sent whenever the rows, focus or window change; while a key is held nothing
    // is loaded (rows flying past only show their names), and the queue is replaced, so rows that scrolled past or the
    // previous group are never loaded after the fact.
    val epg = m.epg
    if (epg is DbEpg) LaunchedEffect(epg, list) {
        snapshotFlow {
            val vis = listState.layoutInfo.visibleItemsInfo
            val a = vis.firstOrNull()?.index ?: listState.firstVisibleItemIndex
            val b = vis.lastOrNull()?.index ?: a
            longArrayOf(a.toLong(), b.toLong(), (m.focusCh?.key?.let { rowOf[it] } ?: -1).toLong(), winStart).toList()
        }.distinctUntilChanged().collectLatest { (a, b, f, ws) ->
            while (KeyHold.busy()) { epg.hold(); delay(40) }
            val order = ArrayList<Channel>(((b - a) + 2 * PREFETCH + 3).toInt().coerceAtLeast(4))
            fun add(i: Long) { list.getOrNull(i.toInt())?.let { order += it } }
            if (f in (a - PREFETCH)..(b + PREFETCH)) { add(f); add(f - 1); add(f + 1) }
            for (i in a..b) add(i)
            for (i in 1..PREFETCH) { add(b + i); add(a - i) }
            epg.want(order, ws - MARGIN, ws + GuideLayout.WINDOW_MIN * 60L + MARGIN)
            // Then, once focus rests: the visible rows of the two groups above and below (their remembered scroll), at
            // low priority, so moving to a neighbouring group shows its guide at once (DbEpg.prefetch).
            delay(400)
            while (KeyHold.busy()) delay(40)
            if (Lat.noPrefetch) return@collectLatest
            val gl = m.groupList; val gi = gl.indexOf(m.group)
            if (gi < 0) return@collectLatest
            val near = listOf(gi + 1, gi - 1, gi + 2, gi - 2).mapNotNull { gl.getOrNull(it) }.filter { it !in m.prefs.locked || it in m.unlocked }
            val shown = m.shown; val prefs = m.prefs; val favs = m.favourites
            val from = near.map { m.tvScroll[it]?.first ?: 0 }
            val rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                near.zip(from).flatMap { (g, s) -> neighbourRows.get(shown, g, prefs, favs).let { l -> l.subList(s.coerceIn(0, l.size), (s + NEIGHBOUR_ROWS).coerceAtMost(l.size)) } }
            }
            epg.prefetch(rows, ws - MARGIN, ws + GuideLayout.WINDOW_MIN * 60L + MARGIN)
        }
    }
    // Focus must never fall out of the guide: when the focused block goes away (a placeholder replaced by arriving
    // programmes), the focused row puts it back within two frames, before the app-wide rescue (side menu) would.
    val focusBack = remember { HashMap<String, () -> Boolean>() }
    DisposableEffect(focusBack) {
        val back = { m.section == Section.TV && m.screen == Screen.LIST && m.focusCh?.key?.let { focusBack[it] }?.invoke() == true }
        FocusMemory.contentBack = back
        onDispose { if (FocusMemory.contentBack === back) FocusMemory.contentBack = null }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { FocusMemory.anyFocus }.collect { any ->
            if (any) return@collect
            withFrameNanos { }; withFrameNanos { }
            if (Lat.enabled) android.util.Log.i("tvbro-lat", "focus lost: still=${!FocusMemory.anyFocus} row=${m.focusCh?.key?.let { it in focusBack }}")
            // (not while the screen is on its way out: another section is taking focus)
            if (!FocusMemory.anyFocus && !FocusMemory.restoring && m.section == Section.TV && m.screen == Screen.LIST) m.focusCh?.key?.let { focusBack[it] }?.invoke()
        }
    }
    if (Lat.enabled) {
        SideEffect { if (Lat.lastList !== list) { Lat.lastList = list; Lat.rows() } }
        DisposableEffect(listState) { Lat.visible = { Lat.offscreen(listState) }; onDispose { Lat.visible = null } }
    }

    Row(Modifier.fillMaxSize()) {
        // entered at the selected group: Compose's first focus (the window gaining focus) took the top group,
        // Favourites, and focusing a group selects it (the restored screen switched to Favourites at start)
        LazyColumn(Modifier.width(190.dp).fillMaxHeight().padding(8.dp).enterAt(groupSel), state = groupState) {
            item { SectionTitle("TV", Modifier.padding(start = 10.dp, bottom = 6.dp)) }
            items(m.groupList, key = { it }) { g -> // keyed: a moved group keeps its focus
                val sel = g == m.group
                Text((if (sel) "> " else "  ") + m.groupLabel(g), color = if (sel) Accent else TextMain, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().safeFocus(if (sel) groupSel else null)
                        .onPreviewKeyEvent { e ->
                            // Right: always into this group's channels (never stuck in the column)
                            if (e.type != KeyEventType.KeyDown) false
                            else when (e.key) {
                                Key.DirectionRight -> {
                                    if (pendingGroup == g) { m.group = g; pendingGroup = null; scope.launch { delay(60); if (listNow.value.isNotEmpty()) enterChannels() } }
                                    else if (list.isNotEmpty()) enterChannels()
                                    true
                                }
                                Key.DirectionLeft -> m.railSel?.request() == true // the side menu at the TV icon
                                else -> false
                            }
                        }
                        .onFocusChanged {
                            if (it.isFocused) {
                                Lat.focus("group"); m.favAction = { m.toggleFavGroup(g) }
                                // (only when the user moved here: Compose's first focus or a rescue landing on a group
                                // switched the screen to it, e.g. to Favourites at start)
                                if ((g !in m.prefs.locked || g in m.unlocked) && (g == m.group || FocusMemory.upDownJustNow())) { if (g == m.group) pendingGroup = null else pendingGroup = g }
                            }
                        }
                        .tvFocus(onLongClick = { m.openGroupMenu(g) }) { m.selectGroup(g) }.padding(10.dp))
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            // one time scale for the ruler and every row (each row measuring itself cost a subcomposition per row)
            val dpm = (maxWidth - TV_LABEL - 8.dp).value.coerceAtLeast(1f) / GuideLayout.WINDOW_MIN
            Column(Modifier.fillMaxSize()) {
                TvHeader(m, now, list, t)
                // time ruler on the same scale as the blocks
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.width(TV_LABEL).padding(start = 8.dp)) {
                        Text(dateFmt.format(Date(now * 1000)), color = TextDim, fontSize = 12.sp)
                        Text(t(now), color = TextMain, fontSize = 20.sp)
                    }
                    Box(Modifier.weight(1f).height(34.dp).clipToBounds()) {
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
                // Fast-forward / rewind (and Page Down / Up): a page of rows down / up, focus on that row's programme on
                // now (one key per screenful, as in TiviMate, instead of holding Down)
                Box(Modifier.weight(1f).fillMaxWidth().padding(end = 8.dp).onPreviewKeyEvent { e ->
                    val dir = if (e.type != KeyEventType.KeyDown) 0 else when (e.key) {
                        Key.MediaFastForward, Key.PageDown -> 1
                        Key.MediaRewind, Key.PageUp -> -1
                        else -> 0
                    }
                    val i = m.focusCh?.key?.let { rowOf[it] }
                    if (dir == 0 || i == null || list.isEmpty()) return@onPreviewKeyEvent false
                    val li = listState.layoutInfo
                    val per = li.visibleItemsInfo.count { it.offset >= 0 && it.offset + it.size <= li.viewportEndOffset }.coerceAtLeast(1)
                    val to = (i + dir * per).coerceIn(0, list.size - 1)
                    if (to != i) scope.launch { listState.reveal(to); nowFocus.orLabel = true; nowFocus.pending = list[to].key }
                    true
                }) {
                    LazyColumn(Modifier.fillMaxSize().onFocusChanged { gridFocus[0] = it.hasFocus }, state = listState) {
                        itemsIndexed(list, key = if (uniqueKeys) { _, c -> c.key } else null, contentType = { _, _ -> 0 }) { i, ch ->
                            TvRow(m, ch, i, now, winStart, winEnd, dpm, shift, ensure, t, chFocus, target, nowFocus, toNow, toGroups, numW, focusBack)
                        }
                    }
                }
            }
        }
    }
}

/** Preview on the left; the programme under the cursor (or the focused channel's current one) on the right. */
@Composable
private fun TvHeader(m: AppModel, now: Long, list: List<Channel>, t: (Long) -> String) {
    // The header follows focus one frame later (and only once a held key settles): the frame of a key press only
    // moves the focus box; the header's text layout comes in the next one.
    var shown by remember { mutableStateOf<Pair<Channel?, Programme?>>(m.focusCh to m.focusProg) }
    FollowFocus(m) { shown = it }
    val focus = shown.first ?: m.current ?: list.firstOrNull()
    val focusProg = shown.second
    Row(Modifier.fillMaxWidth().height(180.dp).padding(8.dp)) {
        Preview(m, m.current, Modifier.size(280.dp, 158.dp))
        Spacer(Modifier.width(14.dp))
        // (the focused row's data, from the cache: flying past rows never loads anything for the header)
        val cached = if (focus != null) rowProgrammes(m.epg, focus, now, now + 1) else emptyArray()
        val prog = focusProg ?: cached.firstOrNull { it.start <= now && it.stop > now }
        Column(Modifier.weight(1f)) {
            Text(focus?.let { (if (m.showNumbers) "${it.number}  " else "") + it.name } ?: "No channel", color = Accent, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(prog?.title ?: "No guide data", color = TextMain, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            if (prog != null) {
                io.github.kmmarin.tvbro.data.EpisodeNum.line(prog.ep, prog.sub)?.let { Text(it, color = TextMain.copy(alpha = 0.85f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                val live = prog.start <= now && prog.stop > now
                Text(t(prog.start) + " - " + t(prog.stop) + if (live) "   ${(prog.stop - now) / 60} min left" else if (prog.stop <= now) "   (finished)" else "", color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 3.dp))
                if (live) ProgressLine(io.github.kmmarin.tvbro.data.ProgTime.progress(prog.start, prog.stop, now), Modifier.padding(top = 5.dp))
                prog.desc?.let { Text(it, color = TextDim, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp)) }
            }
        }
        Column(Modifier.width(150.dp).padding(start = 8.dp), horizontalAlignment = Alignment.End) {
            focus?.let { Text(m.groupLabel(it.group), color = TextDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            if (focus != null && focus.key in m.favourites) Text("* My list", color = Accent, fontSize = 13.sp)
        }
    }
}

/** Reports focus changes ([AppModel.focusCh], [AppModel.focusProg]) after the next frame, or when a held key settles. */
@Composable
private fun FollowFocus(m: AppModel, set: (Pair<Channel?, Programme?>) -> Unit) {
    val ch = m.focusCh
    val p = m.focusProg
    LaunchedEffect(ch, p) {
        withFrameNanos { }
        while (KeyHold.busy()) delay(40)
        set(ch to p)
    }
}

private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
private val IdleBg = Color(0xFF12171E)
private val RowFocusBg = Color(0xFF1B222C)

@Composable
private fun TvRow(
    m: AppModel, ch: Channel, idx: Int, now: Long, winStart: Long, winEnd: Long, dpm: Float, shift: (Long) -> Unit, ensure: (Programme, Boolean) -> Unit,
    t: (Long) -> String, chFocus: SafeFocus, target: State<String?>, nowFocus: NowFocus, toNow: (Channel, SafeFocus) -> Boolean, toGroups: () -> Boolean,
    numW: androidx.compose.ui.unit.Dp, focusBack: HashMap<String, () -> Boolean>,
) {
    // per-row derived states: a focus move recomposes (or redraws) only the rows it leaves and enters
    val rowFocused = remember(ch.key) { derivedStateOf { m.focusCh?.key == ch.key } }
    val nowFr = remember { SafeFocus() }
    // this row's channel name: where focus goes back to when the row has nothing else (Left from the first block in
    // the window goes to the groups)
    val labelFr = remember { FocusRequester() }
    val toLabel = { runCatching { labelFr.requestFocus() }; true }
    val rowFocus = { p: Programme? ->
        Lat.focus(if (p == null) "ch" else "prog"); Lat.focusIdx = idx
        m.focusCh = ch; m.focusProg = p; m.favAction = { m.toggleFav(ch) }; m.groupFocus[m.group] = ch.key
    }
    LaunchedEffect(nowFocus.pending, nowFr.isAttached) {
        if (nowFocus.pending != ch.key) return@LaunchedEffect
        if (nowFr.request()) { nowFocus.pending = null; return@LaunchedEffect }
        // a page jump onto a row with nothing on now (no guide data): its channel name
        if (nowFocus.orLabel) { delay(250); if (nowFocus.pending == ch.key && !nowFr.request()) runCatching { labelFr.requestFocus() }; nowFocus.pending = null }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        TvLabel(m, ch, numW, labelFr, chFocus, target, { toNow(ch, nowFr) }, toGroups, { rowFocus(null) })
        Box(Modifier.weight(1f).height(ROW_H).clipToBounds()) {
            // the window plus a margin, in whole cache chunks (the row re-reads only when the chunks change)
            val ext = remember(winStart, winEnd) { DbEpg.extent(winStart - MARGIN, winEnd + MARGIN) }
            val progs = rowProgrammes(m.epg, ch, ext.first, ext.second)
            if (progs.isNotEmpty() && Lat.enabled) SideEffect { Lat.guide() }
            if (progs.isNotEmpty()) SideEffect { StartLog.guide(m.epg !is DbEpg || SnapGuide.progs[ch.key] === progs) }
            val total = dpm * GuideLayout.WINDOW_MIN
            // the row highlight is read while drawing: moving focus between rows redraws, never recomposes, them
            val idle = Modifier.drawBehind { drawRect(if (rowFocused.value) RowFocusBg else IdleBg) }
            val progsNow = rememberUpdatedState(progs)
            val cells = remember { HashMap<Long, FocusRequester>() }
            // Left into the past (catch-up): the programme to focus once the window has moved back and its block exists
            var pastFocus by remember { mutableStateOf<Long?>(null) }
            LaunchedEffect(pastFocus, winStart) {
                val k = pastFocus ?: return@LaunchedEffect
                repeat(8) {
                    withFrameNanos { }
                    if (cells[k]?.let { runCatching { it.requestFocus() }.isSuccess } == true) { pastFocus = null; return@LaunchedEffect }
                }
                pastFocus = null
            }
            val catchupOk = { p: Programme -> ch.catchup != null && p.stop <= now && io.github.kmmarin.tvbro.data.CatchupUrl.supports(ch, p.start, now) }
            // "No guide data" blocks take focus too (OK: the channel), so Up/Down never skip a row or jump far away.
            // [from]: the guide time where the block starts.
            val noData: @Composable (Float, Float, SafeFocus?, Long) -> Unit = { x, w, fr, from ->
                // Programmes arriving replace the block: when it held focus, focus goes to the programme now at that
                // time right after the change is applied (the new blocks register in the same apply, after this
                // disposal), before the next frame. Two frames later the side menu had already taken it.
                // (the block's own focus flag may already be cleared here: "focus was on this row, on no programme" and,
                // once the change is applied, "focus is nowhere" instead; the channel name, also "no programme", is not
                // affected since it keeps its focus)
                val fromNow = rememberUpdatedState(from)
                DisposableEffect(Unit) {
                    onDispose {
                        val from = fromNow.value
                        if (m.focusCh?.key != ch.key || m.focusProg != null) return@onDispose
                        mainHandler.post {
                            if (FocusMemory.anyFocus || m.section != Section.TV || m.screen != Screen.LIST) return@post
                            val p = progsNow.value.firstOrNull { it.stop > from }
                            val ok = p != null && cells[p.start]?.let { runCatching { it.requestFocus() }.isSuccess } == true
                            if (!ok) focusBack[ch.key]?.invoke()
                        }
                    }
                }
                Box(Modifier.offset(x.dp, 0.dp).width(w.dp).height(ROW_H).safeFocus(fr).then(idle)
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) false
                        else if (e.key == Key.DirectionLeft && x < 1f) toGroups()
                        // Right at the window's end: the window moves on, as from a programme (held Right stopped here)
                        else if (e.key == Key.DirectionRight && x + w >= dpm * GuideLayout.WINDOW_MIN - 4f) { shift(1800); true }
                        else false
                    }
                    .onFocusChanged { if (it.isFocused) rowFocus(null) }
                    .tvFocus(onLongClick = { m.openChannelMenu(ch) }) { m.previewOrPlay(ch) }
                    .padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                    Text("No guide data", color = TextDim, fontSize = 13.sp, maxLines = 1)
                }
            }
            val inWindow = progs.any { it.stop > winStart && it.start < winEnd }
            // where focus goes back to in this row when the focused block went away (the "No guide data" placeholder
            // replaced by the programmes that arrived): the same programme, else the one on now, else the name
            DisposableEffect(ch.key) {
                val back = {
                    val p = m.focusProg
                    val r = if (p != null && cells[p.start]?.let { runCatching { it.requestFocus() }.isSuccess } == true) "prog"
                        else if (nowFr.request()) "now" else { toLabel(); "label" }
                    if (Lat.enabled) android.util.Log.i("tvbro-lat", "focus back: $r")
                    true
                }
                focusBack[ch.key] = back
                onDispose { if (focusBack[ch.key] === back) focusBack.remove(ch.key) }
            }
            var cursor = winStart
            if (inWindow) {
                // the programme that Right from the channel name lands on: on now, else the next one in the window
                val nowP = progs.firstOrNull { it.stop > now && it.stop > winStart && it.start < winEnd }
                for (p in progs) {
                    val b = GuideLayout.block(p.start, p.stop, winStart, winEnd, dpm) ?: continue
                    val s = maxOf(p.start, winStart)
                    if (s > cursor + 120) GuideLayout.block(cursor, s, winStart, winEnd, dpm)?.let { g ->
                        if (g.second > 60f) key("gap$cursor") { noData(g.first, g.second, null, cursor) }
                    }
                    key(p.start) {
                        // each block registers its requester: Left/Right inside the window go straight to the
                        // neighbouring block (Compose's geometric search sometimes left the row for the side menu)
                        val cellFr = remember { FocusRequester() }
                        DisposableEffect(p.start) { cells[p.start] = cellFr; onDispose { if (cells[p.start] === cellFr) cells.remove(p.start) } }
                        val live = p.start <= now && p.stop > now
                        val past = p.stop <= now
                        Column(
                            Modifier.offset(b.first.dp, 0.dp).width(b.second.dp).height(ROW_H).clipToBounds()
                                .safeFocus(if (p === nowP) nowFr else null).focusRequester(cellFr)
                                .then(if (live) Modifier.background(Surface) else idle)
                                .onPreviewKeyEvent { e ->
                                    if (e.type != KeyEventType.KeyDown) false
                                    else when (e.key) {
                                        // Right at the window's end: the window moves on (also when the next programme is
                                        // not loaded yet, i.e. starts beyond the loaded margin)
                                        Key.DirectionRight -> {
                                            val nx = progs.firstOrNull { it.start >= p.stop }
                                            if ((nx != null && nx.start >= winEnd) || (nx == null && p.stop >= winEnd)) { shift(1800); true }
                                            else nx?.takeIf { it.start - p.stop <= 120 }?.let { n -> cells[n.start]?.let { runCatching { it.requestFocus() }.isSuccess } } ?: false
                                        }
                                        // Left from the first block in the window: the window moves back only when that stays in the present,
                                        // else focus goes to the group list (the past is never shown; one press to the groups and two to
                                        // the side menu, as in TiviMate: it went to the channel name first)
                                        Key.DirectionLeft -> {
                                            val pv = progs.lastOrNull { it.stop <= p.start }
                                            // a catch-up channel: Left goes into the past programme by programme (the window
                                            // follows), so a past programme plays with Left, OK: two presses, as in TiviMate
                                            if (pv != null && p.start - pv.stop <= 120 && catchupOk(pv)) {
                                                if (pv.stop <= winStart) { ensure(pv, true); pastFocus = pv.start; return@onPreviewKeyEvent true }
                                                return@onPreviewKeyEvent cells[pv.start]?.let { runCatching { it.requestFocus() }.isSuccess } ?: false
                                            }
                                            // (only when that brings the previous programme into view: a long programme that started in
                                            // the past went on scrolling the window back 30 min per press while focus stayed on it)
                                            if (pv != null && pv.stop <= winStart && pv.stop > GuideLayout.initialStart(now) && GuideLayout.shifted(winStart, -1800, now) != winStart) { shift(-1800); true }
                                            else if (p.start <= winStart + 120 || pv == null || pv.stop <= winStart) {
                                                // the groups, with the window back at now (where Right from the groups goes again)
                                                if (winStart != GuideLayout.initialStart(now)) shift(-48 * 3600L)
                                                toGroups()
                                            } else if (p.start - pv.stop <= 120) cells[pv.start]?.let { runCatching { it.requestFocus() }.isSuccess } ?: false else false
                                        }
                                        else -> false
                                    }
                                }
                                .onFocusChanged { if (it.isFocused) { rowFocus(p); ensure(p, catchupOk(p)) } }
                                .tvFocus(onLongClick = { m.progMenu = ch to p }) { if (p.stop > now) m.previewOrPlay(ch) else m.guidePlay(ch, p) }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text((if (p.start < winStart) "< " else "") + p.title, color = if (past) TextDim else TextMain, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Clip, softWrap = false)
                            // on now: its progress in place of the start time (the header shows the times)
                            if (live) GuideNowBar(p.start, p.stop, now)
                            else if (b.second > 90f) Text(t(p.start), color = TextDim, fontSize = 11.sp, maxLines = 1, softWrap = false)
                        }
                    }
                    cursor = minOf(p.stop, winEnd)
                }
            }
            // The window's end without programmes, or all of it: one call site, so a focused placeholder stays when the
            // row switches between the two (held Right moving the window past the loaded data and back into it).
            val endGap = if (!inWindow) 0f to (total - 2f)
                else if (winEnd > cursor + 120) GuideLayout.block(cursor, winEnd, winStart, winEnd, dpm)?.takeIf { it.second > 60f } else null
            if (endGap != null) key("gapEnd") { noData(endGap.first, endGap.second, if (!inWindow) nowFr else null, if (!inWindow) winStart else cursor) }
        }
    }
}

/**
 * A row's channel name (own scope: when it becomes or stops being the place Right from the groups lands, only the
 * name recomposes, not the row's programmes).
 */
@Composable
private fun TvLabel(
    m: AppModel, ch: Channel, numW: androidx.compose.ui.unit.Dp, labelFr: FocusRequester, chFocus: SafeFocus, target: State<String?>,
    onRight: () -> Unit, toGroups: () -> Boolean, onFocus: () -> Unit,
) {
    val playing by remember(ch.key) { derivedStateOf { m.current?.key == ch.key } }
    val isTarget by remember(ch.key, target) { derivedStateOf { target.value == ch.key } }
    Row(
        Modifier.safeFocus(if (isTarget) chFocus else null).focusRequester(labelFr).width(TV_LABEL).height(ROW_H).padding(end = 4.dp)
            // Right: this row's programme on now (never a programme in another row)
            // Left: this channel's group (never another one)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) false
                else when (e.key) { Key.DirectionRight -> { onRight(); true }; Key.DirectionLeft -> toGroups(); else -> false }
            }
            .onFocusChanged { if (it.isFocused) onFocus() }
            .tvFocus(onLongClick = { m.openChannelMenu(ch) }) { m.previewOrPlay(ch) }.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (numW > 0.dp) Text(ch.number.toString(), color = TextDim, fontSize = 12.sp, modifier = Modifier.width(numW), maxLines = 1, softWrap = false)
        Logo(ch.logo, ch.name, 26.dp)
        Spacer(Modifier.width(5.dp))
        Text((if (playing) "> " else "") + (if (ch.key in m.favourites) "* " else "") + ch.name, color = if (playing) Accent else TextMain, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (ch.catchup != null) Image(painterResource(R.drawable.ic_catchup), null, Modifier.size(14.dp), colorFilter = ColorFilter.tint(TextDim))
    }
}

/** Width of a 12 sp channel-number column for numbers up to [max] (at least 30 dp). */
fun numberWidth(max: Int): androidx.compose.ui.unit.Dp = (max.coerceAtLeast(1).toString().length * 8 + 4).coerceAtLeast(30).dp

/** Channel whose on-now programme should take focus once it is composed (after the window moved back to now). */
class NowFocus {
    var pending by mutableStateOf<String?>(null)
    /** (a page jump) no programme on now in the row: its channel name instead */
    var orLabel = false
}
