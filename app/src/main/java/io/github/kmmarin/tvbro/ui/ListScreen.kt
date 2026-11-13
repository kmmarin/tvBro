// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import io.github.kmmarin.tvbro.*
import io.github.kmmarin.tvbro.data.Channel

/**
 * Text input that behaves like a button until OK is pressed: focusing it never opens the keyboard.
 * OK starts editing (keyboard opens); Back or the IME "done" key stops editing and returns focus to the button.
 */
/**
 * Closes the on-screen keyboard through the window as well (Done on a touch keyboard): a keyboard left open covers the
 * lower part of Add source, and its window takes the touches meant for Save.
 */
private fun hideIme(view: android.view.View) {
    runCatching {
        (view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(view.windowToken, 0)
    }
    runCatching {
        val w = (view.context as? android.app.Activity)?.window ?: return
        androidx.core.view.WindowCompat.getInsetsController(w, view).hide(androidx.core.view.WindowInsetsCompat.Type.ime())
    }
}

@Composable
fun TvField(
    label: String, value: String, onChange: (String) -> Unit,
    modifier: Modifier = Modifier, password: Boolean = false, requester: FocusRequester? = null,
    /** Take focus when first shown (pop-ups: OK must never reach the screen underneath). */
    focusFirst: Boolean = false,
) {
    var editing by remember { mutableStateOf(false) }
    var was by remember { mutableStateOf(false) }
    var f by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    val ownBoxFr = remember { FocusRequester() }
    val boxFr = requester ?: ownBoxFr // (a caller's requester focuses this field's box)
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val fm = androidx.compose.ui.platform.LocalFocusManager.current
    val view = androidx.compose.ui.platform.LocalView.current
    val shape = RoundedCornerShape(8.dp)
    // Up/Down while typing ends the editing and moves on, like from any other row (a one-line field is no trap)
    var leave by remember { mutableStateOf<androidx.compose.ui.focus.FocusDirection?>(null) }
    // the field lost focus while typing (Back on the Fire TV keyboard closes it and takes focus away): editing ends
    var lost by remember { mutableStateOf(false) }
    if (focusFirst) LaunchedEffect(Unit) { repeat(5) { delay(100); if (runCatching { boxFr.requestFocus() }.isSuccess) return@LaunchedEffect } }
    LaunchedEffect(editing) {
        if (editing) { was = true; delay(50); runCatching { fr.requestFocus() }; kb?.show() }
        else if (was) {
            was = false
            delay(50)
            runCatching { boxFr.requestFocus() } // (also after the keyboard took focus away: back on this field)            lost = false
            leave?.let { d -> leave = null; delay(30); fm.moveFocus(d) }
        }
    }
    Column(modifier) {
        Text(label, color = TextDim, fontSize = 13.sp)
        if (!editing) {
            Box(
                Modifier.fillMaxWidth().focusRequester(boxFr).tvFocus(shape) { editing = true }
                    .border(1.dp, TextDim.copy(alpha = 0.4f), shape).padding(12.dp),
            ) {
                val shown = if (password) "*".repeat(value.length) else value
                Text(if (shown.isEmpty()) "Press OK to type" else shown, color = if (shown.isEmpty()) TextDim else TextMain, fontSize = 18.sp, maxLines = 1)
            }
        } else {
            androidx.activity.compose.BackHandler { editing = false; kb?.hide() }
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = TextMain, fontSize = 18.sp),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { kb?.hide(); hideIme(view); editing = false }),
                visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation()
                else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().focusRequester(fr).countFocus()
                    .onFocusChanged { val w = f; f = it.isFocused; if (w && !f && editing) { lost = true; editing = false; kb?.hide() } }
                    .onPreviewKeyEvent { e ->
                        if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) false
                        else when (e.key) {
                            androidx.compose.ui.input.key.Key.DirectionDown -> { leave = androidx.compose.ui.focus.FocusDirection.Down; editing = false; kb?.hide(); true }
                            androidx.compose.ui.input.key.Key.DirectionUp -> { leave = androidx.compose.ui.focus.FocusDirection.Up; editing = false; kb?.hide(); true }
                            else -> false
                        }
                    }
                    .background(if (f) FocusBg else Surface, shape)
                    .border(3.dp, Accent, shape)
                    .padding(12.dp),
            )
        }
    }
}
@Composable
fun ListScreen(m: AppModel) {
    val groups = m.groupList
    val list = m.filtered
    val now = rememberNow()
    val startIdx = remember { maxOf(0, list.indexOfFirst { it.key == m.current?.key }) }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = startIdx)
    val fr = remember { FocusRequester() }
    LaunchedEffect(m.channels.isNotEmpty()) {
        if (m.channels.isNotEmpty()) { delay(150); runCatching { fr.requestFocus() } }
    }
    Row(Modifier.fillMaxSize().background(Bg).padding(24.dp)) {
        Column(Modifier.width(260.dp).fillMaxHeight()) {
            Text("tvBro", color = Accent, fontSize = 24.sp, modifier = Modifier.padding(bottom = 12.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(groups) { g ->
                    val sel = g == m.group
                    Text(
                        (if (sel) "> " else "  ") + m.groupLabel(g), color = if (sel) Accent else TextMain, fontSize = 17.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().focusProperties { right = if (list.isNotEmpty()) fr else FocusRequester.Default }.tvFocus(onLongClick = { m.openGroupMenu(g) }) { m.selectGroup(g) }.padding(10.dp),
                    )
                }
            }
            TvButton("TV guide", Modifier.fillMaxWidth()) { m.screen = Screen.GUIDE }
            TvButton("Movies", Modifier.fillMaxWidth()) { m.openVod(false) }
            TvButton("Series", Modifier.fillMaxWidth()) { m.openVod(true) }
            TvButton("Recordings", Modifier.fillMaxWidth()) { m.screen = Screen.RECORDINGS }
            TvButton("Settings", Modifier.fillMaxWidth()) { m.screen = Screen.SETTINGS }
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            TvField("Search", m.query, { m.query = it })
            val status = m.loading.ifEmpty { m.message }
            if (status.isNotEmpty()) Text(status, color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))
            if (list.isEmpty() && m.loading.isEmpty()) {
                Text(
                    if (m.group == GROUP_FAV) "No favourites yet. Long-press OK on a channel for its menu." else "No channels.",
                    color = TextDim, modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(Modifier.weight(1f).padding(top = 8.dp), state = state) {
                itemsIndexed(list) { i, c ->
                    ChannelRow(m, c, now, Modifier.requesterIf(i == startIdx, fr))
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(m: AppModel, c: Channel, now: Long, mod: Modifier) {
    val (cur, next) = m.epg.nowNext(c, now)
    Row(
        mod.fillMaxWidth().tvFocus(onLongClick = { m.openChannelMenu(c) }) { m.play(c) }.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(c.number.toString(), color = TextDim, fontSize = 16.sp, modifier = Modifier.width(56.dp))
        Logo(c.logo, c.name)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text(c.name, color = TextMain, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                if (c.key in m.favourites) Text("  *", color = Accent, fontSize = 18.sp)
                if (c.catchup != null) Text("  [archive]", color = TextDim, fontSize = 12.sp)
            }
            if (cur != null) {
                Text("${hhmm(cur.start)} ${cur.title}", color = TextMain.copy(alpha = 0.85f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.fillMaxWidth().height(3.dp).background(Surface)) {
                    val f = ((now - cur.start).toFloat() / (cur.stop - cur.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth(f).height(3.dp).background(Accent))
                }
            }
            if (next != null) Text("${hhmm(next.start)} ${next.title}", color = TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
