// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import io.github.kmmarin.tvbro.*
import io.github.kmmarin.tvbro.R
import io.github.kmmarin.tvbro.data.Channel
import io.github.kmmarin.tvbro.data.EpisodeNum
import io.github.kmmarin.tvbro.data.ProgState
import io.github.kmmarin.tvbro.data.ProgTime
import io.github.kmmarin.tvbro.data.SearchStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dayFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())

/** Search: mic, field (keyboard on OK only) and settings on top; history chips before typing; result rows after. */
@Composable
fun SearchScreen(m: AppModel, onBack: () -> Unit) {
    BackHandler { onBack() }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { m.setSearch(it); m.rememberSearch(it) }
    }
    val speak = {
        try {
            launcher.launch(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
        } catch (e: Exception) { m.toast("Voice search is not available on this device") }
    }
    val ver = SearchStore.version.intValue
    LaunchedEffect(ver) { if (ver > 0) m.refreshSearch() }
    val fieldFr = remember { SafeFocus() }
    LaunchedEffect(Unit) { delay(150); fieldFr.request() }
    val v = m.searchView
    val now = rememberNow()

    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 24.dp, vertical = 16.dp)) {
        // Up from the history / results enters this row at the search field (it went to the mic button, the nearest)
        Row(Modifier.enterAt(fieldFr), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).tvFocus(CircleShape) { speak() }.background(Surface, CircleShape), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_mic), "Voice search", Modifier.size(30.dp), colorFilter = ColorFilter.tint(Accent))
            }
            Spacer(Modifier.width(16.dp))
            SearchField(m, Modifier.weight(1f), fieldFr)
            Spacer(Modifier.width(16.dp))
            Box(Modifier.size(52.dp).tvFocus(CircleShape) { m.screen = Screen.SETTINGS }, contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_nav_settings), "Settings", Modifier.size(26.dp), colorFilter = ColorFilter.tint(TextMain))
            }
        }
        if (m.searchQ.isBlank()) History(m)
        else if (v == null) Text(if (SearchStore.building) "Searching (the search index is being built)..." else "Searching...", color = TextDim, modifier = Modifier.padding(16.dp))
        else Results(m, v, now)
    }
}

@Composable
private fun SearchField(m: AppModel, modifier: Modifier, boxFr: SafeFocus) {
    var editing by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(30.dp)
    var was by remember { mutableStateOf(false) }
    // the field lost focus while typing (Back on the Fire TV keyboard closes it and takes focus away): editing ends and
    // focus returns to the field (it went to the mic button while the field still showed as being edited)
    var lost by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (editing) { was = true; delay(50); runCatching { fr.requestFocus() }; kb?.show() }
        else if (was) { was = false; delay(50); for (i in 0 until 6) { if (boxFr.request()) break; delay(40) }; lost = false }
    }
    val stop = { editing = false; kb?.hide(); m.rememberSearch() }
    if (!editing) {
        Box(modifier.height(56.dp).safeFocus(boxFr).tvFocus(shape) { editing = true }.border(1.dp, TextDim.copy(alpha = 0.4f), shape).padding(horizontal = 22.dp), contentAlignment = Alignment.CenterStart) {
            Text(m.searchQ.ifEmpty { "Search channels, programmes, movies and shows" }, color = if (m.searchQ.isEmpty()) TextDim else TextMain, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        BackHandler { stop() }
        var f by remember { mutableStateOf(false) }
        BasicTextField(
            value = m.searchQ, onValueChange = { m.setSearch(it) }, singleLine = true,
            textStyle = TextStyle(color = TextMain, fontSize = 19.sp), cursorBrush = SolidColor(Accent),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { stop() }, onDone = { stop() }),
            // Down while typing: done, on to the results (the field is no trap)
            modifier = modifier.height(56.dp).focusRequester(fr).countFocus().onFocusChanged { val w = f; f = it.isFocused; if (w && !f && editing) { lost = true; stop() } }
                .onPreviewKeyEvent { e -> if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.DirectionDown) { stop(); true } else false }
                .background(if (f) FocusBg else Surface, shape).border(3.dp, Accent, shape).padding(horizontal = 22.dp, vertical = 16.dp),
        )
    }
}

@Composable
private fun History(m: AppModel) {
    Column(Modifier.padding(top = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Search history", color = TextMain, fontSize = 20.sp, modifier = Modifier.weight(1f))
            if (m.history.isNotEmpty()) Row(Modifier.tvFocus { m.clearHistory() }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_trash), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(TextDim))
                Text("  Clear history", color = TextDim, fontSize = 15.sp)
            }
        }
        if (m.history.isEmpty()) Text("Your searches will appear here.", color = TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
        LazyColumn(Modifier.padding(top = 8.dp)) {
            items(m.history.chunked(2)) { pair ->
                Row(Modifier.fillMaxWidth()) {
                    for (h in pair) Box(Modifier.weight(1f).padding(4.dp).tvFocus(RoundedCornerShape(24.dp)) { m.setSearch(h); m.rememberSearch(h) }
                        .background(Surface, RoundedCornerShape(24.dp)).padding(horizontal = 18.dp, vertical = 10.dp)) {
                        Text(h, color = TextMain, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(t: String, n: Int) = Text("$t  ($n)", color = TextMain, fontSize = 18.sp, modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 2.dp))

@Composable
private fun Results(m: AppModel, v: AppModel.SearchView, now: Long) {
    val nothing = v.channels.isEmpty() && v.movies.isEmpty() && v.series.isEmpty() && v.progs.isEmpty()
    LazyColumn(Modifier.fillMaxSize().padding(top = 6.dp)) {
        item {
            Text((if (nothing) "No matches for \"${v.q}\"" else "Results for \"${v.q}\"") + "   ·   ${v.ms} ms" + if (!v.indexReady) "   ·   index not built yet (channels only)" else "",
                color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
        }
        if (v.movies.isNotEmpty()) item {
            SectionTitle("Movies", v.movies.size)
            LazyRow { items(v.movies) { x -> Box(Modifier.onFocusChanged { if (it.hasFocus) m.rememberSearch() }) { PosterTile(m, x, Modifier) } } }
        }
        if (v.series.isNotEmpty()) item {
            SectionTitle("Shows", v.series.size)
            LazyRow { items(v.series) { x -> Box(Modifier.onFocusChanged { if (it.hasFocus) m.rememberSearch() }) { PosterTile(m, x, Modifier) } } }
        }
        if (v.channels.isNotEmpty()) item {
            SectionTitle("Channels", v.channels.size)
            LazyRow { items(v.channels) { c -> ChannelTile(m, c, now, onFocus = { m.rememberSearch() }) } }
        }
        if (v.progs.isNotEmpty()) item {
            SectionTitle("Programs", v.progs.sumOf { it.second.size })
            Programs(m, v, now)
        }
    }
}

/** Channels with matching programmes on the left, that channel's matches in the middle, details of the focused one on the right. */
@Composable
private fun Programs(m: AppModel, v: AppModel.SearchView, now: Long) {
    var selCh by remember(v) { mutableStateOf(v.progs.firstOrNull()?.first?.key) }
    var focused by remember(v) { mutableStateOf<AppModel.ProgRow?>(null) }
    val rows = v.progs.firstOrNull { it.first.key == selCh } ?: v.progs.first()
    Row(Modifier.fillMaxWidth().height(330.dp)) {
        LazyColumn(Modifier.width(230.dp).fillMaxHeight()) {
            items(v.progs, key = { it.first.key }) { (c, l) ->
                val sel = c.key == rows.first.key
                Row(Modifier.fillMaxWidth().padding(2.dp).onFocusChanged { if (it.isFocused) { selCh = c.key; m.favAction = { m.toggleFav(c) } } }
                    .tvFocus { selCh = c.key }.background(if (sel) Surface else Color.Transparent).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Logo(c.logo, c.name, 36.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = if (sel) Accent else TextMain, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${l.size} match${if (l.size == 1) "" else "es"}", color = TextDim, fontSize = 12.sp)
                    }
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        LazyColumn(Modifier.weight(1f).fillMaxHeight()) {
            items(rows.second, key = { "${it.p.key}|${it.p.start}" }) { r -> ProgItem(m, rows.first, r, now) { focused = r } }
        }
        Spacer(Modifier.width(10.dp))
        ProgDetails(m, rows.first, focused?.takeIf { f -> rows.second.any { it === f } } ?: rows.second.firstOrNull(), now, Modifier.width(300.dp).fillMaxHeight())
    }
}

private fun airTime(p: io.github.kmmarin.tvbro.data.Programme, now: Long): String {
    val today = dayFmt.format(Date(now * 1000))
    val d = dayFmt.format(Date(p.start * 1000))
    return (if (d == today) "Today" else d) + "  " + hhmm(p.start) + " - " + hhmm(p.stop)
}

@Composable
private fun ProgItem(m: AppModel, c: Channel, r: AppModel.ProgRow, now: Long, onFocus: () -> Unit) {
    val p = r.p
    Column(Modifier.fillMaxWidth().padding(2.dp).onFocusChanged { if (it.isFocused) { onFocus(); m.favAction = { m.toggleFav(c) } } }
        .tvFocus { m.rememberSearch(); m.progActions(c, r) }.padding(horizontal = 10.dp, vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (r.state == ProgState.PAST_CATCHUP) { Image(painterResource(R.drawable.ic_catchup), "Catch-up", Modifier.size(16.dp), colorFilter = ColorFilter.tint(Accent)); Spacer(Modifier.width(6.dp)) }
            if (r.state == ProgState.NOW) { Badge("LIVE"); Spacer(Modifier.width(6.dp)) }
            Text(p.title, color = TextMain, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        EpisodeNum.line(p.ep, p.sub)?.let { Text(it, color = TextMain.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        when (r.state) {
            ProgState.NOW -> {
                Text("${hhmm(p.start)} - ${hhmm(p.stop)}   ${ProgTime.minutesLeft(p.stop, now)} min left", color = TextDim, fontSize = 12.sp)
                ProgressLine(ProgTime.progress(p.start, p.stop, now), Modifier.padding(top = 3.dp))
            }
            ProgState.FUTURE -> Text("On air " + airTime(p, now), color = TextDim, fontSize = 12.sp)
            else -> Text(airTime(p, now) + "   catch-up", color = TextDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ProgDetails(m: AppModel, c: Channel, r: AppModel.ProgRow?, now: Long, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(Surface).padding(14.dp)) {
        if (r == null) return@Column
        val p = r.p
        val desc by produceState<String?>(null, p.key, p.start) { value = withContext(Dispatchers.IO) { m.progDesc(p) } }
        Text(p.title, color = TextMain, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        EpisodeNum.line(p.ep, p.sub)?.let { Text(it, color = Accent, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Text("${c.name}   ${airTime(p, now)}", color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        Text(when (r.state) { ProgState.NOW -> "On now: OK to watch"; ProgState.FUTURE -> "Upcoming: OK to record or be reminded"; ProgState.PAST_CATCHUP -> "Catch-up available: OK to watch"; else -> "" },
            color = Accent, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        Text(desc ?: "", color = TextDim, fontSize = 13.sp, maxLines = 9, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
    }
}

/** "<title> is starting now on <channel>" with Watch now / Dismiss, while tvBro is open. */
@Composable
fun ReminderPopup(m: AppModel) {
    val r = m.dueReminder ?: return
    BackHandler { m.reminderDismiss(r) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(r.id) { delay(100); runCatching { fr.requestFocus() } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Column(Modifier.padding(28.dp).width(440.dp).clip(RoundedCornerShape(12.dp)).background(Surface).border(2.dp, Accent, RoundedCornerShape(12.dp)).padding(20.dp)) {
            Text("Reminder", color = Accent, fontSize = 14.sp)
            Text("${r.title} is starting now on ${r.channel}", color = TextMain, fontSize = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("Watch now", Modifier.focusRequester(fr)) { m.reminderWatch(r) }
                TvButton("Dismiss") { m.reminderDismiss(r) }
            }
        }
    }
}
