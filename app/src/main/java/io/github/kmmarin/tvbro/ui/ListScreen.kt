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
            runCatching { boxFr.requestFocus() } // (also after the keyboard took focus away: back on this field)
            lost = false
            leave?.let { d -> leave = null; delay(30); fm.moveFocus(d) }
        }
    }
    Column(modifier) {
        if (label.isNotEmpty()) Text(label, color = TextDim, fontSize = 13.sp)
        if (!editing) {
            Box(
                Modifier.fillMaxWidth().focusRequester(boxFr).tvFocus(shape, rest = Surface, outline = true) { editing = true }.padding(12.dp),
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
                // a password field says so to the keyboard: the Fire TV's full-screen keyboard otherwise shows it in
                // clear text and keyboards may learn it
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    keyboardType = if (password) androidx.compose.ui.text.input.KeyboardType.Password else androidx.compose.ui.text.input.KeyboardType.Text,
                    autoCorrectEnabled = !password,
                ),
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
                    .fieldLook(f, shape)
                    .padding(12.dp),
            )
        }
    }
}
