// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import io.github.kmmarin.tvbro.*

class MenuState(val title: String, val items: List<Pair<String, () -> Unit>>)
class PromptState(val title: String, val initial: String, val password: Boolean, val onOk: (String) -> Unit)

/** Generic popup list of actions (channel / group menus). */
@Composable
fun ActionMenu(m: AppModel) {
    val s = m.menu ?: return
    BackHandler { m.menu = null }
    RestoreFocusOnClose(s) { m.menu != null || m.prompt != null || m.progMenu != null }
    val fr = remember(s) { FocusRequester() }
    LaunchedEffect(s) { delay(100); runCatching { fr.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        // long menus scroll with the focus (the TV screen is only ~540 dp tall)
        Column(Modifier.width(520.dp).heightIn(max = 520.dp).focusTrap().background(Surface).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(24.dp)) {
            Text(s.title, color = Accent, fontSize = 20.sp, maxLines = 1, modifier = Modifier.padding(bottom = 10.dp))
            s.items.forEachIndexed { i, (label, act) ->
                TvButton(label, Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(fr) else Modifier)) { m.menu = null; act() }
            }
            TvButton("Cancel", Modifier.fillMaxWidth()) { m.menu = null }
        }
    }
}

@Composable
fun PromptDialog(m: AppModel) {
    val s = m.prompt ?: return
    BackHandler { m.prompt = null }
    RestoreFocusOnClose(s) { m.menu != null || m.prompt != null || m.progMenu != null }
    var v by remember(s) { mutableStateOf(s.initial) }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(480.dp).focusTrap().background(Surface).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TvField(s.title, v, { v = it }, password = s.password, focusFirst = true)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("OK") { m.prompt = null; s.onOk(v) }
                TvButton("Cancel") { m.prompt = null }
            }
        }
    }
}
