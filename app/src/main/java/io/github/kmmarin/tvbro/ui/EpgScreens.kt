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

/** Programme details and actions in the app's one pop-up; shown over any screen while [AppModel.progMenu] is set. */
@Composable
fun ProgMenu(m: AppModel) {
    val (ch, p) = m.progMenu ?: return
    if (p == null) { m.progMenu = null; return }
    val now = System.currentTimeMillis() / 1000
    val fr = remember(ch to p) { FocusRequester() }
    Popup(ch to p, p.title, { m.progMenu = null }, m, focus = fr) {
        io.github.kmmarin.tvbro.data.EpisodeNum.line(p.ep, p.sub)?.let { Text(it, color = TextMain, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Text("${ch.name}   ${hhmm(p.start)}-${hhmm(p.stop)}", color = TextMain, fontSize = 15.sp, modifier = Modifier.padding(vertical = 4.dp))
        Text(p.desc ?: "No description.", color = TextDim, fontSize = 14.sp, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 12.dp))
        var used = false
        fun first(): FocusRequester? = if (used) null else { used = true; fr }
        if (p.start <= now && p.stop > now) PopupItem("Watch live", first()) { m.progMenu = null; m.play(ch) }
        if (p.stop <= now && CatchupUrl.supports(ch, p.start, now)) PopupItem("Watch catch-up", first()) { m.progMenu = null; m.guidePlay(ch, p) }
        if (p.stop > now) PopupItem("Record", first()) { m.progMenu = null; m.recordProgramme(ch, p) }
        if (p.start > now) PopupItem("Remind me at start", first()) { m.progMenu = null; m.remind(ch, p) }
        val rule = m.seriesRuleFor(ch, p)
        if (rule != null) PopupItem("Cancel series recording (every \"${rule.title}\")", first()) { m.progMenu = null; m.cancelSeries(rule) }
        else PopupItem("Record series (every \"${p.title}\" on ${ch.name})", first()) { m.progMenu = null; m.recordSeries(ch, p) }
        if (p.start > now || p.stop <= now) PopupItem("Watch ${ch.name} now", first()) { m.progMenu = null; m.play(ch) }
        PopupItem(if (ch.key in m.favourites) "Remove ${ch.name} from Favourites" else "Add ${ch.name} to Favourites", first()) { m.toggleFav(ch) }
        PopupItem("Channel options (rename, hide, move)...", first()) { m.progMenu = null; m.openChannelMenu(ch) }
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
        item { SectionTitle("Map channels to guide") }
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
