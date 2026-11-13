// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import io.github.kmmarin.tvbro.data.*
import java.io.File
import java.util.UUID

private val pages = listOf("Sources", "General", "Playback", "Recording", "Channels and groups", "Backup and restore", "About and diagnostics")

@Composable
private fun Field(label: String, v: String, secret: Boolean = false, width: Int = 420, set: (String) -> Unit) =
    OutlinedTextField(v, set, Modifier.width(width.dp).onFocusChanged { typing = it.isFocused }, label = { T(label, 12) }, singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)

/** One setting: label, current value; Enter / click changes it (cycles). */
@Composable
private fun Row2(label: String, value: String, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().tvItem(onClick = onClick).padding(10.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
        T(label, 15, modifier = Modifier.weight(1f)); T(value, 14, Theme.accent)
    }

@Composable
private fun H(s: String) = T(s, 18, Theme.accent, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))

@Composable
fun SettingsScreen(app: AppState) {
    val page = app.settingsPage
    if (page !in 1..pages.size) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { first.settle(5) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            T("Settings", 22, Theme.accent)
            pages.forEachIndexed { i, p -> Box(Modifier.width(420.dp).tvItem(requester = if (i == 0) first else null) { app.settingsPage = i + 1 }.padding(12.dp, 10.dp)) { T(p, 16) } }
        }
        return
    }
    val back = remember { FocusRequester() }
    LaunchedEffect(page) { back.settle(5) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Btn("‹ Settings", requester = back) { app.settingsPage = 0 }; T(pages[page - 1], 22, Theme.accent)
        }
        when (page) {
            1 -> SourcesPage(app)
            2 -> GeneralPage(app)
            3 -> PlaybackPage(app)
            4 -> RecordingPage(app)
            5 -> ChannelsPage(app)
            6 -> BackupPage(app)
            7 -> AboutPage(app)
        }
    }
}

/** The source's login travels unencrypted (Xtream over http, or an http playlist address). */
private fun cleartext(s: Source) = if (s.isXtream) Xtream.isCleartext(s.server) else s.url.trim().startsWith("http://", true)

@Composable
private fun SourcesPage(app: AppState) {
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf("xtream") }
    var name by remember { mutableStateOf("") }; var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }; var pass by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }; var epg by remember { mutableStateOf("") }
    app.sources.forEachIndexed { i, s ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val age = SourceRepo(s).cache.ageHours(if (s.isXtream) "live" else "m3u")
            Column(Modifier.width(300.dp)) {
                T("${s.name}  (${if (s.isXtream) "Xtream" else "M3U"})" + if (!s.enabled) "  · off" else "", 15)
                T(if (age == Double.MAX_VALUE) "Not synced yet" else "Channels synced %.1f h ago".format(age), 12, Theme.dim)
                if (cleartext(s)) T("Unencrypted connection (http): the login is sent in clear text", 12, Theme.dim)
            }
            Btn(if (s.enabled) "Disable" else "Enable") { app.saveSources(app.sources.map { if (it.id == s.id) it.copy(enabled = !it.enabled) else it }) }
            Btn("Rename") { app.prompt = PromptState("Playlist name", s.name, false) { v -> if (v.isNotBlank()) app.saveSources(app.sources.map { if (it.id == s.id) it.copy(name = v.trim()) else it }) } }
            if (i > 0) Btn("↑") { app.saveSources(app.sources.toMutableList().also { l -> l[i] = l[i - 1].also { l[i - 1] = l[i] } }) }
            Btn("Remove") { scope.launch(Dispatchers.IO) { SourceRepo(s).cache.clear() }; app.saveSources(app.sources.filter { it.id != s.id }) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Btn("Sync everything now") { app.load(true) } }
    H("Add a source")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn((if (kind == "xtream") "● " else "○ ") + "Xtream") { kind = "xtream" }
        Btn((if (kind == "m3u") "● " else "○ ") + "M3U + XMLTV") { kind = "m3u" }
    }
    Field("Name", name) { name = it }
    if (kind == "xtream") { Field("Server (http://host:port)", server) { server = it }; Field("Username", user) { user = it }; Field("Password", pass, true) { pass = it } }
    else { Field("Playlist URL or file", url) { url = it }; Field("XMLTV URL (optional)", epg) { epg = it } }
    Btn("Save source") {
        val ok = if (kind == "xtream") server.isNotBlank() && user.isNotBlank() else url.isNotBlank()
        if (ok) {
            val s = Source(UUID.randomUUID().toString(), name.ifBlank { if (kind == "xtream") "Xtream" else "Playlist" }, kind, url.trim(), epg.trim(), server.trim(), user.trim(), pass)
            name = ""; server = ""; user = ""; pass = ""; url = ""; epg = ""
            scope.launch {
                // a server typed without http:// or https:// is tried with https first; the one that answers is saved
                val saved = if (s.isXtream && !Xtream.hasScheme(s.server)) s.copy(server = withContext(Dispatchers.IO) {
                    Xtream.pickServer(s.server) { Net.reachable(Xtream.authUrl(it, s.user, s.pass)) }
                }) else s
                app.saveSources(app.sources + saved)
                if (cleartext(saved)) app.toast("Saved. This provider uses an unencrypted connection: your login is sent in clear text")
            }
        } else app.toast("Fill in the server and user name (Xtream) or the playlist address")
    }
    T("Credentials are stored encrypted (${SourceStore.method}).", 12, Theme.dim)
    H("Refresh")
    val eh = listOf(6, 12, 24, 48); val ch = listOf(12, 24, 48, 168)
    Row2("Guide refresh", "every ${app.settings.epgHours} h") { app.update { it.copy(epgHours = eh[(eh.indexOf(it.epgHours) + 1) % eh.size]) } }
    Row2("Channels and catalogue refresh", "every ${app.settings.catalogueHours} h") { app.update { it.copy(catalogueHours = ch[(ch.indexOf(it.catalogueHours) + 1) % ch.size]) } }
    T("Checked every 30 minutes while tvBro runs; a failed download keeps the stored data.", 12, Theme.dim)
}

@Composable
private fun GeneralPage(app: AppState) {
    val s = app.settings
    Row2("Time format", TimeFmt.names[s.timeFmt.coerceIn(0, 2)]) { app.update { it.copy(timeFmt = (it.timeFmt + 1) % 3) } }
    Row2("Channel numbers", if (s.numberMode == ChannelView.NUM_PROVIDER) "Provider numbers" else "Numbers per group (1, 2, 3…)") { app.update { it.copy(numberMode = 1 - it.numberMode) } }
    Row2("At start", if (s.startLast) "Play the last channel / return to the last screen" else "Open the TV screen") { app.update { it.copy(startLast = !it.startLast) } }
    Row2("Live preview in the TV screen", if (s.previewOn) "On" else "Off") { app.update { it.copy(previewOn = !it.previewOn) }; if (s.previewOn && !app.full) app.stopPlayback() }
    Row2("Preview sound", if (s.previewMuted) "Muted" else "On") { app.update { it.copy(previewMuted = !it.previewMuted) } }
    val shifts = listOf(0, 30, 60, 90, 120, 180, -180, -120, -90, -60, -30)
    Row2("Guide time shift", (if (s.epgShiftMin > 0) "+" else "") + "${s.epgShiftMin} min") { app.setShift(shifts[(shifts.indexOf(s.epgShiftMin) + 1) % shifts.size]) }
    T("Keys: arrows move · Enter selects · hold Enter, right click or the Menu key for options · Esc back · F11 full screen.", 12, Theme.dim, maxLines = 2)
}

@Composable
private fun PlaybackPage(app: AppState) {
    val s = app.settings
    Row2("Autoplay next episode", if (s.autoplayNext) "On (Up next card, ${Watch.UP_NEXT_COUNTDOWN_S} s countdown)" else "Off") { app.update { it.copy(autoplayNext = !it.autoplayNext) } }
    val hw = if (Platform.windows) listOf("auto-safe", "d3d11va", "no") else listOf("v4l2m2m-copy,auto-safe", "drm,auto-safe", "auto-safe", "no")
    Row2("Hardware decoding", s.hwdec) { app.update { it.copy(hwdec = hw[(hw.indexOf(it.hwdec) + 1) % hw.size]) } }
    Row2("Audio delay", "${s.audioDelayMs} ms (Enter resets; [ ] in the player)") { app.update { it.copy(audioDelayMs = 0) } }
    Row2("Caption size", listOf("Small", "Normal", "Large")[s.captionSize.coerceIn(0, 2)]) { app.update { it.copy(captionSize = (it.captionSize + 1) % 3) } }
    Row2("Caption background", listOf("None (outline)", "Dimmed", "Box")[s.captionBg.coerceIn(0, 2)]) { app.update { it.copy(captionBg = (it.captionBg + 1) % 3) } }
    var ext by remember { mutableStateOf(s.externalPlayer.ifBlank { listOf("C:\\Program Files\\VideoLAN\\VLC\\vlc.exe", "/usr/bin/vlc", "/usr/bin/mpv").firstOrNull { File(it).exists() } ?: "" }) }
    Field("External player (program or command, e.g. vlc)", ext, width = 560) { ext = it; app.update { x -> x.copy(externalPlayer = it.trim()) } }
    Row2("Play with", if (s.useExternal && s.externalPlayer.isNotBlank()) "External player" else "tvBro") {
        if (s.externalPlayer.isBlank() && ext.isNotBlank()) app.update { it.copy(externalPlayer = ext.trim()) }
        app.update { it.copy(useExternal = !it.useExternal) }
    }
    T("Caption style applies from the next video. Captions and audio language picked during an episode are kept for the whole show.", 12, Theme.dim, maxLines = 2)
    val lib = MpvLoader.lib
    Row2("Video library", if (lib != null) "libmpv loaded (${MpvLoader.from}); your own copy, not part of tvBro" else "Missing: Enter shows how to add it") {
        if (lib == null) app.videoSetup = true
    }
}

@Composable
private fun RecordingPage(app: AppState) {
    val s = app.settings
    val scope = rememberCoroutineScope()
    var free by remember(s.storage, s.drivePath, s.smb, s.recDir) { mutableStateOf("…") }
    LaunchedEffect(s.storage, s.drivePath, s.smb, s.recDir) { free = withContext(Dispatchers.IO) { RecTarget.freeText(s) } }
    T(RecTarget.describe(s), 14); T(free, 13, Theme.dim)
    H("Where recordings go")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((k, l) in listOf("local" to "This computer", "drive" to "USB / removable drive", "smb" to "Network share (SMB)")) Btn((if (s.storage == k) "● " else "○ ") + l) { app.update { it.copy(storage = k) } }
    }
    when (s.storage) {
        "local" -> {
            var dir by remember { mutableStateOf(s.recDir) }
            Field("Folder (empty = ${Paths.recordings.path})", dir, width = 560) { dir = it; app.update { x -> x.copy(recDir = it.trim()) } }
        }
        "drive" -> {
            var drives by remember { mutableStateOf<List<Drive>?>(null) }
            LaunchedEffect(Unit) { drives = withContext(Dispatchers.IO) { Drives.list() } }
            val l = drives
            if (l == null) T("Looking for drives…", 13, Theme.dim)
            else if (l.isEmpty()) T("No other drive found. Plug in a USB drive, then Refresh.", 13, Theme.dim)
            else for (d in l) Row2((if (s.drivePath == d.path) "● " else "○ ") + d.label, "${d.freeMb / 1024} GB free  ·  ${d.path}") { app.update { it.copy(drivePath = d.path) } }
            Btn("Refresh") { scope.launch { drives = null; drives = withContext(Dispatchers.IO) { Drives.list() } } }
            T("Recordings go into a tvBro folder on the drive.", 12, Theme.dim)
        }
        "smb" -> {
            var c by remember { mutableStateOf(s.smb) }
            var pw by remember { mutableStateOf(SmbShare.password) }
            var result by remember { mutableStateOf("") }
            Field("Server (name or IP)", c.host) { c = c.copy(host = it.trim()) }
            Field("Share", c.share) { c = c.copy(share = it.trim()) }
            Field("Folder on the share", c.path) { c = c.copy(path = it.trim()) }
            Field("User name (empty = guest)", c.user) { c = c.copy(user = it.trim()) }
            Field("Password", pw, secret = true) { pw = it }
            Field("Domain (optional)", c.domain) { c = c.copy(domain = it.trim()) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Save") { app.update { it.copy(smb = c) }; SmbShare.password = pw; scope.launch(Dispatchers.IO) { Secrets.writeString("smb", pw) }; app.toast("Share saved") }
                Btn("Test") { result = "Testing…"; scope.launch { result = withContext(Dispatchers.IO) { SmbShare.test(c, pw) } } }
            }
            if (result.isNotEmpty()) T(result, 13, if (result.startsWith("OK")) Theme.ok else Theme.live, maxLines = 2)
            T("Recordings are written on this computer first and copied to the share when they end; if the share cannot be reached they stay here with a note. The password is stored encrypted (${Secrets.method}). SMB by jcifs-ng (LGPL-2.1).", 12, Theme.dim, maxLines = 3)
        }
    }
    H("Padding")
    val pads = listOf(0, 1, 2, 5, 10)
    Row2("Start early", "${s.recPadStartMin} min") { app.update { it.copy(recPadStartMin = pads[(pads.indexOf(it.recPadStartMin) + 1) % pads.size]) } }
    Row2("End late", "${s.recPadEndMin} min") { app.update { it.copy(recPadEndMin = pads[(pads.indexOf(it.recPadEndMin) + 1) % pads.size]) } }
}

@Composable
private fun ChannelsPage(app: AppState) {
    val p = app.settings.prefs
    T("Hold Enter (or right click) on a channel or group in the TV screen to rename, hide, move or add it to your own group.", 12, Theme.dim, maxLines = 2)
    H("Groups")
    val provider = remember(app.channels) { app.channels.map { it.group }.distinct() }
    val order = (p.groupOrder.filter { it in provider } + provider.filter { it !in p.groupOrder })
    for (g in p.custom.keys) Row2("$g  (own group, ${p.custom[g].orEmpty().size} channels)", "Delete") { app.editPrefs { it.copy(custom = it.custom - g) } }
    Btn("New own group") { app.prompt = PromptState("New group name", "", false) { v -> val n = v.trim(); if (n.isNotEmpty() && n != ChannelView.ALL && n != ChannelView.FAV) app.editPrefs { it.copy(custom = it.custom + (n to emptyList())) } } }
    for (g in order) {
        val hidden = g in p.hiddenGroups
        Row(Modifier.fillMaxWidth().tvItem(onMenu = { app.groupMenu(g) }) {
            app.editPrefs { it.copy(hiddenGroups = if (hidden) it.hiddenGroups - g else it.hiddenGroups + g) }
        }.padding(10.dp, 6.dp)) {
            T(ChannelView.label(g, p) + if (p.groupNames[g] != null) "  ($g)" else "", 14, if (hidden) Theme.dim else Theme.text, modifier = Modifier.weight(1f))
            T(if (hidden) "Hidden · Enter shows" else "Shown · Enter hides · hold for more", 12, Theme.dim)
        }
    }
    H("Hidden channels")
    val hidden = app.channels.filter { it.key in p.hiddenCh }
    if (hidden.isEmpty()) T("None", 13, Theme.dim)
    for (c in hidden) Row2(c.name, "Show again") { app.editPrefs { it.copy(hiddenCh = it.hiddenCh - c.key) } }
    H("Parental controls")
    T("Lock live groups, single channels (channel menu) and film / show categories (hold Enter on a category). A right PIN opens every lock for 4 hours; after 5 wrong PINs each try waits 30 s. The PIN is kept only as a salted hash.", 12, Theme.dim, maxLines = 3)
    Row2("PIN", if (p.pinHash.isEmpty()) "Not set · Enter to set" else "Set · Enter to change") { app.setPin() }
    if (p.pinHash.isNotEmpty()) {
        val until = app.pinGate.unlockedUntil
        Row2("Locks", if (app.pinOpen) "Open until ${TimeFmt.time(until / 1000)} · Enter locks again" else "Closed · Enter opens them (PIN)") { if (app.pinOpen) app.lockAgain() else app.askPin {} }
        val movieCats = remember(app.movies, app.series) { (app.movies + app.series).mapTo(HashSet()) { it.category } }
        val liveGroups = remember(app.channels) { app.channels.mapTo(HashSet()) { it.group } }
        val chans = app.channels.filter { it.key in p.lockedCh }
        if (p.locked.isEmpty() && chans.isEmpty()) T("Nothing is locked yet.", 13, Theme.dim)
        for (g in p.locked.sorted()) {
            val kind = listOfNotNull("group".takeIf { g in liveGroups }, "film / show category".takeIf { g in movieCats }).joinToString(" and ").ifEmpty { "not in the playlists now" }
            Row2("🔒 $g  ($kind)", "Unlock") { app.toggleLock(g) }
        }
        for (c in chans) Row2("🔒 ${c.name}  (channel)", "Unlock") { app.toggleChLock(c) }
        Btn("Remove PIN and locks") { app.removePin() }
    }
    H("Reset")
    Btn("Reset names, order, hidden items and own groups") {
        app.menu = MenuState("Reset the channel organisation?", listOf("Reset" to { app.editPrefs { Prefs(locked = it.locked, pinHash = it.pinHash, lockedCh = it.lockedCh) }; app.toast("Channel organisation reset") }, "Cancel" to {}))
    }
}

@Composable
private fun BackupPage(app: AppState) {
    T("A backup holds My list, resume points and watched marks, shows' progress, reminders, search history, settings, the channel layout, parental locks, the guide mapping and series recordings. " +
        "Playlist logins, the PIN, the share login and recordings are never in it. A restore checks the whole file first and shows what is in it.", 13, Theme.dim, maxLines = 4)
    val scope = rememberCoroutineScope()
    fun pick(save: Boolean): File? {
        val d = java.awt.FileDialog(null as java.awt.Frame?, if (save) "Save tvBro backup" else "Open tvBro backup", if (save) java.awt.FileDialog.SAVE else java.awt.FileDialog.LOAD)
        if (save) d.file = "tvbro-backup-${java.time.LocalDate.now()}.json"
        d.isVisible = true
        return d.file?.let { File(d.directory, it) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn("Save a backup…") {
            val f = pick(true) ?: return@Btn
            val text = BackupFile.export(app.settings, app.recorder.rules, app.watch.data)
            scope.launch {
                try { withContext(Dispatchers.IO) { atomicWrite(f, text.toByteArray()) }; app.toast("Backup saved: ${f.name}") }
                catch (e: Exception) { app.toast("Could not save: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
        Btn("Restore a backup…") {
            val f = pick(false) ?: return@Btn
            scope.launch {
                // the whole file is checked before anything is written; then the viewer confirms what is in it
                val b = try { withContext(Dispatchers.IO) { BackupFile.check(f.readText()) } }
                    catch (e: BadBackup) { return@launch app.toast("Not restored: ${e.message}") }
                    catch (e: Exception) { return@launch app.toast("Not restored: ${e.message ?: e.javaClass.simpleName}") }
                app.menu = MenuState(BackupFile.summary(b).joinToString("\n"), listOf(
                    "Restore" to { app.restoreBackup(b) },
                    "Cancel" to { app.toast("Nothing restored") },
                ))
            }
        }
    }
}

@Composable
private fun AboutPage(app: AppState) {
    H("Keys")
    T("Arrows move · Enter selects · hold Enter / right click / Menu key: options · Esc back · F11 full screen.\n" +
        "Player: ↑↓ or PgUp PgDn channel (VOD: PgUp PgDn ±1 min), ←→ seek 10 s, digits pick a channel, Enter or M quick settings, I info, Space pause, A audio, S subtitles, " +
        "[ ] audio delay, R record, N / B next / previous episode, Y favourite, Esc back (live TV shrinks into the guide preview).", 13, Theme.dim, maxLines = 6)
    H("About")
    T("tvBro desktop 0.2.0 · Apache-2.0 · video by libmpv (not included; your own copy, its own licence) · SMB by jcifs-ng (LGPL-2.1) · see NOTICE", 13, Theme.dim, maxLines = 2)
    H("Diagnostics")
    for (l in Diag.snapshot().takeLast(30)) T(l, 12, Theme.dim)
}
