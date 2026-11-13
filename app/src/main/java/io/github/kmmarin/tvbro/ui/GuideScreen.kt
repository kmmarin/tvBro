// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import io.github.kmmarin.tvbro.*
import io.github.kmmarin.tvbro.data.Channel
import io.github.kmmarin.tvbro.data.Programme

private const val DP_PER_MIN = 5
private val LABEL_W = 200.dp

@Composable
fun GuideScreen(m: AppModel) {
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
    var sel by remember { mutableStateOf<Pair<Channel, Programme?>?>(null) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(list.isNotEmpty()) { if (list.isNotEmpty()) { delay(300); runCatching { fr.requestFocus() } } }

    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        Column(Modifier.fillMaxWidth().height(116.dp)) {
            val s = sel
            if (s != null) {
                val p = s.second
                Text(s.first.name + (p?.let { "  -  ${it.title}  (${hhmm(it.start)}-${hhmm(it.stop)})" } ?: ""), color = TextMain, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(p?.let { io.github.kmmarin.tvbro.data.EpisodeNum.line(it.ep, it.sub) }, p?.desc).joinToString("  ·  "), color = TextDim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (p != null && p.stop <= now) {
                    Text(if (s.first.catchup != null) "OK: watch catch-up" else "No catch-up for this channel", color = Accent, fontSize = 13.sp)
                } else Text("OK: watch    Long-press OK: details, record, remind", color = Accent, fontSize = 13.sp)
            } else Text("TV guide", color = TextMain, fontSize = 20.sp)
            TvButton("Search guide") { m.screen = Screen.GUIDE_SEARCH }
        }
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
        // opens at the playing channel (else the focused one), its programme on now focused
        val startIdx = remember { (m.current ?: m.focusCh)?.key?.let { k -> list.indexOfFirst { it.key == k } }?.coerceAtLeast(0) ?: 0 }
        val rows = androidx.compose.foundation.lazy.rememberLazyListState((startIdx - 2).coerceAtLeast(0))
        LazyColumn(Modifier.weight(1f), state = rows) {
            items(list) { ch ->
                val first = ch.key == list.getOrNull(startIdx)?.key
                // the channel name takes focus: Left from the first programme lands here (the guide never scrolls into
                // the past); OK plays the channel, long-press opens its menu (catch-up, record...)
                val labelFr = remember { FocusRequester() }
                val toLabel = { runCatching { labelFr.requestFocus() }; true }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.width(LABEL_W).height(52.dp).padding(end = 6.dp).focusRequester(labelFr)
                        .onFocusChanged { if (it.isFocused) sel = ch to null }
                        .tvFocus(onLongClick = { m.openChannelMenu(ch) }) { m.play(ch) }.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Logo(ch.logo, ch.name, 32.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("${ch.number} ${ch.name}", color = TextMain, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.weight(1f).horizontalScroll(scroll)) {
                        Row(Modifier.width(totalDp)) {
                            val progs = m.epg.programmes(ch)
                            if (progs.isEmpty()) {
                                Cell(m, ch, null, "No guide data", totalDp, now, first, fr, toLabel) { sel = ch to null }
                            } else {
                                var cursor = gridStart
                                var gaveFocus = false
                                for (p in progs) {
                                    val s = maxOf(p.start, cursor)
                                    val e = minOf(p.stop, gridEnd)
                                    if (e <= s) continue
                                    if (s > cursor) Spacer(Modifier.width((((s - cursor) / 60).toInt() * DP_PER_MIN).dp))
                                    val w = (((e - s) / 60).toInt() * DP_PER_MIN - 2).coerceAtLeast(4).dp
                                    val isNow = p.start <= now && p.stop > now
                                    // first focus: the programme on now (or the next one), never a finished one
                                    val upcoming = p.stop > now
                                    Cell(m, ch, p, p.title, w, now, first && upcoming && !gaveFocus, fr, if (s <= gridStart) toLabel else null) { sel = ch to p }
                                    if (upcoming || isNow) gaveFocus = true
                                    Spacer(Modifier.width(2.dp))
                                    cursor = e
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Cell(
    m: AppModel, ch: Channel, p: Programme?, title: String, width: androidx.compose.ui.unit.Dp,
    now: Long, requester: Boolean, fr: FocusRequester, toLabel: (() -> Boolean)?, onFocus: () -> Unit,
) {
    val live = p != null && p.start <= now && p.stop > now
    val past = p != null && p.stop <= now
    Column(
        Modifier.width(width).height(52.dp)
            .requesterIf(requester, fr)
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
