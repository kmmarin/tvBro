// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.delay

/** Set while a text field has focus, so arrow keys edit text instead of moving focus. */
var typing = false

/** Holding Enter this long opens the item's menu (key auto-repeat delivers the repeats). */
const val LONG_PRESS_MS = 550L

private class PressState { var down = 0L; var last = 0L; var fired = false }

/**
 * Set when a long press opened a menu: the rest of that press (repeats, and the release, which the new menu window
 * receives) must not activate anything.
 */
private var swallowEnter = false

/** A key-down this long after the previous one is a new press (auto-repeat starts within about a second). */
private const val NEW_PRESS_GAP_MS = 1100L

/**
 * A focusable, clickable item with a clear TV-style focus look (filled + accent border). Enter (on release) or a
 * left click activates; holding Enter, a right click, the Menu key or Shift+F10 opens [onMenu].
 */
@Composable
fun Modifier.tvItem(onFocus: () -> Unit = {}, onMenu: (() -> Unit)? = null, requester: FocusRequester? = null, onClick: () -> Unit): Modifier {
    var focused by remember { mutableStateOf(false) }
    val own = remember { FocusRequester() }
    val fr = requester ?: own
    val st = remember { PressState() }
    val click = rememberUpdatedState(onClick)
    val menu = rememberUpdatedState(onMenu)
    val focusCb = rememberUpdatedState(onFocus)
    return this
        .focusRequester(fr)
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) focusCb.value() else st.down = 0 }
        .onKeyEvent { e ->
            val enter = e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.DirectionCenter
            val t = System.currentTimeMillis()
            when {
                enter && e.type == KeyEventType.KeyDown -> {
                    if (swallowEnter) return@onKeyEvent true
                    // a release can be lost (focus moved to another window): a key-down long after the last one is a new press
                    if (st.down == 0L || t - st.last > NEW_PRESS_GAP_MS) { st.down = t; st.fired = false }
                    else if (!st.fired && menu.value != null && t - st.down >= LONG_PRESS_MS) { st.fired = true; swallowEnter = true; st.down = 0; menu.value?.invoke() }
                    st.last = t
                    true
                }
                enter && e.type == KeyEventType.KeyUp -> {
                    if (swallowEnter) { swallowEnter = false; st.down = 0; return@onKeyEvent true }
                    val was = st.down != 0L; val fired = st.fired
                    st.down = 0
                    if (was && !fired) click.value()
                    was
                }
                e.type == KeyEventType.KeyDown && menu.value != null && (e.key == Key.Menu || (e.key == Key.F10 && e.isShiftPressed)) -> { menu.value?.invoke(); true }
                else -> false
            }
        }
        .focusable()
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.type != PointerEventType.Press) continue
                    if (ev.buttons.isSecondaryPressed) { runCatching { fr.requestFocus() }; menu.value?.invoke() }
                    else if (ev.buttons.isPrimaryPressed) { runCatching { fr.requestFocus() }; click.value() }
                }
            }
        }
        .clip(RoundedCornerShape(6.dp))
        .background(if (focused) Theme.surface2 else Color.Transparent)
        .border(if (focused) 3.dp else 0.dp, if (focused) Theme.accent else Color.Transparent, RoundedCornerShape(6.dp))
}

@Composable
fun T(s: String, size: Int = 15, color: Color = Theme.text, bold: Boolean = false, maxLines: Int = 1, modifier: Modifier = Modifier) =
    Text(s, modifier, color = color, fontSize = size.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, maxLines = maxLines,
        softWrap = maxLines > 1, overflow = TextOverflow.Ellipsis)

@Composable
fun Btn(label: String, onMenu: (() -> Unit)? = null, requester: FocusRequester? = null, onClick: () -> Unit) =
    Box(Modifier.tvItem(onMenu = onMenu, requester = requester, onClick = onClick).background(Theme.surface2, RoundedCornerShape(6.dp)).padding(14.dp, 8.dp)) { T(label, 14) }

/** A thin progress line (watched share, programme progress). */
@Composable
fun ProgressLine(f: Float, modifier: Modifier = Modifier) =
    Box(modifier.height(3.dp).background(Theme.surface2)) { Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).fillMaxHeight().background(Theme.accent)) }

/** Requests focus once it can (the target may still be composing); never throws. */
suspend fun FocusRequester.settle(tries: Int = 20): Boolean {
    repeat(tries) { if (runCatching { requestFocus() }.isSuccess) return true; delay(50) }
    return false
}

/** Arrow keys move focus inside a dialog; Esc closes it. */
@Composable
private fun Modifier.dialogKeys(onClose: () -> Unit): Modifier {
    val fm = LocalFocusManager.current
    return onKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
        when (e.key) {
            Key.Escape, Key.Backspace -> if (!typing || e.key == Key.Escape) { onClose(); true } else false
            Key.DirectionUp -> if (typing) false else fm.moveFocus(FocusDirection.Up)
            Key.DirectionDown -> if (typing) false else fm.moveFocus(FocusDirection.Down)
            Key.DirectionLeft -> if (typing) false else fm.moveFocus(FocusDirection.Left)
            Key.DirectionRight -> if (typing) false else fm.moveFocus(FocusDirection.Right)
            else -> false
        }
    }
}

/**
 * Pop-ups are their own small windows: they stay above the native video surface (full screen or the preview) and
 * keep focus to themselves.
 */
@Composable
fun Overlays(app: AppState) {
    app.menu?.let { m -> key(m) {
        val rows = m.items.size.coerceAtMost(12)
        // header lines wrap at about 60 characters (a restore summary has long ones)
        val head = m.title.lines().take(6).sumOf { (it.length / 60 + 1).coerceAtMost(3) }.coerceAtMost(14)
        val st = rememberDialogState(position = WindowPosition(Alignment.Center), size = androidx.compose.ui.unit.DpSize(520.dp, (90 + head * 22 + rows * 46).dp))
        DialogWindow(onCloseRequest = { app.menu = null }, state = st, title = "tvBro", undecorated = true, resizable = false) {
            val first = remember { FocusRequester() }
            LaunchedEffect(m) { first.settle() }
            Column(Modifier.fillMaxSize().background(Theme.surface).border(2.dp, Theme.accent).padding(14.dp).dialogKeys { app.menu = null }) {
                val lines = m.title.lines()
                T(lines.first(), 18, Theme.accent, bold = true, maxLines = 2)
                for (l in lines.drop(1).take(5)) T(l, 13, Theme.dim, maxLines = 3)
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(m.items) { i, (label, act) ->
                        Box(Modifier.fillMaxWidth().tvItem(requester = if (i == 0) first else null) { app.menu = null; act() }.padding(12.dp, 9.dp)) { T(label, 15) }
                    }
                }
            }
        }
    } }
    app.prompt?.let { p -> key(p) {
        val st = rememberDialogState(position = WindowPosition(Alignment.Center), size = androidx.compose.ui.unit.DpSize(520.dp, 190.dp))
        DialogWindow(onCloseRequest = { app.prompt = null }, state = st, title = "tvBro", undecorated = true, resizable = false) {
            var v by remember(p) { mutableStateOf(p.initial) }
            val fr = remember { FocusRequester() }
            LaunchedEffect(p) { fr.settle() }
            val done = { app.prompt = null; typing = false; p.done(v) }
            Column(Modifier.fillMaxSize().background(Theme.surface).border(2.dp, Theme.accent).padding(16.dp).dialogKeys { app.prompt = null; typing = false },
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                T(p.title, 16, Theme.accent, bold = true)
                OutlinedTextField(v, { v = it }, Modifier.fillMaxWidth().focusRequester(fr).onFocusChanged { typing = it.isFocused }
                    .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { done(); true } else false },
                    singleLine = true, visualTransformation = if (p.secret) PasswordVisualTransformation() else VisualTransformation.None)
                T("Enter to save · Esc to cancel", 12, Theme.dim)
            }
        }
    } }
    app.picker?.let { pk -> key(pk) {
        val st = rememberDialogState(position = WindowPosition(Alignment.Center), size = androidx.compose.ui.unit.DpSize(560.dp, 600.dp))
        val close = { app.picker = null; typing = false }
        DialogWindow(onCloseRequest = close, state = st, title = "tvBro", undecorated = true, resizable = false) {
            var q by remember(pk) { mutableStateOf("") }
            var rows by remember(pk) { mutableStateOf(pk.search("")) }
            LaunchedEffect(q) { delay(150); rows = pk.search(q) } // debounce typing
            val field = remember { FocusRequester() }
            val first = remember { FocusRequester() }
            val fm = LocalFocusManager.current
            LaunchedEffect(pk) { field.settle() }
            Column(Modifier.fillMaxSize().background(Theme.surface).border(2.dp, Theme.accent).padding(14.dp).dialogKeys(close),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                T(pk.title, 18, Theme.accent, bold = true, maxLines = 2)
                for (l in pk.sub.lines()) T(l, 13, Theme.dim, maxLines = 2)
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().focusRequester(field).onFocusChanged { typing = it.isFocused }
                    .onPreviewKeyEvent { e ->
                        val down = e.type == KeyEventType.KeyDown
                        when {
                            down && (e.key == Key.DirectionDown || e.key == Key.Enter || e.key == Key.NumPadEnter) -> { typing = false; runCatching { first.requestFocus() }.isSuccess || fm.moveFocus(FocusDirection.Down) }
                            else -> false
                        }
                    },
                    singleLine = true, placeholder = { T("Search the guide's channels", 14, Theme.dim) })
                val all = pk.fixed + rows
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(all, key = { i, r -> "$i|${r.first}" }) { i, (label, act) ->
                        Box(Modifier.fillMaxWidth().tvItem(requester = if (i == 0) first else null) { close(); act() }.padding(12.dp, 8.dp)) {
                            T(label, 14, if (i < pk.fixed.size) Theme.accent else Theme.text)
                        }
                    }
                    if (rows.isEmpty()) item { T(if (q.isBlank()) "No guide channels known yet" else "No guide channel matches", 13, Theme.dim, modifier = Modifier.padding(12.dp)) }
                }
            }
        }
    } }
    app.dueReminder?.takeIf { !app.full }?.let { r ->
        val st = rememberDialogState(position = WindowPosition(Alignment.Center), size = androidx.compose.ui.unit.DpSize(480.dp, 200.dp))
        DialogWindow(onCloseRequest = { app.reminderDone(r, false) }, state = st, title = "tvBro", undecorated = true, resizable = false) {
            val fr = remember { FocusRequester() }
            LaunchedEffect(r) { fr.settle() }
            Column(Modifier.fillMaxSize().background(Theme.surface).border(2.dp, Theme.accent).padding(16.dp).dialogKeys { app.reminderDone(r, false) },
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                T("Reminder", 14, Theme.accent)
                T(r.title, 20, bold = true, maxLines = 2)
                T("${r.channel} · ${io.github.kmmarin.tvbro.data.TimeFmt.time(r.startMs / 1000)}", 14, Theme.dim)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("Watch now", requester = fr) { app.reminderDone(r, true) }
                    Btn("Dismiss") { app.reminderDone(r, false) }
                }
            }
        }
    }
}
