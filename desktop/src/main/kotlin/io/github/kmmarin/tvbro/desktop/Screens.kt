// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.kmmarin.tvbro.data.*

fun hhmm(sec: Long): String = TimeFmt.time(sec)

@Composable
private fun Logo(url: String?) = NetImage(url, Modifier.size(56.dp, 34.dp).clip(RoundedCornerShape(4.dp)))

@Composable
private fun Section(title: String) = T(title, 16, Theme.accent, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))

/** A poster card; [progress] draws a bar under it, [badge] a line under the title. */
@Composable
fun PosterCard(v: VodItem, app: AppState, width: androidx.compose.ui.unit.Dp? = null, badge: String? = null, progress: Float? = null, onClick: () -> Unit) {
    val w = app.watch.state(v.key)
    val fr = remember { FocusRequester() }
    // back from its page: the opened tile has the focus again
    LaunchedEffect(Unit) { if (app.lastOpened == v.key) { app.lastOpened = null; fr.settle() } }
    Column((if (width != null) Modifier.width(width) else Modifier).tvItem(onFocus = { app.favAction = { app.toggleList(v.key) } }, onMenu = { app.vodMenu(v) }, requester = fr, onClick = onClick).padding(6.dp)) {
        NetImage(v.poster, Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)), ContentScale.Crop)
        val p = progress ?: w?.takeIf { it.inProgress }?.fraction
        if (p != null) ProgressLine(p, Modifier.fillMaxWidth().padding(top = 2.dp))
        T(v.name, 13, maxLines = 2)
        Row {
            if (app.inList(v.key)) T("★ ", 12, Theme.accent)
            if (w?.watched == true) T("✓ Watched ", 12, Theme.ok)
            badge?.let { T(it, 12, Theme.dim) }
        }
    }
}

@Composable
private fun ContinueRow(app: AppState, shows: Boolean) {
    val data = app.watch.data // recompose on change
    val list: List<Pair<VodItem, String>> = remember(data, shows) {
        if (shows) app.watch.continueShows().map { (v, r) -> v to listOfNotNull(if (r.nextSeason > 0) "S${r.nextSeason} E${r.nextNum}" else "E${r.nextNum}", r.nextTitle).joinToString(" · ") }
        else app.watch.continueMovies().map { (v, w) -> v to "${Watch.clock(w.posMs)} of ${Watch.clock(w.durMs)}" }
    }
    if (list.isEmpty()) return
    Section("Continue watching")
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list, key = { "c" + it.first.key }) { (v, line) ->
            val prog = if (shows) app.watch.series(v.key)?.nextEp?.let { id -> app.watch.state("e|${v.sourceId}|$id")?.takeIf { it.inProgress }?.fraction } else app.watch.state(v.key)?.fraction
            PosterCard(v, app, 140.dp, line, prog ?: 0f) { app.detail = v }
        }
    }
}

@Composable
fun VodBrowse(app: AppState, all: List<VodItem>, shows: Boolean) {
    if (all.isEmpty() && app.watch.data.snaps.isEmpty()) return Empty(app)
    val locked = app.settings.prefs.locked
    val cats = remember(all) { listOf("All") + all.map { it.category }.distinct() }
    var cat by remember(all) { mutableStateOf("All") }
    val open = app.pinOpen || app.settings.prefs.pinHash.isEmpty()
    val list = remember(all, cat, locked, open) {
        if (cat == "All") all.filter { it.category !in locked || open } else all.filter { it.category == cat }
    }
    Row(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.width(220.dp).fillMaxHeight()) {
            items(cats) { c ->
                Box(Modifier.fillMaxWidth().tvItem(onMenu = {
                    if (c != "All") app.menu = MenuState(c, listOf((if (c in locked) "Unlock category" else "Lock category (PIN)") to { app.toggleLock(c) }))
                }) { if (app.catLocked(c)) app.askPin { cat = c } else cat = c }.padding(8.dp)) {
                    T(c + if (c in locked) "  🔒" else "", 14, if (c == cat) Theme.accent else Theme.text)
                }
            }
        }
        LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (cat == "All") item(span = { GridItemSpan(maxLineSpan) }) { Column { ContinueRow(app, shows); Section(if (shows) "All shows" else "All movies") } }
            items(list, key = { it.key }) { v -> PosterCard(v, app) { app.detail = v } }
        }
    }
}

@Composable
fun DetailScreen(app: AppState, v: VodItem) {
    val repo = remember(v) { app.repo(v.sourceId) }
    var d by remember(v) { mutableStateOf(app.detailCache[v.key]) }
    var err by remember(v) { mutableStateOf<String?>(null) }
    LaunchedEffect(v) {
        try { withContext(Dispatchers.IO) { repo?.detail(v) }?.let { d = it; app.detailCache[v.key] = it } } catch (e: Exception) { if (d == null) err = Net.describe(e) }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(v, d != null) { first.settle() }
    val data = app.watch.data // recompose on watch changes
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        NetImage(v.poster, Modifier.width(220.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)), ContentScale.Crop)
        Column(Modifier.weight(1f)) {
            T(v.name, 24, bold = true, maxLines = 2)
            val dd = d
            T(listOfNotNull(dd?.year, dd?.genre, (dd?.rating ?: v.rating)?.let { "★ $it" }, VodFmt.runtime(dd?.duration)).joinToString("  ·  "), 14, Theme.dim)
            val eps = dd?.episodes.orEmpty()
            Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (v.kind != "series" && repo != null) {
                    val st = app.watch.state(v.key)
                    Btn(if (st?.inProgress == true) "Resume ${Watch.clock(st.posMs)}" else "Play", requester = first) { app.openMovie(v, repo, d) }
                    if (st?.inProgress == true || st?.watched == true) Btn("Play from start") { app.openMovie(v, repo, d, fromStart = true) }
                    Btn(if (st?.watched == true) "Mark unwatched" else "Mark watched") { app.markMovie(v, st?.watched != true) }
                }
                if (v.kind == "series" && eps.isNotEmpty()) {
                    val t = remember(data, eps) { app.resumeTarget(v, eps) }
                    if (t != null) {
                        Btn(t.second, requester = first) { app.playEpisode(v, eps, t.first) }
                        if (app.watch.state(WatchStore.epKey(v, t.first))?.inProgress == true) Btn("Play from start") { app.playEpisode(v, eps, t.first, fromStart = true) }
                    } else Btn("Watched all · play S${Watch.ordered(eps).first().season} E${Watch.ordered(eps).first().num}", requester = first) { app.playEpisode(v, eps, Watch.ordered(eps).first(), fromStart = true) }
                    Btn("Watched / unwatched…") {
                        app.menu = MenuState(v.name, listOf("Mark show as watched" to { app.markEpisodes(v, eps, eps, true) }, "Mark show as unwatched" to { app.markEpisodes(v, eps, eps, false) }))
                    }
                }
                Btn(if (app.inList(v.key)) "Remove from My list" else "Add to My list") { app.toggleList(v.key) }
                Btn("Back") { app.detail = null }
            }
            err?.let { T("Could not load details: $it", 13, Theme.live) }
            dd?.plot?.let { T(it, 14, maxLines = 5) }
            dd?.cast?.let { T("Cast: $it", 13, Theme.dim, maxLines = 2) }
            dd?.director?.let { T("Director: $it", 13, Theme.dim) }
            if (eps.isNotEmpty() && repo != null) {
                val seasons = eps.map { it.season }.distinct().sortedWith(compareBy { if (it <= 0) Int.MAX_VALUE else it })
                val row = app.watch.series(v.key)
                val startSeason = row?.nextSeason?.takeIf { it in seasons } ?: seasons.first()
                var season by remember(v) { mutableStateOf(startSeason) }
                LazyRow(Modifier.padding(top = 10.dp)) {
                    items(seasons) { s ->
                        val inS = eps.filter { it.season == s }
                        val all = inS.all { app.watch.state(WatchStore.epKey(v, it))?.watched == true }
                        Box(Modifier.tvItem(onMenu = {
                            app.menu = MenuState("Season $s", listOf("Mark season $s as watched" to { app.markEpisodes(v, eps, inS, true) }, "Mark season $s as unwatched" to { app.markEpisodes(v, eps, inS, false) }))
                        }) { season = s }.padding(10.dp, 6.dp)) { T((if (s <= 0) "Specials" else "Season $s") + if (all) " ✓" else "", 14, if (s == season) Theme.accent else Theme.text) }
                    }
                }
                LazyColumn {
                    items(eps.filter { it.season == season }.sortedBy { it.num }, key = { it.id }) { e ->
                        val st = app.watch.state(WatchStore.epKey(v, e))
                        val next = row?.nextEp == e.id
                        Row(Modifier.fillMaxWidth().tvItem(onMenu = { app.episodeMenu(v, eps, e) }) { app.playEpisode(v, eps, e) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            T("E${e.num}", 14, if (next) Theme.accent else Theme.dim, modifier = Modifier.width(48.dp))
                            Column(Modifier.weight(1f)) {
                                T(VodFmt.cleanTitle(v.name, e.title), 14, bold = true)
                                e.plot?.let { T(it, 12, Theme.dim, maxLines = 2) }
                                if (st?.inProgress == true) ProgressLine(st.fraction, Modifier.fillMaxWidth(0.6f).padding(top = 3.dp))
                            }
                            T(listOfNotNull(if (st?.watched == true) "✓" else null, st?.takeIf { it.inProgress }?.let { "Resume ${Watch.clock(it.posMs)}" }, VodFmt.runtime(e.duration)).joinToString("  "), 12,
                                if (st?.watched == true) Theme.ok else Theme.dim)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MyListScreen(app: AppState) {
    val keys = app.settings.myList.toSet()
    val ch = app.shown.filter { it.key in keys }
    val groups = keys.filter { it.startsWith("g|") }.map { it.removePrefix("g|") }.filter { it in app.groups }
    val all = (app.movies + app.series).associateBy { it.key }
    val vod = keys.mapNotNull { all[it] ?: app.watch.snapOf(it) }
    val recs = app.recorder.items.filter { "r|${it.id}" in keys || it.status == "recording" }
    val rem = app.settings.reminders
    val hasContinue = app.watch.continueShows().isNotEmpty() || app.watch.continueMovies().isNotEmpty()
    if (ch.isEmpty() && vod.isEmpty() && groups.isEmpty() && recs.isEmpty() && rem.isEmpty() && !hasContinue)
        return Column { T("My list is empty", 18); T("Add channels, groups, movies, shows and recordings with their menu (hold Enter, right click, or Y on a channel).", 14, Theme.dim, maxLines = 3) }
    LazyColumn {
        item { ContinueRow(app, true) }
        item { ContinueRow(app, false) }
        if (ch.isNotEmpty()) item {
            Section("Channels")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ch, key = { it.key }) { c ->
                    val cur = app.epg.nowNext(c, app.now).first
                    Column(Modifier.width(170.dp).tvItem(onMenu = { app.channelMenu(c) }) { app.playChannel(c) }.padding(6.dp)) {
                        Logo(c.logo); T(c.name, 13, bold = true)
                        T(cur?.title ?: "No guide data", 12, Theme.dim)
                        if (cur != null) ProgressLine(ProgTime.progress(cur.start, cur.stop, app.now), Modifier.fillMaxWidth().padding(top = 2.dp))
                    }
                }
            }
        }
        if (groups.isNotEmpty()) item {
            Section("Groups")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groups) { g -> Box(Modifier.tvItem(onMenu = { app.groupMenu(g) }) { app.selectGroup(g); app.tab = Tab.TV }.background(Theme.surface, RoundedCornerShape(6.dp)).padding(16.dp, 12.dp)) { T(app.groupLabel(g), 14) } }
            }
        }
        val mv = vod.filter { it.kind != "series" }; val sh = vod.filter { it.kind == "series" }
        if (mv.isNotEmpty()) item { Section("Movies"); LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(mv, key = { it.key }) { v -> PosterCard(v, app, 140.dp) { app.detail = v } } } }
        if (sh.isNotEmpty()) item { Section("Shows"); LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(sh, key = { it.key }) { v -> PosterCard(v, app, 140.dp) { app.detail = v } } } }
        if (rem.isNotEmpty()) item {
            Section("Programmes (reminders)")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rem, key = { it.id }) { r ->
                    Column(Modifier.width(200.dp).tvItem(onMenu = { app.menu = MenuState(r.title, listOf("Remove reminder" to { app.reminderDone(r, false) })) }) {
                        app.shown.firstOrNull { it.key == r.chKey }?.let(app::playChannel)
                    }.background(Theme.surface, RoundedCornerShape(6.dp)).padding(10.dp)) {
                        T(r.title, 14, bold = true); T("${r.channel} · ${TimeFmt.dayTime(r.startMs, "EEE")}", 12, Theme.dim)
                    }
                }
            }
        }
        if (recs.isNotEmpty()) item {
            Section("Recordings")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(recs, key = { it.id }) { r -> RecCard(app, r, Modifier.width(220.dp)) } }
        }
    }
}

@Composable
private fun RecCard(app: AppState, r: Rec, modifier: Modifier) =
    Column(modifier.tvItem(onMenu = { app.recordingMenu(r) }) { if (r.status == "done") app.playRecording(r) else app.recordingMenu(r) }.background(Theme.surface, RoundedCornerShape(6.dp)).padding(10.dp)) {
        T(r.title, 14, bold = true)
        T("${r.channel} · ${TimeFmt.dayTime(r.startMs, "EEE d MMM")}" + if (r.endMs > 0) " – ${TimeFmt.time(r.endMs / 1000)}" else " (until stopped)", 12, Theme.dim)
        val (txt, col) = when (r.status) {
            "recording" -> "● Recording" to Theme.live
            "scheduled" -> (if (r.seriesId != null) "Scheduled (series)" else "Scheduled") to Theme.accent
            "done" -> ("Recorded" + if (r.bytes > 0) " · ${r.bytes / (1024 * 1024)} MB" else "") to Theme.ok
            else -> "Failed" to Theme.live
        }
        T(txt + (if (r.file.startsWith("smb://")) " · on the share" else ""), 12, col)
        if (r.note.isNotBlank()) T(r.note, 11, Theme.dim, maxLines = 2)
        val w = app.watch.state("r|${r.id}")
        if (w?.inProgress == true) ProgressLine(w.fraction, Modifier.fillMaxWidth().padding(top = 3.dp))
    }

@Composable
fun RecordingsScreen(app: AppState) {
    val recs = app.recorder.items.sortedWith(compareBy<Rec>({ when (it.status) { "recording" -> 0; "scheduled" -> 1; else -> 2 } }, { if (it.status == "scheduled") it.startMs else -it.startMs }))
    val rules = app.recorder.rules
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            T("Recordings", 20, Theme.accent)
            T(RecTarget.describe(app.settings) + "   ·   change in Settings > Recording", 12, Theme.dim)
            T("Recordings run while tvBro is open. From the guide: hold Enter (or right click) on a programme > Record / Record series.", 12, Theme.dim, maxLines = 2)
        }
        if (rules.isNotEmpty()) {
            item { Section("Series recordings") }
            items(rules, key = { "rule" + it.id }) { r ->
                Row(Modifier.fillMaxWidth().tvItem(onMenu = { app.menu = MenuState(r.title, listOf("Cancel series recording" to { app.recorder.cancelSeries(r) })) }) {
                    app.menu = MenuState(r.title, listOf("Cancel series recording" to { app.recorder.cancelSeries(r) }))
                }.padding(8.dp)) {
                    T("Every \"${r.title}\"", 15, bold = true, modifier = Modifier.weight(1f))
                    T("on ${r.channel} · airings booked 48 h ahead · ${app.recorder.items.count { it.seriesId == r.id && it.status == "scheduled" }} scheduled", 12, Theme.dim)
                }
            }
        }
        if (recs.isEmpty()) item { T("No recordings yet.", 14, Theme.dim, modifier = Modifier.padding(top = 12.dp)) }
        else item { Section("All") }
        items(recs, key = { it.id }) { r -> RecCard(app, r, Modifier.fillMaxWidth()) }
    }
}
