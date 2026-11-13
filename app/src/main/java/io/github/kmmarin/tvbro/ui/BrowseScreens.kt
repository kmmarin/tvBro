// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kmmarin.tvbro.*
import io.github.kmmarin.tvbro.R
import io.github.kmmarin.tvbro.data.DlRepo
import io.github.kmmarin.tvbro.data.Episode
import io.github.kmmarin.tvbro.data.RecRepo
import io.github.kmmarin.tvbro.data.VodDetail
import io.github.kmmarin.tvbro.data.VodFmt
import io.github.kmmarin.tvbro.data.VodItem
import io.github.kmmarin.tvbro.data.Watch
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- home + navigation rail

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(m: AppModel) {
    val railSel = remember { SafeFocus() }
    DisposableEffect(railSel) { m.railSel = railSel; onDispose { if (m.railSel === railSel) m.railSel = null } }
    var railFocused by remember { mutableStateOf(false) }
    // Back: from another section to the TV screen; on the TV screen to the side menu; from the side menu it leaves
    // the app (the TV screen is home). Screens and layers above handle Back first.
    // (off while a menu, dialog or programme pop-up is open: Back closes that and focus returns to the item)
    BackHandler(enabled = m.screen == Screen.LIST && m.menu == null && m.prompt == null && m.progMenu == null &&
        (m.section != Section.TV || !railFocused)) {
        if (m.section != Section.TV) m.section = Section.TV else railSel.request()
    }
    Box(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxSize()) {
            Spacer(Modifier.width(64.dp))
            // no focusRestorer here: its enter redirect swallowed requestFocus() between the section's own columns (Right
            // from the TV groups did nothing); Right from the side menu returns to the remembered item explicitly
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (m.section) {
                    Section.TV -> TvSection(m)
                    Section.SEARCH -> SearchScreen(m) { m.section = Section.TV }
                    Section.MOVIES, Section.SHOWS -> BrowseScreen(m)
                    Section.RECORDINGS -> RecordingsScreen(m)
                    Section.MYLIST -> MyListScreen(m)
                }
            }
        }
        NavRail(m, railSel) { railFocused = it }
        if (m.screen == Screen.SETTINGS) SettingsScreen(m)
    }
}

@Composable
private fun RailItem(icon: Int, label: String, selected: Boolean, expanded: Boolean, right: () -> Boolean, sel: SafeFocus? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().safeFocus(if (selected) sel else null)
            // Right: back to where the user was in the section (explicit: a focusProperties redirect alone was not reliable)
            .onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && right() }.padding(horizontal = 8.dp, vertical = 2.dp).tvFocus(onClick = onClick).padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(icon), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(if (selected) Accent else TextMain))
        if (expanded) {
            Spacer(Modifier.width(14.dp))
            Text(label, color = if (selected) Accent else TextMain, fontSize = 17.sp, maxLines = 1, softWrap = false)
        }
    }
}

/** Icons only while browsing; expands with labels over the content while it holds focus (content does not re-layout). */
@Composable
fun NavRail(m: AppModel, sel: SafeFocus, onFocus: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val w by animateDpAsState(if (expanded) 210.dp else 64.dp, label = "rail")
    Column(
        // entered at the current section's icon (Left from anywhere in the content), not the nearest icon
        Modifier.fillMaxHeight().width(w).background(if (expanded) Color(0xFF10151C) else Bg)
            .onFocusChanged { expanded = it.hasFocus; onFocus(it.hasFocus) }.enterAt(sel).padding(vertical = 16.dp),
    ) { CompositionLocalProvider(LocalInRail provides true) {
        val s = m.section
        // Right goes back to where the user was: the item Left (or Back) came from, a group as well as a channel (user
        // 2026-11-12: Left from a group to the side menu, Right went to a channel instead of that group); else the TV
        // screen's focused channel. Never to whatever sits next to the icon (false: normal focus search, e.g. a section
        // that remembers nothing yet)
        val right = { FocusMemory.restoreContent() || m.railRight?.request() == true }
        RailItem(R.drawable.ic_nav_search, "Search", s == Section.SEARCH, expanded, right, sel) { m.section = Section.SEARCH }
        RailItem(R.drawable.ic_nav_tv, "TV", s == Section.TV, expanded, right, sel) { m.section = Section.TV }
        RailItem(R.drawable.ic_nav_movie, "Movies", s == Section.MOVIES, expanded, right, sel) { m.openVod(false) }
        RailItem(R.drawable.ic_nav_shows, "Shows", s == Section.SHOWS, expanded, right, sel) { m.openVod(true) }
        // OK on a section moves focus into it, like TV, Movies and Search do by themselves
        val scope = rememberCoroutineScope()
        val fm = androidx.compose.ui.platform.LocalFocusManager.current
        val enter = { scope.launch { delay(350); if (expanded) runCatching { fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Right) } } }
        RailItem(R.drawable.ic_nav_rec, "Recordings", s == Section.RECORDINGS, expanded, right, sel) { m.section = Section.RECORDINGS; enter() }
        RailItem(R.drawable.ic_nav_list, "My list", s == Section.MYLIST, expanded, right, sel) { m.refreshLists(); m.section = Section.MYLIST; enter() }
        Spacer(Modifier.weight(1f))
        if (expanded) Text("Tip: long-press OK on a channel, programme or title for its menu: Favourites, record, remind, watched...",
            color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        RailItem(R.drawable.ic_nav_settings, "Settings", false, expanded, right) { m.screen = Screen.SETTINGS }
    } }
}

/** A focusable card that scales up a little while focused (shared by My list and search result rows). */
@Composable
fun Card(m: AppModel, width: Dp, fav: (() -> Unit)?, onFocus: () -> Unit = {}, long: (() -> Unit)? = fav, mod: Modifier = Modifier, click: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var f by remember { mutableStateOf(false) }
    Column(
        mod.width(width).padding(4.dp)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) { m.favAction = fav; onFocus() } }
            .scale(if (f) 1.05f else 1f)
            .tvFocus(onLongClick = long, onClick = click).padding(5.dp),
        content = content,
    )
}

@Composable
fun ProgressLine(f: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).background(Color(0xFF2A3240))) { Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).height(4.dp).background(Accent)) }
}

/** Channel tile: logo, name and what is on now with a progress line. */
@Composable
fun ChannelTile(m: AppModel, c: io.github.kmmarin.tvbro.data.Channel, now: Long, fav: (() -> Unit)? = { m.toggleFav(c) }, onFocus: () -> Unit = {}) {
    val cur = m.epg.nowNext(c, now).first
    Card(m, 210.dp, fav, onFocus, long = { m.openChannelMenu(c) }, click = { m.play(c) }) {
        Box(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(6.dp)).background(Surface), contentAlignment = Alignment.Center) {
            Logo(c.logo, c.name, 64.dp)
            if (c.key in m.favourites) Badge("*", Modifier.align(Alignment.TopStart).padding(4.dp))
            Text(c.number.toString(), color = TextDim, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        Text(c.name, color = TextMain, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Text(cur?.title ?: "No guide data", color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (cur != null) ProgressLine(io.github.kmmarin.tvbro.data.ProgTime.progress(cur.start, cur.stop, now), Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun MyRow(title: String, count: Int, content: LazyListScope.() -> Unit) {
    Column(Modifier.padding(top = 10.dp)) {
        Text("$title  ($count)", color = TextMain, fontSize = 18.sp, modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 2.dp), content = content)
    }
}

@Composable
fun MyListScreen(m: AppModel) {
    LaunchedEffect(Unit) { m.refreshLists() }
    val now = rememberNow()
    val favCh = m.shown.filter { it.key in m.favourites }
    val groups = m.groupList.filter { "g|$it" in m.favourites }
    val recs = RecRepo.items.filter { "r|${it.id}" in m.favourites }
    val movies = m.mySnaps.filter { it.kind == "movie" && it.key in m.favourites }
    val series = m.mySnaps.filter { it.kind == "series" && it.key in m.favourites }
    val reminders = io.github.kmmarin.tvbro.data.Reminders.items.sortedBy { it.startMs }
    val rev = m.watch.rev.intValue
    val showList = remember(rev, m.histSnaps) { m.continueShows() }
    val shows = showList.associate { it.first.key to it.second }
    // recent titles: movies not finished, shows still in progress (the next episode on the card)
    val cont = m.histSnaps.filter { if (it.kind == "series") it.key in shows else !m.isWatched(it.key) } +
        showList.map { it.first }.filter { s -> m.histSnaps.none { it.key == s.key } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        item { Text("My list", color = Accent, fontSize = 28.sp) }
        item { Text("Add anything with long-press OK, the yellow or bookmark key, or gamepad Y.", color = TextDim, fontSize = 13.sp) }
        if (cont.isNotEmpty()) item {
            MyRow("Continue watching", cont.size) {
                items(cont, key = { it.key }) { v ->
                    val sr = shows[v.key]
                    if (sr != null) ContinueShowCard(m, v, sr) else {
                        val pos = remember(v.key, rev) { m.store.pos(v.key) }; val dur = remember(v.key, rev) { m.store.dur(v.key) }
                        Card(m, 140.dp, { m.toggleFavKey(v) }, long = { vodMenu(m, v) }, click = { m.openDetail(v) }) {
                            Box {
                                Poster(v.poster, v.name, 130.dp, 184.dp)
                                if (pos > 5000 && dur > 0) ProgressLine(pos.toFloat() / dur, Modifier.align(Alignment.BottomStart))
                            }
                            Text(v.name, color = TextMain, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
        if (favCh.isNotEmpty()) item { MyRow("Channels", favCh.size) { items(favCh) { c -> ChannelTile(m, c, now) } } }
        if (movies.isNotEmpty()) item { MyRow("Movies", movies.size) { items(movies) { v -> PosterTile(m, v, Modifier) } } }
        if (series.isNotEmpty()) item { MyRow("Shows", series.size) { items(series) { v -> PosterTile(m, v, Modifier) } } }
        if (reminders.isNotEmpty()) item {
            MyRow("Programmes and reminders", reminders.size) {
                items(reminders, key = { it.id }) { r ->
                    val ch = m.channels.firstOrNull { it.key == r.chKey }
                    Card(m, 230.dp, null, click = {
                        m.menu = MenuState(r.title, listOfNotNull(
                            ch?.let { "Watch ${it.name}" to { m.play(it) } },
                            "Cancel reminder" to { m.cancelReminder(r.id) },
                        ))
                    }) {
                        Box(Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(6.dp)).background(Surface).padding(8.dp)) {
                            Text(r.title, color = TextMain, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Badge("Reminder", Modifier.align(Alignment.BottomEnd))
                        }
                        Text(r.channel, color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        Text(io.github.kmmarin.tvbro.data.TimeFmt.dayTime(r.startMs), color = TextDim, fontSize = 12.sp)
                    }
                }
            }
        }
        if (recs.isNotEmpty()) item {
            MyRow("Recordings", recs.size) {
                items(recs, key = { it.id }) { r ->
                    Card(m, 230.dp, { m.toggleFavRec(r) }, click = { if (r.status == "done") m.playRec(r) else m.toast("Not finished yet") }) {
                        Box(Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(6.dp)).background(Surface).padding(8.dp)) {
                            Text(r.title, color = TextMain, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Image(painterResource(R.drawable.ic_nav_rec), null, Modifier.size(18.dp).align(Alignment.BottomEnd), colorFilter = ColorFilter.tint(if (r.status == "recording") Color(0xFFE57373) else TextDim))
                        }
                        Text(r.channel + "  ·  " + r.status, color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        if (groups.isNotEmpty()) item {
            MyRow("Groups", groups.size) {
                items(groups) { g ->
                    Card(m, 190.dp, { m.toggleFavGroup(g) }, click = { m.selectGroup(g); m.section = Section.TV }) {
                        Box(Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(6.dp)).background(Surface).padding(8.dp), contentAlignment = Alignment.CenterStart) {
                            Text(m.prefs.groupNames[g] ?: g, color = TextMain, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text("${m.shown.count { it.group == g || it.key in (m.prefs.custom[g] ?: emptyList()) }} channels", color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        if (favCh.isEmpty() && movies.isEmpty() && series.isEmpty() && reminders.isEmpty() && recs.isEmpty() && groups.isEmpty() && cont.isEmpty())
            item { Text("Nothing here yet.", color = TextDim, fontSize = 16.sp, modifier = Modifier.padding(top = 20.dp)) }
    }
}
// ---------------------------------------------------------------- shared pieces

@Composable
fun Backdrop(url: String?, modifier: Modifier = Modifier) {
    val bmp by produceState<Bitmap?>(null, url) { value = if (url != null) Logos.load(url, 960) else null }
    Box(modifier) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.9f) }
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Bg, Bg.copy(alpha = 0.88f), Color.Transparent))))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Bg))))
    }
}

@Composable
fun Poster(url: String?, name: String, w: Dp, h: Dp) {
    val bmp by produceState<Bitmap?>(null, url) { value = if (url != null) Logos.load(url, 200) else null }
    Box(Modifier.size(w, h).clip(RoundedCornerShape(6.dp)).background(Surface), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(name.take(1).uppercase(), color = TextDim, fontSize = 22.sp)
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Box(modifier.background(Accent, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, color = Bg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** Title, rating badge, meta line, cast, director and plot of a movie or show. */
@Composable
fun VodHeader(item: VodItem?, info: VodDetail?, modifier: Modifier = Modifier, plotLines: Int = 5) {
    Column(modifier.widthIn(max = 620.dp)) {
        if (item == null) { Text("Select a title", color = TextDim, fontSize = 18.sp); return }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.name + (info?.year?.let { " ($it)" } ?: ""), color = TextMain, fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
            (info?.rating ?: item.rating)?.let { Badge(it, Modifier.padding(start = 10.dp)) }
        }
        val seasons = info?.episodes?.map { it.season }?.distinct()?.size ?: 0
        val meta = listOfNotNull(info?.year, VodFmt.runtime(info?.duration), if (seasons > 0) "$seasons season${if (seasons > 1) "s" else ""}" else null, info?.genre).joinToString("  ·  ")
        if (meta.isNotEmpty()) Text(meta, color = TextDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        info?.cast?.let { Text("Cast: $it", color = TextDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
        info?.director?.let { Text("Director: $it", color = TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        info?.plot?.let { Text(it, color = TextMain.copy(alpha = 0.9f), fontSize = 14.sp, maxLines = plotLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
    }
}

/** Long-press menu for a movie or show (the context menu of a title anywhere). */
fun vodMenu(m: AppModel, item: VodItem) {
    val series = item.kind == "series"
    val items = ArrayList<Pair<String, () -> Unit>>()
    items += (if (series) "Open" else "Details") to { m.openDetail(item) }
    if (!series && !m.isDirect(item)) items += "Play" to { m.playItem(item) }
    items += (if (item.key in m.favourites) "Remove from My list" else "Add to My list") to { m.toggleFavKey(item) }
    if (series) {
        items += "Mark show as watched" to { m.markSeries(item, true) }
        items += "Mark show as unwatched" to { m.markSeries(item, false) }
    } else {
        val w = m.isWatched(item.key)
        items += (if (w) "Mark as unwatched" else "Mark as watched") to { m.markWatched(item.key, !w); m.refreshLists() }
        if (!m.isDirect(item)) items += "Download" to { m.downloadMovie(item) }
    }
    m.menu = MenuState(item.name, items)
}

/** A show in "Continue watching": poster, next episode code and title, progress of that episode. */
@Composable
fun ContinueShowCard(m: AppModel, v: VodItem, r: io.github.kmmarin.tvbro.data.SeriesRow, mod: Modifier = Modifier) {
    val st = m.watch.state("e|${v.sourceId}|${r.nextEp}")
    Card(m, 140.dp, { m.toggleFavKey(v) }, onFocus = { m.peekVod(v) }, long = { vodMenu(m, v) }, mod = mod, click = { m.openDetail(v) }) {
        Box {
            Poster(v.poster, v.name, 130.dp, 184.dp)
            Badge((if (r.nextSeason > 0) "S${r.nextSeason} " else "") + "E${r.nextNum}", Modifier.align(Alignment.TopStart).padding(4.dp))
            if (st != null && st.inProgress && st.durMs > 0) ProgressLine(st.fraction, Modifier.align(Alignment.BottomStart))
        }
        Text(v.name, color = TextMain, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Text(r.nextTitle ?: "", color = TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PosterTile(m: AppModel, item: VodItem, mod: Modifier) {
    var f by remember { mutableStateOf(false) }
    Column(
        mod.width(130.dp).padding(4.dp)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) { m.peekVod(item); m.favAction = { m.toggleFavKey(item) } } }
            .scale(if (f) 1.06f else 1f)
            .tvFocus(onLongClick = { vodMenu(m, item) }) { m.openDetail(item) }.padding(5.dp),
    ) {
        Box {
            Poster(item.poster, item.name, 120.dp, 170.dp)
            item.rating?.let { Badge(it, Modifier.align(Alignment.TopEnd).padding(4.dp)) }
            if (item.key in m.favourites) Badge("*", Modifier.align(Alignment.TopStart).padding(4.dp))
            if (DlRepo.has(item.key)) Badge("Offline", Modifier.align(Alignment.BottomStart).padding(4.dp))
        }
        Text(item.name, color = TextMain, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

// ---------------------------------------------------------------- Movies / Shows browse

@Composable
fun BrowseScreen(m: AppModel) {
    val items = m.vodFiltered
    val cats = listOf(CAT_MYLIST, CAT_RECENT, VOD_ALL) + m.vodCatNames
    val gridFr = remember { SafeFocus() }
    val contFr = remember { SafeFocus() }
    val catSel = remember { SafeFocus() }
    val shows = m.vodSeries
    val rev = m.watch.rev.intValue
    val cont = remember(rev, shows) { if (shows) m.continueShows() else emptyList() }
    // The title the user was on (back from its page, or Right from the categories) keeps focus; else the first one.
    val lastKey = m.focusVod?.key
    val gridKey = items.firstOrNull { it.key == lastKey }?.key ?: items.firstOrNull()?.key
    val contKey = cont.firstOrNull { it.first.key == lastKey }?.first?.key ?: cont.firstOrNull()?.first?.key
    val lastInCont = cont.any { it.first.key == lastKey } && items.none { it.key == lastKey }
    // From the categories, Right lands on "Continue watching" for My list / History (or when the user was there),
    // otherwise on the category's titles.
    val rightTarget = {
        if (contFr.attachedNow && (lastInCont || m.vodCat == CAT_MYLIST || m.vodCat == CAT_RECENT || !gridFr.attachedNow)) contFr.target() else gridFr.target()
    }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(m.vodScroll.first, m.vodScroll.second)
    DisposableEffect(Unit) { onDispose { m.vodScroll = gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset } }
    // Entering the section (or coming back from a title's page): focus on the remembered title, else the category.
    LaunchedEffect(shows) {
        delay(150)
        val gi = items.indexOfFirst { it.key == gridKey }
        if (!shows && gi >= 0 && gridState.layoutInfo.visibleItemsInfo.none { it.index == gi }) gridState.scrollToItem(gi)
        repeat(20) { if ((if (lastInCont) contFr.request() else false) || gridFr.request() || contFr.request()) return@LaunchedEffect; delay(50) }
        catSel.request()
    }
    // Like the TV groups, a category shows its titles once focus rests on it (Right from a category the list did not
    // show yet stayed put, or would have gone to another category's titles); Right enters them as soon as they are there.
    var enterTitles by remember { mutableStateOf(false) }
    LaunchedEffect(enterTitles, items.isNotEmpty(), cont.isNotEmpty()) {
        if (!enterTitles) return@LaunchedEffect
        repeat(30) { if (gridFr.request() || contFr.request()) { enterTitles = false; return@LaunchedEffect }; delay(60) }
        enterTitles = false
    }
    val catScope = rememberCoroutineScope()
    var catJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var jobCat by remember { mutableStateOf("") }
    Row(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.width(210.dp).fillMaxHeight().padding(12.dp).enterAt(catSel)) {
            item { Text(if (shows) "Shows" else "Movies", color = Accent, fontSize = 24.sp, modifier = Modifier.padding(bottom = 8.dp)) }
            item { TvField("Search", m.vodQuery, { m.vodQuery = it }) }
            items(cats) { g ->
                val sel = g == m.vodCat
                val label = vodCatLabel(g, shows)
                val open = g !in m.prefs.locked || g in m.unlocked
                Text((if (sel) "> " else "  ") + label + if (g in m.prefs.locked) " (locked)" else "", color = if (sel) Accent else TextMain, fontSize = 16.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().safeFocus(if (sel) catSel else null).focusProperties { right = rightTarget() }
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown || e.key != Key.DirectionRight) false
                            else if (g != m.vodCat) { catJob?.cancel(); m.selectVodCat(g); enterTitles = true; true }
                            else if (!gridFr.attachedNow && !contFr.attachedNow) { enterTitles = true; true } // still loading
                            else false
                        }
                        // (a short pause first: moving through the list does not load every category on the way)
                        .onFocusChanged { s ->
                            if (s.isFocused) {
                                catJob?.cancel()
                                jobCat = g
                                if (g != m.vodCat && open) catJob = catScope.launch { delay(400); if (g != m.vodCat) m.selectVodCat(g) }
                            } else if (jobCat == g) catJob?.cancel()
                        }
                        .tvFocus(onLongClick = { m.vodCatMenu(g) }) { m.selectVodCat(g) }.padding(10.dp))
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.fillMaxWidth().height(200.dp)) {
                Backdrop(m.peek?.backdrop ?: m.focusVod?.poster, Modifier.fillMaxSize())
                VodHeader(m.focusVod, m.peek, Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp))
            }
            if (m.vodMsg.isNotEmpty()) Text(m.vodMsg, color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (shows) {
                // Rows: "Continue watching" first, then the selected category.
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    if (cont.isNotEmpty()) item(key = "continue") {
                        Column {
                            Text("Continue watching", color = TextMain, fontSize = 17.sp, modifier = Modifier.padding(start = 14.dp, top = 4.dp))
                            LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                itemsIndexed(cont, key = { _, p -> p.first.key }) { i, (v, r) ->
                                    ContinueShowCard(m, v, r, Modifier.safeFocus(if (v.key == contKey) contFr else null))
                                }
                            }
                        }
                    }
                    item(key = "cat") {
                        Column {
                            if (cont.isNotEmpty()) Text(vodCatLabel(m.vodCat, true), color = TextMain, fontSize = 17.sp, modifier = Modifier.padding(start = 14.dp, top = 6.dp))
                            val rowState = rememberLazyListState((items.indexOfFirst { it.key == gridKey } - 2).coerceAtLeast(0))
                            LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), state = rowState) {
                                itemsIndexed(items) { i, v ->
                                    if (i >= items.size - 40 && m.vodHasMore) LaunchedEffect(items.size) { m.vodMore() } // next page from the stored catalogue
                                    PosterTile(m, v, Modifier.safeFocus(if (v.key == gridKey) gridFr else null))
                                }
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(GridCells.Adaptive(130.dp), Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp), state = gridState) {
                    itemsIndexed(items) { i, v ->
                        if (i >= items.size - 40 && m.vodHasMore) LaunchedEffect(items.size) { m.vodMore() } // next page from the stored catalogue
                        PosterTile(m, v, Modifier.safeFocus(if (v.key == gridKey) gridFr else null))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- details page (movie or show)

@Composable
private fun EpisodeCard(m: AppModel, series: VodItem, e: Episode, rev: Int, mod: Modifier, onFocus: () -> Unit) {
    val k = m.epKey(series, e)
    val st = remember(rev) { m.watch.state(k) }
    val watched = st?.watched == true
    val durMs = (st?.durMs ?: 0L).takeIf { it > 0 } ?: VodFmt.seconds(e.duration) * 1000
    var f by remember { mutableStateOf(false) }
    Column(
        mod.width(210.dp).onFocusChanged { f = it.isFocused; if (it.isFocused) { onFocus(); m.favAction = { m.toggleFavKey(series) } } }.scale(if (f) 1.05f else 1f)
            .tvFocus(onLongClick = {
                m.menu = MenuState("${Watch.code(e)} ${VodFmt.cleanTitle(series.name, e.title)}", listOf(
                    "Play" to { m.playEpisode(series, e) },
                    "Play from start" to { m.restartEpisode(series, e) },
                    (if (watched) "Mark as unwatched" else "Mark as watched") to { m.markEpisodes(series, listOf(e), !watched) },
                    (if (series.key in m.favourites) "Remove show from My list" else "Add show to My list") to { m.toggleFavKey(series) },
                    "Download episode" to { m.downloadEpisode(series, e) },
                ))
            }) { m.playEpisode(series, e) }.padding(5.dp),
    ) {
        Box {
            Poster(e.image ?: series.poster, series.name, 200.dp, 112.dp)
            if (watched) Badge("\u2713", Modifier.align(Alignment.TopEnd).padding(4.dp))
            if (DlRepo.has(k)) Badge("Offline", Modifier.align(Alignment.BottomEnd).padding(4.dp))
            if (st != null && st.inProgress && durMs > 0) ProgressLine(st.posMs.toFloat() / durMs, Modifier.align(Alignment.BottomStart))
        }
        Text("E${e.num}. ${VodFmt.cleanTitle(series.name, e.title)}", color = if (watched) TextDim else TextMain, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun SeasonRow(m: AppModel, series: VodItem, season: Int, eps: List<Episode>, rev: Int, target: Episode?, targetFr: FocusRequester) {
    var idx by remember { mutableIntStateOf(0) }
    val all = remember(rev) { eps.all { m.watch.state(m.epKey(series, it))?.watched == true } }
    Column(Modifier.padding(top = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text((if (season > 0) "Season $season" else "Episodes") + if (all) "  \u2713" else "", color = TextMain, fontSize = 18.sp, modifier = Modifier.weight(1f))
            TvButton("Season options", Modifier.padding(end = 12.dp)) {
                m.menu = MenuState(if (season > 0) "Season $season" else "Episodes", listOf(
                    "Mark season as watched" to { m.markEpisodes(series, eps, true) },
                    "Mark season as unwatched" to { m.markEpisodes(series, eps, false) },
                    "Download season" to { m.downloadSeason(series, eps) },
                ))
            }
            Text("${idx + 1} / ${eps.size}", color = TextDim, fontSize = 14.sp)
        }
        val state = rememberLazyListState(initialFirstVisibleItemIndex = eps.indexOfFirst { it.id == target?.id }.coerceAtLeast(0))
        LazyRow(state = state) {
            itemsIndexed(eps) { i, e -> EpisodeCard(m, series, e, rev, Modifier.requesterIf(e.id == target?.id, targetFr)) { idx = i } }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun DetailPage(m: AppModel) {
    BackHandler { m.screen = Screen.LIST }
    val item = m.detail ?: run { m.screen = Screen.LIST; return }
    val info = m.detailInfo
    val ctx = LocalContext.current
    val rev = m.watch.rev.intValue
    var tick by remember { mutableIntStateOf(0) }
    val series = item.kind == "series"
    // Series: the remembered episode (in progress, or the next unwatched one after it); first episode when finished.
    val target: Episode? = remember(info, rev) {
        info?.episodes?.takeIf { it.isNotEmpty() }?.let { l -> Watch.resume(l, { m.epState(item, it) }, m.watch.seriesRow(item.key)?.lastEp) ?: Watch.ordered(l).first() }
    }
    val playFr = remember { FocusRequester() }
    LaunchedEffect(item.key, info != null) { delay(150); runCatching { playFr.requestFocus() } }
    val epFr = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Bg)) {
        Backdrop(info?.backdrop ?: item.poster, Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.72f))
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
            item { VodHeader(item, info, Modifier.padding(bottom = 14.dp), plotLines = 6) }
            if (m.detailMsg.isNotEmpty()) item { Text(m.detailMsg, color = TextDim, fontSize = 14.sp) }
            item {
                val st = remember(rev, tick, target) { if (series) target?.let { m.epState(item, it) } else m.watch.state(item.key) }
                val pos = if (st?.inProgress == true) st.posMs else 0L
                val left = VodFmt.seconds(info?.duration) - pos / 1000
                val label = if (series) {
                    target?.let { (if (pos > 0) "Resume " else "Play ") + Watch.code(it) + (if (pos > 0) " (${Watch.clock(pos)})" else "") } ?: "Play"
                } else if (pos > 0) "Resume" + (if (left > 60) " (${VodFmt.fmt(left)} left)" else "") else "Play"
                val allWatched = series && info != null && info.episodes.isNotEmpty() && info.episodes.all { m.epState(item, it)?.watched == true }
                // the ends of the button row are edges (Right from the last button jumped down to "Season options")
                Row(Modifier.focusProperties {
                    exit = { d -> if (d == androidx.compose.ui.focus.FocusDirection.Left || d == androidx.compose.ui.focus.FocusDirection.Right) FocusRequester.Cancel else FocusRequester.Default }
                }.focusGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton(label, Modifier.focusRequester(playFr)) { if (series) target?.let { m.playEpisode(item, it) } else m.playItem(item) }
                    if (pos > 0) TvButton("Play from start") {
                        if (series) target?.let { m.restartEpisode(item, it) }
                        else { m.store.setWatched(item.key, false); m.playItem(item) }
                    }
                    info?.trailer?.let { t ->
                        TvButton("Trailer") {
                            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(t)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            catch (e: Exception) { m.toast("No app to open the trailer") }
                        }
                    }
                    TvButton(if (item.key in m.favourites) "Remove from My list" else "Add to My list") { m.toggleFavKey(item) }
                    if (series && info != null && info.episodes.isNotEmpty()) TvButton(if (allWatched) "Mark show unwatched" else "Mark show watched") { m.markEpisodes(item, info.episodes, !allWatched) }
                    if (!series) TvButton(if (m.isWatched(item.key)) "Mark unwatched" else "Mark watched") { m.markWatched(item.key, !m.isWatched(item.key)); tick++ }
                    if (!series) TvButton(if (DlRepo.has(item.key)) "Offline copy saved" else "Download") { m.downloadMovie(item) }
                    TvButton("External player") { m.urlFor(item, target)?.let { m.openExternal(it) } }
                }
            }
            if (series && info != null) {
                val bySeason = Watch.ordered(info.episodes).groupBy { it.season }
                for ((sn, eps) in bySeason) item(key = "season$sn") { SeasonRow(m, item, sn, eps, rev, target, epFr) }
                if (bySeason.isEmpty() && m.detailMsg.isEmpty()) item { Text("No episodes listed.", color = TextDim, modifier = Modifier.padding(top = 12.dp)) }
            }
        }
    }
}
