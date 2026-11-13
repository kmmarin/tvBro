// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import io.github.kmmarin.tvbro.ui.Accent
import io.github.kmmarin.tvbro.ui.Bg
import io.github.kmmarin.tvbro.ui.TextDim
import io.github.kmmarin.tvbro.ui.TextMain
import io.github.kmmarin.tvbro.ui.tvFocus

@Composable
private fun Row2(title: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).tvFocus(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = TextMain, fontSize = 18.sp, modifier = Modifier.weight(1f))
        Text(value, color = Accent, fontSize = 18.sp)
    }
}

@Composable
private fun Btn(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.tvFocus(onClick = onClick).padding(horizontal = 22.dp, vertical = 12.dp)) {
        Text(label, color = TextMain, fontSize = 18.sp)
    }
}

/** One label + action for the current state, shared by the dialog and Settings. */
private fun primary(s: Updater.State): String? = when (s) {
    is Updater.State.Available -> "Update"
    is Updater.State.Downloading -> null
    is Updater.State.Ready -> "Install"
    is Updater.State.Failed -> if (s.release != null) "Try again" else null
    else -> null
}

private fun statusText(s: Updater.State): String? = when (s) {
    is Updater.State.Downloading -> "Downloading ${s.percent} %"
    is Updater.State.Ready -> Updater.readyHint ?: "Downloaded and checked. Choose Install, then Install in the next screen."
    is Updater.State.Failed -> s.message
    else -> null
}

/** The non-blocking "Update available" dialog offered after the daily start-up check. Fully usable with a D-pad. */
@Composable
fun UpdatePrompt() {
    val rel = Updater.prompt ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(rel.version) { runCatching { first.requestFocus() } }
    val s = Updater.state
    val act: () -> Unit = {
        when (s) {
            is Updater.State.Ready -> Updater.install(ctx, s.release, s.file)
            is Updater.State.Downloading -> Unit
            else -> scope.launch { Updater.download(ctx.applicationContext, rel) }
        }
    }
    // A real dialog window: D-pad focus stays inside it and Back means Later.
    Dialog(onDismissRequest = { Updater.later() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(640.dp).background(Bg, RoundedCornerShape(14.dp)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Update available: tvBro ${rel.version}", color = TextMain, fontSize = 24.sp)
            Text("You have ${Updater.currentVersion(ctx)}.", color = TextDim, fontSize = 16.sp)
            if (rel.notes.isNotBlank()) {
                Text(rel.notes.take(1200), color = TextDim, fontSize = 15.sp,
                    modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
            }
            statusText(s)?.let { Text(it, color = Accent, fontSize = 16.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val label = when (s) { is Updater.State.Downloading -> "Downloading..."; else -> primary(s) ?: "Update" }
                Btn(label, Modifier.focusRequester(first), act)
                Btn("Later") { Updater.later() }
                Btn("Skip this version") { Updater.skip(ctx, rel) }
            }
        }
    }
}

/** Settings > About rows: current / latest version, notes, automatic check on/off, check / download / install. */
fun LazyListScope.updateSection() {
    if (!Updater.supported) return
    item {
        val ctx = LocalContext.current
        var auto by remember { mutableStateOf(Updater.autoEnabled(ctx)) }
        Row2("Check for updates automatically", if (auto) "On (once a day)" else "Off") { auto = !auto; Updater.setAuto(ctx, auto) }
    }
    item {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val s = Updater.state
        val latest = Updater.latestSeen?.let { "latest $it" } ?: ""
        val (title, value) = when (s) {
            Updater.State.Idle -> "Check for updates" to "this is ${Updater.currentVersion(ctx)}"
            Updater.State.Checking -> "Checking for updates" to "..."
            is Updater.State.UpToDate -> "Check for updates" to "up to date (${s.version})"
            is Updater.State.Available -> "Download tvBro ${s.release.version}" to "you have ${Updater.currentVersion(ctx)}"
            is Updater.State.Downloading -> "Downloading tvBro ${s.release.version}" to "${s.percent} %"
            is Updater.State.Ready -> "Install tvBro ${s.release.version}" to "downloaded and checked"
            is Updater.State.Failed -> (if (s.release != null) "Try again: tvBro ${s.release.version}" else "Check for updates") to latest
        }
        Row2(title, value) {
            when (s) {
                is Updater.State.Available -> scope.launch { Updater.download(ctx.applicationContext, s.release) }
                is Updater.State.Ready -> Updater.install(ctx, s.release, s.file)
                is Updater.State.Failed -> s.release?.let { r -> scope.launch { Updater.download(ctx.applicationContext, r) } }
                    ?: scope.launch { Updater.check(ctx.applicationContext) }
                Updater.State.Checking, is Updater.State.Downloading -> Unit
                else -> scope.launch { Updater.check(ctx.applicationContext) }
            }
        }
        statusText(s)?.let { Text(it, color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp)) }
        val notes = when (s) { is Updater.State.Available -> s.release.notes; is Updater.State.Ready -> s.release.notes; else -> "" }
        if (notes.isNotBlank()) Text(notes.take(800), color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp))
    }
}
