// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import io.github.kmmarin.tvbro.*
import io.github.kmmarin.tvbro.data.Channel
import io.github.kmmarin.tvbro.data.CatchupUrl
import io.github.kmmarin.tvbro.data.Programme
import io.github.kmmarin.tvbro.data.Reminders

/** Popup with programme details and actions; shown over any screen while [AppModel.progMenu] is set. */
@Composable
fun ProgMenu(m: AppModel) {
    val (ch, p) = m.progMenu ?: return
    if (p == null) { m.progMenu = null; return }
    BackHandler { m.progMenu = null }
    RestoreFocusOnClose(ch to p) { m.menu != null || m.prompt != null || m.progMenu != null }
    val now = System.currentTimeMillis() / 1000
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); runCatching { fr.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(560.dp).heightIn(max = 520.dp).focusTrap().background(Surface).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(24.dp)) {
            Text(p.title, color = TextMain, fontSize = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            io.github.kmmarin.tvbro.data.EpisodeNum.line(p.ep, p.sub)?.let { Text(it, color = TextMain, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text("${ch.name}   ${hhmm(p.start)}-${hhmm(p.stop)}", color = Accent, fontSize = 15.sp, modifier = Modifier.padding(vertical = 4.dp))
            Text(p.desc ?: "No description.", color = TextDim, fontSize = 14.sp, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 12.dp))
            val first = Modifier.focusRequester(fr)
            var used = false
            fun mod(): Modifier { val r = if (used) Modifier else first; used = true; return r }
            if (p.start <= now && p.stop > now) TvButton("Watch live", mod().fillMaxWidth()) { m.progMenu = null; m.play(ch) }
            if (p.stop <= now && CatchupUrl.supports(ch, p.start, now)) TvButton("Watch catch-up", mod().fillMaxWidth()) { m.progMenu = null; m.guidePlay(ch, p) }
            if (p.stop > now) TvButton("Record", mod().fillMaxWidth()) { m.progMenu = null; m.recordProgramme(ch, p) }
            if (p.start > now) TvButton("Remind me at start", mod().fillMaxWidth()) { m.progMenu = null; m.remind(ch, p) }
            val rule = m.seriesRuleFor(ch, p)
            if (rule != null) TvButton("Cancel series recording (every \"${rule.title}\")", mod().fillMaxWidth()) { m.progMenu = null; m.cancelSeries(rule) }
            else TvButton("Record series (every \"${p.title}\" on ${ch.name})", mod().fillMaxWidth()) { m.progMenu = null; m.recordSeries(ch, p) }
            if (p.start > now || p.stop <= now) TvButton("Watch ${ch.name} now", mod().fillMaxWidth()) { m.progMenu = null; m.play(ch) }
            TvButton(if (ch.key in m.favourites) "Remove ${ch.name} from Favourites" else "Add ${ch.name} to Favourites", mod().fillMaxWidth()) { m.toggleFav(ch) }
            TvButton("Channel options (rename, hide, move)...", mod().fillMaxWidth()) { m.progMenu = null; m.openChannelMenu(ch) }
            TvButton("Close", mod().fillMaxWidth()) { m.progMenu = null }
        }
    }
}

@Composable
fun MappingScreen(m: AppModel) {
    BackHandler { m.screen = Screen.SETTINGS }
    var cq by remember { mutableStateOf("") }
    var sel by remember { mutableStateOf<Channel?>(null) }
    var iq by remember { mutableStateOf("") }
    val unmatched = remember(m.channels, m.epg, cq) {
        m.channels.filter { m.epg.programmes(it).isEmpty() && (cq.isBlank() || it.name.lowercase().contains(cq.lowercase())) }.take(150)
    }
    val ids = remember(sel) { sel?.let { m.epgIds(it.sourceId) } ?: emptyList() }
    LazyColumn(Modifier.fillMaxSize().background(Bg).padding(32.dp)) {
        item { Text("Map channels to guide", color = Accent, fontSize = 28.sp) }
        val s = sel
        if (s == null) {
            item { Text("Channels without guide data. OK to pick one.", color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp)) }
            item { TvField("Filter channels", cq, { cq = it }) }
            items(unmatched) { c ->
                Text("${c.number}  ${c.name}", color = TextMain, fontSize = 17.sp, maxLines = 1,
                    modifier = Modifier.fillMaxWidth().tvFocus { sel = c; iq = c.name }.padding(10.dp))
            }
            if (unmatched.isEmpty()) item { Text("Every channel already has guide data (or no guide is loaded yet).", color = TextDim) }
        } else {
            item { Text("Guide entry for: ${s.name}", color = TextMain, fontSize = 18.sp, modifier = Modifier.padding(vertical = 6.dp)) }
            item { TvField("Search guide channels by id or name", iq, { iq = it }) }
            item { TvButton("Back to channel list") { sel = null } }
            val l = ids.filter { iq.isBlank() || it.first.lowercase().contains(iq.lowercase()) || it.second.lowercase().contains(iq.lowercase()) }.take(80)
            items(l) { (id, name) ->
                Text("$name   [$id]", color = TextMain, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().tvFocus { m.mapChannel(s, id); sel = null }.padding(10.dp))
            }
            if (ids.isEmpty()) item { Text("No guide channel list yet. Refresh the guide first (Settings).", color = TextDim) }
        }
    }
}

@Composable
fun ReminderRows(m: AppModel) {
    val now = System.currentTimeMillis()
    for (r in Reminders.items.sortedBy { it.startMs }) {
        Text("${r.title}  |  ${r.channel}  |  ${hhmm(r.startMs / 1000)}  (OK: cancel)", color = TextMain, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().tvFocus { m.cancelReminder(r.id) }.padding(10.dp))
    }
}
