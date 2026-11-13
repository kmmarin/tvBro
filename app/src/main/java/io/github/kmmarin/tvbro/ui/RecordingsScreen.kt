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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kmmarin.tvbro.*
import io.github.kmmarin.tvbro.data.DlRepo
import io.github.kmmarin.tvbro.data.RecRepo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordingsScreen(m: AppModel) {
    // as its own screen Back returns to the home screen; as a home section the home screen handles Back
    BackHandler(enabled = m.screen == Screen.RECORDINGS) { m.screen = Screen.LIST }
    val ctx = LocalContext.current
    val dir = RecRepo.currentDir(ctx, m.store.recStorage)
    val fmt = remember { object { fun format(d: Date) = io.github.kmmarin.tvbro.data.TimeFmt.dayTime(d.time) } }
    val list = RecRepo.items.sortedByDescending { it.startMs }
    var confirm by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().background(Bg).padding(32.dp)) {
        item { Text("Recordings", color = Accent, fontSize = 28.sp) }
        item {
            Text("Saving to ${remember(m.storageTick) { m.storageLabel() }} (minimum ${m.store.recMinFreeMb} MB free). Change in Settings > Recording.",
                color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
        }
        m.current?.let { c ->
            item {
                val a = m.activeRec(c)
                TvButton(if (a != null) "Stop recording ${c.name}" else "Record ${c.name} now (until stopped)") {
                    if (a != null) m.stopRec(a) else m.recordNow(c, 0)
                }
            }
        }
        val rules = io.github.kmmarin.tvbro.data.SeriesRules.items
        if (rules.isNotEmpty()) {
            item { Text("Series recordings (new airings are scheduled from the guide, 48 h ahead)", color = TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp)) }
            items(rules, key = { "rule" + it.id }) { r ->
                Text("Every \"${r.title}\" on ${r.channel}    OK: options", color = TextMain, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().tvFocus {
                        m.menu = MenuState(r.title, listOf("Cancel series recording" to { m.cancelSeries(r) }, "Check the guide now" to { m.scheduleSeries() }))
                    }.padding(12.dp))
            }
        }
        item { Text("Downloads (movies and episodes, Wi-Fi/Ethernet only)", color = TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp)) }
        items(DlRepo.items.sortedBy { it.title }) { d ->
            val have = DlRepo.progress[d.id] ?: File(d.path).takeIf { it.exists() }?.length() ?: 0L
            val pct = if (d.total > 0) "  ${have * 100 / d.total}%" else ""
            val running = d.status == "downloading" || d.status == "queued"
            Column(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).tvFocus(onLongClick = {
                    m.menu = MenuState(d.title, listOf(
                        (if (running) "Pause" else "Resume") to { if (running) m.pauseDl(d) else m.resumeDl(d) },
                        "Cancel and delete" to { m.cancelDl(d) },
                    ))
                }) { when (d.status) { "done" -> m.playLocal(d); "downloading", "queued" -> m.pauseDl(d); else -> m.resumeDl(d) } }.padding(12.dp),
            ) {
                Text(d.title, color = TextMain, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${d.status}$pct  |  ${have / (1024 * 1024)} MB" + (if (d.note.isNotEmpty()) "  |  ${d.note}" else "") +
                    (if (d.status == "done") "    OK: play" else if (running) "    OK: pause" else "    OK: resume"), color = TextDim, fontSize = 14.sp)
            }
        }
        if (DlRepo.items.isEmpty()) item { Text("No downloads. Use Download on a movie page or Download season on a show.", color = TextDim) }
        if (list.isEmpty()) item { Text("No recordings. Press the red button while watching, or long-press OK on a programme in the guide.", color = TextDim, modifier = Modifier.padding(top = 12.dp)) }
        items(list) { r ->
            val size = (RecRepo.progress[r.id] ?: File(r.path).takeIf { r.path.isNotEmpty() }?.length() ?: 0L) / (1024 * 1024)
            val state = when (r.status) {
                "scheduled" -> "scheduled"
                "recording" -> "RECORDING"
                "done" -> "done"
                else -> "failed" + if (r.note.isNotEmpty()) " (${r.note})" else ""
            }
            Column(
                Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .onFocusChanged { if (it.isFocused) m.favAction = { m.toggleFavRec(r) } }
                    .tvFocus(onLongClick = {
                        m.menu = MenuState(r.title, listOf(
                            (if ("r|${r.id}" in m.favourites) "Remove from My list" else "Add to My list") to { m.toggleFavRec(r) },
                            "Delete" to { confirm = r.id },
                        ))
                    }) {
                        when (r.status) {
                            "done" -> m.playRec(r)
                            "recording", "scheduled" -> m.stopRec(r)
                            else -> confirm = r.id
                        }
                    }.padding(12.dp),
            ) {
                Text((if ("r|${r.id}" in m.favourites) "* " else "") + r.title, color = TextMain, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${r.channel}  |  ${fmt.format(Date(r.startMs))}  |  $state  |  $size MB", color = TextDim, fontSize = 14.sp)
                Text(
                    when (r.status) { "done" -> "OK: play    Long-press: delete"; "failed" -> "OK: delete"; else -> "OK: stop / cancel    Long-press: delete" },
                    color = Accent, fontSize = 12.sp,
                )
                if (confirm == r.id) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                        TvButton("Delete recording and file") { m.deleteRec(r); confirm = null }
                        TvButton("Keep") { confirm = null }
                    }
                }
            }
        }
    }
}
