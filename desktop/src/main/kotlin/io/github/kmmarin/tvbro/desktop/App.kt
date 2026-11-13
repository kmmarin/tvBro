// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import io.github.kmmarin.tvbro.data.*
import java.io.File
import java.util.UUID

enum class Tab(val label: String) { TV("TV"), MOVIES("Movies"), SHOWS("Shows"), MYLIST("My list"), RECORDINGS("Recordings"), SEARCH("Search"), SETTINGS("Settings") }

/** What the player plays. [resumeKey] is set for VOD / catch-up so the position is remembered. */
data class PlayReq(
    val url: String, val title: String, val live: Channel? = null, val resumeKey: String? = null,
    val startSec: Long = 0, val archive: Programme? = null, val vod: VodItem? = null,
    val series: VodItem? = null, val episode: Episode? = null, val episodes: List<Episode> = emptyList(),
    val recording: Rec? = null,
) {
    val isEpisode: Boolean get() = series != null && episode != null
    fun nextEpisode(): Episode? = episode?.let { Watch.next(episodes, it.id) }
    fun prevEpisode(): Episode? = episode?.let { e -> Watch.ordered(episodes).let { o -> o.getOrNull(o.indexOfFirst { it.id == e.id } - 1) } }
}

/** A pop-up list of actions (context menu). */
class MenuState(val title: String, val items: List<Pair<String, () -> Unit>>)
/** A one-line text question (rename, PIN, share password). */
class PromptState(val title: String, val initial: String, val secret: Boolean, val done: (String) -> Unit)
/** A pick list with a search field: [fixed] rows first, then [search] (query -> rows). */
class PickerState(val title: String, val sub: String, val fixed: List<Pair<String, () -> Unit>>, val search: (String) -> List<Pair<String, () -> Unit>>)

class AppState(private val scope: CoroutineScope, testSources: List<Source>? = null) {
    private val temporary = testSources != null
    var sources by mutableStateOf(testSources ?: SourceStore.load())
    var settings by mutableStateOf(SettingsStore.load())
    var channels by mutableStateOf(emptyList<Channel>())
    var epg by mutableStateOf(EpgIndex.EMPTY)
    private var rawEpg: Map<String, Array<Programme>> = emptyMap()
    var movies by mutableStateOf(emptyList<VodItem>())
    var series by mutableStateOf(emptyList<VodItem>())
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)
    var tab by mutableStateOf(Tab.TV)
    private var detailState = mutableStateOf<VodItem?>(null)
    /** The film / show page shown; closing it remembers the item so its tile takes the focus again ([lastOpened]). */
    var detail: VodItem?
        get() = detailState.value
        set(v) { detailState.value?.let { if (v == null) lastOpened = it.key }; detailState.value = v }
    var lastOpened: String? = null
    var now by mutableStateOf(System.currentTimeMillis() / 1000)
    /** Window placement (F11). */
    var fullscreen by mutableStateOf(false)
    /** The "Video library missing" screen is shown (at start without libmpv, or from Settings > Playback). */
    var videoSetup by mutableStateOf(false)
    private var loadJob: Job? = null

    // ---- playback: one player for full screen and the TV preview ----
    var playing by mutableStateOf<PlayReq?>(null)
        private set
    /** The player fills the window (false: it sits in the TV screen's preview spot). */
    var full by mutableStateOf(false)
    var previewRect by mutableStateOf<Rect?>(null)
    val player = PlayerCtl(this, scope)

    // ---- overlays ----
    var menu by mutableStateOf<MenuState?>(null)
    var prompt by mutableStateOf<PromptState?>(null)
    var toastMsg by mutableStateOf("")
    private var toastJob: Job? = null
    var dueReminder by mutableStateOf<Reminder?>(null)
    val overlayOpen: Boolean get() = menu != null || prompt != null || dueReminder != null || picker != null

    // ---- stores ----
    private var watchSave: Job? = null
    val watch = WatchStore(File(Paths.config, "watch.json"), saveLater = { f -> watchSave?.cancel(); watchSave = scope.launch(Dispatchers.IO) { delay(1500); f() } })
    val recorder = Recorder(this)
    val detailCache = HashMap<String, VodDetail>()

    // ---- parental PIN: a right PIN unlocks everything for 4 h; 5 wrong ones make every further try wait ----
    val pinGate = PinGate()
    /** Locks are open (a right PIN in the last 4 h). */
    var pinOpen by mutableStateOf(false)
    private val pinSet: Boolean get() = settings.prefs.pinHash.isNotEmpty()
    /** [c] needs the PIN before it plays (its group or the channel itself is locked). */
    fun isLocked(c: Channel): Boolean = pinSet && !pinOpen && (c.key in settings.prefs.lockedCh || c.group in settings.prefs.locked)
    /** A film / show category that needs the PIN. */
    fun catLocked(cat: String): Boolean = pinSet && !pinOpen && cat in settings.prefs.locked
    /** Runs [then] at once, or after the PIN when [locked]. */
    fun withPin(locked: Boolean, then: () -> Unit) { if (locked) askPin(then) else then() }

    // ---- channels as the user organised them ----
    val shown: List<Channel> by derivedStateOf { ChannelView.shown(channels, settings.prefs, if (pinOpen) settings.prefs.locked else emptySet()) }
    val groups: List<String> by derivedStateOf { ChannelView.groups(channels, settings.prefs) }
    var group by mutableStateOf(settings.lastGroup)
    val filtered: List<Channel> by derivedStateOf { ChannelView.filter(shown, group, settings.prefs, settings.myList, settings.numberMode) }
    fun groupLabel(g: String) = ChannelView.label(g, settings.prefs) + if ("g|$g" in settings.myList) "  ★" else ""

    // ---- TV screen / guide position, kept while the app runs ----
    var focusCh by mutableStateOf<Channel?>(null)
    var focusProg by mutableStateOf<Programme?>(null)
    var winStart by mutableStateOf(GuideWindow.initialStart(now))
    var following = true
    /** Per group: first visible row and its offset. */
    val tvScroll = HashMap<String, Pair<Int, Int>>()
    /** The TV screen's zone focused last (groups, channel name or programmes), restored when focus comes back. */
    var tvZone = TvNav.Zone.LABEL
    /** Right from the side menu on the TV screen: back to its last focused item; null = the nearest item. */
    var railReturn: (() -> Boolean)? = null
    /** The side menu's item of the current section (Left from the groups column, Escape on a screen). */
    val railFr = FocusRequester()
    /** Something in the main window has the keyboard focus (false: the focused item went away). */
    var homeHasFocus = false
    /** Focus went away (its item left the screen): back to the screen's last place, else the side menu. */
    fun refocus() {
        val r = railReturn
        if (tab == Tab.TV && detail == null && r != null) r() else scope.launch { railFr.settle() }
    }
    /** Bumped when full screen shrinks into the preview: the TV screen focuses the playing channel again. */
    var focusTick by mutableStateOf(0)
    /** The preview shows the video that just left full screen: it keeps its sound (no preview mute). */
    var docked by mutableStateOf(false)
    var settingsPage by mutableStateOf(0)
    private var restored = false
    /** A change (guide mapping) came while a load ran: load again when it ends. */
    @Volatile private var reloadAfter = false
    /** Per playlist: the channels of its guide (XMLTV), for the guide-channel picker. */
    var guideChannels by mutableStateOf(mapOf<String, List<GuideChannel>>())
    private var altIndex: Pair<List<Channel>, AltIndex>? = null
    /** The searchable pick list on top (guide channel). */
    var picker by mutableStateOf<PickerState?>(null)

    fun repo(sourceId: String) = sources.firstOrNull { it.id == sourceId }?.let { SourceRepo(it) }

    fun update(f: (Settings) -> Settings) {
        settings = f(settings); val s = settings
        TimeFmt.mode = s.timeFmt
        scope.launch(Dispatchers.IO) { SettingsStore.save(s) }
    }
    fun saveSources(l: List<Source>) { sources = l; if (!temporary) scope.launch(Dispatchers.IO) { SourceStore.save(l)?.let { m -> runOnUi { toast(m) } } }; load(force = true) }
    fun editPrefs(f: (Prefs) -> Prefs) = update { it.copy(prefs = f(it.prefs)) }

    fun inList(key: String) = key in settings.myList
    fun toggleList(key: String) {
        val had = key in settings.myList
        update { s -> s.copy(myList = if (had) s.myList - key else s.myList + key) }
        toast(if (had) "Removed from My list" else "Added to My list")
    }

    fun runOnUi(f: () -> Unit) { scope.launch { f() } }
    fun io(f: suspend () -> Unit) { scope.launch(Dispatchers.IO) { f() } }

    fun toast(s: String) {
        if (full && playing != null) { player.osd(s); return }
        toastMsg = s; toastJob?.cancel(); toastJob = scope.launch { delay(4000); toastMsg = "" }
    }

    init {
        TimeFmt.mode = settings.timeFmt
        TimeFmt.system24 = systemUses24h()
        if (!temporary) SourceStore.problem?.let { m -> scope.launch { delay(1500); toast(m) } }
        io { SmbShare.password = Secrets.readString("smb"); SmbBridge.cfg = { settings.smb } }
        if (settings.resume.isNotEmpty()) { watch.importLegacy(settings.resume); update { it.copy(resume = emptyMap()) } }
        scope.launch { while (true) { delay(15_000); now = System.currentTimeMillis() / 1000; if (pinOpen && !pinGate.unlocked) pinOpen = false } }
        // Scheduled refresh: cheap check every 30 min; downloads only what is older than 12 h (guide) / 24 h (catalogues).
        scope.launch { while (true) { delay(30 * 60_000L); if (playing == null || !full) load(false); recorder.scheduleSeries() } }
        scope.launch { while (true) { recorder.tick(); delay(5_000) } }
        scope.launch {
            while (true) {
                delay(10_000)
                if (dueReminder == null) settings.reminders.firstOrNull { val d = System.currentTimeMillis() - it.startMs; d in -60_000..10 * 60_000 }?.let {
                    dueReminder = it
                    if (full) player.osd("Reminder: ${it.title} on ${it.channel} (W to watch)")
                }
            }
        }
        scope.launch {
            snapshotFlow { listOf(if (full && playing?.live != null) "full" else "", tab.name, focusCh?.key ?: "", settingsPage.toString(), group).joinToString("|") }
                .collectLatest { v -> delay(800); if (restored && v != settings.lastUi) update { it.copy(lastUi = v) } }
        }
        scope.launch { snapshotFlow { group }.collectLatest { g -> delay(500); if (restored && g != settings.lastGroup) update { it.copy(lastGroup = g) } } }
        load(false)
    }

    private fun systemUses24h(): Boolean = try {
        val p = (java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) as java.text.SimpleDateFormat).toPattern()
        p.contains('H') || p.contains('k')
    } catch (e: Exception) { false }

    /** Cache-first load of every enabled source; [force] re-downloads. */
    fun load(force: Boolean) {
        if (loadJob?.isActive == true) { if (!force) reloadAfter = true; return }
        loadJob = scope.launch(Dispatchers.IO) {
            busy = true
            val srcs = sources.filter { it.enabled }
            val all = ArrayList<Channel>()
            val cats = HashMap<SourceRepo, Catalogue>()
            for (s in srcs) {
                status = "Loading ${s.name}…"
                val r = SourceRepo(s)
                try { val c = r.channels(settings.catalogueHours, force).let { k -> k.copy(channels = k.channels.map(::mapped)) }; cats[r] = c; all += c.channels }
                catch (e: Exception) { status = "${s.name}: ${Net.describe(e)}"; Diag.log("${s.name}: channels failed (${Net.describe(e)})") }
            }
            if (all.isNotEmpty() || srcs.isEmpty()) channels = all // a failed load keeps what is shown
            val mv = ArrayList<VodItem>(); val sr = ArrayList<VodItem>()
            for (r in cats.keys) {
                try { mv += r.movies(settings.catalogueHours, force); sr += r.series(settings.catalogueHours, force) }
                catch (e: Exception) { Diag.log("${r.src.name}: VOD failed (${Net.describe(e)})") }
            }
            if (mv.isNotEmpty() || cats.isNotEmpty()) { movies = mv; series = sr }
            runOnUi { restoreUi() }
            val map = HashMap<String, ArrayList<Programme>>()
            val gch = HashMap(guideChannels)
            for ((r, c) in cats) {
                status = "Loading guide for ${r.src.name}…"
                val names = r.epg(c.epgUrls, c.channels, settings.epgHours, force) { p -> map.getOrPut(p.key) { ArrayList() }.add(p) }
                if (names.isNotEmpty()) gch[r.src.id] = names.map { (id, n) -> GuideChannel(id, n.firstOrNull() ?: id) }
            }
            guideChannels = gch
            if (map.isNotEmpty() || rawEpg.isEmpty()) { rawEpg = map.mapValues { (_, v) -> v.sortedBy { it.start }.toTypedArray() }; applyShift() }
            status = if (srcs.isEmpty()) "Add a source in Settings" else "${all.size} channels, ${mv.size} movies, ${sr.size} shows"
            busy = false
            runOnUi { recorder.scheduleSeries() }
            if (all.isNotEmpty()) SearchStore.rebuild(all, mv + sr, rawEpg)
            if (reloadAfter) { reloadAfter = false; runOnUi { load(false) } }
        }
    }

    // ---- manual guide mapping (channel menu > Guide channel…): used by the guide, now / next, search and series rules ----
    private fun mapped(c: Channel): Channel = GuideMap.apply(c, settings.epgMap[c.key])

    fun setEpgId(c: Channel, id: String) {
        update { s -> s.copy(epgMap = if (id.isBlank()) s.epgMap - c.key else s.epgMap + (c.key to id.trim())) }
        toast(when { id == GuideMap.NONE -> "No guide for ${c.name}"; id.isBlank() -> "Guide: automatic again"; else -> "Guide channel saved" } + " · reloading the guide")
        load(false)
    }

    /** Picker: a searchable list of the playlist's guide channels, plus Automatic and No guide. */
    fun guidePicker(c: Channel) {
        val list = guideChannels[c.sourceId].orEmpty()
        val cur = settings.epgMap[c.key]?.takeIf { it.isNotBlank() }
        val raw = channels.firstOrNull { it.key == c.key } ?: c
        val auto = list.firstOrNull { g -> raw.tvgId?.let { g.id.equals(it.trim(), true) } == true } ?: list.firstOrNull { it.name.equals(raw.name, true) }
        val now = when {
            cur == GuideMap.NONE -> "Now: no guide for this channel"
            cur != null -> "Now: picked by hand · " + (list.firstOrNull { it.id.equals(cur, true) }?.name ?: cur)
            auto != null -> "Now: automatic · ${auto.name}"
            else -> "Now: automatic · nothing in the guide matches"
        } + if (list.isEmpty()) "\nThe guide's channel list comes with the next guide load (Settings > Sources > Sync)." else ""
        fun mark(sel: Boolean) = if (sel) "✓ " else ""
        picker = PickerState("Guide channel for ${c.name}", now,
            fixed = listOf(mark(cur == null) + "Automatic (tvg-id, then the name)" to { setEpgId(c, "") },
                mark(cur == GuideMap.NONE) + "No guide for this channel" to { setEpgId(c, GuideMap.NONE) }),
            search = { q -> GuideMap.search(list, q, raw.name).map { g ->
                mark(cur != null && g.id.equals(cur, true)) + g.name + (if (!g.name.equals(g.id, true)) "  ·  ${g.id}" else "") to { setEpgId(c, g.id) }
            } })
    }

    // ---- alternate sources of a live channel (the player tries them when the channel stays down) ----
    fun alternates(ch: Channel): List<Channel> {
        val all = channels
        val idx = altIndex?.takeIf { it.first === all }?.second ?: AltIndex(all).also { altIndex = all to it }
        val p = settings.prefs
        return idx.alternates(ch.key, usable = { it.key !in p.hiddenCh && it.group !in p.hiddenGroups && !isLocked(it) })
    }

    /** Writes a checked backup ([BackupFile.check]); this device's PIN, playlists and recordings stay. */
    fun restoreBackup(b: DesktopBackup) {
        update { BackupFile.restored(it, b) }
        b.watch?.let { watch.replace(it) }
        recorder.restoreRules(BackupFile.mergeRules(recorder.rules, b.series))
        group = settings.lastGroup.takeIf { it in groups } ?: ChannelView.ALL
        toast("Backup restored · reloading the guide"); load(false)
    }

    /** Item under the cursor for the favourite key (Y, F or the bookmark key). */
    var favAction: (() -> Unit)? = null

    /** Plays [url] in the external player when Settings ask for it ([force]: menu item); false = play inside tvBro. */
    fun external(url: String, force: Boolean = false): Boolean {
        val cmd = settings.externalPlayer.trim()
        if (cmd.isEmpty() || !(settings.useExternal || force)) return false
        val line = ExternalPlayer.command(cmd, url) ?: run { toast(PlayUrl.refusal(url)); return true } // refused: not played anywhere
        return try {
            ProcessBuilder(line).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            toast("Opened in the external player"); true
        } catch (e: Exception) { toast("External player failed: ${e.message ?: e.javaClass.simpleName}"); false }
    }

    /** Guide time shift (Settings): every programme moves by the same minutes. */
    private fun applyShift() {
        val sh = settings.epgShiftMin * 60L
        epg = EpgIndex(if (sh == 0L) rawEpg else rawEpg.mapValues { (_, a) -> Array(a.size) { a[it].copy(start = a[it].start + sh, stop = a[it].stop + sh) } })
    }
    fun setShift(min: Int) { update { it.copy(epgShiftMin = min.coerceIn(-720, 720)) }; io { applyShift() } }

    // ---- UI restore: a relaunch returns to the same place ----
    private fun restoreUi() {
        if (restored) return
        restored = true
        val p = settings.lastUi.split('|')
        val last = if (settings.startLast) shown.firstOrNull { it.key == settings.lastChannel } else null
        p.getOrNull(4)?.takeIf { it.isNotEmpty() && it in groups }?.let { group = it }
        if (group !in groups) group = ChannelView.ALL
        focusCh = p.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { k -> shown.firstOrNull { it.key == k } } ?: last
        settingsPage = p.getOrNull(3)?.toIntOrNull() ?: 0
        Tab.entries.firstOrNull { it.name == p.getOrNull(1) }?.let { tab = it }
        if (last != null && (p.getOrNull(0) == "full" || settings.lastUi.isEmpty())) playChannel(last)
    }

    // ---- live ----
    fun playChannel(ch: Channel) {
        if (isLocked(ch)) return askPin { playChannel(ch) }
        update { it.copy(lastChannel = ch.key) }
        focusCh = ch
        if (external(ch.url)) return
        val cur = playing
        if (cur?.live?.key == ch.key && cur.archive == null) { full = true; player.showBar(); return } // the preview keeps playing
        setPlaying(PlayReq(ch.url, ch.name, live = ch)); full = true
    }

    /** The TV screen's preview: plays [ch] small, without taking over the window. */
    fun preview(ch: Channel) {
        if (full || !settings.previewOn) return
        if (playing?.live?.key == ch.key && playing?.archive == null) return
        if (isLocked(ch)) return // a locked current channel waits for Enter and the PIN
        setPlaying(PlayReq(ch.url, ch.name, live = ch))
    }

    fun stopPlayback() { player.close(); playing = null; full = false }

    internal fun setPlaying(r: PlayReq?) { playing = r; docked = false; if (r != null) player.load(r) }

    /**
     * Enter / OK on a channel name or its programme on now (keyboard or mouse). The preview never follows focus:
     * Enter on the playing channel goes full screen; on another one the preview switches to it (full screen when
     * the preview is off).
     */
    fun enterChannel(ch: Channel) {
        val cur = playing?.takeIf { it.archive == null }?.live?.key
        when (TvNav.enter(ch.key, cur, settings.previewOn)) {
            TvNav.Enter.FULL_SCREEN, TvNav.Enter.PLAY_FULL_SCREEN -> playChannel(ch)
            TvNav.Enter.SWITCH_PREVIEW -> {
                if (isLocked(ch)) return askPin { enterChannel(ch) }
                if (external(ch.url)) return
                update { it.copy(lastChannel = ch.key) }
                setPlaying(PlayReq(ch.url, ch.name, live = ch))
            }
        }
    }

    /**
     * Esc in full screen: live TV shrinks into the preview (the same player keeps playing with its sound, also with
     * the preview setting off) and the guide focuses the playing channel; VOD and catch-up stop.
     */
    fun leavePlayer() {
        val r = playing ?: return
        val ch = r.live
        if (ch != null && r.archive == null) {
            full = false; detail = null; tab = Tab.TV; docked = true
            if (filtered.none { it.key == ch.key }) group = groups.firstOrNull { it == ch.group } ?: ChannelView.ALL
            focusCh = filtered.firstOrNull { it.key == ch.key } ?: ch
            focusProg = null; tvZone = TvNav.Zone.LABEL
            winStart = GuideWindow.initialStart(System.currentTimeMillis() / 1000); following = true
            focusTick++
        } else {
            stopPlayback()
            if (ch != null) { tab = Tab.TV; focusCh = ch }
        }
    }

    fun zap(d: Int) {
        // channel up / down passes over locked channels
        val l = filtered.ifEmpty { shown }.filter { !isLocked(it) || it.key == playing?.live?.key }
        if (l.isEmpty()) return
        val i = l.indexOfFirst { it.key == playing?.live?.key }
        val n = l[((if (i < 0) 0 else i) + d).mod(l.size)]
        if (isLocked(n)) return
        update { it.copy(lastChannel = n.key) }
        focusCh = n
        setPlaying(PlayReq(n.url, n.name, live = n))
    }

    fun zapNumber(n: Int): Boolean {
        val c = ChannelView.byNumber(filtered, n) ?: ChannelView.byNumber(shown, n) ?: return false
        if (isLocked(c)) { askPin { zapNumber(n) }; return true }
        if (full) { focusCh = c; update { it.copy(lastChannel = c.key) }; setPlaying(PlayReq(c.url, c.name, live = c)) } else playChannel(c)
        return true
    }

    fun playArchive(ch: Channel, p: Programme) {
        if (isLocked(ch)) return askPin { playArchive(ch, p) }
        val url = CatchupUrl.build(ch, p.start, p.stop, now) ?: return toast("Catch-up not available for this programme")
        if (external(url)) return
        val key = "a|${ch.key}|${p.start}"
        setPlaying(PlayReq(url, "${ch.name} · ${p.title}", live = ch, resumeKey = key, archive = p, startSec = (watch.state(key)?.takeIf { it.inProgress }?.posMs ?: 0) / 1000))
        full = true
    }

    /** Guide block: on now plays live, finished with catch-up plays the archive, the future opens its menu. */
    fun guidePlay(ch: Channel, p: Programme) {
        val t = System.currentTimeMillis() / 1000
        when (ProgTime.classify(ch, p.start, p.stop, t)) {
            ProgState.NOW -> enterChannel(ch)
            ProgState.PAST_CATCHUP -> playArchive(ch, p)
            else -> programmeMenu(ch, p)
        }
    }

    // ---- movies and shows ----
    fun playMovie(v: VodItem, url: String, fromStart: Boolean = false) {
        if (catLocked(v.category)) return askPin { playMovie(v, url, fromStart) }
        if (fromStart) watch.setWatched(v.key, false)
        watch.snap(v)
        if (external(url)) return
        val st = watch.state(v.key)?.takeIf { it.inProgress && !fromStart }
        setPlaying(PlayReq(url, v.name, resumeKey = v.key, startSec = (st?.posMs ?: 0) / 1000, vod = v)); full = true
    }

    /** Resolves the movie's address off the UI thread (saved copies carry none) and plays it. */
    fun openMovie(v: VodItem, repo: SourceRepo, d: VodDetail?, fromStart: Boolean = false) = io {
        val url = try { repo.movieUrl(v, d) } catch (e: Exception) { runOnUi { toast(e.message ?: "This title cannot be played") }; return@io }
        runOnUi { playMovie(v, url, fromStart) }
    }

    fun markMovie(v: VodItem, watched: Boolean) { watch.snap(v); watch.setWatched(v.key, watched); toast(if (watched) "Marked as watched" else "Marked as unwatched") }

    fun epTitle(series: VodItem, e: Episode) = "${series.name} · ${Watch.code(e)} · ${VodFmt.cleanTitle(series.name, e.title)}"

    /** Plays an episode; the series enters "Continue watching" right away (from the first second). */
    fun playEpisode(series: VodItem, eps: List<Episode>, e: Episode, fromStart: Boolean = false) {
        val repo = repo(series.sourceId) ?: return toast("The playlist of this show is not available")
        if (catLocked(series.category)) return askPin { playEpisode(series, eps, e, fromStart) }
        val key = WatchStore.epKey(series, e)
        if (fromStart) watch.setWatched(key, false, series.key)
        watch.updateSeries(series, eps, lastId = e.id, touch = true)
        if (external(repo.episodeUrl(e))) return
        val st = watch.state(key)?.takeIf { it.inProgress && !fromStart }
        setPlaying(PlayReq(repo.episodeUrl(e), epTitle(series, e), resumeKey = key, startSec = (st?.posMs ?: 0) / 1000, series = series, episode = e, episodes = eps)); full = true
    }

    /** The episode to offer on the series page / continue card, and its label ("Resume S2 E4 (12:31)"). */
    fun resumeTarget(series: VodItem, eps: List<Episode>): Pair<Episode, String>? {
        val e = Watch.resume(eps, { watch.state(WatchStore.epKey(series, it)) }, watch.series(series.key)?.lastEp) ?: return null
        val st = watch.state(WatchStore.epKey(series, e))
        return e to if (st?.inProgress == true) "Resume ${Watch.code(e)} (${Watch.clock(st.posMs)})" else if (watch.series(series.key) != null) "Play ${Watch.code(e)}" else "Play ${Watch.code(e)} from the start"
    }

    fun markEpisodes(series: VodItem, all: List<Episode>, which: List<Episode>, watched: Boolean) {
        watch.markEpisodes(series, all, which, watched)
        toast(if (watched) "Marked as watched" else "Marked as unwatched")
    }

    /** Episodes of [series]: cached, else loaded (off the UI thread). */
    fun withEpisodes(series: VodItem, then: (List<Episode>) -> Unit) {
        detailCache[series.key]?.episodes?.takeIf { it.isNotEmpty() }?.let { return then(it) }
        val repo = repo(series.sourceId) ?: return toast("The playlist of this show is not available")
        io {
            try {
                val d = repo.detail(series)
                runOnUi { detailCache[series.key] = d; then(d.episodes) }
            } catch (e: Exception) { runOnUi { toast("Could not load the episodes: ${Net.describe(e)}") } }
        }
    }

    fun markSeries(series: VodItem, watched: Boolean) = withEpisodes(series) { eps -> markEpisodes(series, eps, eps, watched) }

    fun playRecording(r: Rec) {
        if (r.file.isBlank()) return toast("Nothing recorded yet")
        val key = "r|${r.id}"
        setPlaying(PlayReq(recorder.playUrl(r), r.title, resumeKey = key, startSec = (watch.state(key)?.takeIf { it.inProgress }?.posMs ?: 0) / 1000, recording = r)); full = true
    }

    // ---- reminders ----
    fun reminderFor(c: Channel, p: Programme) = settings.reminders.firstOrNull { it.chKey == c.key && it.startMs == p.start * 1000 }
    fun toggleReminder(c: Channel, p: Programme) {
        val r = reminderFor(c, p)
        if (r != null) { update { s -> s.copy(reminders = s.reminders.filter { it.id != r.id }) }; toast("Reminder removed"); return }
        update { s -> s.copy(reminders = (s.reminders + Reminder(UUID.randomUUID().toString().take(8), c.key, c.name, p.title, p.start * 1000)).sortedBy { it.startMs }) }
        toast("Reminder set: ${p.title} at ${TimeFmt.time(p.start)}")
    }
    fun reminderDone(r: Reminder, watchIt: Boolean) {
        dueReminder = null
        update { s -> s.copy(reminders = s.reminders.filter { it.id != r.id }) }
        if (watchIt) shown.firstOrNull { it.key == r.chKey }?.let(::playChannel)
    }

    // ---- context menus (right click, long-press Enter, Menu key) ----
    fun channelMenu(c: Channel) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += "Watch" to { playChannel(c) }
        items += (if (inList(c.key)) "Remove from Favourites (My list)" else "Add to Favourites (My list)") to { toggleList(c.key) }
        val cur = epg.nowNext(c, System.currentTimeMillis() / 1000).first
        recorder.active(c)?.let { r -> items += "Stop recording" to { recorder.stop(r) } } ?: run { items += "Record…" to { recordMenu(c) } }
        cur?.let { p -> recorder.ruleFor(c, p)?.let { r -> items += "Cancel series recording" to { recorder.cancelSeries(r) } } ?: run { items += "Record series: every \"${p.title}\"" to { recorder.recordSeries(c, p) } } }
        cur?.let { p -> items += "Programme info: ${p.title}" to { programmeMenu(c, p) } }
        if (c.catchup != null && c.catchupDays > 0) items += "Catch-up: earlier programmes…" to { catchupMenu(c) }
        items += "Guide channel…" to { guidePicker(c) }
        if (settings.externalPlayer.isNotBlank()) items += "Open in external player" to { withPin(isLocked(c)) { external(c.url, force = true) } }
        val p0 = settings.prefs
        items += (if (c.key in p0.lockedCh) "Unlock channel" else "Lock channel (PIN)") to { toggleChLock(c) }
        if (c.group !in p0.custom.keys) items += (if (c.group in p0.locked) "Unlock group ${c.group}" else "Lock group ${c.group} (PIN)") to { toggleLock(c.group) }
        items += "Rename" to { prompt = PromptState("Channel name (empty = provider name)", settings.prefs.names[c.key] ?: c.name, false) { v -> editPrefs { p -> p.copy(names = if (v.isBlank()) p.names - c.key else p.names + (c.key to v.trim())) } } }
        items += "Hide channel" to { editPrefs { p -> p.copy(hiddenCh = p.hiddenCh + c.key) }; toast("Hidden. Settings > Channels and groups shows it again") }
        if (group in settings.prefs.custom) {
            items += "Move up" to { editPrefs { p -> p.copy(custom = p.custom + (group to ChannelView.move(p.custom[group].orEmpty(), c.key, -1))) } }
            items += "Move down" to { editPrefs { p -> p.copy(custom = p.custom + (group to ChannelView.move(p.custom[group].orEmpty(), c.key, 1))) } }
            items += "Remove from this group" to { editPrefs { p -> p.copy(custom = p.custom + (group to p.custom[group].orEmpty().filter { it != c.key })) } }
        } else {
            items += "Move up" to { moveChannel(c, -1) }
            items += "Move down" to { moveChannel(c, 1) }
        }
        items += "Add to own group…" to {
            val gs = settings.prefs.custom.keys.map { g -> g to { editPrefs { p -> p.copy(custom = p.custom + (g to (p.custom[g].orEmpty() + c.key).distinct())) } } }
            menu = MenuState("Add ${c.name} to", gs + ("New group…" to {
                prompt = PromptState("New group name", "", false) { v -> val n = v.trim(); if (n.isNotEmpty() && n != ChannelView.ALL && n != ChannelView.FAV) editPrefs { p -> p.copy(custom = p.custom + (n to listOf(c.key))) } }
            }))
        }
        menu = MenuState(c.name, items)
    }

    /** The channel's past programmes still in its archive, newest first (the guide grid shows none of them). */
    fun catchupMenu(c: Channel) {
        val t = System.currentTimeMillis() / 1000
        val past = GuideWindow.pastCatchup(c, epg.programmes(c), t)
        if (past.isEmpty()) return toast("No earlier programmes in the guide for ${c.name}")
        menu = MenuState("${c.name} · catch-up", past.map { p -> "${TimeFmt.dayTime(p.start * 1000, "EEE")} · ${p.title}" to { playArchive(c, p) } })
    }

    fun moveChannel(c: Channel, d: Int) = editPrefs { p -> p.copy(order = ChannelView.moveChannel(p.order, shown.map { it.key }, c.key, d)) }

    fun groupMenu(g: String) {
        if (g == ChannelView.ALL || g == ChannelView.FAV) return
        val p = settings.prefs
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += (if (inList("g|$g")) "Remove from My list" else "Add to My list") to { toggleList("g|$g") }
        if (g in p.custom) items += "Delete group" to { editPrefs { it.copy(custom = it.custom - g) }; if (group == g) group = ChannelView.ALL }
        else {
            items += "Rename" to { prompt = PromptState("Group name (empty = provider name)", p.groupNames[g] ?: g, false) { v -> editPrefs { q -> q.copy(groupNames = if (v.isBlank()) q.groupNames - g else q.groupNames + (g to v.trim())) } } }
            items += "Hide group" to { editPrefs { it.copy(hiddenGroups = it.hiddenGroups + g) }; if (group == g) group = ChannelView.ALL }
            items += "Move up" to { editPrefs { it.copy(groupOrder = ChannelView.moveGroup(groups, it, g, -1)) } }
            items += "Move down" to { editPrefs { it.copy(groupOrder = ChannelView.moveGroup(groups, it, g, 1)) } }
        }
        items += (if (g in p.locked) "Unlock group" else "Lock group (PIN)") to { toggleLock(g) }
        menu = MenuState(groupLabel(g), items)
    }

    fun selectGroup(g: String) { if (g in settings.prefs.locked && pinSet && !pinOpen) askPin { group = g } else group = g }

    /**
     * Asks for the PIN (no question while the locks are open). A right PIN opens every lock for 4 h; after 5 wrong
     * ones every further try waits (30 s, longer after more).
     */
    fun askPin(then: () -> Unit) {
        if (!pinSet) return toast("Set a PIN first (Settings > Channels and groups)")
        if (pinOpen && pinGate.unlocked) return then()
        pinGate.waitSec().takeIf { it > 0 }?.let { return toast("Too many wrong PINs · try again in $it s") }
        prompt = PromptState("Enter PIN", "", true) { v ->
            when (val r = pinGate.check(settings.prefs.pinHash, v)) {
                PinGate.Result.Ok -> { pinOpen = true; then() }
                is PinGate.Result.Wrong -> toast("Wrong PIN" + if (r.waitSec > 0) " · try again in ${r.waitSec} s" else "")
                is PinGate.Result.Wait -> toast("Too many wrong PINs · try again in ${r.sec} s")
            }
        }
    }

    /** The locks close again now (before the 4 h are over). */
    fun lockAgain() { pinGate.lockNow(); pinOpen = false; toast("Locked again") }

    /** Asks for a new PIN twice (4-8 digits), then [done]. */
    private fun newPin(title: String, done: () -> Unit = {}) {
        prompt = PromptState(title, "", true) { v ->
            if (!Pin.validFormat(v)) toast("The PIN must be 4 to 8 digits")
            else prompt = PromptState("Repeat the new PIN", "", true) { w ->
                if (w != v) toast("The PINs did not match; nothing changed")
                else {
                    editPrefs { it.copy(pinHash = Pin.hash(v)) }
                    pinGate.check(settings.prefs.pinHash, v); pinOpen = true // the one who set it may go on
                    toast("PIN saved"); done()
                }
            }
        }
    }

    fun setPin() { if (!pinSet) newPin("New PIN (4 to 8 digits)") else askPin { newPin("New PIN (4 to 8 digits)") } }

    fun removePin() = askPin { editPrefs { it.copy(pinHash = "", locked = emptySet(), lockedCh = emptySet()) }; pinGate.lockNow(); pinOpen = false; toast("PIN and locks removed") }

    /** Locks or unlocks a live group or a film / show category (a PIN is created first when there is none). */
    fun toggleLock(name: String) {
        val act = { val l = settings.prefs.locked; editPrefs { it.copy(locked = if (name in l) l - name else l + name) }; toast(if (name in l) "Unlocked $name" else "Locked $name") }
        if (!pinSet) newPin("Create a PIN first (4 to 8 digits)", act) else askPin(act)
    }

    fun toggleChLock(c: Channel) {
        val act = { val l = settings.prefs.lockedCh; editPrefs { it.copy(lockedCh = if (c.key in l) l - c.key else l + c.key) }; toast(if (c.key in l) "Unlocked ${c.name}" else "Locked ${c.name}") }
        if (!pinSet) newPin("Create a PIN first (4 to 8 digits)", act) else askPin(act)
    }

    fun recordMenu(c: Channel) {
        val cur = epg.nowNext(c, System.currentTimeMillis() / 1000).first
        menu = MenuState("Record ${c.name}", listOfNotNull(
            cur?.let { p -> "Record \"${p.title}\" (until it ends)" to { recorder.recordNow(c, 0, p.stop * 1000 + settings.recPadEndMin * 60_000L, p.title) } },
            cur?.let { p -> recorder.ruleFor(c, p)?.let { r -> "Cancel series recording" to { recorder.cancelSeries(r) } } ?: ("Record series: every \"${p.title}\"" to { recorder.recordSeries(c, p) }) },
            "Record 30 minutes" to { recorder.recordNow(c, 30) },
            "Record 1 hour" to { recorder.recordNow(c, 60) },
            "Record until stopped" to { recorder.recordNow(c, 0) },
        ))
    }

    /** Programme pop-up: watch, record, record series, remind, favourites, channel options. */
    fun programmeMenu(c: Channel, p: Programme) {
        val t = System.currentTimeMillis() / 1000
        val st = ProgTime.classify(c, p.start, p.stop, t)
        val items = ArrayList<Pair<String, () -> Unit>>()
        when (st) {
            ProgState.NOW -> items += "Watch live" to { playChannel(c) }
            ProgState.PAST_CATCHUP -> items += "Watch catch-up" to { playArchive(c, p) }
            else -> items += "Watch ${c.name} now" to { playChannel(c) }
        }
        if (st == ProgState.NOW || st == ProgState.FUTURE) {
            val sched = recorder.scheduledFor(c, p)
            val act = recorder.active(c)
            when {
                act != null && st == ProgState.NOW -> items += "Stop recording" to { recorder.stop(act) }
                sched != null -> items += "Cancel recording" to { recorder.stop(sched) }
                else -> items += (if (st == ProgState.NOW) "Record (until it ends)" else "Record") to { recorder.recordProgramme(c, p) }
            }
            recorder.ruleFor(c, p)?.let { r -> items += "Cancel series recording" to { recorder.cancelSeries(r) } } ?: run { items += "Record series: every \"${p.title}\" on ${c.name}" to { recorder.recordSeries(c, p) } }
        }
        if (st == ProgState.FUTURE) items += (if (reminderFor(c, p) != null) "Remove reminder" else "Remind me") to { toggleReminder(c, p) }
        // the grid never shows the past: earlier programmes are reached from here
        if (c.catchup != null && c.catchupDays > 0) items += "Catch-up: earlier programmes…" to { catchupMenu(c) }
        items += (if (inList(c.key)) "Remove ${c.name} from Favourites" else "Add ${c.name} to Favourites") to { toggleList(c.key) }
        items += "Channel options…" to { channelMenu(c) }
        val head = "${p.title}\n${c.name} · ${TimeFmt.dayTime(p.start * 1000, "EEE")} – ${TimeFmt.time(p.stop)}" +
            (EpisodeNum.line(p.ep, p.sub)?.let { "\n$it" } ?: "") + (p.desc?.let { "\n" + it.take(300) } ?: "")
        menu = MenuState(head, items)
    }

    fun vodMenu(v: VodItem) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += "Open" to { detail = v; full = false }
        if (v.kind == "series") {
            items += "Mark show as watched" to { markSeries(v, true) }
            items += "Mark show as unwatched" to { markSeries(v, false) }
        } else {
            val w = watch.state(v.key)?.watched == true
            items += (if (w) "Mark as unwatched" else "Mark as watched") to { markMovie(v, !w) }
        }
        items += (if (inList(v.key)) "Remove from My list" else "Add to My list") to { toggleList(v.key) }
        menu = MenuState(v.name, items)
    }

    fun episodeMenu(series: VodItem, all: List<Episode>, e: Episode) {
        val key = WatchStore.epKey(series, e)
        val st = watch.state(key)
        val season = all.filter { it.season == e.season }
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += (if (st?.inProgress == true) "Resume (${Watch.clock(st.posMs)})" else "Play") to { playEpisode(series, all, e) }
        if (st?.inProgress == true || st?.watched == true) items += "Play from start" to { playEpisode(series, all, e, fromStart = true) }
        items += (if (st?.watched == true) "Mark episode as unwatched" else "Mark episode as watched") to { markEpisodes(series, all, listOf(e), st?.watched != true) }
        if (e.season > 0) {
            items += "Mark season ${e.season} as watched" to { markEpisodes(series, all, season, true) }
            items += "Mark season ${e.season} as unwatched" to { markEpisodes(series, all, season, false) }
        }
        items += "Mark show as watched" to { markEpisodes(series, all, all, true) }
        items += "Mark show as unwatched" to { markEpisodes(series, all, all, false) }
        menu = MenuState("${series.name} · ${Watch.code(e)}", items)
    }

    fun recordingMenu(r: Rec) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (r.status == "done") items += "Play" to { playRecording(r) }
        if (r.status == "recording") { items += "Watch the channel" to { shown.firstOrNull { it.key == r.chKey }?.let(::playChannel) }; items += "Stop recording" to { recorder.stop(r) } }
        if (r.status == "scheduled") items += "Cancel" to { recorder.stop(r) }
        if (r.status != "scheduled") items += "Delete" to { recorder.delete(r) }
        if (r.status == "done") items += (if (inList("r|${r.id}")) "Remove from My list" else "Add to My list") to { toggleList("r|${r.id}") }
        menu = MenuState(r.title, items)
    }

    /** Neighbouring channel in the current list order (kept for callers outside the player). */
    fun neighbour(ch: Channel, d: Int): Channel? {
        val l = filtered.ifEmpty { shown }
        val i = l.indexOfFirst { it.key == ch.key }
        return if (i < 0 || l.isEmpty()) null else l[(i + d).mod(l.size)]
    }

    fun shutdown() { player.close(); recorder.shutdown(); watch.save() }
}
