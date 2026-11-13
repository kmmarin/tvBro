// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import android.app.Application
import android.widget.Toast
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import io.github.kmmarin.tvbro.data.*
import kotlinx.serialization.json.put
import io.github.kmmarin.tvbro.ui.MenuState
import io.github.kmmarin.tvbro.ui.PromptState
import java.util.UUID

enum class Section { SEARCH, TV, MOVIES, SHOWS, RECORDINGS, MYLIST }

enum class Screen { LIST, PLAYER, GUIDE, SETTINGS, ADD_SOURCE, GUIDE_SEARCH, MAPPING, VOD, DETAIL, VODPLAY, RECORDINGS, EDITOR }

data class CatchupPlay(val url: String, val title: String)
data class VodPlay(val key: String, val url: String, val title: String)

// Built-in Movies / Shows categories: keys no provider category can have (a provider "History" category collided
// with the watch history: both showed as selected and choosing it showed the history); vodCatLabel() names them.
const val CAT_RECENT = "\u0001History"
const val CAT_MYLIST = "\u0001My list"
const val VOD_ALL = "\u0001All titles"

fun vodCatLabel(g: String, shows: Boolean): String = when (g) {
    CAT_RECENT -> "History"; CAT_MYLIST -> "My list"; VOD_ALL -> if (shows) "All shows" else "All movies"; else -> g
}
const val M3U_CAT = "Playlist VOD"
const val VOD_PAGE = 240

const val GROUP_ALL = "All channels"
const val GROUP_FAV = "Favourites"

class AppModel(private val app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    private val repo = Repository(app)

    var sources by mutableStateOf<List<Source>>(emptyList())
    var channels by mutableStateOf<List<Channel>>(emptyList())
    // With no source yet (fresh install, or the last one removed) the app opens directly on Add source: the stored
    // sources are read off the main thread, so a plain hint (no credentials) decides the first frame.
    var screen by mutableStateOf(if (store.hasSourcesHint == true) Screen.LIST else if (store.hasSourcesHint == false || store.noSourceFile()) Screen.ADD_SOURCE else Screen.LIST)
    var loading by mutableStateOf("")
    var message by mutableStateOf("")
    var epg by mutableStateOf(EpgIndex.EMPTY)
    var favourites by mutableStateOf(store.favourites())
    var current by mutableStateOf<Channel?>(null)
    var catchup by mutableStateOf<CatchupPlay?>(null)
    var group by mutableStateOf(GROUP_ALL)
    var query by mutableStateOf("")

    var epgHours by mutableIntStateOf(store.epgHours); private set
    var epgShiftMin by mutableIntStateOf(store.epgShiftMin)
    var startLast by mutableStateOf(store.startLast); private set
    var bufferSec by mutableIntStateOf(store.bufferSec)
    var external by mutableStateOf(store.external); private set
    var previewOn by mutableStateOf(store.previewOn); private set
    var previewMuted by mutableStateOf(store.previewMuted); private set
    fun changePreviewOn(b: Boolean) { previewOn = b; store.previewOn = b }
    fun changePreviewMuted(b: Boolean) { previewMuted = b; store.previewMuted = b }
    var focusCh by mutableStateOf<Channel?>(null)
    var focusProg by mutableStateOf<Programme?>(null)
    var groupReady by mutableStateOf(false)
    private var groupInit = false
    var numberMode by mutableIntStateOf(store.numberMode); private set
    fun changeNumberMode(v: Int) { numberMode = v; store.numberMode = v }
    var aspectMode by mutableIntStateOf(0)
    val delayProc = io.github.kmmarin.tvbro.ui.AudioDelayProcessor()
    var audioDelayMs by mutableIntStateOf(0)

    fun applyDelayFor(key: String?) {
        val ms = (key?.let { store.audioDelay(it) }) ?: store.audioDelayDefault
        audioDelayMs = ms; delayProc.targetMs = ms
    }

    fun changeAudioDelay(step: Int, key: String?) {
        val ms = if (step == 0) 0 else (audioDelayMs + step).coerceIn(-1000, 1000)
        audioDelayMs = ms; delayProc.targetMs = ms
        key?.let { store.setAudioDelay(it, ms) }
    }
    var sleepAt by mutableLongStateOf(0L)
    private var sleepJob: kotlinx.coroutines.Job? = null

    fun setSleep(min: Int) {
        sleepJob?.cancel()
        sleepAt = if (min > 0) System.currentTimeMillis() + min * 60_000L else 0L
        if (min > 0) sleepJob = viewModelScope.launch {
            kotlinx.coroutines.delay(min * 60_000L)
            sleepAt = 0L; screen = Screen.LIST; toast("Sleep timer: playback stopped")
        }
    }
    var matchFps by mutableStateOf(store.matchFps); private set
    /** Settings > General > Time format; TimeFmt is read by every time label. */
    var timeMode by mutableIntStateOf(store.timeFormat.coerceIn(0, 2).also { TimeFmt.mode = it; TimeFmt.system24 = android.text.format.DateFormat.is24HourFormat(app) }); private set
    fun changeTimeMode(v: Int) { timeMode = v; TimeFmt.mode = v; store.timeFormat = v; TimeFmt.system24 = android.text.format.DateFormat.is24HourFormat(app) }
    fun changeExternal(b: Boolean) { external = b; store.external = b }
    fun changeMatchFps(b: Boolean) { matchFps = b; store.matchFps = b }

    fun alternates(c: Channel): List<Channel> =
        channels.filter { it.key != c.key && it.url != c.url && (it.nameKey == c.nameKey || (c.idKey != null && it.idKey == c.idKey)) && !isLocked(it) }

    private fun launchExternal(url: String): Boolean = try {
        app.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(url), "video/*").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) { toast("No external player found"); false }

    private var started = false

    var prefs by mutableStateOf(store.prefs())
    var unlocked by mutableStateOf<Set<String>>(emptySet())
    var menu by mutableStateOf<MenuState?>(null)
    var prompt by mutableStateOf<PromptState?>(null)

    val shown: List<Channel> by derivedStateOf {
        val p = prefs
        val pos = p.order.withIndex().associate { it.value to it.index }
        val l = channels.filter { it.key !in p.hiddenCh && it.group !in p.hiddenGroups && (it.group !in p.locked || it.group in unlocked) }
            .map { c -> p.names[c.key]?.let { c.copy(name = it) } ?: c }
        // PPV channels last by default (stable: the provider's order otherwise); the user's own order wins
        if (pos.isEmpty()) l.sortedBy { GroupOrder.isPpv(it.group) }
        else l.sortedBy { pos[it.key] ?: if (GroupOrder.isPpv(it.group)) Int.MAX_VALUE else Int.MAX_VALUE - 1 }
    }

    /** Favourites and All channels always first; then custom groups, then provider groups (PPV last by default). */
    val groupList: List<String> by derivedStateOf {
        val p = prefs
        val g = channels.filter { it.group !in p.hiddenGroups }.map { it.group }.distinct()
        listOf(GROUP_FAV, GROUP_ALL) + p.custom.keys.toList() + GroupOrder.order(g, p.groupOrder)
    }

    fun groupLabel(g: String) = (prefs.groupNames[g] ?: g) + (if (g in prefs.locked) " (locked)" else "") + (if ("g|$g" in favourites) "  *" else "")

    val filtered: List<Channel> by derivedStateOf {
        val q = query.trim().lowercase()
        shown.filter {
            (when (group) { GROUP_ALL -> true; GROUP_FAV -> it.key in favourites
                else -> if (group in prefs.custom) it.key in (prefs.custom[group] ?: emptyList()) else it.group == group }) &&
                (q.isEmpty() || it.name.lowercase().contains(q))
        }.let { l -> if (numberMode == 1) l.mapIndexed { i, c -> c.copy(number = i + 1) } else l }
    }

    init {
        // Create these objects' Compose state here, on the main thread before the first composition: created on the IO
        // thread below while the UI composes, reading them crashed ("state created after the snapshot was taken").
        RecRepo.items; io.github.kmmarin.tvbro.data.DlRepo.items
        io.github.kmmarin.tvbro.data.Reminders.items; io.github.kmmarin.tvbro.data.SeriesRules.items
        // Keystore-backed storage and file work stay off the main thread; the UI shows cached data as soon as it is read.
        viewModelScope.launch(Dispatchers.IO) {
            io.github.kmmarin.tvbro.ui.Logos.dir = java.io.File(app.cacheDir, "logos")
            SyncStatus.init(app)
            val s = store.sources(); sources = s
            store.hasSourcesHint = s.isNotEmpty()
            // no source: straight to Add source, decided before anything else here can fail (a recordings folder
            // that cannot be read must not leave an empty TV screen)
            if (s.isEmpty()) screen = Screen.ADD_SOURCE else if (screen == Screen.ADD_SOURCE) screen = Screen.LIST
            if (store.secureFailed) toast("Saved sources could not be decrypted on this device. Please add them again.")
            try { RecRepo.init(app) } catch (e: Exception) { Diag.log("recordings init failed (${e.javaClass.simpleName})") }
            if (s.isNotEmpty()) loadAll(false)
            try { Refresh.schedule(app, store.chHours) } catch (e: Exception) { Diag.log("could not schedule refresh") }
        }
    }

    fun toast(s: String) = Toast.makeText(app, s, Toast.LENGTH_SHORT).show()

    fun loadAll(force: Boolean) {
        val srcs = sources.filter { it.enabled }
        val multi = srcs.size > 1
        viewModelScope.launch(Dispatchers.IO) {
            Refresh.fgBusy = true
            try {
            loading = "Loading channels..."
            message = ""
            val all = ArrayList<Channel>()
            val t0 = System.currentTimeMillis()
            for (s in srcs) {
                try { val l = repo.channels(s, force); all += if (multi) l.map { it.copy(group = s.name + " | " + it.group) } else l } catch (e: Exception) { message = "${s.name}: ${Net.describe(e)}" }
            }
            val (vodC, liveC) = all.partition { VodApi.isVodUrl(it.url) }
            m3uVod = vodC.map { VodApi.fromChannel(it) }
            all.clear(); all.addAll(liveC)
            if (force) for (s in srcs) store.setLastRefresh(s.id, System.currentTimeMillis())
            val em = store.epgMap()
            channels = all.mapIndexed { i, c -> (if (c.number > 0) c else c.copy(number = i + 1)).let { x -> em[x.key]?.let { id -> x.copy(tvgId = id, idKey = "${x.sourceId}|${id.lowercase()}") } ?: x } }
            // on the main thread: groupList / filtered are derived states, and reading them here (IO) while the UI
            // composes crashed ("state created after the snapshot was taken")
            kotlinx.coroutines.withContext(Dispatchers.Main) {
            pendingPlay?.let { k -> pendingPlay = null; channels.firstOrNull { it.key == k }?.let { play(it) } }
            if (!groupInit) {
                groupInit = true
                val gl = groupList
                val last = store.lastGroup
                group = when {
                    last != null && last in gl -> last
                    favourites.any { f -> channels.any { it.key == f } } -> GROUP_FAV
                    else -> gl.getOrNull(2 + prefs.custom.size) ?: GROUP_ALL
                }
                groupReady = true
            }
            loading = ""
            if (!started) {
                started = true
                restoreUi()
            }
            }
            for (s in srcs) SyncStatus.get(s.id, SyncKind.CHANNELS).let { r -> if (r.lastErr >= t0) message = "${s.name}: channels not updated (${r.err}), showing the stored list" }
            val guideChanged = loadEpg(srcs, force)
            scheduleSeries()
            var catChanged = false
            for (s in srcs) if (s.isXtream && (force || SyncStatus.due(s.id, SyncKind.CATALOGUE, store.vodHours))) {
                loading = "Updating movies and series..."
                try { repo.syncCatalogue(s); catChanged = true } catch (e: Exception) { }
                loading = ""
            }
            if (force || guideChanged || catChanged || !SearchStore.exists(app)) SearchStore.requestRebuild(app, if (force) "refresh" else "start")
            } finally { Refresh.fgBusy = false }
        }
    }

    private fun loadEpg(srcs: List<Source>, force: Boolean): Boolean {
        rebuildIndex() // cache first: show whatever is stored right away
        var changed = false
        for (s in srcs) {
            val mine = channels.filter { it.sourceId == s.id }
            if (force || repo.epgStale(s, epgHours)) {
                loading = "Updating guide..."
                try { if (repo.refreshEpg(s, mine)) changed = true } catch (e: Exception) { message = "Guide ${s.name}: ${Net.describe(e)}"; Diag.log("guide refresh failed (${Net.describe(e)}), kept old data") }
                loading = ""
                rebuildIndex()
            }
        }
        return changed
    }

    fun rebuildIndex() { epg = DbEpg(EpgDb.get(app), epgShiftMin * 60L) }
    fun setShift(min: Int) {
        epgShiftMin = min.coerceIn(-720, 720); store.epgShiftMin = epgShiftMin
        viewModelScope.launch(Dispatchers.Default) { rebuildIndex() }
    }
    fun changeEpgHours(h: Int) { epgHours = h; store.epgHours = h }
    fun changeStartLast(b: Boolean) { startLast = b; store.startLast = b }
    fun setBuffer(s: Int) { bufferSec = s; store.bufferSec = s }

    // ---- Stream limit: recordings hold a stream; preview and playback share the rest ----
    var maxStreams by mutableIntStateOf(store.maxStreams); private set
    fun changeMaxStreams(n: Int) { maxStreams = n.coerceIn(1, 3); store.maxStreams = maxStreams }
    fun recordingStreams(): Int = RecRepo.items.count { it.status == "recording" }
    /** True when recordings already use every allowed stream (the preview then stays off). */
    fun streamsFull(): Boolean = recordingStreams() >= maxStreams

    // ---- One live player for full screen and the TV preview (see ui/LiveVideo.kt) ----
    var liveExo: androidx.media3.exoplayer.ExoPlayer? = null
    /** Address the live player has loaded (null when stopped). */
    var liveUrl: String? = null
    /** Channel key the live player was loaded for (its live, alternate or catch-up address). */
    var liveKey: String? = null
    var livePv by mutableStateOf<androidx.media3.ui.PlayerView?>(null)
    /** The app is in front (started); false in the background, where live playback stops. */
    var foreground by mutableStateOf(true)
    /** Bumped when the app comes back to the front: the live players start their stream again. */
    var liveGen by mutableIntStateOf(0)
    /** Failed preview streams in a row (the TV screen tries again with a growing pause). */
    var previewRetry by mutableIntStateOf(0)
    /** Where the TV preview sits on screen (root coordinates, px). */
    var previewRect by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    init { io.github.kmmarin.tvbro.ui.LivePlayer.users++ }
    override fun onCleared() {
        // the live player is shared by every activity instance: only the last model releases it
        val lp = io.github.kmmarin.tvbro.ui.LivePlayer
        lp.users--
        if (lp.users <= 0 || lp.exo !== liveExo) { liveExo?.release(); if (lp.exo === liveExo) lp.exo = null }
        liveExo = null
        super.onCleared()
    }
    // ---- Last UI state: a cold start (relaunch, or after Android killed the process) returns to the same place ----
    var settingsSec by mutableIntStateOf(0)
    /** Saves the UI state as it changes (started from the last init block, after every property exists). */
    private fun watchUiState() {
        viewModelScope.launch {
            androidx.compose.runtime.snapshotFlow { listOf(screen.name, section.name, focusCh?.key ?: "", settingsSec.toString()).joinToString("|") }
                .collectLatest { v -> kotlinx.coroutines.delay(800); if (started) store.lastUi = v }
        }
    }

    /** Screen (full screen / guide / settings), section, focused channel and settings page as they were; the last channel plays as before. */
    private fun restoreUi() {
        val p = store.lastUi.split('|')
        val scr = p.getOrNull(0)?.takeIf { it.isNotEmpty() }
        val sec = p.getOrNull(1)?.let { s -> Section.values().firstOrNull { it.name == s } }
        settingsSec = p.getOrNull(3)?.toIntOrNull() ?: 0
        // (a locked channel is not resumed at start: it would play without the PIN)
        // (debug builds: `setprop debug.tvbro.noautoplay 1` starts with nothing playing, settings untouched)
        val last = if (store.startLast && !DebugHooks.noAutoplay()) channels.firstOrNull { it.key == store.lastKey }?.takeUnless { isLocked(it) } else null
        if (last != null) current = last
        (p.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { k -> channels.firstOrNull { it.key == k } } ?: last)?.let { focusCh = it }
        if (screen != Screen.LIST) return // a phone link or a "play" intent already took over
        when (sec) {
            Section.MOVIES -> openVod(false)
            Section.SHOWS -> openVod(true)
            null -> {}
            else -> section = sec
        }
        screen = when {
            last != null && (scr == null || scr == Screen.PLAYER.name) -> Screen.PLAYER
            scr == Screen.SETTINGS.name -> Screen.SETTINGS
            else -> Screen.LIST
        }
    }

    /** TV screen scroll per group (first visible row, offset), kept while the app runs. */
    val tvScroll = HashMap<String, Pair<Int, Int>>()
    /** Channel key last focused per group: Right from a group goes back to it. */
    val groupFocus = HashMap<String, String>()
    /** A group was just moved from its menu: the TV screen puts focus back on it once the list has re-laid out. */
    var groupMoved = false
    /** Where Right from the side menu goes back to (the TV screen's focused channel); null = default focus search. */
    var railRight: io.github.kmmarin.tvbro.ui.SafeFocus? = null
    /** The side menu's current-section icon: where Left from a section's first column goes. */
    var railSel: io.github.kmmarin.tvbro.ui.SafeFocus? = null
    /** Where Back from Settings goes (full screen when opened from the player's quick bar). */
    var settingsBack = Screen.LIST
    /** Brings focus back when nothing holds it (set by the UI root); true when something took focus. */
    var focusRescue: ((hard: Boolean) -> Boolean)? = null

    /** Back from full-screen live TV to the TV screen: the playing channel focused in its group (the video shrinks into the preview). */
    fun leavePlayer() {
        val c = current
        if (c != null) {
            if (section != Section.TV) section = Section.TV
            if (filtered.none { it.key == c.key }) groupList.firstOrNull { it == c.group }?.let { group = it }
            focusCh = filtered.firstOrNull { it.key == c.key } ?: c
            focusProg = null
        }
        screen = Screen.LIST
    }

    fun play(c: Channel) {
        // parental lock, on every path (lists, guide, search, the "play" launch extra)
        if (isLocked(c)) { askPin { unlock(c); play(c) }; return }
        if (streamsFull() && activeRec(c) == null) toast("Stream limit $maxStreams: a recording is using it, the provider may refuse this channel")
        current = c; catchup = null; store.lastKey = c.key
        if (external && launchExternal(c.url)) return
        screen = Screen.PLAYER
    }

    /**
     * OK on a channel (or its on-now programme) in the TV screen: the preview switches to it; OK on the channel already
     * in the preview opens it full screen. Moving focus alone never changes channel (user 2026-11-11). Without a
     * preview (off, or the stream limit is reached) OK goes straight to full screen.
     */
    fun previewOrPlay(c: Channel) {
        if (!previewOn || streamsFull() || external || current?.key == c.key || isLocked(c)) { play(c); return }
        current = c; catchup = null; store.lastKey = c.key
    }

    fun zap(delta: Int) {
        // channel up / down skips locked channels (they open only through play(), which asks for the PIN)
        val l = filtered.ifEmpty { shown }.filter { !isLocked(it) || it.key == current?.key }
        if (l.isEmpty()) return
        val i = l.indexOfFirst { it.key == current?.key }
        val n = l[((if (i < 0) 0 else i) + delta).mod(l.size)]
        current = n; catchup = null; store.lastKey = n.key
    }

    fun zapNumber(n: Int): Boolean {
        val c = shown.firstOrNull { it.number == n } ?: return false
        if (isLocked(c)) { play(c); return true }
        current = c; catchup = null; store.lastKey = c.key
        return true
    }

    /** Programme chosen in the guide: live if not finished, otherwise catch-up when available. */
    fun guidePlay(c: Channel, p: Programme?) {
        val now = System.currentTimeMillis() / 1000
        if (p == null || p.stop > now) { play(c); return }
        if (isLocked(c)) { askPin { unlock(c); guidePlay(c, p) }; return }
        val url = if (CatchupUrl.supports(c, p.start, now)) CatchupUrl.build(c, p.start, p.stop, now) else null
        if (url == null) { toast("Catch-up not available for this programme"); return }
        current = c; store.lastKey = c.key
        catchup = CatchupPlay(url, p.title)
        screen = Screen.PLAYER
    }

    fun toggleFav(c: Channel) {
        favourites = if (c.key in favourites) favourites - c.key else favourites + c.key
        store.saveFavourites(favourites)
        toast(if (c.key in favourites) "Added to My list" else "Removed from My list")
    }

    /** Last focused item registers what the yellow / bookmark key or gamepad Y should toggle in My list. */
    var favAction: (() -> Unit)? = null

    private fun toggleKey(k: String) {
        favourites = if (k in favourites) favourites - k else favourites + k
        store.saveFavourites(favourites)
        toast(if (k in favourites) "Added to My list" else "Removed from My list")
    }
    fun toggleFavGroup(g: String) = toggleKey("g|$g")
    fun toggleFavRec(r: Rec) = toggleKey("r|${r.id}")

    /** Long-press Back applies in the TV section (live preview) and the full guide, when there is a channel to show. */
    fun backLongApplies(): Boolean =
        ((screen == Screen.LIST && section == Section.TV) || screen == Screen.GUIDE) && (focusCh ?: current) != null &&
            menu == null && prompt == null && progMenu == null

    /** Long-press Back: the previewed (focused) channel full screen. Returns true when it acted. */
    fun backLongPress(): Boolean {
        if (!backLongApplies()) return false
        val c = focusCh ?: current ?: return false
        play(c); return true
    }

    fun favShortcut(): Boolean {
        when (screen) {
            Screen.PLAYER -> current?.let { toggleFav(it); return true }
            Screen.VODPLAY -> (epSeries?.takeIf { vodPlay?.key?.startsWith("e|") == true } ?: detail)?.let { toggleFavKey(it); return true }
            Screen.LIST -> favAction?.let { it(); return true }
            else -> {}
        }
        return false
    }

    fun addSource(s: Source) {
        sources = sources + s.copy(id = UUID.randomUUID().toString().take(8))
        store.saveSources(sources)
        screen = Screen.LIST
        loadAll(true)
    }

    fun removeSource(s: Source) {
        sources = sources - s
        store.saveSources(sources)
        repo.removeSourceFiles(s.id)
        channels = channels.filter { it.sourceId != s.id }
        rebuildIndex()
        if (sources.isEmpty()) screen = Screen.ADD_SOURCE
    }

    // ---- Add / edit a source from a phone (LAN only, server runs only while the screen is open) ----
    /** [inline]: shown beside the manual form on Add source (started by that screen), not as a page of its own. */
    class LinkState(val url: String, val code: String, val edit: Source?, val inline: Boolean = false)
    var link by mutableStateOf<LinkState?>(null)
    var linkStatus by mutableStateOf("")
    private var server: LinkServer? = null

    fun startLink(edit: Source?, inline: Boolean = false) {
        stopLink()
        val ip = LinkNet.lanAddress() ?: run { if (!inline) toast("No local network address found"); return }
        val gate = CodeGate.create()
        val srv = LinkServer(gate, LinkPage.html(edit != null, edit?.name ?: ""), { linkInfo(edit) }, { f -> applyLink(f, edit) }, { onLinkDone() })
        try {
            val port = srv.start(ip)
            server = srv; linkStatus = ""
            val st = LinkState("http://${ip.hostAddress}:$port/", gate.code, edit, inline)
            link = st
            // the server stops when its one-time code expires (Add source renews its own code before that)
            viewModelScope.launch { kotlinx.coroutines.delay(10 * 60_000L); if (link === st) stopLink() }
            screen = Screen.ADD_SOURCE
        } catch (e: Exception) { if (!inline) toast("Could not start the phone link") }
    }

    fun stopLink() { server?.stop(); server = null; link = null }

    // called on the link server's thread: UI state changes on the main thread
    private fun onLinkDone() {
        server?.stop(); server = null
        viewModelScope.launch(Dispatchers.Main) { linkStatus = "Added"; kotlinx.coroutines.delay(2500); link = null; screen = Screen.LIST }
    }

    // Xtream login is the default type (user 2026-11-12); M3U stays selectable on the phone page
    private fun linkInfo(e: Source?): String = kotlinx.serialization.json.buildJsonObject {
        put("ok", true); put("kind", e?.kind ?: "xtream"); put("name", e?.name ?: ""); put("epg", e?.epgUrl ?: "")
        put("server", e?.server ?: ""); put("user", e?.user ?: "")
    }.toString()

    /** Validates a phone submission and stores it like the manual form. Returns an error message or null. */
    private fun applyLink(f: Map<String, String>, edit: Source?): String? {
        val xt = f["kind"] == "xtream"
        val server = (f["server"] ?: "").trim().ifBlank { edit?.server ?: "" }
        val user = (f["user"] ?: "").trim().ifBlank { edit?.user ?: "" }
        val pass = (f["pass"] ?: "").ifEmpty { edit?.pass ?: "" }
        val url = (f["url"] ?: "").trim().ifBlank { if (edit != null && !edit.isXtream) edit.url else "" }
        if (xt && (server.isBlank() || user.isBlank() || pass.isEmpty())) return "Fill in server, username and password"
        if (!xt && !url.startsWith("http://", true) && !url.startsWith("https://", true)) return "Playlist URL must start with http:// or https://"
        val s = Source(
            id = edit?.id ?: UUID.randomUUID().toString().take(8),
            name = (f["name"] ?: "").trim().ifBlank { edit?.name ?: if (xt) "Xtream" else "Playlist" },
            kind = if (xt) "xtream" else "m3u", url = if (xt) "" else url, epgUrl = if (xt) "" else (f["epg"] ?: "").trim(),
            server = if (xt) server else "", user = if (xt) user else "", pass = if (xt) pass else "",
        )
        sources = if (edit != null) sources.map { if (it.id == edit.id) s else it } else sources + s
        store.saveSources(sources)
        if (edit != null) repo.removeSourceFiles(edit.id)
        loadAll(true)
        return null
    }

    // ---- Organisation, parental lock, backup ----
    private fun savePrefs2(p: Prefs) { prefs = p; store.savePrefs(p) }

    /** Wrong-PIN limiter: one per process (see [ParentalLock.gate]), so relaunching the activity gives no new tries. */
    private val pinGate get() = ParentalLock.gate

    fun askPin(then: () -> Unit) {
        if (prefs.pinHash.isEmpty()) { toast("Set a PIN first (Settings)"); return }
        pinGate.waitSec().let { if (it > 0) { toast("Too many wrong PINs: wait $it s"); return } }
        prompt = PromptState("Enter PIN", "", true) { v ->
            when (val r = pinGate.check(prefs.pinHash, v)) {
                is PinGate.Result.Ok -> then()
                is PinGate.Result.Wrong -> toast(if (r.waitSec > 0) "Wrong PIN. Wait ${r.waitSec} s" else "Wrong PIN")
                is PinGate.Result.Wait -> toast("Too many wrong PINs: wait ${r.sec} s")
            }
        }
    }

    fun setPin() {
        val doSet = { prompt = PromptState("New PIN (4 to 8 digits)", "", true) { v -> if (Pin.validFormat(v)) { savePrefs2(prefs.copy(pinHash = Pin.hash(v))); toast("PIN saved") } else toast("Use 4 to 8 digits") } }
        if (prefs.pinHash.isEmpty()) doSet() else askPin(doSet)
    }

    fun removePin() = askPin { savePrefs2(prefs.copy(pinHash = "", locked = emptySet())); unlocked = emptySet(); toast("PIN removed, locks cleared") }

    fun toggleLock(name: String) {
        val act = {
            val l = prefs.locked
            savePrefs2(prefs.copy(locked = if (name in l) l - name else l + name)); unlocked = unlocked - name
            toast(if (name in l) "Unlocked $name" else "Locked $name")
        }
        if (prefs.pinHash.isEmpty()) {
            prompt = PromptState("Create a PIN first (4 to 8 digits)", "", true) { v ->
                if (Pin.validFormat(v)) { savePrefs2(prefs.copy(pinHash = Pin.hash(v))); act() } else toast("Use 4 to 8 digits")
            }
        } else askPin(act)
    }

    fun selectGroup(g: String) {
        if (g in prefs.locked && g !in unlocked) askPin { unlocked = unlocked + g; group = g } else group = g
    }


    fun vodCatMenu(g: String) {
        if (g == CAT_RECENT || g == CAT_MYLIST || g == VOD_ALL) return
        menu = MenuState(g, listOf((if (g in prefs.locked) "Unlock category" else "Lock category (PIN)") to { toggleLock(g) }))
    }

    private fun move(list: List<String>, key: String, d: Int): List<String> {
        val l = list.toMutableList(); val i = l.indexOf(key); val j = i + d
        if (i < 0 || j < 0 || j >= l.size) return list
        l[i] = l[j].also { l[j] = l[i] }; return l
    }

    fun openChannelMenu(c: Channel) {
        val fav = if (c.key in favourites) "Remove from Favourites (My list)" else "Add to Favourites (My list)"
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += "Watch" to { play(c) }
        items += fav to { toggleFav(c) }
        val now = System.currentTimeMillis() / 1000
        val cur = epg.nowNext(c, now).first
        activeRec(c)?.let { r -> items += "Stop recording" to { stopRec(r) } } ?: run { items += "Record..." to { recordMenu(c) } }
        cur?.let { p -> items += "Programme info: ${p.title}" to { progMenu = c to p } }
        if (c.catchup != null && c.catchupDays > 0) items += "Catch-up (guide, Left into the past)" to { focusCh = c; section = Section.TV; screen = Screen.LIST; toast("In the guide, press Left on ${c.name} to go back in time") }
        items += "Rename" to { prompt = PromptState("Channel name", c.name, false) { v -> if (v.isNotBlank()) savePrefs2(prefs.copy(names = prefs.names + (c.key to v.trim()))) } }
        items += "Hide channel" to { savePrefs2(prefs.copy(hiddenCh = prefs.hiddenCh + c.key)) }
        items += "Move up" to { savePrefs2(prefs.copy(order = move(prefs.order.ifEmpty { shown.map { it.key } }.let { o -> o + shown.map { it.key }.filter { k -> k !in o } }, c.key, -1))) }
        items += "Move down" to { savePrefs2(prefs.copy(order = move(prefs.order.ifEmpty { shown.map { it.key } }.let { o -> o + shown.map { it.key }.filter { k -> k !in o } }, c.key, 1))) }
        items += "Add to custom group..." to {
            val gs = prefs.custom.keys.map { g -> g to { savePrefs2(prefs.copy(custom = prefs.custom + (g to ((prefs.custom[g] ?: emptyList()) + c.key).distinct()))) } }
            menu = MenuState("Add to group", gs + ("New group..." to {
                prompt = PromptState("New group name", "", false) { v ->
                    val n = v.trim(); if (n.isNotEmpty() && n != GROUP_ALL && n != GROUP_FAV) savePrefs2(prefs.copy(custom = prefs.custom + (n to listOf(c.key))))
                }
            }))
        }
        if (group in prefs.custom) items += "Remove from this group" to { savePrefs2(prefs.copy(custom = prefs.custom + (group to (prefs.custom[group] ?: emptyList()).filter { it != c.key }))) }
        menu = MenuState(c.name, items)
    }

    fun openGroupMenu(g: String) {
        if (g == GROUP_ALL || g == GROUP_FAV) return
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += (if ("g|$g" in favourites) "Remove from My list" else "Add to My list") to { toggleFavGroup(g) }
        if (g in prefs.custom) {
            items += "Delete group" to { savePrefs2(prefs.copy(custom = prefs.custom - g)); if (group == g) group = GROUP_ALL }
        } else {
            items += "Rename" to { prompt = PromptState("Group name", prefs.groupNames[g] ?: g, false) { v -> if (v.isNotBlank()) savePrefs2(prefs.copy(groupNames = prefs.groupNames + (g to v.trim()))) } }
            // the hidden group comes back from Settings > Channels and groups
            items += "Hide group" to { savePrefs2(prefs.copy(hiddenGroups = prefs.hiddenGroups + g)); if (group == g) group = GROUP_ALL }
            // moves among the provider groups only: Favourites and All channels stay at the top (focus stays on the
            // moved group: the group column is keyed by name)
            val plain = providerGroups().let { all -> all.filter { it !in prefs.hiddenGroups } + all.filter { it in prefs.hiddenGroups } }
            items += "Move up" to { groupMoved = true; savePrefs2(prefs.copy(groupOrder = move(plain, g, -1))) }
            items += "Move down" to { groupMoved = true; savePrefs2(prefs.copy(groupOrder = move(plain, g, 1))) }
            items += "Send to top" to { groupMoved = true; savePrefs2(prefs.copy(groupOrder = GroupOrder.sendTo(plain, g, -1))) }
            items += "Send to bottom" to { groupMoved = true; savePrefs2(prefs.copy(groupOrder = GroupOrder.sendTo(plain, g, 1))) }
        }
        items += (if (g in prefs.locked) "Unlock group" else "Lock group (PIN)") to { toggleLock(g) }
        menu = MenuState(groupLabel(g), items)
    }

    fun newGroup() { prompt = PromptState("New group name", "", false) { v ->
        val n = v.trim(); if (n.isNotEmpty() && n != GROUP_ALL && n != GROUP_FAV) savePrefs2(prefs.copy(custom = prefs.custom + (n to emptyList())))
    } }

    // ---- Channels and groups editor ----
    fun editPrefs(f: (Prefs) -> Prefs) = savePrefs2(f(prefs))

    fun moveChannelKey(k: String, d: Int) {
        val all = channels.map { it.key }
        val base = prefs.order.ifEmpty { shown.map { it.key } }
        editPrefs { it.copy(order = move((base + all.filter { x -> x !in base }).distinct(), k, d)) }
    }

    /** Provider groups in their current order (hidden ones included). */
    fun providerGroups(): List<String> {
        return GroupOrder.order(channels.map { it.group }.distinct(), prefs.groupOrder)
    }

    fun moveGroup(g: String, d: Int) { editPrefs { it.copy(groupOrder = move(providerGroups(), g, d)) } }

    fun moveInCustom(group: String, k: String, d: Int) { editPrefs { it.copy(custom = it.custom + (group to move(it.custom[group] ?: emptyList(), k, d))) } }

    fun resetLayout() {
        editPrefs { it.copy(hiddenCh = emptySet(), names = emptyMap(), order = emptyList(), hiddenGroups = emptySet(), groupNames = emptyMap(), groupOrder = emptyList(), custom = emptyMap()) }
        if (group !in groupList) group = GROUP_ALL
        toast("Channel layout reset to the provider's")
    }

    fun unhideAll() { savePrefs2(prefs.copy(hiddenCh = emptySet(), hiddenGroups = emptySet())); toast("All channels and groups visible") }

    private fun backupFile() = java.io.File(app.getExternalFilesDir(null), "backup").apply { mkdirs() }.let { java.io.File(it, "tvbro-backup.json") }

    fun backup() {
        try {
            // the PIN hash stays out of the backup file (it sits in shared storage)
            val b = Backup(1, epgHours, epgShiftMin, startLast, bufferSec, store.recPadStart, store.recPadEnd, favourites, store.epgMap(), ParentalLock.forBackup(prefs))
            val f = backupFile(); f.writeText(BackupIo.encode(b)); toast("Saved ${f.path}")
        } catch (e: Exception) { toast("Backup failed: ${Net.describe(e)}") }
    }

    /**
     * Restores settings from the backup file. With a PIN set it asks for it first (a restore can change the locks),
     * and the current PIN always stays: a backup never brings in or removes a PIN.
     */
    fun restore() {
        if (ParentalLock.restoreNeedsPin(prefs)) askPin { doRestore() } else doRestore()
    }

    private fun doRestore() {
        try {
            val f = backupFile()
            if (!f.exists()) { toast("No backup at ${f.path}"); return }
            val b = BackupIo.decode(f.readText())
            changeEpgHours(b.epgHours); setShift(b.epgShiftMin); changeStartLast(b.startLast); setBuffer(b.bufferSec)
            store.recPadStart = b.recPadStart; store.recPadEnd = b.recPadEnd
            favourites = b.favourites; store.saveFavourites(favourites)
            store.saveEpgMap(b.epgMap); savePrefs2(ParentalLock.restored(prefs, b.prefs))
            toast("Restored. Reloading..."); loadAll(false)
        } catch (e: Exception) { toast("Restore failed: ${Net.describe(e)}") }
    }

    // ---- EPG extras ----
    var progMenu by mutableStateOf<Pair<Channel, Programme?>?>(null)
    private var pendingPlay: String? = null
    init { Reminders.init(app) }

    fun epgIds(sourceId: String) = sources.firstOrNull { it.id == sourceId }?.let { repo.epgIds(it) } ?: emptyList()

    fun mapChannel(c: Channel, id: String?) {
        val mp = store.epgMap().toMutableMap()
        if (id == null) mp.remove(c.key) else mp[c.key] = id
        store.saveEpgMap(mp)
        if (id != null) channels = channels.map { if (it.key == c.key) it.copy(tvgId = id, idKey = "${it.sourceId}|${id.lowercase()}") else it }
        val s = sources.firstOrNull { it.id == c.sourceId } ?: return
        viewModelScope.launch(Dispatchers.IO) { loadEpg(listOf(s), true) }
    }

    fun remind(c: Channel, p: Programme) {
        Reminders.add(app, Reminder(UUID.randomUUID().toString().take(8), c.key, c.name, p.title, p.start * 1000))
        toast("Reminder set for ${p.title}")
    }
    fun cancelReminder(id: String) = Reminders.remove(app, id)

    /** The "play" launch extra (reminders, recordings notifications): the parental lock applies (see [play]). */
    fun playByKey(key: String) {
        val c = channels.firstOrNull { it.key == key }
        if (c != null) play(c) else pendingPlay = key
    }

    /** A channel in a locked group (or a locked channel) not unlocked in this session. */
    fun isLocked(c: Channel) = ParentalLock.channel(prefs, unlocked, c.group, c.key)
    private fun unlock(c: Channel) { unlocked = ParentalLock.unlockChannel(unlocked, c.group, c.key) }
    /** A film / show of a locked category not unlocked in this session. */
    fun isLocked(v: VodItem) = ParentalLock.category(prefs, unlocked, v.category)

    // ---- Recording ----

    private fun startRec(r: Rec) {
        RecRepo.put(r)
        if (r.startMs <= System.currentTimeMillis() + 2000) {
            try { app.startForegroundService(android.content.Intent(app, RecordingService::class.java).setAction("start").putExtra("id", r.id)) }
            catch (e: Exception) { toast("Could not start recording service") }
        } else RecRepo.schedule(app, r)
    }

    fun recordNow(c: Channel, minutes: Int, endMs: Long = 0) {
        val now = System.currentTimeMillis()
        val end = if (endMs > 0) endMs else if (minutes > 0) now + minutes * 60_000L else 0L
        val dir = RecRepo.currentDir(app, store.recStorage)
        if (RecRepo.freeMb(dir) < store.recMinFreeMb) { toast("Not enough free space (${RecRepo.freeMb(dir)} MB, need ${store.recMinFreeMb} MB)"); return }
        startRec(Rec(UUID.randomUUID().toString().take(8), c.name, c.name, c.url, now, end, chKey = c.key))
        toast("Recording ${c.name}")
    }

    fun recordProgramme(c: Channel, p: Programme, quiet: Boolean = false) {
        val now = System.currentTimeMillis()
        val end = p.stop * 1000 + store.recPadEnd * 60_000L
        if (end < now) { if (!quiet) toast("That programme has ended"); return }
        val start = maxOf(p.start * 1000 - store.recPadStart * 60_000L, now)
        startRec(Rec(UUID.randomUUID().toString().take(8), p.title.ifBlank { c.name }, c.name, c.url, start, end, chKey = c.key))
        if (!quiet) toast("Scheduled: ${p.title}")
    }

    // ---- Record series: rules that keep scheduling new airings of a title on a channel from the guide ----
    init { SeriesRules.init(app) }
    fun seriesRuleFor(c: Channel, p: Programme): SeriesRule? = SeriesRules.items.firstOrNull { it.chKey == c.key && SeriesRec.matches(it, p) }

    fun recordSeries(c: Channel, p: Programme) {
        if (seriesRuleFor(c, p) != null) { toast("Already recording this series"); return }
        SeriesRules.add(app, SeriesRule(UUID.randomUUID().toString().take(8), c.key, c.name, p.title, System.currentTimeMillis()))
        toast("Series recording: every \"${p.title}\" on ${c.name}")
        scheduleSeries()
    }

    fun cancelSeries(r: SeriesRule) {
        SeriesRules.remove(app, r.id)
        // scheduled (not started) airings of this rule go too; finished and running recordings stay
        for (x in RecRepo.items.filter { it.status == "scheduled" && it.channel == r.channel && SeriesRec.key(it.title) == SeriesRec.key(r.title) }) stopRec(x)
        toast("Series recording cancelled")
    }

    /** One channel's guide between two times (stored data, with the guide time shift), off the main thread. */
    private fun storedProgrammes(c: Channel, from: Long, to: Long): List<Programme> {
        val db = EpgDb.get(app); val sh = epgShiftMin * 60L
        var r = c.idKey?.let { db.load(it.substringBefore('|'), it, from - sh, to - sh) } ?: emptyArray()
        if (r.isEmpty()) r = db.load(c.nameKey.substringBefore('|'), c.nameKey, from - sh, to - sh)
        return r.map { it.copy(start = it.start + sh, stop = it.stop + sh) }
    }

    /** Schedules the airings of every series rule in the next 48 h that are not scheduled yet. */
    fun scheduleSeries() {
        if (SeriesRules.items.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis() / 1000
            for (r in SeriesRules.items) {
                val c = channels.firstOrNull { it.key == r.chKey } ?: continue
                val progs = try { storedProgrammes(c, now - 6 * 3600, now + 48 * 3600) } catch (e: Exception) { continue }
                // an airing counts as scheduled when any recording of this channel overlaps it
                val have = progs.filter { p -> RecRepo.items.any { it.channel == c.name && it.status != "failed" && it.startMs < p.stop * 1000 && (it.endMs == 0L || it.endMs > p.start * 1000) } }
                    .map { it.start }.toSet()
                val due = SeriesRec.due(r, progs, have, now)
                kotlinx.coroutines.withContext(Dispatchers.Main) { for (p in due) recordProgramme(c, p, quiet = true) }
                if (due.isNotEmpty()) Diag.log("series recording: scheduled ${due.size} airing(s)")
            }
        }
    }
    init { viewModelScope.launch { while (true) { kotlinx.coroutines.delay(30 * 60_000L); scheduleSeries() } } }

    fun recordMenu(c: Channel) {
        val cur = epg.nowNext(c, System.currentTimeMillis() / 1000).first
        menu = MenuState("Record ${c.name}", listOfNotNull(
            cur?.let { "Record \"${it.title}\" (until it ends)" to { recordNow(c, 0, it.stop * 1000 + 60_000) } },
            cur?.let { p -> (seriesRuleFor(c, p)?.let { r -> "Cancel series recording" to { cancelSeries(r) } } ?: ("Record series: every \"${p.title}\"" to { recordSeries(c, p) })) },
            "Record 30 minutes" to { recordNow(c, 30) },
            "Record 1 hour" to { recordNow(c, 60) },
            "Record until stopped" to { recordNow(c, 0) },
        ))
    }

    fun activeRec(c: Channel): Rec? = RecRepo.items.firstOrNull { it.status == "recording" && it.channel == c.name }

    fun stopRec(r: Rec) {
        if (r.status == "scheduled") { RecRepo.cancelAlarm(app, r.id); RecRepo.remove(r.id) }
        else app.startService(android.content.Intent(app, RecordingService::class.java).setAction("stop").putExtra("id", r.id))
    }

    fun deleteRec(r: Rec) {
        if (r.status == "recording" || r.status == "scheduled") stopRec(r)
        if (r.path.startsWith("smb:")) { val cfg = store.smb(); val p = r.path; viewModelScope.launch(Dispatchers.IO) { SmbShare.delete(cfg, p) } }
        else if (r.path.isNotEmpty()) java.io.File(r.path).delete()
        RecRepo.cancelAlarm(app, r.id); RecRepo.remove(r.id)
    }

    fun playRec(r: Rec) {
        // a recording of a locked channel asks for the PIN like the channel
        val c = ParentalLock.recChannel(r.chKey, r.channel, channels, { it.key }, { listOfNotNull(it.name, prefs.names[it.key]) })
        if (c != null && isLocked(c)) { askPin { unlock(c); playRec(r) }; return }
        detail = null; vodPlay = VodPlay("r|${r.id}", if (r.path.startsWith("smb:")) r.path else "file://" + r.path, r.title); screen = Screen.VODPLAY }

    // ---- Recording storage: internal, a USB drive / SD card, or a network share (SMB) ----
    class StorageOpt(val key: String, val label: String, val free: String)

    /** Choices for Settings > Recording > Storage, with free space (the share's is checked when picked / tested). */
    fun storageOptions(): List<StorageOpt> {
        val dirs = RecRepo.dirs(app)
        val l = dirs.map { StorageOpt(it.path, RecRepo.dirLabel(app, it), "${RecRepo.freeMb(it)} MB free") }.toMutableList()
        val s = store.smb()
        l += StorageOpt(SmbShare.STORAGE_KEY, "Network share (SMB)" + if (s.ok) " ${s.label}" else " - not set up", if (s.ok) "free space: Test" else "set up first")
        return l
    }

    fun storageLabel(): String {
        val p = store.recStorage
        if (p == SmbShare.STORAGE_KEY) return "Network share ${store.smb().label}"
        val d = RecRepo.dirs(app)
        val cur = d.firstOrNull { it.path == p }
        if (p.isNotBlank() && cur == null) return "Internal (the chosen USB drive is not connected)"
        return RecRepo.dirLabel(app, cur ?: d.first()) + ", ${RecRepo.freeMb(cur ?: d.first())} MB free"
    }

    fun pickStorage() {
        menu = MenuState("Save recordings to", storageOptions().map { o ->
            ((if (o.key == store.recStorage || (store.recStorage.isBlank() && o.key == RecRepo.dirs(app).first().path)) "* " else "  ") + o.label + "  (" + o.free + ")") to {
                if (o.key == SmbShare.STORAGE_KEY && !store.smb().ok) setupSmb()
                else { store.recStorage = o.key; storageTick++; testStorage() }
            }
        })
    }
    var storageTick by mutableIntStateOf(0)

    /** Asks for the share step by step on the TV; the password is stored encrypted and never shown or logged. */
    fun setupSmb() {
        val c = store.smb()
        prompt = PromptState("Network share: server name or IP address", c.host, false) { host ->
            prompt = PromptState("Share name (e.g. recordings)", c.share, false) { share ->
                prompt = PromptState("Folder inside the share (optional)", c.path, false) { path ->
                    prompt = PromptState("User name (empty for guest access)", c.user, false) { user ->
                        prompt = PromptState(if (c.pass.isNotEmpty()) "Password (leave empty to keep the saved one)" else "Password", "", true) { pass ->
                            val n = SmbConfig(host.trim(), share.trim().trim('/', '\\'), path.trim(), user.trim(), if (pass.isEmpty()) c.pass else pass, c.domain)
                            store.saveSmb(n)
                            if (n.ok) { store.recStorage = SmbShare.STORAGE_KEY; storageTick++; testStorage() } else toast("Server and share are needed")
                        }
                    }
                }
            }
        }
    }

    var storageTest by mutableStateOf(""); private set
    /** Writes and deletes a test file at the chosen place and shows the free space. */
    fun testStorage() {
        storageTest = "Testing..."
        val p = store.recStorage
        viewModelScope.launch(Dispatchers.IO) {
            storageTest = if (p == SmbShare.STORAGE_KEY) SmbShare.test(store.smb()) else try {
                val dir = RecRepo.currentDir(app, p)
                val f = java.io.File(dir, ".tvbro-test"); f.writeText("tvBro"); val ok = f.readText() == "tvBro"; f.delete()
                val missing = p.isNotBlank() && RecRepo.dirs(app).none { it.path == p }
                (if (ok) "OK" else "Failed") + ": ${RecRepo.dirLabel(app, dir.parentFile!!)} writable, ${RecRepo.freeMb(dir)} MB free" + if (missing) " (the chosen USB drive is not connected: using internal storage)" else ""
            } catch (e: Exception) { "Failed: ${e.javaClass.simpleName}" }
            kotlinx.coroutines.withContext(Dispatchers.Main) { toast(storageTest) }
        }
    }

    // ---- VOD ----
    var section by mutableStateOf(Section.TV)
    var focusVod by mutableStateOf<VodItem?>(null)
    /** Movies grid scroll (first visible item, offset), kept while away on a title's page. */
    var vodScroll = 0 to 0
    var peek by mutableStateOf<VodDetail?>(null)
    private var peekJob: kotlinx.coroutines.Job? = null

    /** Header info for the focused title, debounced and read from the disk cache when possible. */
    fun peekVod(item: VodItem) {
        focusVod = item; peekJob?.cancel(); peek = null
        if (isDirect(item) || isLocked(item)) return
        val s = sources.firstOrNull { it.id == item.sourceId } ?: return
        peekJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(300)
            try {
                val txt = repo.info(s, item.kind == "series", item.id)
                val d = if (item.kind == "series") VodApi.parseSeriesInfo(txt) else VodApi.parseVodInfo(txt)
                if (focusVod?.key == item.key) peek = d
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
        }
    }

    /** A playlist (M3U) title: its address lives only in the decrypted playlist, never with the stored title. */
    fun isDirect(item: VodItem) = item.url != null || item.key.startsWith("v|")
    private fun directUrl(item: VodItem): String? = item.url ?: m3uVod.firstOrNull { it.key == item.key }?.url

    fun urlFor(item: VodItem, e: Episode?): String? {
        if (isDirect(item)) return directUrl(item)
        val s = sources.firstOrNull { it.id == item.sourceId } ?: return null
        return if (item.kind == "series") e?.let { VodApi.episodeUrl(s.server, s.user, s.pass, it.id, it.ext) }
        else VodApi.movieUrl(s.server, s.user, s.pass, item.id, detailInfo?.ext ?: item.ext)
    }

    fun openExternal(url: String) { launchExternal(url) }
    fun epgInfo(s: Source): String = repo.epgInfo(s)

    fun setSourceEnabled(s: Source, b: Boolean) {
        sources = sources.map { if (it.id == s.id) it.copy(enabled = b) else it }
        store.saveSources(sources); loadAll(false)
    }

    fun moveSource(s: Source, d: Int) {
        val l = sources.toMutableList(); val i = l.indexOfFirst { it.id == s.id }; val j = i + d
        if (i < 0 || j < 0 || j >= l.size) return
        l[i] = l[j].also { l[j] = l[i] }
        sources = l; store.saveSources(l); loadAll(false)
    }

    // ---- Downloads (queue handled by RecordingService) ----
    private fun dlIntent(a: String, id: String? = null) =
        android.content.Intent(app, RecordingService::class.java).setAction(a).also { if (id != null) it.putExtra("id", id) }

    fun queueDownload(key: String, title: String, url: String?, quiet: Boolean = false, cat: String = "") {
        if (url == null) { toast("No download address for this title"); return }
        if (DlRepo.items.any { it.key == key && it.status != "failed" }) { if (!quiet) toast("Already queued or downloaded"); return }
        val dir = java.io.File(RecRepo.currentDir(app, store.recStorage).parentFile, "downloads").apply { mkdirs() }
        if (RecRepo.freeMb(dir) < store.recMinFreeMb) { toast("Not enough free space (${RecRepo.freeMb(dir)} MB)"); return }
        val ext = url.substringBefore('?').substringAfterLast('.', "mp4").take(4).filter { it.isLetterOrDigit() }.ifEmpty { "mp4" }
        val base = RecRepo.newFile(dir, title, System.currentTimeMillis()).nameWithoutExtension
        DlRepo.put(Dl(UUID.randomUUID().toString().take(8), key, title, url, java.io.File(dir, "$base.$ext").path, cat = cat))
        try { app.startForegroundService(dlIntent("dl")) } catch (e: Exception) { toast("Could not start the download service") }
        if (!quiet) toast("Download queued")
    }

    fun downloadMovie(item: VodItem) = queueDownload(item.key, item.name, urlFor(item, null), cat = item.category)
    fun downloadEpisode(series: VodItem, e: Episode, quiet: Boolean = false) =
        queueDownload("e|${series.sourceId}|${e.id}", "${series.name} S%02dE%02d ${VodFmt.cleanTitle(series.name, e.title)}".format(e.season, e.num), urlFor(series, e), quiet, series.category)
    fun downloadSeason(series: VodItem, eps: List<Episode>) { eps.forEach { downloadEpisode(series, it, true) }; toast("Queued ${eps.size} episodes") }
    fun pauseDl(d: Dl) { DlRepo.put(d.copy(status = "paused", note = "")); app.startService(dlIntent("dlpause", d.id)) }
    fun resumeDl(d: Dl) { DlRepo.put(d.copy(status = "queued", note = "")); try { app.startForegroundService(dlIntent("dl")) } catch (e: Exception) { } }
    fun cancelDl(d: Dl) { app.startService(dlIntent("dlcancel", d.id)) }
    fun playLocal(d: Dl) {
        // a download of a locked category asks for the PIN (older downloads: the category of the stored title)
        val cat = d.cat.ifEmpty { store.snaps(listOf(d.key)).firstOrNull()?.category ?: "" }
        if (ParentalLock.category(prefs, unlocked, cat)) { askPin { unlocked = unlocked + cat; playLocal(d) }; return }
        vodPlay = VodPlay(d.key, "file://" + d.path, d.title); detail = null; screen = Screen.VODPLAY }

    var vodSeries by mutableStateOf(false)
    var vodItems by mutableStateOf<List<VodItem>>(emptyList())
    var vodCatNames by mutableStateOf<List<String>>(emptyList())
    var vodCat by mutableStateOf(CAT_MYLIST)
    var vodQuery by mutableStateOf("")
    var vodMsg by mutableStateOf("")
    var detail by mutableStateOf<VodItem?>(null)
    var detailInfo by mutableStateOf<VodDetail?>(null)
    var detailMsg by mutableStateOf("")
    var vodPlay by mutableStateOf<VodPlay?>(null)
    var mySnaps by mutableStateOf<List<VodItem>>(emptyList())
    var histSnaps by mutableStateOf<List<VodItem>>(emptyList())
    private var m3uVod: List<VodItem> = emptyList()
    private var catIndex: Map<String, List<Pair<Source, String>>> = emptyMap()
    private var vodJob: kotlinx.coroutines.Job? = null

    val vodFiltered: List<VodItem> by derivedStateOf {
        val q = vodQuery.trim().lowercase()
        val base = when (vodCat) {
            CAT_MYLIST -> mySnaps.filter { (it.kind == "series") == vodSeries }
            CAT_RECENT -> histSnaps.filter { (it.kind == "series") == vodSeries }
            else -> vodItems
        }.filter { it.category !in prefs.locked || it.category in unlocked }
        if (q.isEmpty()) base else base.filter { it.name.lowercase().contains(q) }
    }

    fun refreshLists() {
        mySnaps = store.snaps(favourites.filter { it.startsWith("m|") || it.startsWith("s|") || it.startsWith("v|") })
        histSnaps = store.snaps(store.recent())
    }

    fun openVod(series: Boolean) {
        section = if (series) Section.SHOWS else Section.MOVIES
        screen = Screen.LIST
        refreshLists()
        if (vodSeries == series && vodCatNames.isNotEmpty()) return
        vodSeries = series; vodItems = emptyList(); vodCatNames = emptyList(); vodMsg = "Loading categories..."
        vodCat = if (mySnaps.any { (it.kind == "series") == series }) CAT_MYLIST else CAT_RECENT
        val srcs = sources
        viewModelScope.launch(Dispatchers.IO) {
            // stored catalogue (synced into search.db): categories and pages come from the database
            val kind = if (series) SearchIndex.SERIES else SearchIndex.MOVIE
            val dbCats = SearchStore.use(app) { db -> if (SearchIndex.count(db, kind) > 0) SearchIndex.categories(db, kind) else null }
            if (dbCats != null) {
                catIndex = emptyMap(); vodFromDb = true
                vodCatNames = dbCats; vodMsg = ""
                return@launch
            }
            vodFromDb = false
            if (srcs.any { it.isXtream && it.enabled && SyncStatus.get(it.id, SyncKind.CATALOGUE).let { r -> r.lastOk == 0L && r.failures < 3 } }) viewModelScope.launch(Dispatchers.IO) {
                var any = false
                for (s in srcs.filter { it.isXtream && it.enabled }) try { repo.syncCatalogue(s); any = true } catch (e: Exception) { }
                if (any) SearchStore.requestRebuild(app, "catalogue")
            }
            val idx = HashMap<String, MutableList<Pair<Source, String>>>()
            for (s in srcs) {
                try { for (c in repo.vodCategories(s, series)) idx.getOrPut(c.categoryName) { ArrayList() }.add(s to c.categoryId) }
                catch (e: Exception) { vodMsg = "${s.name}: ${Net.describe(e)}"; Diag.log("vod categories failed: ${Net.describe(e)}") }
            }
            catIndex = idx
            vodCatNames = idx.keys.sorted() + (if (!series && m3uVod.isNotEmpty()) listOf(M3U_CAT) else emptyList())
            if (vodMsg == "Loading categories...") vodMsg = if (vodCatNames.isEmpty()) "No categories (Xtream sources provide Movies/Series)." else ""
        }
    }

    fun selectVodCat(g: String) {
        if (g in prefs.locked && g !in unlocked) askPin { unlocked = unlocked + g; showVodCat(g) } else showVodCat(g)
    }

    private fun showVodCat(g: String) {
        vodCat = g; vodJob?.cancel(); vodItems = emptyList(); vodScroll = 0 to 0
        if (g == CAT_MYLIST || g == CAT_RECENT) { refreshLists(); vodMsg = ""; return }
        vodMsg = "Loading..."
        val series = vodSeries
        if (vodFromDb) { vodMsg = ""; vodHasMore = true; vodLoadingPage = false; vodMore(); return }
        vodJob = viewModelScope.launch(Dispatchers.IO) {
            val all = ArrayList<VodItem>()
            try {
                if (g == M3U_CAT) all += m3uVod
                else if (g == VOD_ALL) { for (s in sources) all += repo.vodItems(s, series, null, false, 20_000); if (!series) all += m3uVod }
                else for ((s, id) in catIndex[g] ?: emptyList()) all += repo.vodItems(s, series, id, false, 30_000)
                vodItems = all
                vodMsg = if (all.isEmpty()) "Nothing in this category." else if (g == VOD_ALL && all.size >= 20_000) "Showing the first 20000 titles; pick a category for the rest." else ""
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { vodMsg = "Could not load: ${Net.describe(e)}"; Diag.log("vod load failed: ${Net.describe(e)}") }
        }
    }

    fun openDetail(it: VodItem) {
        // a title of a locked category (from My list, Continue watching, search) asks for the PIN first
        if (isLocked(it)) { askPin { unlocked = unlocked + it.category; openDetail(it) }; return }
        if (isDirect(it)) { val u = directUrl(it); if (u != null) playVod(it.key, u, it.name, it) else toast("This title is not in the playlist any more"); return }
        detail = it; detailInfo = null; detailMsg = "Loading..."; screen = Screen.DETAIL
        val s = sources.firstOrNull { s -> s.id == it.sourceId } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val txt = repo.info(s, it.kind == "series", it.id)
                detailInfo = if (it.kind == "series") VodApi.parseSeriesInfo(txt) else VodApi.parseVodInfo(txt)
                detailMsg = ""
                Diag.log("details: ${detailInfo?.episodes?.size ?: 0} episodes")
            } catch (e: Exception) { detailMsg = "Could not load details: ${Net.describe(e)}"; Diag.log("details failed: ${Net.describe(e)}") }
        }
    }

    fun playItem(it: VodItem) {
        val s = sources.firstOrNull { s -> s.id == it.sourceId } ?: return
        playVod(it.key, DlRepo.localFor(it.key) ?: VodApi.movieUrl(s.server, s.user, s.pass, it.id, detailInfo?.ext ?: it.ext), it.name, it)
    }

    var epSeries: VodItem? = null
    var epList: List<Episode> = emptyList()

    fun stepEpisode(d: Int) {
        val s = epSeries ?: return
        val k = vodPlay?.key
        val o = Watch.ordered(epList)
        val i = o.indexOfFirst { "e|${s.sourceId}|${it.id}" == k }
        val n = o.getOrNull(i + d)
        if (i < 0 || n == null) { toast("No more episodes"); return }
        playEpisode(s, n)
    }

    fun playEpisode(series: VodItem, e: Episode) {
        if (epSeries?.key != series.key) epList = emptyList()
        epSeries = series
        if (detail?.key == series.key) detailInfo?.episodes?.let { if (it.isNotEmpty()) epList = it }
        // In "Continue watching" from the moment it starts, showing this episode.
        updateSeries(series, epList.ifEmpty { listOf(e) }, lastId = e.id)
        val s = sources.firstOrNull { s -> s.id == series.sourceId } ?: return
        playVod("e|${series.sourceId}|${e.id}", DlRepo.localFor("e|${series.sourceId}|${e.id}") ?: VodApi.episodeUrl(s.server, s.user, s.pass, e.id, e.ext),
            "${series.name}   S%02d E%02d - ${VodFmt.cleanTitle(series.name, e.title)}".format(e.season, e.num), series)
    }

    private fun playVod(key: String, url: String, title: String, item: VodItem) {
        if (isLocked(item)) { askPin { unlocked = unlocked + item.category; playVod(key, url, title, item) }; return }
        store.snap(item); store.addRecent(item.key)
        if (external && !url.startsWith("file:") && launchExternal(url)) return
        vodPlay = VodPlay(key, url, title); screen = Screen.VODPLAY
    }

    fun toggleFavKey(item: VodItem) {
        store.snap(item)
        favourites = if (item.key in favourites) favourites - item.key else favourites + item.key
        store.saveFavourites(favourites); refreshLists()
        toast(if (item.key in favourites) "Added to My list" else "Removed from My list")
    }

    // ---- Stored catalogue, paged from search.db ----
    private var vodFromDb = false
    var vodHasMore by mutableStateOf(false); private set
    @Volatile private var vodLoadingPage = false

    /** Appends the next page of the current category (called as the grid nears its end). */
    fun vodMore() {
        if (!vodFromDb || !vodHasMore || vodLoadingPage) return
        val cat = vodCat; val kind = if (vodSeries) SearchIndex.SERIES else SearchIndex.MOVIE
        if (cat == CAT_MYLIST || cat == CAT_RECENT) return
        vodLoadingPage = true
        val offset = vodItems.size
        vodJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val page = SearchStore.use(app) { SearchIndex.page(it, kind, if (cat == VOD_ALL) null else cat, offset, VOD_PAGE) } ?: emptyList()
                if (vodCat == cat) {
                    vodItems = vodItems + page; vodHasMore = page.size == VOD_PAGE
                    if (vodItems.isEmpty()) vodMsg = "Nothing in this category."
                }
            } finally { vodLoadingPage = false }
        }
    }

    // ---- Search (FTS index built during sync; debounced, stale queries dropped) ----
    class ProgRow(val p: Programme, val state: ProgState)
    class SearchView(
        val q: String, val channels: List<Channel>, val movies: List<VodItem>, val series: List<VodItem>,
        val progs: List<Pair<Channel, List<ProgRow>>>, val ms: Long, val indexReady: Boolean,
    )
    var searchQ by mutableStateOf(""); private set
    var searchView by mutableStateOf<SearchView?>(null); private set
    var history by mutableStateOf(store.searchHistory()); private set
    private var searchJob: kotlinx.coroutines.Job? = null
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val searchDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var keyMapFor: List<Channel>? = null
    private var byKey: Map<String, Channel> = emptyMap()
    private var byEpg: Map<String, Channel> = emptyMap()

    fun setSearch(q: String) {
        searchQ = q
        searchJob?.cancel()
        if (Fold.text(q).length < 2) { searchView = null; return }
        val sh = shown; val shift = epgShiftMin * 60L; val names = prefs.names; val lockedCats = prefs.locked - unlocked
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(200)
            val v = kotlinx.coroutines.withContext(searchDispatcher) { runSearch(q, sh, shift, names.keys, lockedCats) }
            if (searchQ == q) searchView = v
        }
    }

    /** Re-runs the visible search (after an index swap). */
    fun refreshSearch() { if (searchQ.isNotBlank()) setSearch(searchQ) }

    fun rememberSearch(q: String = searchQ) {
        val h = SearchHistory.add(history, q)
        if (h != history) { history = h; store.saveSearchHistory(h) }
    }
    fun clearHistory() { history = emptyList(); store.saveSearchHistory(history) }

    private fun runSearch(q: String, sh: List<Channel>, shift: Long, renamedKeys: Set<String>, lockedCats: Set<String>): SearchView {
        val t0 = System.nanoTime()
        if (keyMapFor !== sh) {
            val k = HashMap<String, Channel>(sh.size * 2); val e = HashMap<String, Channel>(sh.size * 2)
            for (c in sh) { k[c.key] = c; c.idKey?.let { e.putIfAbsent(it, c) }; e.putIfAbsent(c.nameKey, c) }
            byKey = k; byEpg = e; keyMapFor = sh
        }
        val now = System.currentTimeMillis() / 1000
        val maxDays = sh.maxOfOrNull { if (it.catchup != null) it.catchupDays else 0 } ?: 0
        val pastFrom = now - shift - maxDays * 86400L
        val r = SearchStore.use(app) { SearchIndex.search(it, q, pastFrom) }
        val fq = Fold.text(q)
        // renamed channels are matched in memory (the index holds provider names)
        val renamed = renamedKeys.mapNotNull { byKey[it] }.filter { Fold.rank(it.name, q) < 3 || Fold.text(it.name).contains(fq) }
        val chans = if (r == null) sh.filter { Fold.text(it.name).contains(fq) }.sortedBy { Fold.rank(it.name, q) }.take(30)
            else (renamed + r.channels.mapNotNull { byKey[it.key] }).distinctBy { it.key }
        val groups = if (r == null) emptyList() else ProgGroups.group(r.programmes, { byEpg[it] }, { c, h -> ProgTime.classify(c, h.start + shift, h.stop + shift, now) }, { it.key })
            .map { (c, hs) -> c to hs.map { h -> val p = Programme(h.ch, h.start + shift, h.stop + shift, h.title, null, h.sub, h.ep); ProgRow(p, ProgTime.classify(c, p.start, p.stop, now)) }.filter { it.state != ProgState.PAST } }
        val open = { v: VodItem -> v.category !in lockedCats }
        return SearchView(q, chans, r?.movies?.map { it.toVod() }?.filter(open) ?: emptyList(), r?.series?.map { it.toVod() }?.filter(open) ?: emptyList(),
            groups, (System.nanoTime() - t0) / 1_000_000, r != null)
    }

    /** Description for the search details card (read on focus, not part of the result rows). */
    fun progDesc(p: Programme): String? = try { EpgDb.get(app).desc(p.key, p.start - epgShiftMin * 60L) } catch (e: Exception) { null }

    fun progActions(c: Channel, row: ProgRow) {
        val p = row.p
        val items = ArrayList<Pair<String, () -> Unit>>()
        when (row.state) {
            ProgState.NOW -> items += "Watch live" to { rememberSearch(); play(c) }
            ProgState.PAST_CATCHUP -> items += "Watch catch-up" to { rememberSearch(); guidePlay(c, p) }
            else -> {}
        }
        if (row.state == ProgState.NOW || row.state == ProgState.FUTURE) items += "Record" to { recordProgramme(c, p) }
        if (row.state == ProgState.FUTURE) items += "Remind me (My list)" to { remind(c, p) }
        if (row.state == ProgState.NOW || row.state == ProgState.FUTURE)
            items += (seriesRuleFor(c, p)?.let { r -> "Cancel series recording" to { cancelSeries(r) } } ?: ("Record series (every \"${p.title}\")" to { recordSeries(c, p) }))
        items += (if (c.key in favourites) "Remove ${c.name} from My list" else "Add ${c.name} to My list") to { toggleFav(c) }
        menu = MenuState(p.title, items)
    }

    // ---- Reminder pop-up while tvBro is open (the alarm still posts a notification when it is not) ----
    var dueReminder by mutableStateOf<Reminder?>(null)
    init {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(10_000)
                if (dueReminder == null) Reminders.items.firstOrNull { val d = System.currentTimeMillis() - it.startMs; d in -60_000..10 * 60_000 }?.let { dueReminder = it }
            }
        }
    }
    fun reminderWatch(r: Reminder) { dueReminder = null; Reminders.remove(app, r.id); playByKey(r.chKey) }
    fun reminderDismiss(r: Reminder) { dueReminder = null; Reminders.remove(app, r.id) }

    // ---- Sync now (Settings > Sync) ----
    fun syncNow(s: Source, k: SyncKind) {
        if (SyncStatus.progress(s.id, k) != null) return
        SyncStatus.step(s.id, k, "starting")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (k) {
                    SyncKind.CHANNELS -> { repo.refreshChannelFiles(s); store.setLastRefresh(s.id, System.currentTimeMillis()) }
                    SyncKind.GUIDE -> { if (repo.refreshEpg(s, channels.filter { it.sourceId == s.id })) rebuildIndex() else SyncStatus.fail(s.id, k, SafeIOException("this playlist has no guide address")) }
                    SyncKind.CATALOGUE -> { repo.syncCatalogue(s); vodCatNames = emptyList() }
                }
                if (k == SyncKind.CHANNELS) loadAll(false)
                SearchStore.requestRebuild(app, "sync now")
            } catch (e: Exception) { }
        }
    }
    fun guideStats(s: Source): String {
        val db = EpgDb.get(app)
        val m = try { db.meta(s.id) } catch (e: Exception) { null } ?: return "No guide stored"
        val n = try { db.count(s.id) } catch (e: Exception) { 0 }
        val f = object { fun format(d: java.util.Date) = TimeFmt.dayTime(d.time) }
        return "$n programmes, ${f.format(java.util.Date(m.minStart * 1000))} to ${f.format(java.util.Date(m.maxStop * 1000))}"
    }
    var vodHours by mutableIntStateOf(store.vodHours); private set
    fun changeVodHours(h: Int) { vodHours = h; store.vodHours = h }

    fun savePos(key: String, ms: Long, durMs: Long = 0) =
        watch.setPos(key, ms, durMs, if (key.startsWith("e|")) epSeries?.key else null)
    fun isWatched(key: String) = store.watched(key)
    /** Marks one title; for the playing series' episodes the series row (continue watching, next episode) follows. */
    fun markWatched(key: String, b: Boolean) {
        val s = epSeries
        if (key.startsWith("e|") && s != null && epList.any { epKey(s, it) == key }) { watch.setWatched(key, b, s.key); updateSeries(s, epList, touch = false) }
        else store.setWatched(key, b)
    }

    // ---- Series: continue watching, resume, next episode, watched marks, per-series tracks ----
    val watch: WatchDb get() = store.watch
    var autoplayNext by mutableStateOf(store.autoplayNext); private set
    fun changeAutoplayNext(b: Boolean) { autoplayNext = b; store.autoplayNext = b }
    init { viewModelScope.launch(Dispatchers.IO) { try { watch.warm() } catch (e: Exception) { Diag.log("state: could not open state.db") } } }

    fun epKey(series: VodItem, e: Episode) = "e|${series.sourceId}|${e.id}"
    fun epState(series: VodItem, e: Episode): WatchState? = watch.state(epKey(series, e))

    /** Episode list known for [series] (playing series or the open series page), or null. */
    fun episodesFor(series: VodItem): List<Episode>? = when {
        epSeries?.key == series.key && epList.isNotEmpty() -> epList
        detail?.key == series.key -> detailInfo?.episodes?.takeIf { it.isNotEmpty() }
        else -> null
    }

    /** Recomputes a series row (next episode, finished, in "Continue watching") from its episodes and their marks. */
    fun updateSeries(series: VodItem, eps: List<Episode>, lastId: String? = watch.seriesRow(series.key)?.lastEp, touch: Boolean = true) {
        if (eps.isEmpty()) return
        val sum = Watch.summary(eps, { epState(series, it) }, lastId)
        val o = watch.seriesRow(series.key) ?: SeriesRow(series.key)
        val n = sum.next
        watch.putSeries(o.copy(
            updated = if (touch || o.updated == 0L) System.currentTimeMillis() else o.updated, lastEp = lastId,
            nextEp = n?.id, nextSeason = n?.season ?: 0, nextNum = n?.num ?: 0, nextTitle = n?.let { VodFmt.cleanTitle(series.name, it.title) },
            finished = sum.finished, active = sum.active,
        ))
        store.snap(series)
    }

    /** Marks episodes (one, a season or the whole series) watched or unwatched. */
    fun markEpisodes(series: VodItem, which: List<Episode>, watched: Boolean) {
        val all = episodesFor(series)
        val apply = { eps: List<Episode> ->
            for (e in which) watch.setWatched(epKey(series, e), watched, series.key)
            val wholeUnwatched = !watched && eps.all { e -> which.any { it.id == e.id } }
            updateSeries(series, eps, if (wholeUnwatched) null else watch.seriesRow(series.key)?.lastEp, touch = watched)
        }
        if (all != null) apply(all) else apply(which)
        toast(if (watched) "Marked as watched" else "Marked as unwatched")
    }

    /** Whole series from anywhere (continue-watching card): loads the episode list when it is not at hand. */
    fun markSeries(series: VodItem, watched: Boolean) {
        episodesFor(series)?.let { markEpisodes(series, it, watched); return }
        val s = sources.firstOrNull { it.id == series.sourceId } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val eps = VodApi.parseSeriesInfo(repo.info(s, true, series.id)).episodes
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    for (e in eps) watch.setWatched(epKey(series, e), watched, series.key)
                    updateSeries(series, eps, if (watched) watch.seriesRow(series.key)?.lastEp else null, touch = watched)
                    toast(if (watched) "Marked as watched" else "Marked as unwatched")
                }
            } catch (e: Exception) { kotlinx.coroutines.withContext(Dispatchers.Main) { toast("Could not load the episodes: ${Net.describe(e)}") } }
        }
    }

    /** Series in "Continue watching", most recent first, with their catalogue entry. */
    fun continueShows(): List<Pair<VodItem, SeriesRow>> {
        val rows = Watch.continueOrder(watch.allSeries(), { it.active && it.nextEp != null }, { it.updated })
        val snaps = store.snaps(rows.map { it.key }).associateBy { it.key }
        return rows.mapNotNull { r -> snaps[r.key]?.let { it to r } }
    }

    /** Called when the playing episode reached 90 % or its end: watched, and the series moves on. */
    fun episodeDone(key: String) {
        val s = epSeries
        if (key.startsWith("e|") && s != null) { watch.setWatched(key, true, s.key); updateSeries(s, epList, touch = true) }
        else store.setWatched(key, true)
    }

    /** "Play from start": clears the episode's position and watched mark, then plays it. */
    fun restartEpisode(series: VodItem, e: Episode) { watch.setWatched(epKey(series, e), false, series.key); playEpisode(series, e) }

    fun nextEpisode(): Episode? {
        val s = epSeries ?: return null
        val k = vodPlay?.key ?: return null
        val cur = epList.firstOrNull { epKey(s, it) == k } ?: return null
        return Watch.next(epList, cur.id)
    }

    fun playNext() {
        val s = epSeries ?: return
        val n = nextEpisode() ?: return
        vodPlay?.key?.let { episodeDone(it) }
        playEpisode(s, n)
    }

    /** Series of the playing episode, when it is one. */
    fun playingSeries(): VodItem? = epSeries?.takeIf { vodPlay?.key?.startsWith("e|") == true }

    /** The user picked a subtitle (or off) or audio track while an episode plays: remember it for the series. */
    fun rememberTrack(audio: Boolean, lang: String?, on: Boolean) {
        val s = playingSeries() ?: return
        val o = watch.seriesRow(s.key) ?: SeriesRow(s.key, updated = System.currentTimeMillis())
        watch.putSeries(if (audio) o.copy(audioLang = lang?.takeIf { it.isNotBlank() } ?: o.audioLang) else o.copy(subs = SubPref(on, lang ?: "")))
    }

    // keep last: every property above is initialised by now
    init { watchUiState(); DebugHooks.attach(this) }
}
