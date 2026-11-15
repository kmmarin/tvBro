// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloat
import io.github.kmmarin.tvbro.*
import io.github.kmmarin.tvbro.data.Source
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.focusGroup
import io.github.kmmarin.tvbro.update.updateSection

/** The app's one setting row (title left, value right), also Settings > About's update rows. */
@Composable
fun SettingRow(title: String, value: String, onLeft: (() -> Unit)? = null, onRight: (() -> Unit)? = null, mod: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        mod.fillMaxWidth().padding(vertical = 2.dp)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) false
                else when (e.key) {
                    Key.DirectionLeft -> onLeft?.let { it(); true } ?: false
                    Key.DirectionRight -> onRight?.let { it(); true } ?: false
                    else -> false
                }
            }
            .tvFocus(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = TextMain, fontSize = 18.sp, modifier = Modifier.weight(1f))
        Text(value, color = Accent, fontSize = 18.sp)
    }
}

/**
 * A value read off the main thread ([initial] until then): pages build as focus moves down the page names, and the
 * guide database counts, encrypted settings and storage volumes cost tens to hundreds of ms each on the Fire TV.
 */
@Composable
private fun <T> offMain(initial: T, vararg keys: Any?, read: () -> T): State<T> =
    produceState(initial, *keys) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching(read).getOrDefault(initial) } }

private fun <T> cycle(opts: List<T>, cur: T, d: Int): T = opts[(opts.indexOf(cur).let { if (it < 0) 0 else it } + d).mod(opts.size)]

private fun LazyListScope.head(s: String) { item { Text(s, color = TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)) } }
private fun LazyListScope.note(s: String) { item { Text(s, color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 3.dp)) } }

private fun LazyListScope.general(m: AppModel) {
    head("General")
    item {
        val n = io.github.kmmarin.tvbro.data.TimeFmt.names
        SettingRow("Time format", n[m.timeMode] + "  (now " + io.github.kmmarin.tvbro.data.TimeFmt.time(System.currentTimeMillis() / 1000) + ")",
            { m.changeTimeMode((m.timeMode + n.size - 1) % n.size) }, { m.changeTimeMode((m.timeMode + 1) % n.size) }) { m.changeTimeMode((m.timeMode + 1) % n.size) }
    }
    item { SettingRow("Start on last channel", if (m.startLast) "On" else "Off") { m.changeStartLast(!m.startLast) } }
    item { SettingRow("Reload channels and guide now", if (m.loading.isNotEmpty()) m.loading else "") { m.loadAll(true) } }
    item {
        val opts = listOf(6, 12, 24, 48)
        val ctx = androidx.compose.ui.platform.LocalContext.current
        var v by remember { mutableIntStateOf(m.store.chHours) }
        val set = { n: Int -> v = n; m.store.chHours = n; io.github.kmmarin.tvbro.data.Refresh.schedule(ctx, n) }
        SettingRow("Channels and groups refresh (Wi-Fi/Ethernet, background)", "$v h", { set(cycle(opts, v, -1)) }, { set(cycle(opts, v, 1)) }) { set(cycle(opts, v, 1)) }
    }
}

private fun LazyListScope.playlists(m: AppModel) {
    head("Playlists")
    for (s in m.sources) item(key = s.id) {
        val t = m.store.lastRefresh(s.id)
        val n = m.channels.count { it.sourceId == s.id }
        SettingRow(s.name + (if (s.isXtream) "  (Xtream)" else "  (M3U)") + (if (!s.enabled) "  [disabled]" else ""),
            "$n channels, " + (if (t == 0L) "never updated" else "updated " + io.github.kmmarin.tvbro.data.TimeFmt.dayTime(t, "d MMM"))) {
            m.menu = MenuState(s.name, listOf(
                "Refresh now" to { m.loadAll(true) },
                (if (s.enabled) "Disable" else "Enable") to { m.setSourceEnabled(s, !s.enabled) },
                "Move up" to { m.moveSource(s, -1) },
                "Move down" to { m.moveSource(s, 1) },
                "Edit on your phone" to { m.startLink(s) },
                "Remove source" to { m.removeSource(s) },
            ))
        }
    }
    item { SettingRow("Add source", "") { m.screen = Screen.ADD_SOURCE } }
}

private val whenFmt = object { fun format(d: java.util.Date) = io.github.kmmarin.tvbro.data.TimeFmt.dayTime(d.time) }

private fun ago(ms: Long): String {
    val d = (System.currentTimeMillis() - ms) / 60_000
    return when { d < 1 -> "just now"; d < 60 -> "$d min ago"; d < 48 * 60 -> "${d / 60} h ago"; else -> "${d / 1440} days ago" }
}

private fun took(ms: Long) = if (ms < 60_000) "${ms / 1000} s" else "${ms / 60_000} min ${(ms / 1000) % 60} s"

/** One sync kind of one playlist: Sync now, last success, items, duration, next run, last error and live progress. */
@Composable
private fun SyncCard(m: AppModel, s: Source, k: io.github.kmmarin.tvbro.data.SyncKind, intervalH: Int, extra: String?) {
    val r = io.github.kmmarin.tvbro.data.SyncStatus.recs["${s.id}|${k.name}"] ?: io.github.kmmarin.tvbro.data.SyncRec()
    val prog = io.github.kmmarin.tvbro.data.SyncStatus.running["${s.id}|${k.name}"]
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Surface, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(8.dp)) {
        SettingRow(k.label, if (prog != null) "Syncing..." else "Sync now") { m.syncNow(s, k) }
        val lines = ArrayList<String>()
        lines += if (r.lastOk > 0) "Last successful sync: ${whenFmt.format(java.util.Date(r.lastOk))} · ${ago(r.lastOk)} · took ${took(r.tookMs)}" else "Not synced yet on this device"
        if (r.lastOk > 0) lines += when (k) {
            io.github.kmmarin.tvbro.data.SyncKind.CHANNELS -> "${r.items} channels"
            io.github.kmmarin.tvbro.data.SyncKind.GUIDE -> "${r.items} programmes"
            io.github.kmmarin.tvbro.data.SyncKind.CATALOGUE -> "${r.items} movies and series"
        }
        extra?.let { lines += it }
        val next = when {
            r.failures > 0 && r.lastErr >= r.lastOk -> r.lastErr + io.github.kmmarin.tvbro.data.SyncRules.retryDelayMin(r.failures) * 60_000L
            r.lastOk > 0 -> r.lastOk + intervalH * 3_600_000L
            else -> 0L
        }
        lines += if (next == 0L) "Next sync: after the first one" else if (next <= System.currentTimeMillis()) "Next sync: due now (Wi-Fi/Ethernet)" else "Next sync: ${whenFmt.format(java.util.Date(next))}"
        for (l in lines) Text(l, color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(start = 14.dp))
        if (r.lastErr > 0) Text(
            if (r.lastErr >= r.lastOk) "Last error ${whenFmt.format(java.util.Date(r.lastErr))}: ${r.err}. The previous data is kept; retry ${if (r.failures >= 3) "every 15 min" else "in ${io.github.kmmarin.tvbro.data.SyncRules.retryDelayMin(r.failures)} min"}."
            else "Last error ${whenFmt.format(java.util.Date(r.lastErr))}: ${r.err} (a later sync succeeded)",
            color = if (r.lastErr >= r.lastOk) androidx.compose.ui.graphics.Color(0xFFE57373) else TextDim, fontSize = 13.sp, modifier = Modifier.padding(start = 14.dp, top = 2.dp))
        if (prog != null) {
            Text(prog, color = Accent, fontSize = 13.sp, modifier = Modifier.padding(start = 14.dp, top = 4.dp))
            // indeterminate bar: sizes are often unknown (no Content-Length on provider APIs)
            val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "sync")
            val p by t.animateFloat(0f, 0.65f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1200), androidx.compose.animation.core.RepeatMode.Reverse), label = "p")
            BoxWithConstraints(Modifier.padding(start = 14.dp, top = 4.dp, end = 14.dp).fillMaxWidth().height(4.dp).background(Bg)) {
                Box(Modifier.offset(x = maxWidth * p).width(maxWidth * 0.35f).height(4.dp).background(Accent))
            }
        }
    }
}

private fun LazyListScope.syncSection(m: AppModel) {
    head("Sync")
    note("Each kind syncs on its own schedule in the background (Wi-Fi/Ethernet). A sync that fails or returns nothing never replaces stored data.")
    for (s in m.sources.filter { it.enabled }) {
        item(key = "sh" + s.id) { Text(s.name + if (s.isXtream) "  (Xtream)" else "  (M3U)", color = TextMain, fontSize = 18.sp, modifier = Modifier.padding(top = 10.dp)) }
        item(key = "sc" + s.id) { SyncCard(m, s, io.github.kmmarin.tvbro.data.SyncKind.CHANNELS, m.store.chHours, "${m.channels.count { it.sourceId == s.id }} channels in ${m.channels.filter { it.sourceId == s.id }.map { it.group }.distinct().size} groups loaded") }
        item(key = "sg" + s.id) { SyncCard(m, s, io.github.kmmarin.tvbro.data.SyncKind.GUIDE, m.epgHours, offMain("Stored guide: counting...", io.github.kmmarin.tvbro.data.SyncStatus.recs["${s.id}|GUIDE"]) { m.guideStats(s) }.value) }
        if (s.isXtream) item(key = "sv" + s.id) { SyncCard(m, s, io.github.kmmarin.tvbro.data.SyncKind.CATALOGUE, m.vodHours, null) }
        else note("This playlist type has no separate movie or series catalogue (playlist VOD entries are listed with the channels).")
    }
    head("Schedules")
    item {
        val opts = listOf(6, 12, 24, 48)
        val ctx = androidx.compose.ui.platform.LocalContext.current
        var v by remember { mutableIntStateOf(m.store.chHours) }
        val set = { n: Int -> v = n; m.store.chHours = n; io.github.kmmarin.tvbro.data.Refresh.schedule(ctx, minOf(n, m.epgHours)) }
        SettingRow("Channels refresh", "every $v h", { set(cycle(opts, v, -1)) }, { set(cycle(opts, v, 1)) }) { set(cycle(opts, v, 1)) }
    }
    item {
        val hours = listOf(6, 12, 24, 48, 96)
        SettingRow("Guide refresh", "every ${m.epgHours} h", { m.changeEpgHours(cycle(hours, m.epgHours, -1)) }, { m.changeEpgHours(cycle(hours, m.epgHours, 1)) }) { m.changeEpgHours(cycle(hours, m.epgHours, 1)) }
    }
    item {
        val hours = listOf(12, 24, 48, 168)
        SettingRow("Movies and series refresh", "every ${m.vodHours} h", { m.changeVodHours(cycle(hours, m.vodHours, -1)) }, { m.changeVodHours(cycle(hours, m.vodHours, 1)) }) { m.changeVodHours(cycle(hours, m.vodHours, 1)) }
    }
    item { SettingRow("Sync everything now", if (m.loading.isNotEmpty()) m.loading else "") { m.loadAll(true) } }
    item {
        val building = io.github.kmmarin.tvbro.data.SearchStore.building
        SettingRow("Search index", if (building) "building..." else "rebuild now") { io.github.kmmarin.tvbro.data.SearchStore.requestRebuild(m.getApplication(), "manual") }
    }
}

private fun LazyListScope.epgSection(m: AppModel) {
    head("EPG")
    item {
        val hours = listOf(6, 12, 24, 48, 96)
        SettingRow("Guide refresh interval", "${m.epgHours} h  (left/right)",
            { m.changeEpgHours(cycle(hours, m.epgHours, -1)) }, { m.changeEpgHours(cycle(hours, m.epgHours, 1)) }) { m.changeEpgHours(cycle(hours, m.epgHours, 1)) }
    }
    item {
        SettingRow("Guide time shift", "%+d min  (left/right, OK +30)".format(m.epgShiftMin),
            { m.setShift(m.epgShiftMin - 30) }, { m.setShift(m.epgShiftMin + 30) }) { m.setShift(if (m.epgShiftMin >= 720) -720 else m.epgShiftMin + 30) }
    }
    for (s in m.sources) item(key = "epg" + s.id) { SettingRow("Stored guide: ${s.name}", offMain("...", s.id, m.loading) { m.epgInfo(s) }.value) { m.loadAll(true) } }
    note("Guide window: the longest catch-up period of the source (at least 1 day) back, up to 7 days ahead.")
    item { SettingRow("Map channels to guide", "channels without guide data") { m.screen = Screen.MAPPING } }
    head("Reminders")
    item { ReminderRows(m) }
}

private fun LazyListScope.appearance(m: AppModel) {
    head("Appearance")
    item {
        SettingRow("Scrolling", if (m.scrollCentred) "List moves (selection stays centred)" else "Selection moves",
            { m.changeScrollCentred(!m.scrollCentred) }, { m.changeScrollCentred(!m.scrollCentred) }) { m.changeScrollCentred(!m.scrollCentred) }
    }
    item { SettingRow("Show channel numbers", if (m.showNumbers) "On" else "Off") { m.changeShowNumbers(!m.showNumbers) } }
    item { SettingRow("Channel numbers", if (m.numberMode == 0) "Provider numbers" else "Numbers per group (1, 2, 3...)") { m.changeNumberMode(1 - m.numberMode) } }
    item { SettingRow("Live preview in the TV screen", if (m.previewOn) "On" else "Off") { m.changePreviewOn(!m.previewOn) } }
    item { SettingRow("Preview sound", if (m.previewMuted) "Muted" else "On") { m.changePreviewMuted(!m.previewMuted) } }
    item {
        var v by remember { mutableIntStateOf(m.store.captionSize) }
        val names = listOf("Small", "Normal", "Large")
        SettingRow("Caption size", names[v.coerceIn(0, 2)], { v = (v + 2) % 3; m.store.captionSize = v }, { v = (v + 1) % 3; m.store.captionSize = v }) { v = (v + 1) % 3; m.store.captionSize = v }
    }
    item {
        var v by remember { mutableIntStateOf(m.store.captionBg) }
        val names = listOf("None (outline)", "Dimmed", "Box")
        SettingRow("Caption background", names[v.coerceIn(0, 2)], { v = (v + 2) % 3; m.store.captionBg = v }, { v = (v + 1) % 3; m.store.captionBg = v }) { v = (v + 1) % 3; m.store.captionBg = v }
    }
    note("tvBro always uses its dark theme.")
}

private fun LazyListScope.playback(m: AppModel) {
    head("Playback")
    item {
        val bufs = listOf(5, 10, 15, 30, 60)
        SettingRow("Buffer size", "${m.bufferSec} s  (applies next time a channel opens)",
            { m.setBuffer(cycle(bufs, m.bufferSec, -1)) }, { m.setBuffer(cycle(bufs, m.bufferSec, 1)) }) { m.setBuffer(cycle(bufs, m.bufferSec, 1)) }
    }
    item {
        var v by remember { mutableIntStateOf(m.store.audioDelayDefault) }
        SettingRow("Default audio delay (per channel override in the player bar)", "%+d ms".format(v),
            { v = (v - 50).coerceAtLeast(-1000); m.store.audioDelayDefault = v }, { v = (v + 50).coerceAtMost(1000); m.store.audioDelayDefault = v }) { v = 0; m.store.audioDelayDefault = 0 }
    }
    item { SettingRow("Maximum simultaneous streams", "${m.maxStreams}", { m.changeMaxStreams(m.maxStreams - 1) }, { m.changeMaxStreams(m.maxStreams + 1) }) { m.changeMaxStreams(m.maxStreams % 3 + 1) } }
    note("Keep this at or below what your provider account allows. A running recording holds one stream: with the limit reached the live preview stays off and tvBro warns before playing another channel.")
    item { SettingRow("Autoplay next episode", if (m.autoplayNext) "On (Up next card, 10 s countdown)" else "Off") { m.changeAutoplayNext(!m.autoplayNext) } }
    item { SettingRow("Match display frame rate", if (m.matchFps) "On (when seamless)" else "Off") { m.changeMatchFps(!m.matchFps) } }
    item { SettingRow("Use external player", if (m.external) "On (needs a video player app)" else "Off") { m.changeExternal(!m.external) } }
}

private fun LazyListScope.remote() {
    head("Remote control")
    for (l in listOf(
        "Live TV: Up / Down change channel, number keys jump to a channel.",
        "OK: show or hide the info overlay (with stream details).",
        "Left: back to the list.  Right or Menu: TV guide.",
        "Red button: record the current channel.",
        "Long-press OK on anything (channel, group, programme, movie, show, episode): its menu - Favourites, watch, record, record series, remind, catch-up, rename, hide, move, mark watched.",
        "Yellow / bookmark key or gamepad Y: add the focused item to Favourites (My list).",
        "Movies and episodes: Left / Right seek 10 s, OK pause, Up audio and subtitles.",
        "Back always goes one step back.",
    )) note(l)
}

private fun LazyListScope.parental(m: AppModel) {
    head("Parental controls")
    item { SettingRow("Set / change PIN", if (m.prefs.pinHash.isEmpty()) "not set" else "set") { m.setPin() } }
    item { if (m.prefs.pinHash.isNotEmpty()) SettingRow("Remove PIN (clears locks)", "") { m.removePin() } }
    note("Long-press a group or a Movies/Shows category to lock it. Locked items need the PIN.")
}

private fun LazyListScope.recording(m: AppModel) {
    head("Recording")
    item {
        val label by offMain("...", m.storageTick) { m.storageLabel() }
        SettingRow("Recording storage", "$label  (OK: choose)") { m.pickStorage() }
    }
    item {
        // (encrypted storage: Keystore work)
        val s = offMain<io.github.kmmarin.tvbro.data.SmbConfig?>(null, m.storageTick) { m.store.smb() }.value
        SettingRow("Network share (SMB)", if (s == null) "..." else if (s.ok) s.label + (if (s.user.isNotBlank()) "  as ${s.user}" else "  (guest)") + "  (OK: edit)" else "not set up  (OK: add)") { m.setupSmb() }
    }
    item { SettingRow("Test storage", m.storageTest.ifEmpty { "writes and deletes a small file, shows free space" }) { m.testStorage() } }
    note("Internal storage, a USB drive or SD card, or a network share (SMB/CIFS: Windows, NAS, Samba). A network share records to this device first and copies the file when the recording ends; if the share cannot be reached the recording stays on this device with a note. The share password is stored encrypted.")
    item {
        var v by remember { mutableIntStateOf(m.store.recPadStart) }
        SettingRow("Pad start (scheduled)", "$v min", { v = (v - 1).coerceAtLeast(0); m.store.recPadStart = v }, { v = (v + 1).coerceAtMost(30); m.store.recPadStart = v }) { v = (v + 1) % 31; m.store.recPadStart = v }
    }
    item {
        var v by remember { mutableIntStateOf(m.store.recPadEnd) }
        SettingRow("Pad end (scheduled)", "$v min", { v = (v - 1).coerceAtLeast(0); m.store.recPadEnd = v }, { v = (v + 1).coerceAtMost(60); m.store.recPadEnd = v }) { v = (v + 1) % 61; m.store.recPadEnd = v }
    }
    item {
        val opts = listOf(200L, 500L, 1000L, 2000L)
        var v by remember { mutableStateOf(m.store.recMinFreeMb) }
        SettingRow("Refuse recording below free space", "$v MB", { v = cycle(opts, v, -1); m.store.recMinFreeMb = v }, { v = cycle(opts, v, 1); m.store.recMinFreeMb = v }) { v = cycle(opts, v, 1); m.store.recMinFreeMb = v }
    }
}

private fun LazyListScope.other(m: AppModel) {
    head("Channels and groups")
    item { SettingRow("Open the channels and groups editor", "rename, hide, reorder, own groups") { m.screen = Screen.EDITOR } }
    item { SettingRow("New custom group", "") { m.newGroup() } }
    item { SettingRow("Unhide all channels and groups", "") { m.unhideAll() } }
    item { SettingRow("Back up settings and organisation", "app files dir /backup (no credentials)") { m.backup() } }
    item { SettingRow("Restore from backup", "") { m.restore() } }
}

private object CoreTick { var v by androidx.compose.runtime.mutableIntStateOf(0) }

/** Settings > About > Advanced: the developer rows are shown until turned off or the app ends (not saved). */
object AdvancedUi { var shown by androidx.compose.runtime.mutableStateOf(false) }

private fun LazyListScope.about(m: AppModel) {
    head("About")
    note("tvBro ${io.github.kmmarin.tvbro.BuildConfig.VERSION_NAME} - open source, Apache-2.0. A player only: it ships no channels or content.")
    updateSection()
    if (io.github.kmmarin.tvbro.BuildConfig.DEBUG) {
        note("Core: " + io.github.kmmarin.tvbro.data.CoreSwitch.status())
        item(key = "advanced") { SettingRow("Advanced", if (AdvancedUi.shown) "shown below  (OK: hide)" else "developer settings  (OK: show)") { AdvancedUi.shown = !AdvancedUi.shown } }
        if (AdvancedUi.shown) developer(m)
    }
    item {
        var tick by remember { mutableIntStateOf(0) }
        SettingRow("Diagnostics (OK to refresh)", "last load counts and errors") { tick++ }
        val lines = remember(tick) { io.github.kmmarin.tvbro.data.Diag.snapshot().takeLast(25) }
        for (l in lines) Text(l, color = TextDim, fontSize = 12.sp, maxLines = 2)
    }
}

/**
 * Debug builds only: the Rust core rollout switch per row (docs/RUST_CORE.md), applied at the next start. OK cycles
 * Kotlin, Rust and, for the parsing rows, Shadow (Kotlin in charge, the core compared afterwards; summary in
 * files/core-shadow.log).
 */
private fun LazyListScope.developer(m: AppModel) {
    head("Developer: Rust core")
    note("Changes apply when the app restarts.")
    // "Restart now" while a switch differs from what runs (bumped on every change)
    item(key = "core-restart") {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val sw = io.github.kmmarin.tvbro.data.CoreSwitch
        @Suppress("UNUSED_VARIABLE") val tick = CoreTick.v
        val differ = io.github.kmmarin.tvbro.data.CoreSwitch.Row.entries.any { r -> sw.stored(ctx, r)?.let { it != sw.mode(r) } == true }
        if (differ) SettingRow("Restart now to apply", "restarts tvBro now (about two seconds)") {
            if (io.github.kmmarin.tvbro.data.RecRepo.items.any { it.status == "recording" }) m.toast("A recording is running: restart after it ends")
            else io.github.kmmarin.tvbro.RestartActivity.restart(ctx) { m.saveForRestart() }
        }
    }
    for (r in io.github.kmmarin.tvbro.data.CoreSwitch.Row.entries) item(key = "core-" + r.id) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val sw = io.github.kmmarin.tvbro.data.CoreSwitch
        var mode by remember { mutableStateOf(sw.stored(ctx, r) ?: sw.mode(r)) }
        val now = when { sw.core(r) -> "Rust"; sw.shadow(r) -> "Shadow"; else -> "Kotlin" }
        SettingRow(r.label, mode.label + " at next start (now $now)") {
            val order = io.github.kmmarin.tvbro.data.CoreSwitch.Mode.entries.filter { it != io.github.kmmarin.tvbro.data.CoreSwitch.Mode.SHADOW || r.canShadow }
            mode = order[(order.indexOf(mode) + 1) % order.size]; sw.store(ctx, r, mode); CoreTick.v++
        }
    }
    item(key = "core-shadow-log") {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val lines = remember { java.io.File(ctx.filesDir, io.github.kmmarin.tvbro.data.CoreShadow.LOG).takeIf { it.exists() }?.readLines()?.takeLast(6) ?: emptyList() }
        SettingRow("Shadow comparisons", if (lines.isEmpty()) "none yet" else lines.joinToString("\n")) { }
    }
}

/** Settings as a panel sliding in from the right over the current screen. */
@Composable
fun SettingsScreen(m: AppModel) {
    // Back returns where Settings was opened from (full-screen live TV or the home screen)
    BackHandler { m.screen = m.settingsBack; m.settingsBack = Screen.LIST }
    // the home screen stays composed under the panel: closing it puts focus back where Settings was opened from
    RestoreFocusOnClose(Unit) { m.layerOpen() }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    // (read at layout time: the slide moves the panel without recomposing both of its lists every frame)
    // (a short slide: the spring took ~0.6 s to settle on the Fire TV, longer than TiviMate's panel)
    val x = androidx.compose.animation.core.animateDpAsState(if (shown) 0.dp else 820.dp, androidx.compose.animation.core.tween(220), label = "panel")
    val names = listOf("General", "Playlists", "Sync", "EPG", "Appearance", "Playback", "Remote control", "Parental controls", "Recording", "Channels and groups", "About")
    var sec by m::settingsSec // remembered (also across restarts): Settings reopens on the same page
    val firstSec = remember { sec.coerceIn(0, names.size - 1) }
    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { fr.requestFocus() } } // (was 300 ms: the slow slide)
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0x99000000))) {
        Row(Modifier.align(Alignment.CenterEnd).offset { androidx.compose.ui.unit.IntOffset(x.value.roundToPx(), 0) }.width(820.dp).fillMaxHeight().focusTrap().background(androidx.compose.ui.graphics.Color(0xFF141A22))) {
            // Left from a setting goes back to its page's name, not to whichever name is level with it (that would
            // switch pages)
            val selFr = remember { SafeFocus() }
            // Right from a page name enters the page at its first row (it went to the row level with the name: "Add
            // source" under the user's playlist, the middle of a long page)
            val pageFr = remember { androidx.compose.ui.focus.FocusRequester() }
            val pageState = androidx.compose.foundation.lazy.rememberLazyListState()
            val pageScope = rememberCoroutineScope()
            // The page shown follows the focused name once focus rests on it a moment: building a page took 60-80 ms on
            // the Fire TV, so moving down the names built every page on the way. Right enters the name's page at once.
            var page by remember { mutableIntStateOf(sec.coerceIn(0, names.size - 1)) }
            LaunchedEffect(sec) { if (page != sec) { kotlinx.coroutines.delay(150); page = sec; runCatching { pageState.scrollToItem(0) } } }
            LazyColumn(Modifier.width(230.dp).fillMaxHeight().padding(12.dp).enterAt(selFr)) {
                item { SectionTitle("Settings", Modifier.padding(10.dp)) }
                itemsIndexed(names) { i, n ->
                    Text((if (i == sec) "> " else "  ") + n, color = if (i == sec) Accent else TextMain, fontSize = 16.sp, maxLines = 1,
                        modifier = Modifier.fillMaxWidth().then(if (i == firstSec) Modifier.focusRequester(fr) else Modifier).safeFocus(if (i == sec) selFr else null)
                            .onFocusChanged { if (it.isFocused) sec = i }
                            .onPreviewKeyEvent { e ->
                                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                                    pageScope.launch {
                                        if (page != i) { page = i; androidx.compose.runtime.withFrameNanos { } } // (composed first)
                                        runCatching { pageState.scrollToItem(0) }; runCatching { pageFr.requestFocus() }
                                    }
                                    true
                                } else false
                            }
                            .tvFocus { sec = i }.padding(10.dp))
                }
            }
            // the page comes one frame after the panel: the panel starts sliding in at once (the first frame waited for
            // the remembered page to be built, up to a second on the Fire TV for the longer pages)
            var pageReady by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; pageReady = true }
            if (pageReady) SettingsPage(m, page, pageState, pageFr, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
        }
    }
}

/** One page of Settings (its own scope: moving along the page names does not rebuild it). */
@Composable
private fun SettingsPage(m: AppModel, page: Int, state: androidx.compose.foundation.lazy.LazyListState, fr: androidx.compose.ui.focus.FocusRequester, modifier: Modifier) {
    LazyColumn(modifier.fillMaxHeight().padding(16.dp).focusRequester(fr).focusGroup(), state = state) {
        when (page) {
            0 -> general(m); 1 -> playlists(m); 2 -> syncSection(m); 3 -> epgSection(m); 4 -> appearance(m); 5 -> playback(m)
            6 -> remote(); 7 -> parental(m); 8 -> recording(m); 9 -> other(m); else -> about(m)
        }
    }
}
@Composable
fun AddSourceScreen(m: AppModel) {
    val ls = m.link
    if (ls != null && !ls.inline) { LinkPanel(m, ls); return }
    // Back: to Settings; with no source yet Back leaves the app (it was swallowed: nothing happened), never an empty TV screen
    BackHandler(enabled = m.sources.isNotEmpty()) { m.screen = Screen.SETTINGS }
    // Phone sign-in is offered right away: its QR code sits top right beside the form (user 2026-11-12). The one-time
    // code lasts 10 minutes, so it is renewed while the screen stays open; the link stops when the screen closes.
    LaunchedEffect(ls?.code) {
        if (ls == null) m.startLink(null, inline = true)
        else { delay(9 * 60_000L + 30_000L); if (m.link === ls) m.startLink(null, inline = true) }
    }
    DisposableEffect(Unit) { onDispose { if (m.link?.inline == true) m.stopLink() } }
    // Xtream login (server, username, password) is the default type (user 2026-11-12); M3U stays one OK away
    var xtream by remember { mutableStateOf(true) }
    val typeFr = remember { androidx.compose.ui.focus.FocusRequester() }
    // focus starts on the first field to fill in (the server address; Up reaches Type and the phone sign-in)
    val firstFr = remember { androidx.compose.ui.focus.FocusRequester() }
    // (again once the phone sign-in shows and the form has re-laid out)
    // On a fresh start the first request could come before the window had focus and nothing was focused (seen on the
    // emulator after a clear): try again until something holds focus, for up to 4 s.
    LaunchedEffect(ls != null) {
        delay(250)
        if (runCatching { firstFr.requestFocus() }.isFailure) runCatching { typeFr.requestFocus() }
        repeat(15) {
            delay(250)
            if (FocusMemory.anyFocus) return@LaunchedEffect
            if (runCatching { firstFr.requestFocus() }.isFailure) runCatching { typeFr.requestFocus() }
        }
    }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val ok = if (xtream) server.isNotBlank() && user.isNotBlank() && pass.isNotBlank() else url.isNotBlank()
    Row(Modifier.fillMaxSize().background(Bg).padding(32.dp)) {
    LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "title") { SectionTitle("Add source") }
        if (ls == null) item(key = "phone") { TvButton("Use your phone (scan a QR code, type on the phone)") { m.startLink(null) } }
        item(key = "type") { SettingRow("Type", if (xtream) "Xtream login (server, username, password)" else "M3U URL", mod = Modifier.focusRequester(typeFr)) { xtream = !xtream } }
        item(key = "name") { TvField("Name (optional)", name, { name = it }) }
        if (xtream) {
            item(key = "server") { TvField("Server (http://host:port)", server, { server = it }, requester = firstFr) }
            item(key = "user") { TvField("Username", user, { user = it }) }
            item(key = "pass") { TvField("Password", pass, { pass = it }, password = true) }
        } else {
            item(key = "url") { TvField("Playlist URL (.m3u / .m3u8)", url, { url = it }, requester = firstFr) }
            item(key = "epg") { TvField("XMLTV guide URL(s), space separated (optional)", epg, { epg = it }) }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton(if (ok) "Save" else "Fill in the fields first") {
                    if (ok) m.addSource(
                        Source(
                            id = "", kind = if (xtream) "xtream" else "m3u",
                            name = name.ifBlank { if (xtream) "Xtream" else "Playlist" },
                            url = url.trim(), epgUrl = epg.trim(),
                            server = server.trim(), user = user.trim(), pass = pass,
                        ),
                    )
                }
                if (m.sources.isNotEmpty()) TvButton("Cancel") { m.screen = Screen.SETTINGS }
            }
        }
    }
    if (ls != null) {
        // top right, beside the title and above the form; 240 dp is about 30 cm on a 55" TV (scans from 3 m+)
        Column(Modifier.padding(start = 28.dp).width(260.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Or sign in with your phone", color = TextMain, fontSize = 17.sp, maxLines = 1)
            QrCode(ls.url + "?c=" + ls.code, Modifier.padding(top = 8.dp).size(240.dp))
            Text("Home network only: same Wi-Fi as this TV. Or open ${ls.url}", color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            Text("Code: ${ls.code}", color = TextMain, fontSize = 20.sp, modifier = Modifier.padding(top = 2.dp))
            if (m.linkStatus.isNotEmpty()) Text(m.linkStatus, color = androidx.compose.ui.graphics.Color(0xFFA5D6A7), fontSize = 26.sp)
        }
    }
    }
}

/** A QR code (black on white with a quiet zone) for [text]. */
@Composable
fun QrCode(text: String, modifier: Modifier) {
    val matrix = remember(text) {
        com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, mapOf(com.google.zxing.EncodeHintType.MARGIN to 2))
    }
    androidx.compose.foundation.Canvas(modifier.background(androidx.compose.ui.graphics.Color.White)) {
        val n = matrix.width
        val cell = size.width / n
        for (y in 0 until n) for (x in 0 until n) if (matrix.get(x, y)) {
            drawRect(androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.geometry.Offset(x * cell, y * cell), androidx.compose.ui.geometry.Size(cell + 0.6f, cell + 0.6f))
        }
    }
}

@Composable
private fun LinkPanel(m: AppModel, ls: AppModel.LinkState) {
    BackHandler { m.stopLink() }
    Row(Modifier.fillMaxSize().background(Bg).padding(40.dp), verticalAlignment = Alignment.CenterVertically) {
        QrCode(ls.url + "?c=" + ls.code, Modifier.size(300.dp))
        Spacer(Modifier.width(40.dp))
        Column {
            SectionTitle(if (ls.edit != null) "Edit ${ls.edit.name} on your phone" else "Add a source with your phone")
            Text("1. Join the same Wi-Fi network as this TV.", color = TextMain, fontSize = 18.sp, modifier = Modifier.padding(top = 16.dp))
            Text("2. Scan the code, or open:", color = TextMain, fontSize = 18.sp)
            Text(ls.url, color = Accent, fontSize = 24.sp, modifier = Modifier.padding(vertical = 6.dp))
            Text("3. Enter this code if asked:  ${ls.code}", color = TextMain, fontSize = 24.sp)
            Text("Use this only on your home network. The code works once and expires in 10 minutes; the link only runs while this screen is open.", color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
            if (m.linkStatus.isNotEmpty()) Text(m.linkStatus, color = androidx.compose.ui.graphics.Color(0xFFA5D6A7), fontSize = 32.sp, modifier = Modifier.padding(top = 16.dp))
            TvButton("Cancel", Modifier.padding(top = 24.dp)) { m.stopLink() }
        }
    }
}