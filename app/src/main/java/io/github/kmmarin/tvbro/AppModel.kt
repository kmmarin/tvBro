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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collectLatest
import io.github.kmmarin.tvbro.data.*
import kotlinx.serialization.json.put
import io.github.kmmarin.tvbro.ui.MenuState
import io.github.kmmarin.tvbro.ui.PromptState
import java.util.UUID

enum class Section { SEARCH, TV, MOVIES, SHOWS, RECORDINGS, MYLIST }

enum class Screen { LIST, PLAYER, GUIDE, SETTINGS, ADD_SOURCE, GUIDE_SEARCH, MAPPING, VOD, DETAIL, VODPLAY, RECORDINGS, EDITOR, SMB_SETUP, MULTIVIEW }

/** A past programme playing from the archive; [lenMs]: its length (the stream itself may run on for hours). */
data class CatchupPlay(val url: String, val title: String, val lenMs: Long = 0)
data class VodPlay(val key: String, val url: String, val title: String)

// Built-in Movies / Shows categories: keys no provider category can have (a provider "History" category collided
// with the watch history: both showed as selected and choosing it showed the history); vodCatLabel() names them.
const val CAT_RECENT = "\u0001History"
/** Search: matches per result row shown, and fetched from the index to pick them in name order. */
const val SEARCH_ROW = 30
const val SEARCH_FETCH = 150
const val CAT_MYLIST = "\u0001My list"
const val VOD_ALL = "\u0001All titles"

fun vodCatLabel(g: String, shows: Boolean): String = when (g) {
    CAT_RECENT -> "History"; CAT_MYLIST -> "My list"; VOD_ALL -> if (shows) "All shows" else "All movies"; else -> g
}
const val M3U_CAT = "Playlist VOD"
const val VOD_PAGE = 240

/** The TV screen's rows for group [g] (from [shown]: visible channels in display order). */
fun groupRows(shown: List<Channel>, g: String, prefs: Prefs, favourites: Set<String>): List<Channel> = when (g) {
    GROUP_ALL -> shown
    GROUP_FAV -> shown.filter { it.key in favourites }
    else -> if (g in prefs.custom) (prefs.custom[g] ?: emptyList()).toHashSet().let { ks -> shown.filter { it.key in ks } } else shown.filter { it.group == g }
}

const val GROUP_ALL = "All channels"
const val GROUP_FAV = "Favourites"

class AppModel(private val app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    private val repo = Repository(app)
    /** When this model was made: a provider account read before it is read again (once per run). */
    private val startedAt = System.currentTimeMillis()

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
    private val groupState = mutableStateOf(GROUP_ALL)
    var group: String
        get() = groupState.value
        set(v) {
            if (BuildConfig.DEBUG && v != groupState.value) android.util.Log.i("tvbro-dbg", "group -> $v from " + Throwable().stackTrace.drop(1).take(5).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" })
            groupState.value = v
        }
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
    /** Settings > Appearance > Show channel numbers (TV screen rows, player banner); off by default. */
    var showNumbers by mutableStateOf(store.showNumbers); private set
    fun changeShowNumbers(b: Boolean) { showNumbers = b; store.showNumbers = b }
    /** Settings > Appearance > Scrolling: the list moves under a centred selection (default), or the selection moves. */
    var scrollCentred by mutableStateOf(store.scrollCentred.also { io.github.kmmarin.tvbro.ui.FocusScroll.centred = it }); private set
    fun changeScrollCentred(b: Boolean) { scrollCentred = b; store.scrollCentred = b; io.github.kmmarin.tvbro.ui.FocusScroll.centred = b }
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
    /** The account info popup (ui/AccountPopup.kt): the source shown, null when closed. */
    var accountOf by mutableStateOf<Source?>(null)
    /** Sources whose account is being asked right now (the popup shows "Checking..."); declared before init's loadAll. */
    val accountBusy = androidx.compose.runtime.mutableStateListOf<String>()

    val shown: List<Channel> by derivedStateOf {
        val p = prefs
        val pos = p.order.withIndex().associate { it.value to it.index }
        val l = channels.filter { it.key !in p.hiddenCh && it.group !in p.hiddenGroups && (it.group !in p.locked || it.group in unlocked) }
            .map { c -> p.names[c.key]?.let { c.copy(name = it) } ?: c }
        // PPV channels last by default (stable: the provider's order otherwise); the user's own order wins
        // (PPV once per group: a regex in every comparison of 15 800 channels held the main thread ~1 s at start)
        val ppv = HashMap<String, Boolean>()
        val isPpv = { c: Channel -> ppv.getOrPut(c.group) { GroupOrder.isPpv(c.group) } }
        if (pos.isEmpty()) l.sortedBy { isPpv(it) }
        else l.sortedBy { pos[it.key] ?: if (isPpv(it)) Int.MAX_VALUE else Int.MAX_VALUE - 1 }
    }

    /** Favourites and All channels always first; then custom groups, then provider groups (PPV last by default). */
    val groupList: List<String> by derivedStateOf {
        snapGroups?.let { return@derivedStateOf it } // the start snapshot (see applySnapshot)
        val p = prefs
        val g = channels.filter { it.group !in p.hiddenGroups }.map { it.group }.distinct()
        listOf(GROUP_FAV, GROUP_ALL) + p.custom.keys.toList() + GroupOrder.order(g, p.groupOrder)
    }

    fun groupLabel(g: String) = (prefs.groupNames[g] ?: g) + (if (g in prefs.locked) " (locked)" else "") + (if ("g|$g" in favourites) "  *" else "")

    val filtered: List<Channel> by derivedStateOf {
        val q = query.trim().lowercase()
        groupRows(shown, group, prefs, favourites).let { l -> if (q.isEmpty()) l else l.filter { it.name.lowercase().contains(q) } }.let { l -> if (numberMode == 1 && !snapActive) l.mapIndexed { i, c -> c.copy(number = i + 1) } else l }
    }

    init {
        // Create these objects' Compose state here, on the main thread before the first composition: created on the IO
        // thread below while the UI composes, reading them crashed ("state created after the snapshot was taken").
        RecRepo.items; io.github.kmmarin.tvbro.data.DlRepo.items
        io.github.kmmarin.tvbro.data.Reminders.items; io.github.kmmarin.tvbro.data.SeriesRules.items; StreamLimits.items
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
            try { StreamLimits.init(store, s.map { it.id }) } catch (e: Exception) { Diag.log("stream limits init failed (${e.javaClass.simpleName})") }
            try { RecRepo.init(app) } catch (e: Exception) { Diag.log("recordings init failed (${e.javaClass.simpleName})") }
            if (s.isNotEmpty()) loadAll(false)
            try { Refresh.schedule(app, store.chHours) } catch (e: Exception) { Diag.log("could not schedule refresh") }
        }
    }

    fun toast(s: String) = Toast.makeText(app, s, Toast.LENGTH_SHORT).show()

    fun loadAll(force: Boolean) {
        val srcs = sources.filter { it.enabled }
        val multi = srcs.size > 1
        // the providers' accounts (stream limit, expiry) beside the playlist, never in its way: once per run, and with
        // every refresh
        for (s in srcs) if (force || (StreamLimits.get(s.id)?.checkedAt ?: 0L) < startedAt) refreshAccount(s)
        viewModelScope.launch(Dispatchers.IO) {
            Refresh.fgBusy = true
            // the guide database opens while the channel list is read (the rows of the start snapshot load from it)
            val dbOpen = if (epg !is DbEpg) launch { try { rebuildIndex() } catch (e: Exception) { Diag.log("guide db open failed (${e.javaClass.simpleName})") } } else null
            try {
            if (channels.isEmpty() && !snapActive) loading = "Loading channels..." // (quiet while the start snapshot shows)
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
            realLoaded = true
            channels = all.mapIndexed { i, c -> (if (c.number > 0) c else c.copy(number = i + 1)).let { x -> em[x.key]?.let { id -> x.copy(tvgId = id, idKey = "${x.sourceId}|${id.lowercase()}") } ?: x } }
            // on the main thread: groupList / filtered are derived states, and reading them here (IO) while the UI
            // composes crashed ("state created after the snapshot was taken")
            kotlinx.coroutines.withContext(Dispatchers.Main) {
            endSnapshot()
            pendingPlay?.let { k -> pendingPlay = null; channels.firstOrNull { it.key == k }?.let { play(it) } }
            if (!groupInit || group !in groupList) {
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
            dbOpen?.join()
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
        if (epg !is DbEpg) rebuildIndex() // cache first: show whatever is stored right away (opened early by loadAll)
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

    // ---- Stream limit, per provider (data/StreamLimits.kt): recordings hold a stream; live TV shares the rest ----
    fun sourceName(id: String?): String = sources.firstOrNull { it.id == id }?.name ?: "The provider"
    /** The provider of each running recording (null: unknown, it counts against every provider). */
    fun recordingSources(): List<String?> = RecRepo.items.filter { it.status == "recording" }.map { r ->
        StreamRules.recSource(r.chKey, r.channel) { n -> channels.firstOrNull { it.name == n }?.sourceId }
    } + DlRepo.items.filter { it.status == "downloading" }.map { d -> StreamRules.dlSource(d.url, sources) }
    /** Streams provider [id] allows ([StreamLimits]: its own number, else the user's, else 1). */
    fun streamLimit(id: String?): Int = id?.let { StreamLimits.limit(it) } ?: StreamRules.DEFAULT
    /** Streams provider [id] has left for live TV: its limit minus its running recordings. */
    fun freeStreams(id: String?): Int = if (id == null) streamLimit(null) else StreamRules.free(streamLimit(id), id, recordingSources())
    /** True when recordings already use every stream of [id]'s provider (the preview then stays off). */
    fun streamsFull(id: String? = current?.sourceId): Boolean = freeStreams(id) <= 0
    private fun recNote(id: String): String = recordingSources().count { it == null || it == id }.let { n ->
        when (n) { 0 -> ""; 1 -> ", a recording or download uses 1"; else -> ", recordings and downloads use $n" }
    }
    /** "Provider allows 3 streams, a recording uses 1": for every stream limit message. */
    fun limitText(id: String): String = streamLimit(id).let { n -> "${sourceName(id)} allows $n stream${if (n == 1) "" else "s"}" } + recNote(id)

    /** Asks the provider for [s]'s account (Xtream only) off the main thread; the stored one stays when it fails. */
    fun refreshAccount(s: Source, done: () -> Unit = {}) {
        if (!s.isXtream) { done(); return }
        viewModelScope.launch(Dispatchers.Main) {
            if (s.id in accountBusy) { done(); return@launch }
            accountBusy += s.id
            val a = kotlinx.coroutines.withContext(Dispatchers.IO) { repo.account(s) }
            if (a != null) { StreamLimits.update(s.id, a); mvFit() }   // a lower limit closes tiles past it
            Diag.log("account ${s.id.take(6)}: " + (a?.let { "max ${it.reported}, active ${it.active}, ${it.status}" } ?: "not available"))
            accountBusy -= s.id
            done()
        }
    }

    /** Account info of [s], asked again as it opens. */
    fun openAccount(s: Source) { accountOf = s; refreshAccount(s) }

    /** The user's own stream limit for a provider that does not report one. */
    fun setUserStreams(s: Source, n: Int) = StreamLimits.setUser(s.id, n)

    // ---- Multiview (ui/Multiview.kt): 2 to 4 live channels at once, one connection each, within each provider's limit ----
    /** The tiles' channels, in tile order (empty: multiview is not open). */
    val mvTiles = androidx.compose.runtime.mutableStateListOf<Channel>()
    /** The layout chosen (used when it fits the number of tiles, see [MvRules.resolve]). */
    var mvLayout by mutableStateOf(MvLayout.SIDE)

    /** The focused tile: it has the sound, and OK / Back act on it. */
    var mvFocus by mutableIntStateOf(0)
    private fun mvSources(): List<String> = mvTiles.map { it.sourceId }

    /** True when [c] can go into tile [slot] (null: as a new tile) within its provider's limit. */
    fun mvFits(c: Channel, slot: Int?): Boolean = MvRules.fits(mvSources(), slot, c.sourceId) { freeStreams(it) }

    /**
     * Opens multiview with the playing channel (and [add], from a channel menu). A provider whose account was never
     * read is asked first; with room for fewer than two streams it says so (naming the provider's limit) and stays.
     */
    fun openMultiview(add: Channel? = null) {
        val first = listOfNotNull(current, add).distinctBy { it.key }.filterNot { isLocked(it) }
        if (first.isEmpty()) { toast("Play a channel first, then open multiview"); return }
        viewModelScope.launch {
            for (id in first.map { it.sourceId }.distinct()) {
                val s = sources.firstOrNull { it.id == id } ?: continue
                if (s.isXtream && (StreamLimits.get(id)?.checkedAt ?: 0L) == 0L)
                    kotlinx.coroutines.withContext(Dispatchers.IO) { repo.account(s) }?.let { StreamLimits.update(id, it) }
            }
            val ids = first.map { it.sourceId }.distinct()
            if (MvRules.room(ids) { freeStreams(it) } < 2) {
                toast("Multiview needs 2 streams: " + ids.joinToString("; ") { limitText(it) })
                return@launch
            }
            // the full-screen / preview player gives its connection up first (the tiles wait for it, see Multiview.kt)
            liveExo?.let { it.stop(); it.clearMediaItems() }; liveUrl = null; liveKey = null
            mvTiles.clear(); mvTiles.addAll(MvRules.take(first, { it.sourceId }) { freeStreams(it) })
            mvFocus = (mvTiles.size - 1).coerceAtLeast(0)
            screen = Screen.MULTIVIEW
            Diag.log("multiview: ${mvTiles.size} tiles (" + ids.joinToString { "limit ${streamLimit(it)}, free ${freeStreams(it)}" } + ")")
        }
    }

    /** Debug builds (DebugHooks `debug.tvbro.mv`): multiview on [chs] at once (test streams, no limit check). */
    fun mvOpenForTest(chs: List<Channel>, layout: MvLayout) {
        if (!BuildConfig.DEBUG || chs.isEmpty()) return
        liveExo?.let { it.stop(); it.clearMediaItems() }; liveUrl = null; liveKey = null
        mvTiles.clear(); mvTiles.addAll(chs.take(MvRules.MAX_TILES)); mvLayout = layout; mvFocus = 0
        screen = Screen.MULTIVIEW
    }

    /** Why [c] cannot be a new tile: "Provider allows 3 streams: all in use. Change a tile's channel instead". */
    fun mvRefusal(c: Channel): String =
        if (mvTiles.size >= MvRules.MAX_TILES) "Multiview shows at most ${MvRules.MAX_TILES} channels: change a tile's channel instead"
        else "${limitText(c.sourceId)}: all in use. Change a tile's channel instead"

    /** Puts [c] into tile [i] (a new tile when [i] is past the end and its provider's limit allows one). */
    fun mvSet(i: Int, c: Channel) {
        if (isLocked(c)) { askPin { unlock(c); mvSet(i, c) }; return }
        if (mvTiles.any { it.key == c.key }) { mvFocus = mvTiles.indexOfFirst { it.key == c.key }; return }
        val slot = i.takeIf { it in mvTiles.indices }
        when {
            !mvFits(c, slot) -> toast(mvRefusal(c))
            slot != null -> { mvTiles[slot] = c; mvFocus = slot }
            else -> { mvTiles.add(c); mvFocus = mvTiles.size - 1 }
        }
    }

    /**
     * A recording started while multiview runs: tiles past their provider's limit close (the last ones first), so the
     * provider never sees more streams than it allows. One tile left: back to full screen.
     */
    fun mvFit() {
        if (mvTiles.isEmpty()) return
        val keep = MvRules.take(mvTiles.toList(), { it.sourceId }) { freeStreams(it) }
        if (keep.size == mvTiles.size) return
        val gone = mvTiles.size - keep.size
        if (keep.isEmpty()) { val id = mvTiles[0].sourceId; mvLeave(full = false); toast("Multiview closed: " + limitText(id)); return }
        mvTiles.retainAll(keep.toSet()); mvFocus = mvFocus.coerceAtMost(mvTiles.size - 1)
        toast("$gone tile${if (gone == 1) "" else "s"} closed: " + limitText(mvTiles[0].sourceId))
    }

    fun mvRemove(i: Int) {
        if (i !in mvTiles.indices) return
        if (mvTiles.size <= 1) { mvLeave(); return }
        mvTiles.removeAt(i); mvFocus = mvFocus.coerceAtMost(mvTiles.size - 1)
    }

    /** Leaves multiview: full screen on the focused tile's channel ([full]), else the TV screen. */
    fun mvLeave(full: Boolean = true) {
        val c = mvTiles.getOrNull(mvFocus)
        mvTiles.clear()
        if (c != null) { current = c; catchup = null; store.lastKey = c.key }
        screen = if (full && c != null) Screen.PLAYER else Screen.LIST
        if (!full) leavePlayer()
    }

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
        if (snapRestored) {
            // the snapshot already put the screen back (the user may have moved on since): only the real objects now
            current?.let { c -> channels.firstOrNull { it.key == c.key }?.let { if (it != c) current = it } }
            focusCh?.let { c -> channels.firstOrNull { it.key == c.key }?.let { if (it != c) focusCh = it } }
            return
        }
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
    /** The TV screen's Back: the guide back to now, or the groups from the programmes; false: not handled (side menu). */
    var tvBack: (() -> Boolean)? = null
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

    /** True while a live channel plays (full screen or in the TV screen's preview). */
    val livePlaying: Boolean get() = current != null && liveUrl != null

    /**
     * Stops live playback: the preview goes dark and the stream closes (Back on the TV screen after leaving full screen).
     * The playing channel stays focused, so OK on it starts it again.
     */
    fun stopLive() {
        current?.let { c -> if (focusCh == null) focusCh = c }
        current = null; catchup = null
        liveExo?.let { it.stop(); it.clearMediaItems() }
        liveUrl = null; liveKey = null
    }

    fun play(c: Channel) {
        // parental lock, on every path (lists, guide, search, the "play" launch extra)
        if (isLocked(c)) { askPin { unlock(c); play(c) }; return }
        if (streamsFull(c.sourceId) && activeRec(c) == null) toast("${limitText(c.sourceId)}: the provider may refuse this channel")
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
        if (!previewOn || streamsFull(c.sourceId) || external || current?.key == c.key || isLocked(c)) { play(c); return }
        current = c; catchup = null; store.lastKey = c.key
    }

    fun zap(delta: Int) {
        // channel up / down skips locked channels (they open only through play(), which asks for the PIN)
        // within the playing channel's group: the one shown on the TV screen when it is there, else its own (played
        // from Search or My list, Up went to the first channel of whatever group the TV screen showed)
        val cur = current
        val base = filtered.takeIf { f -> cur == null || f.any { it.key == cur.key } }
            ?: shown.filter { it.group == cur?.group }.ifEmpty { filtered.ifEmpty { shown } }
        val l = base.filter { !isLocked(it) || it.key == current?.key }
        if (l.isEmpty()) return
        val i = l.indexOfFirst { it.key == current?.key }
        val n = l[((if (i < 0) 0 else i) + delta).mod(l.size)]
        current = n; catchup = null; store.lastKey = n.key
    }

    /** Live channels watched lately (keys, newest first): the player's quick-access row. In memory, saved on change. */
    var recentCh by mutableStateOf(store.recentChannels()); private set
    fun noteWatched(c: Channel) {
        val l = ChannelHistory.add(recentCh, c.key)
        if (l != recentCh) { recentCh = l; store.saveRecentChannels(l) }
    }

    /** Live TV switches to [c] at once (player quick-access row, history); a locked channel asks for the PIN. */
    fun zapTo(c: Channel) {
        if (isLocked(c)) { play(c); return }
        current = c; catchup = null; store.lastKey = c.key
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
        catchup = CatchupPlay(url, p.title, (p.stop - p.start) * 1000)
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
        ((screen == Screen.LIST && section == Section.TV) || screen == Screen.GUIDE) &&
            io.github.kmmarin.tvbro.data.BackLong.target(current, focusCh) != null &&
            menu == null && prompt == null && progMenu == null

    /** Long-press Back: the playing (preview) channel full screen, see [io.github.kmmarin.tvbro.data.BackLong]. True when it acted. */
    fun backLongPress(): Boolean {
        if (!backLongApplies()) return false
        val c = io.github.kmmarin.tvbro.data.BackLong.target(current, focusCh) ?: return false
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

    /**
     * Row URL_GUARD of [CoreSwitch]: the source's addresses are checked before it is saved (no DNS here; host names
     * are checked when they are resolved). Null when allowed or when the row runs Kotlin.
     */
    private fun sourceRefusal(s: Source): String? {
        val shadow = CoreSwitch.shadow(CoreSwitch.Row.URL_GUARD)
        if (!CoreSwitch.core(CoreSwitch.Row.URL_GUARD) && !shadow) return null
        val urls = if (s.isXtream) listOf(Xtream.normalizeServer(s.server) + "/") else listOf(s.url) + s.epgUrl.split(Regex("[,\\s]+")).filter { it.isNotBlank() }
        val r = urls.firstNotNullOfOrNull { Core.urlRefusal(it) }
        if (shadow) { CoreShadow.urlGuard("add-source", r); return null }
        return r?.let { Diag.log("add source refused by the URL guard: $it"); "Address not allowed ($it)" }
    }

    fun addSource(s: Source) {
        sourceRefusal(s)?.let { toast(it); return }
        sources = sources + s.copy(id = UUID.randomUUID().toString().take(8))
        store.saveSources(sources)
        screen = Screen.LIST
        loadAll(true)
    }

    fun removeSource(s: Source) {
        sources = sources - s
        store.saveSources(sources)
        repo.removeSourceFiles(s.id)
        StreamLimits.remove(s.id)
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
        sourceRefusal(s)?.let { return it }
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
                is PinGate.Result.Ok -> { if (Pins.needsRehash(prefs.pinHash)) savePrefs2(prefs.copy(pinHash = Pins.hash(v))); then() }
                is PinGate.Result.Wrong -> toast(if (r.waitSec > 0) "Wrong PIN. Wait ${r.waitSec} s" else "Wrong PIN")
                is PinGate.Result.Wait -> toast("Too many wrong PINs: wait ${r.sec} s")
            }
        }
    }

    fun setPin() {
        val doSet = { prompt = PromptState("New PIN (4 to 8 digits)", "", true) { v -> if (Pin.validFormat(v)) { savePrefs2(prefs.copy(pinHash = Pins.hash(v))); toast("PIN saved") } else toast("Use 4 to 8 digits") } }
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
                if (Pin.validFormat(v)) { savePrefs2(prefs.copy(pinHash = Pins.hash(v))); act() } else toast("Use 4 to 8 digits")
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
        if (screen == Screen.MULTIVIEW) items += "Show in multiview" to { mvSet(mvTiles.size, c) }
        else if (current != null && current?.key != c.key) items += "Multiview with ${current?.name}" to { openMultiview(c) }
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
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (g != GROUP_ALL && g != GROUP_FAV) {
            items += (if ("g|$g" in favourites) "Remove from My list" else "Add to My list") to { toggleFavGroup(g) }
            if (g in prefs.custom) {
                items += "Delete group" to { val at = groupList.indexOf(g); savePrefs2(prefs.copy(custom = prefs.custom - g)); if (group == g) focusGroupAt(at) }
            } else {
                items += "Rename" to { prompt = PromptState("Group name", prefs.groupNames[g] ?: g, false) { v -> if (v.isNotBlank()) savePrefs2(prefs.copy(groupNames = prefs.groupNames + (g to v.trim()))) } }
                items += "Hide group" to { hideGroup(g) }
                // moves among the provider groups only: Favourites and All channels stay at the top. Move up / down: focus
                // stays on the moved group (the group column is keyed by name). Top / bottom: on the group now in its
                // place, so focus stays in the group column and on screen (it jumped into the channel list).
                val plain = providerGroups().let { all -> all.filter { it !in prefs.hiddenGroups } + all.filter { it in prefs.hiddenGroups } }
                items += "Move up" to { groupMoved = true; savePrefs2(prefs.copy(groupOrder = move(plain, g, -1))) }
                items += "Move down" to { groupMoved = true; savePrefs2(prefs.copy(groupOrder = move(plain, g, 1))) }
                items += "Send to top" to { val t = nextGroup(g); savePrefs2(prefs.copy(groupOrder = GroupOrder.sendTo(plain, g, -1))); focusGroup(t) }
                items += "Send to bottom" to { val t = nextGroup(g); savePrefs2(prefs.copy(groupOrder = GroupOrder.sendTo(plain, g, 1))); focusGroup(t) }
            }
            items += (if (g in prefs.locked) "Unlock group" else "Lock group (PIN)") to { toggleLock(g) }
        }
        val hidden = providerGroups().filter { it in prefs.hiddenGroups }
        if (hidden.isNotEmpty()) items += "Show hidden groups... (${hidden.size})" to { showHiddenGroups() }
        if (items.isEmpty()) return
        menu = MenuState(groupLabel(g), items)
    }

    /** The TV screen's way to focus its selected group (set while the TV screen is composed). */
    var focusGroupSel: (() -> Boolean)? = null

    /**
     * Selects and focuses the group now at [at] in the group column (the next one; the one before at the end), skipping
     * [avoid] and locked groups, once the open menu has closed.
     */
    fun focusGroupAt(at: Int, avoid: String? = null) = focusGroup(GroupOrder.neighbour(groupList, at, avoid) { x -> x !in prefs.locked || x in unlocked })

    /** The group after [g] in the column (before it at the end), skipping locked ones: where focus goes when [g] leaves. */
    private fun nextGroup(g: String): String {
        val gl = groupList; val i = gl.indexOf(g)
        return if (i < 0) group else GroupOrder.neighbour(gl, i + 1, g) { x -> x !in prefs.locked || x in unlocked }
    }

    private fun focusGroup(t: String) {
        group = t
        focusGroupSel?.let { f -> io.github.kmmarin.tvbro.ui.FocusMemory.nextFocus = f }
    }

    /** One hidden-group undo at a time: the bar shown after "Hide group" (see UndoBar). */
    class UndoState(val text: String, val undo: () -> Unit)
    var undo by mutableStateOf<UndoState?>(null)

    fun hideGroup(g: String) {
        val at = groupList.indexOf(g)
        savePrefs2(prefs.copy(hiddenGroups = prefs.hiddenGroups + g))
        if (group == g) focusGroupAt(at)
        undo = UndoState("Group hidden: ${prefs.groupNames[g] ?: g}") { unhideGroup(g) }
    }

    fun unhideGroup(g: String) {
        savePrefs2(prefs.copy(hiddenGroups = prefs.hiddenGroups - g))
        if (g !in prefs.locked || g in unlocked) focusGroup(g)
    }

    /** Group menu > Show hidden groups...: one row per hidden group, OK shows it again (no confirmation). */
    fun showHiddenGroups() {
        val hidden = providerGroups().filter { it in prefs.hiddenGroups }
        if (hidden.isEmpty()) { toast("No hidden groups"); return }
        val items = hidden.map { h -> "Show ${prefs.groupNames[h] ?: h}" to { unhideGroup(h) } }.toMutableList()
        if (hidden.size > 1) items += "Show all ${hidden.size}" to { savePrefs2(prefs.copy(hiddenGroups = prefs.hiddenGroups - hidden.toSet())); focusGroup(hidden.first()) }
        menu = MenuState("Hidden groups", items)
    }

    /** A popup layer is open (focus stays in it; closing it puts focus back). */
    fun layerOpen(): Boolean = menu != null || prompt != null || progMenu != null || undo != null || dueReminder != null || accountOf != null

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
            try { androidx.core.content.ContextCompat.startForegroundService(app, android.content.Intent(app, RecordingService::class.java).setAction("start").putExtra("id", r.id)) }
            catch (e: Exception) { toast("Could not start recording service") }
        } else RecRepo.schedule(app, r)
    }

    fun recordNow(c: Channel, minutes: Int, endMs: Long = 0) {
        val now = System.currentTimeMillis()
        val end = if (endMs > 0) endMs else if (minutes > 0) now + minutes * 60_000L else 0L
        val dir = RecRepo.currentDir(app, store.recStorage)
        if (RecRepo.freeMb(dir) < store.recMinFreeMb) { toast("Not enough free space (${RecRepo.freeMb(dir)} MB, need ${store.recMinFreeMb} MB)"); return }
        val full = streamsFull(c.sourceId)
        startRec(Rec(UUID.randomUUID().toString().take(8), c.name, c.name, c.url, now, end, chKey = c.key))
        toast(if (full) "Recording ${c.name}. ${limitText(c.sourceId)}: the provider may refuse it" else "Recording ${c.name}")
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

    // ---- Add / Edit network share: one screen (typed on the TV or sent from a phone), Test before Save ----
    /** The values on the screen, not saved until Save. The password is never shown, logged or sent to the phone. */
    var smbForm by mutableStateOf(SmbConfig())
    /** Bumped when the phone filled the form (the screen moves focus to Save). */
    var smbFilled by mutableIntStateOf(0); private set
    class SmbTestState(val running: Boolean, val ok: Boolean, val text: String, val tested: SmbConfig?)
    var smbTest by mutableStateOf<SmbTestState?>(null); private set
    private var smbTestGen = 0
    var smbLink by mutableStateOf<LinkState?>(null); private set
    var smbLinkStatus by mutableStateOf(""); private set
    private var smbServer: LinkServer? = null
    private var smbBack = Screen.SETTINGS

    /** Opens the share screen with the saved share (or the defaults: folder "tvBro", guest). */
    fun setupSmb() {
        val c = store.smb()
        smbForm = if (c.host.isBlank() && c.share.isBlank()) SmbConfig() else c
        smbTest = null; smbTestGen++
        smbLinkStatus = ""
        if (screen != Screen.SMB_SETUP) smbBack = screen
        screen = Screen.SMB_SETUP
    }

    fun closeSmb() { stopSmbLink(); smbTestGen++; smbTest = null; screen = smbBack; smbBack = Screen.SETTINGS }

    /** Test with the values on the screen (before saving): off the main thread, capped at 15 s. */
    fun testSmbForm() {
        val c = SmbSetup.normalize(smbForm); smbForm = c
        val g = ++smbTestGen
        if (!c.ok) { smbTest = SmbTestState(false, false, "Fill in the server and the share name first", null); return }
        smbTest = SmbTestState(true, false, "Testing ${c.label}...", null)
        val work = viewModelScope.async(Dispatchers.IO) {
            try { SmbShare.check(c) } catch (e: Exception) { SmbShare.Check(false, "Could not connect: " + SmbShare.explain(e)) }
        }
        viewModelScope.launch {
            val r = kotlinx.coroutines.withTimeoutOrNull(15_000) { work.await() }
                ?: SmbShare.Check(false, "Could not connect: server did not answer. Check the address and that the server is on.")
            if (g == smbTestGen) smbTest = SmbTestState(false, r.ok, r.text, if (r.ok) c else null)
        }
    }

    /** Saves (encrypted, see [Store.saveSmb]) and records to the share from now on. */
    fun saveSmbForm() {
        val c = SmbSetup.normalize(smbForm); smbForm = c
        if (!c.ok) { smbTestGen++; smbTest = SmbTestState(false, false, "Fill in the server and the share name first", null); return }
        store.saveSmb(c)
        store.recStorage = SmbShare.STORAGE_KEY; storageTick++
        val t = smbTest
        closeSmb()
        if (t != null && t.ok && t.tested == c) { storageTest = "OK: ${c.label} is writable, " + t.text.substringAfter("Connected - "); toast("Network share saved") } else testStorage()
    }

    fun startSmbLink() {
        stopSmbLink()
        val ip = LinkNet.lanAddress() ?: return
        val gate = CodeGate.create()
        val srv = LinkServer(gate, SmbLinkPage.html(), { SmbSetup.info(smbForm) }, { f -> applySmbLink(f) }, { onSmbLinkDone() })
        try {
            val port = srv.start(ip)
            smbServer = srv
            val st = LinkState("http://${ip.hostAddress}:$port/", gate.code, null, true)
            smbLink = st
            // the server stops when its one-time code expires (the screen renews it before that)
            viewModelScope.launch { kotlinx.coroutines.delay(10 * 60_000L); if (smbLink === st) stopSmbLink() }
        } catch (e: Exception) { srv.stop() }
    }

    fun stopSmbLink() { smbServer?.stop(); smbServer = null; smbLink = null }

    // called on the link server's thread
    private fun applySmbLink(f: Map<String, String>): String? {
        val (n, err) = SmbSetup.fromPhone(f, smbForm)
        // (the page checks the fields first; a refused submission still used up the code: a new one after the reply)
        if (n == null) { viewModelScope.launch(Dispatchers.Main) { kotlinx.coroutines.delay(800); if (screen == Screen.SMB_SETUP) startSmbLink() }; return "$err, then scan the new code on the TV" }
        viewModelScope.launch(Dispatchers.Main) { smbForm = n; smbFilled++; smbLinkStatus = "Filled in from your phone"; testSmbForm() }
        return null
    }

    // the code is used up: a new one (and QR code) so the phone can send corrections
    private fun onSmbLinkDone() {
        viewModelScope.launch(Dispatchers.Main) { if (screen == Screen.SMB_SETUP) startSmbLink() else stopSmbLink() }
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
        if (focusVod?.key == item.key && peek != null) return
        focusVod = item; peekJob?.cancel()
        if (isDirect(item) || isLocked(item)) { peek = null; return }
        // details read before show at once (back to a title, or its page opening right after the header showed them)
        peek = infoCache[item.key]
        if (peek != null) return
        val s = sources.firstOrNull { it.id == item.sourceId } ?: return
        peekJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(300)
            try {
                val txt = repo.info(s, item.kind == "series", item.id)
                val d = if (item.kind == "series") VodApi.parseSeriesInfo(txt) else VodApi.parseVodInfo(txt)
                infoCache[item.key] = d
                if (focusVod?.key == item.key) peek = d
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
        }
    }
    /** Parsed title details, newest 60 (the header and the title's page share them). */
    private val infoCache = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, VodDetail>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, VodDetail>?) = size > 60
    })

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
        try { androidx.core.content.ContextCompat.startForegroundService(app, dlIntent("dl")) } catch (e: Exception) { toast("Could not start the download service") }
        if (!quiet) toast("Download queued")
    }

    fun downloadMovie(item: VodItem) = queueDownload(item.key, item.name, urlFor(item, null), cat = item.category)
    fun downloadEpisode(series: VodItem, e: Episode, quiet: Boolean = false) =
        queueDownload("e|${series.sourceId}|${e.id}", "${series.name} S%02dE%02d ${VodFmt.cleanTitle(series.name, e.title)}".format(e.season, e.num), urlFor(series, e), quiet, series.category)
    fun downloadSeason(series: VodItem, eps: List<Episode>) { eps.forEach { downloadEpisode(series, it, true) }; toast("Queued ${eps.size} episodes") }
    fun pauseDl(d: Dl) { DlRepo.put(d.copy(status = "paused", note = "")); app.startService(dlIntent("dlpause", d.id)) }
    fun resumeDl(d: Dl) { DlRepo.put(d.copy(status = "queued", note = "")); try { androidx.core.content.ContextCompat.startForegroundService(app, dlIntent("dl")) } catch (e: Exception) { } }
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

    /** The selected category's titles (locked categories hidden). Search is the global Search section only. */
    val vodShown: List<VodItem> by derivedStateOf {
        when (vodCat) {
            CAT_MYLIST -> mySnaps.filter { (it.kind == "series") == vodSeries }
            CAT_RECENT -> histSnaps.filter { (it.kind == "series") == vodSeries }
            else -> vodItems
        }.filter { it.category !in prefs.locked || it.category in unlocked }
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
        if (vodCatNames.isNotEmpty()) store.setVodScroll(vodSeries, vodScroll) // (the other kind's place)
        vodSeries = series; vodItems = emptyList(); vodCatNames = emptyList(); vodMsg = "Loading categories..."
        // the category shown last time (and its scroll); the first time All movies / All shows, never an empty page
        val cat = store.vodCat(series) ?: VOD_ALL
        val scroll = store.vodScroll(series)
        vodCat = cat
        // categories of the stored catalogue read before (Movies <-> Shows, or at start): shown at once
        vodCatCache[series]?.takeIf { it.first == SearchStore.version.intValue }?.let { (_, cats) ->
            catIndex = emptyMap(); vodFromDb = true; vodCatNames = cats; vodMsg = ""; showVodCat(cat, scroll); return
        }
        val srcs = sources
        viewModelScope.launch(Dispatchers.IO) {
            // stored catalogue (synced into search.db): categories and pages come from the database
            val dbCats = storedCats(series)
            if (dbCats != null) {
                catIndex = emptyMap(); vodFromDb = true
                vodCatNames = dbCats; vodMsg = ""
                kotlinx.coroutines.withContext(Dispatchers.Main) { if (vodSeries == series && vodCat == cat) showVodCat(cat, scroll) }
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
            if (vodCatNames.isNotEmpty() || cat == CAT_MYLIST || cat == CAT_RECENT) kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (vodSeries == series && vodCat == cat) showVodCat(cat.takeIf { it in vodCatNames || it == CAT_MYLIST || it == CAT_RECENT || it == VOD_ALL } ?: VOD_ALL, scroll)
            }
        }
    }

    /** Movies / Shows categories of the stored catalogue with the index version they were read at. */
    private val vodCatCache = java.util.concurrent.ConcurrentHashMap<Boolean, Pair<Int, List<String>>>()

    /** The stored catalogue's categories (off the main thread), or null when it holds no titles of that kind. */
    private fun storedCats(series: Boolean): List<String>? {
        val v = SearchStore.version.intValue
        val kind = if (series) SearchIndex.SERIES else SearchIndex.MOVIE
        return SearchStore.use(app) { db -> if (db.count(kind) > 0) db.categories(kind) else null }?.also { vodCatCache[series] = v to it }
    }

    /** Reads both category lists ahead (cheap: two queries), so the first visit to Movies or Shows shows them at once. */
    fun prefetchVod() { viewModelScope.launch(Dispatchers.IO) { try { storedCats(false); storedCats(true) } catch (e: Exception) { } } }

    fun selectVodCat(g: String) {
        if (g in prefs.locked && g !in unlocked) askPin { unlocked = unlocked + g; showVodCat(g) } else showVodCat(g)
    }

    /** Shows category [g] at [scroll] (first item, offset; the top for a category picked by the user). */
    private fun showVodCat(g: String, scroll: Pair<Int, Int> = 0 to 0) {
        vodCat = g; vodJob?.cancel(); vodItems = emptyList(); vodScroll = scroll
        store.setVodCat(vodSeries, g); store.setVodScroll(vodSeries, scroll)
        if (g == CAT_MYLIST || g == CAT_RECENT) { refreshLists(); vodMsg = ""; return }
        vodMsg = "Loading..."
        val series = vodSeries
        // (the remembered place may lie beyond the first page: enough pages for it, in one query)
        if (vodFromDb) { vodMsg = ""; vodHasMore = true; vodLoadingPage = false; vodMore(first = maxOf(FIRST_PAGE, scroll.first + 60)); return }
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
        val known = infoCache[it.key]
        detail = it; detailInfo = known; detailMsg = if (known == null) "Loading..." else ""; screen = Screen.DETAIL
        if (known != null) return
        val s = sources.firstOrNull { s -> s.id == it.sourceId } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val txt = repo.info(s, it.kind == "series", it.id)
                val d = if (it.kind == "series") VodApi.parseSeriesInfo(txt) else VodApi.parseVodInfo(txt)
                infoCache[it.key] = d
                if (detail?.key == it.key) detailInfo = d
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
    /** Titles in a category's first page: a screen and a half of posters, so the grid shows at once (the rest pages in). */
    private val FIRST_PAGE = 48

    fun vodMore(first: Int = 0) {
        if (!vodFromDb || !vodHasMore || vodLoadingPage) return
        val cat = vodCat; val kind = if (vodSeries) SearchIndex.SERIES else SearchIndex.MOVIE
        if (cat == CAT_MYLIST || cat == CAT_RECENT) return
        vodLoadingPage = true
        val offset = vodItems.size
        vodJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val n = if (first > 0) first else VOD_PAGE
                val page = SearchStore.use(app) { it.page(kind, if (cat == VOD_ALL) null else cat, offset, n) } ?: emptyList()
                if (vodCat == cat) {
                    vodItems = vodItems + page; vodHasMore = page.size == n
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
    private var foldedFor: List<Channel>? = null
    private var folded: Array<String> = emptyArray()
    /** Search opened by the user (side menu, Search key, guide): the keyboard opens with it, as in TiviMate. */
    var searchKeyboard = false

    fun openSearch() { searchKeyboard = true; section = Section.SEARCH; screen = Screen.LIST }

    /** Folds the channel names for search before the first letter (~15,000 names: the first query paid for it). */
    fun warmSearch() {
        val sh = shown
        if (foldedFor === sh) return
        viewModelScope.launch(searchDispatcher) { if (foldedFor !== sh) { folded = Array(sh.size) { " " + Fold.text(sh[it].name) }; foldedFor = sh } }
    }

    fun setSearch(q: String) {
        searchQ = q
        searchJob?.cancel()
        if (Fold.text(q).length < 2) { searchView = null; return }
        val sh = shown; val shift = epgShiftMin * 60L; val names = prefs.names; val lockedCats = prefs.locked - unlocked
        searchJob = viewModelScope.launch {
            // (a letter on a TV keyboard takes longer than this: the wait only skips letters typed in one burst)
            kotlinx.coroutines.delay(100)
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
        // The order TiviMate shows (user 2026-11-14: same results, same order): each row alphabetical over all its
        // matches (favourite channels first), programmes by channel name. The index ranks and cuts at [perSection],
        // so many more are fetched and the first [SEARCH_ROW] in name order are kept.
        val r = SearchStore.search(app, q, pastFrom, SEARCH_FETCH)
        // Channels are matched in memory over every channel (names folded once per list), the index's rule: each word
        // of the query starts a word of the name. The index keeps only its best-ranked hits, names starting with the
        // query first, so "4K: ESPN" and "ARG: ESPN" never reached the alphabetical row TiviMate shows for "espn".
        if (foldedFor !== sh) { folded = Array(sh.size) { " " + Fold.text(sh[it].name) }; foldedFor = sh }
        val toks = Fold.tokens(q).map { " $it" }
        val names = folded
        val found = sh.filterIndexed { i, _ -> val n = names[i]; toks.all { n.contains(it) } }
        val favs = favourites
        // (sort keys folded once per item: folding inside the comparator cost ~250 ms on the Fire TV)
        fun <T> alpha(l: List<T>, name: (T) -> String, first: (T) -> Boolean = { false }): List<T> =
            l.map { Triple(it, Fold.text(name(it)), first(it)) }.sortedWith(compareBy<Triple<T, String, Boolean>>({ !it.third }, { it.second }, { name(it.first) })).map { it.first }
        val chans = alpha(found, { it.name }, { it.key in favs }).take(SEARCH_ROW)
        val groups = if (r == null) emptyList() else ProgGroups.group(r.programmes, { byEpg[it] }, { c, h -> ProgTime.classify(c, h.start + shift, h.stop + shift, now) }, { it.key })
            .map { (c, hs) -> c to hs.map { h -> val p = Programme(h.ch, h.start + shift, h.stop + shift, h.title, null, h.sub, h.ep); ProgRow(p, ProgTime.classify(c, p.start, p.stop, now)) }.filter { it.state != ProgState.PAST } }
            .filter { it.second.isNotEmpty() }.let { g -> alpha(g, { it.first.name }) }
        val open = { v: VodItem -> v.category !in lockedCats }
        val vod = { l: List<SearchHit>? -> alpha(l.orEmpty().map { it.toVod() }.filter(open), { it.name }).take(SEARCH_ROW) }
        return SearchView(q, chans, vod(r?.movies), vod(r?.series), groups, (System.nanoTime() - t0) / 1_000_000, r != null)
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
    init { viewModelScope.launch(Dispatchers.IO) { try { watch.warm() } catch (e: Exception) { Diag.log("state: could not open state.db") }; store.warmSnaps(); prefetchVod() } }

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

    // ---- Start snapshot: the TV screen as it was, shown in the first frame (see StartSnap) ----
    /** The group column while the snapshot stands in for the channel list (null: from [channels]). */
    var snapGroups by mutableStateOf<List<String>?>(null); private set
    /** True while [channels] holds the snapshot's rows, until the stored channel list is loaded. */
    var snapActive by mutableStateOf(false); private set
    /** The real channel list was set (the snapshot must not replace it). */
    @Volatile private var realLoaded = false
    /** The TV screen's scroll (first visible row, offset) while it is composed. */
    var tvView: (() -> Pair<Int, Int>)? = null

    private fun applySnapshot() {
        if (store.hasSourcesHint != true || screen != Screen.LIST || realLoaded) return
        val t0 = android.os.SystemClock.uptimeMillis()
        val s = StartSnap.read(app.filesDir, CacheKey.get(app)) ?: return
        val p = store.lastUi.split('|')
        if (p.getOrNull(1)?.let { it != Section.TV.name } == true) return // the app closed elsewhere (Movies, ...)
        snapGroups = s.groups; snapActive = true
        channels = s.rows
        group = s.group; groupInit = true; groupReady = true
        tvScroll[s.group] = (s.scrollIdx - s.first).coerceAtLeast(0) to s.scrollOff
        val map = HashMap<String, Array<Programme>>(s.progs.size * 2)
        for (c in s.rows) s.progs[c.key]?.let { a -> map[c.idKey ?: c.nameKey] = a; if (c.idKey == null) map[c.nameKey] = a }
        epg = EpgIndex(map)
        io.github.kmmarin.tvbro.ui.SnapGuide.progs = s.progs
        section = Section.TV
        val last = (s.current ?: s.rows.firstOrNull { it.key == store.lastKey })?.takeIf { it.key == store.lastKey }
            ?.takeIf { store.startLast && !DebugHooks.noAutoplay() && !isLocked(it) }
        if (last != null) current = last
        focusCh = s.rows.firstOrNull { it.key == s.focusKey } ?: last?.takeIf { l -> s.rows.any { it.key == l.key } }
        settingsSec = p.getOrNull(3)?.toIntOrNull() ?: 0
        val scr = p.getOrNull(0)?.takeIf { it.isNotEmpty() }
        screen = when {
            last != null && (scr == null || scr == Screen.PLAYER.name) -> Screen.PLAYER
            scr == Screen.SETTINGS.name -> Screen.SETTINGS
            else -> Screen.LIST
        }
        snapRestored = true
        Diag.log("start snapshot: ${s.rows.size} rows, ${s.progs.size} with guide, ${s.groups.size} groups, ${(System.currentTimeMillis() - s.savedAt) / 1000} s old, read+applied in ${android.os.SystemClock.uptimeMillis() - t0} ms")
    }
    /** The snapshot restored the screen: the full load does not move it again. */
    private var snapRestored = false

    /** Called with the stored channel list loaded (main thread): the snapshot gives way. */
    private fun endSnapshot() {
        if (!snapActive) return
        snapActive = false; snapGroups = null
        // (the rows keep showing the snapshot's programmes until the guide database has them)
        viewModelScope.launch { kotlinx.coroutines.delay(30_000); io.github.kmmarin.tvbro.ui.SnapGuide.progs = emptyMap() }
    }

    /**
     * Saves what the TV screen shows now (on stop, every minute in front, before a restart). Gathered on the main
     * thread (a slice of the list and the guide cache), written on IO, or right here when [now] (the process ends).
     */
    fun saveSnapshot(now: Boolean = false) {
        if (!started || snapActive || channels.isEmpty() || section != Section.TV || screen !in setOf(Screen.LIST, Screen.PLAYER, Screen.SETTINGS)) {
            if (BuildConfig.DEBUG) Diag.log("start snapshot: not saved (started=$started snap=$snapActive channels=${channels.size} section=$section screen=$screen)")
            return
        }
        val list = filtered
        val (idx, off) = tvView?.invoke() ?: tvScroll[group] ?: (0 to 0)
        val a = (idx - 3).coerceIn(0, (list.size - 1).coerceAtLeast(0))
        val b = (idx + 22).coerceAtMost(list.size - 1)
        val rows = if (b >= a) ArrayList(list.subList(a, b + 1)) else ArrayList()
        val fk = focusCh?.key ?: ""
        val nowS = System.currentTimeMillis() / 1000
        val ws = io.github.kmmarin.tvbro.ui.GuideLayout.initialStart(nowS)
        val we = ws + io.github.kmmarin.tvbro.ui.GuideLayout.WINDOW_MIN * 60L
        val e = epg
        val progs = HashMap<String, Array<Programme>>()
        for (c in rows) {
            val pr = (if (e is DbEpg) e.peek(c, ws - 1800, we + 1800) else e.programmes(c)).filter { it.stop > ws - 1800 && it.start < we + 1800 }
            if (pr.isNotEmpty()) progs[c.key] = pr.toTypedArray()
        }
        val snap = StartSnap(System.currentTimeMillis(), screen.name, group, groupList, rows, a, idx, off, fk, current, progs)
        val dir = app.filesDir
        val write = { try { StartSnap.write(dir, snap, CacheKey.get(app)) } catch (x: Exception) { Diag.log("start snapshot not saved (${x.javaClass.simpleName})") } }
        if (now) write() else viewModelScope.launch(Dispatchers.IO) { write() }
    }

    /** Settings > About > Advanced > Restart now: what must survive the restart. */
    fun saveForRestart() { store.lastUi = listOf(screen.name, section.name, focusCh?.key ?: "", settingsSec.toString()).joinToString("|"); saveSnapshot(now = true) }

    // keep last: every property above is initialised by now
    init {
        try { applySnapshot() } catch (e: Exception) { Diag.log("start snapshot failed (${e.javaClass.simpleName})") }
        watchUiState(); DebugHooks.attach(this)
        viewModelScope.launch { while (true) { kotlinx.coroutines.delay(60_000); if (foreground) saveSnapshot() } }
    }
}
