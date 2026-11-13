// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import io.github.kmmarin.tvbro.*
import io.github.kmmarin.tvbro.data.Channel

/**
 * Channels and groups editor: groups on the left (provider groups and own groups, hidden ones dimmed), the selected
 * group's channels on the right. OK opens the actions for a row. Edits are kept across syncs (they are keyed by
 * channel key and original group name).
 */
@Composable
fun EditorScreen(m: AppModel) {
    BackHandler { m.screen = Screen.SETTINGS }
    val p = m.prefs
    val own = p.custom.keys.toList()
    val prov = m.providerGroups()
    var sel by remember { mutableStateOf(own.firstOrNull() ?: prov.firstOrNull()) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { fr.requestFocus() } }
    val pos = remember(p.order) { p.order.withIndex().associate { it.value to it.index } }
    val g = sel
    val chans: List<Channel> = when {
        g == null -> emptyList()
        g in p.custom -> (p.custom[g] ?: emptyList()).mapNotNull { k -> m.channels.firstOrNull { it.key == k } }
        else -> m.channels.filter { it.group == g }.let { l -> if (pos.isEmpty()) l else l.sortedBy { pos[it.key] ?: Int.MAX_VALUE } }
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Channels and groups", color = Accent, fontSize = 26.sp, modifier = Modifier.weight(1f))
            TvButton("+ New group", Modifier.focusRequester(fr)) { m.newGroup() }
            Spacer(Modifier.width(8.dp))
            TvButton("Reset all") {
                m.menu = MenuState("Reset every rename, hidden item, order and own group?", listOf("Yes, reset to the provider's layout" to { m.resetLayout() }, "Cancel" to {}))
            }
            Spacer(Modifier.width(8.dp))
            TvButton("Done") { m.screen = Screen.SETTINGS }
        }
        Text("OK on a row for its actions. Hidden items disappear from TV, the guide and search.", color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.width(380.dp).fillMaxHeight()) {
                items(own + prov) { gr ->
                    val mine = gr in p.custom
                    val hidden = gr in p.hiddenGroups
                    val n = if (mine) p.custom[gr]?.size ?: 0 else m.channels.count { it.group == gr }
                    val label = (if (mine) "* " else "") + (p.groupNames[gr]?.let { "$it  ($gr)" } ?: gr)
                    Row(Modifier.fillMaxWidth().padding(1.dp).onFocusChanged { if (it.isFocused) sel = gr }
                        .tvFocus { groupActions(m, gr, mine, hidden) }.background(if (gr == g) Surface else Bg).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = if (hidden) TextDim else if (gr == g) Accent else TextMain, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text((if (hidden) "hidden · " else "") + "$n", color = TextDim, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            LazyColumn(Modifier.weight(1f).fillMaxHeight()) {
                if (g != null && chans.isEmpty()) item { Text("No channels in this group. Add channels from their own group (OK > Add to one of my groups).", color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(10.dp)) }
                items(chans) { c ->
                    val hidden = c.key in p.hiddenCh
                    val name = p.names[c.key]
                    Row(Modifier.fillMaxWidth().padding(1.dp).tvFocus { channelActions(m, c, g, hidden) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.number.toString(), color = TextDim, fontSize = 13.sp, modifier = Modifier.width(52.dp), maxLines = 1, softWrap = false)
                        Logo(c.logo, c.name, 28.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(name?.let { "$it  (${c.name})" } ?: c.name, color = if (hidden) TextDim else TextMain, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (hidden) Text("hidden", color = TextDim, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

private fun groupActions(m: AppModel, g: String, mine: Boolean, hidden: Boolean) {
    val items = ArrayList<Pair<String, () -> Unit>>()
    if (mine) {
        items += "Rename" to {
            m.prompt = PromptState("Group name", g, false) { v ->
                val n = v.trim()
                if (n.isNotEmpty() && n != g && n !in m.prefs.custom) m.editPrefs { p -> p.copy(custom = p.custom.entries.associate { (k, l) -> (if (k == g) n else k) to l }) }
            }
        }
        items += "Move up" to { m.editPrefs { p -> p.copy(custom = reorder(p.custom, g, -1)) } }
        items += "Move down" to { m.editPrefs { p -> p.copy(custom = reorder(p.custom, g, 1)) } }
        items += "Delete group" to { m.editPrefs { p -> p.copy(custom = p.custom - g) }; if (m.group == g) m.group = GROUP_ALL }
    } else {
        items += "Rename (empty = provider name)" to {
            m.prompt = PromptState("Group name", m.prefs.groupNames[g] ?: g, false) { v ->
                val n = v.trim()
                m.editPrefs { p -> p.copy(groupNames = if (n.isEmpty() || n == g) p.groupNames - g else p.groupNames + (g to n)) }
            }
        }
        items += (if (hidden) "Show group" else "Hide group") to { m.editPrefs { p -> p.copy(hiddenGroups = if (hidden) p.hiddenGroups - g else p.hiddenGroups + g) } }
        items += "Move up" to { m.moveGroup(g, -1) }
        items += "Move down" to { m.moveGroup(g, 1) }
    }
    items += (if (g in m.prefs.locked) "Unlock group" else "Lock group (PIN)") to { m.toggleLock(g) }
    m.menu = MenuState(m.prefs.groupNames[g] ?: g, items)
}

private fun reorder(map: Map<String, List<String>>, k: String, d: Int): Map<String, List<String>> {
    val keys = map.keys.toMutableList(); val i = keys.indexOf(k); val j = i + d
    if (i < 0 || j < 0 || j >= keys.size) return map
    keys[i] = keys[j].also { keys[j] = keys[i] }
    return LinkedHashMap<String, List<String>>().apply { for (x in keys) put(x, map[x] ?: emptyList()) }
}

private fun channelActions(m: AppModel, c: Channel, g: String?, hidden: Boolean) {
    val mine = g != null && g in m.prefs.custom
    val items = ArrayList<Pair<String, () -> Unit>>()
    items += "Rename (empty = provider name)" to {
        m.prompt = PromptState("Channel name", m.prefs.names[c.key] ?: c.name, false) { v ->
            val n = v.trim()
            m.editPrefs { p -> p.copy(names = if (n.isEmpty() || n == c.name) p.names - c.key else p.names + (c.key to n)) }
        }
    }
    if (mine) {
        items += "Move up" to { m.moveInCustom(g!!, c.key, -1) }
        items += "Move down" to { m.moveInCustom(g!!, c.key, 1) }
        items += "Remove from this group" to { m.editPrefs { p -> p.copy(custom = p.custom + (g!! to (p.custom[g] ?: emptyList()).filter { it != c.key })) } }
    } else {
        items += (if (hidden) "Show channel" else "Hide channel") to { m.editPrefs { p -> p.copy(hiddenCh = if (hidden) p.hiddenCh - c.key else p.hiddenCh + c.key) } }
        items += "Move up" to { m.moveChannelKey(c.key, -1) }
        items += "Move down" to { m.moveChannelKey(c.key, 1) }
    }
    items += "Add to one of my groups..." to {
        val gs = m.prefs.custom.keys.map { x -> x to { m.editPrefs { p -> p.copy(custom = p.custom + (x to ((p.custom[x] ?: emptyList()) + c.key).distinct())) } } }
        m.menu = MenuState("Add ${c.name} to", gs + ("New group..." to {
            m.prompt = PromptState("New group name", "", false) { v ->
                val n = v.trim(); if (n.isNotEmpty() && n != GROUP_ALL && n != GROUP_FAV) m.editPrefs { p -> p.copy(custom = p.custom + (n to ((p.custom[n] ?: emptyList()) + c.key).distinct())) }
            }
        }))
    }
    items += (if (c.key in m.favourites) "Remove from My list" else "Add to My list") to { m.toggleFav(c) }
    m.menu = MenuState(m.prefs.names[c.key] ?: c.name, items)
}
