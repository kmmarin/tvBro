// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kmmarin.tvbro.*

/**
 * A popup list of actions or choices. Focus opens on [focus] when given, else on the current choice (the item marked
 * "* ", the choosers' convention), else on the first item.
 */
class MenuState(val title: String, val items: List<Pair<String, () -> Unit>>, val focus: Int = -1) {
    val focusIndex: Int get() = MenuFocus.index(items.map { it.first }, focus)
}
class PromptState(val title: String, val initial: String, val password: Boolean, val onOk: (String) -> Unit)

/** Which row of a menu takes focus when it opens (unit tested). */
object MenuFocus {
    fun index(labels: List<String>, focus: Int): Int {
        if (focus in labels.indices) return focus
        val cur = labels.indexOfFirst { it.trimStart().startsWith("* ") }
        return if (cur >= 0) cur else 0
    }
}

private val PopupShape = RoundedCornerShape(12.dp)

/**
 * The app's one pop-up (menus, programme details, prompts, reminder, update, Up next). A Surface panel with a title in
 * the accent colour; Back (or a tap outside on a touch screen) closes it through [onDismiss], so lists have no Cancel /
 * Close item. [dim]: the screen behind is dimmed and blocked; false for a card over playback (reminder, Up next).
 * Focus moves in at [focus] when it opens (retried until it is inside: also over Settings, see [TakeLayerFocus]),
 * stays inside, and goes back where it was when it closes ([m] null: no other layers to wait for).
 */
@Composable
fun Popup(
    key: Any,
    title: String?,
    onDismiss: () -> Unit,
    m: AppModel? = null,
    width: Dp = 560.dp,
    align: Alignment = Alignment.Center,
    dim: Boolean = true,
    restoreFocus: Boolean = true,
    focus: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler { onDismiss() }
    if (restoreFocus) RestoreFocusOnClose(key) { m != null && m.layerOpen() }
    val layer = remember(key) { LayerFocus() }
    if (focus != null) TakeLayerFocus(key, layer, focus)
    Box(
        Modifier.fillMaxSize().then(if (dim) Modifier.background(Color(0xCC000000)).pointerInput(key) { detectTapGestures { onDismiss() } } else Modifier),
        contentAlignment = align,
    ) {
        Column(
            Modifier.padding(if (dim) 0.dp else 28.dp).width(width).heightIn(max = 520.dp).enterAnim(key).popupLayer(layer)
                .background(Surface, PopupShape).pointerInput(Unit) { detectTapGestures { } } // (a tap on the panel is not outside)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(24.dp),
        ) {
            if (title != null) Text(title, color = Accent, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 10.dp))
            content()
        }
    }
}

/** One full-width choice of a pop-up list; [first] takes focus when the pop-up opens. */
@Composable
fun PopupItem(label: String, first: FocusRequester? = null, onClick: () -> Unit) =
    TvButton(label, Modifier.fillMaxWidth().then(if (first != null) Modifier.focusRequester(first) else Modifier), onClick)

/** The button row of a pop-up that asks for a decision: the first button is the main action and takes focus. */
@Composable
fun PopupButtons(first: FocusRequester?, vararg buttons: Pair<String, () -> Unit>) {
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        buttons.forEachIndexed { i, (label, act) -> TvButton(label, if (i == 0 && first != null) Modifier.focusRequester(first) else Modifier, act) }
    }
}

/** Generic pop-up list of actions (channel / group / title menus, choosers): choosing one closes the list first. */
@Composable
fun ActionMenu(m: AppModel) {
    val s = m.menu ?: return
    val fr = remember(s) { FocusRequester() }
    val at = s.focusIndex
    Popup(s, s.title, { m.menu = null }, m, width = 520.dp, focus = fr) {
        s.items.forEachIndexed { i, (label, act) -> PopupItem(label, if (i == at) fr else null) { m.menu = null; act() } }
    }
}

@Composable
fun PromptDialog(m: AppModel) {
    val s = m.prompt ?: return
    var v by remember(s) { mutableStateOf(s.initial) }
    val fr = remember(s) { FocusRequester() }
    Popup(s, s.title, { m.prompt = null }, m, width = 480.dp, focus = fr) {
        TvField("", v, { v = it }, password = s.password, requester = fr)
        PopupButtons(null, "OK" to { m.prompt = null; s.onOk(v) }, "Cancel" to { m.prompt = null })
    }
}

/**
 * "Group hidden - Undo" after hiding a group: focus is on Undo for 8 seconds, so one OK brings the group back.
 * Back, an arrow or the timeout closes it and focus goes on in the list (the group now in the hidden one's place).
 */
@Composable
fun UndoBar(m: AppModel) {
    val s = m.undo ?: return
    BackHandler { m.undo = null }
    RestoreFocusOnClose(s) { m.layerOpen() }
    val fr = remember(s) { FocusRequester() }
    val layer = remember(s) { LayerFocus() }
    TakeLayerFocus(s, layer, fr)
    LaunchedEffect(s) { kotlinx.coroutines.delay(8000); if (m.undo === s) m.undo = null }
    Box(Modifier.fillMaxSize().padding(bottom = 36.dp), contentAlignment = Alignment.BottomCenter) {
        Row(Modifier.popupLayer(layer)
            .onPreviewKeyEvent { e ->
                val k = e.key
                if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && (k == androidx.compose.ui.input.key.Key.DirectionUp || k == androidx.compose.ui.input.key.Key.DirectionDown ||
                        k == androidx.compose.ui.input.key.Key.DirectionLeft || k == androidx.compose.ui.input.key.Key.DirectionRight)) { m.undo = null; true } else false
            }
            .background(Surface, PopupShape).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(s.text, color = TextMain, fontSize = 16.sp, maxLines = 1)
            TvButton("Undo", Modifier.focusRequester(fr)) { m.undo = null; s.undo() }
        }
    }
}
