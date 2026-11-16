// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.kmmarin.tvbro.ui

import android.view.KeyEvent as AKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import io.github.kmmarin.tvbro.*
import io.github.kmmarin.tvbro.R
import io.github.kmmarin.tvbro.data.Channel
import io.github.kmmarin.tvbro.data.MvRules

// ONE player overlay for everything that plays full screen: live TV, catch-up, movies, episodes and recordings
// (user 2026-11-14: "one consistent design everywhere"). The screens only own their player; the banner, the
// quick-access row, the icon row, their choosers and every key are here, so look and behaviour are the same by
// construction. What does not apply to a kind of playback is simply not in its item list.
//
// Keys (focus stays on the player the whole time, so every row opens in the same frame and playback never stops):
//  - nothing shown: OK shows the banner; live TV: Up / Down = channel above / below (as in the guide list),
//    Left = channel list, Right = TV guide; everything else: Left / Right seek (hold to speed up), Up / Down nothing.
//  - banner: Down = quick-access row (live TV) or icon row, Up / OK hide it.
//  - a row: Left / Right choose, OK runs, Down = next row, Up = previous row. Back closes the overlay, never playback.
//  - media keys always: play/pause, play, pause, fast-forward / rewind (seek; live TV: Rewind = last channel),
//    next / previous (channel or episode).

/** What is playing full screen: decides the overlay's items. */
enum class PlayKind { LIVE, CATCHUP, MOVIE, EPISODE, RECORDING }

private val PlayKind.seekable get() = this != PlayKind.LIVE
private val PlayKind.zaps get() = this == PlayKind.LIVE || this == PlayKind.CATCHUP

/**
 * A button of the icon row: [name] says what it does, [value] its current state ("Off", "0 ms"); both under the icon.
 * Equal when it shows the same (the action is not compared: rows run the current list's action, see RowItems).
 */
@Immutable
class OverlayItem(val icon: Int, val name: String, val value: String = "", val tint: Color? = null, val onClick: () -> Unit) {
    override fun equals(other: Any?): Boolean = other is OverlayItem && other.icon == icon && other.name == name && other.value == value && other.tint == tint
    override fun hashCode(): Int = icon * 31 + name.hashCode()
}

/**
 * The icon row's items compared by what they show (and what plays), so the row skips recomposing when only the host
 * recomposed (every key, every poll): rebuilding its ~11 icons each time cost tens of ms on the Fire TV Stick.
 */
@Immutable
private class RowItems(val ctx: Any?, val items: List<OverlayItem>) {
    override fun equals(other: Any?): Boolean = other is RowItems && other.ctx == ctx && other.items.size == items.size &&
        items == other.items
    override fun hashCode(): Int = items.size
}

/** A tile of the quick-access row: an icon (TV guide, History) or a channel logo with its programme. */
class QuickTile(val title: String, val sub: String = "", val icon: Int = 0, val channel: Channel? = null, val progress: Float = -1f, val onClick: () -> Unit)

/** Overlay levels: hidden, banner, quick-access row (with the banner), icon row. */
private const val HIDDEN = 0
private const val BANNER = 1
private const val QUICK = 2
private const val ICONS = 3

@Stable
class OverlayState {
    var level by mutableIntStateOf(BANNER)
        private set
    /** Shown by itself (channel change, start, seek): keys still act as if nothing were shown, except OK. */
    var passive by mutableStateOf(true)
        private set
    var quickSel by mutableIntStateOf(0)
    var iconSel by mutableIntStateOf(0)
    /** Bumped on every key in the overlay: restarts the auto-hide timer. */
    var touched by mutableIntStateOf(0)
        private set

    val interactive get() = level != HIDDEN && !passive

    fun open(l: Int) { level = l; passive = false; touched++ }
    fun showPassive() { if (!interactive) { level = BANNER; passive = true; touched++ } }
    fun hide() { level = HIDDEN; passive = false }
    fun touch() { touched++ }
}

private val RecRed = Color(0xFFE53935)
private val Scrim = Brush.verticalGradient(0f to Color.Transparent, 0.25f to Color(0xE6000000), 1f to Color(0xF5000000))
private val RowBg = IconRest
private const val IDLE_HIDE_MS = 8_000L
private const val PASSIVE_HIDE_MS = 5_000L

/**
 * The full-screen player surface: focus target, keys, Back, and the overlay over [content] (the video, status lines).
 * [contentKey] changes with the channel / title (shows the banner by itself). [blocked]: another layer of the screen
 * holds focus (the episode "Up next" card). [extraKeys]: the screen's own keys while no row is open (digits, red).
 */
@Composable
fun PlayerOverlayHost(
    m: AppModel,
    exo: ExoPlayer,
    pv: PlayerView?,
    kind: PlayKind,
    contentKey: Any,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
    blocked: Boolean = false,
    extraKeys: (KeyEvent) -> Boolean = { false },
    problem: String? = null,
    onRetry: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val st = remember { OverlayState() }
    val now = rememberNow()
    LaunchedEffect(contentKey) { st.showPassive() }
    // a stream that does not play: the banner comes up with what is wrong and stays while it lasts
    LaunchedEffect(problem) { if (problem != null) st.showPassive() }

    // position / formats are not observable: polled into state while something is shown
    val poll = remember { PlayerPoll() }
    val shown = st.level != HIDDEN
    LaunchedEffect(shown) { while (shown) { poll.read(exo); delay(500) } }

    // auto-hide: 5 s when shown by itself, 8 s after the last key in a row; never while one of its choosers is open
    val popup = m.menu != null || m.prompt != null
    AutoHide(st, popup || problem != null)

    // the recent channels: kept in memory, logos warmed into the logo cache before the row is ever opened
    val byKey = remember(m.shown) { m.shown.associateBy { it.key } }
    val live = m.current
    val recent = remember(m.recentCh, byKey, live?.key) { m.recentCh.mapNotNull { byKey[it] }.filter { it.key != live?.key }.take(10) }
    LaunchedEffect(recent) { for (c in recent) c.logo?.let { Logos.load(it) } }

    val tiles: List<QuickTile> = if (kind != PlayKind.LIVE) emptyList() else remember(recent, now) {
        listOf(
            QuickTile("TV guide", icon = R.drawable.ic_pm_guide) { st.hide(); m.screen = Screen.GUIDE },
            QuickTile("History", icon = R.drawable.ic_pm_history) { historyMenu(m, byKey, now) { st.hide(); m.zapTo(it) } },
        ) + recent.map { c ->
            val p = m.epg.nowNext(c, now).first
            val f = p?.let { ((now - it.start).toFloat() / (it.stop - it.start).coerceAtLeast(1)).coerceIn(0f, 1f) } ?: -1f
            QuickTile(c.name, p?.title ?: "", channel = c, progress = f) { st.hide(); m.zapTo(c) }
        }
    }
    val items = overlayItems(m, exo, pv, kind, now, poll.tick)
    val itemsNow = rememberUpdatedState(items)
    val rowItems = RowItems(contentKey to kind, items)

    fun seek(back: Boolean, repeat: Int) {
        val step = 10_000L * (1 + repeat / 4).coerceAtMost(12)
        exo.seekTo(if (back) (exo.currentPosition - step).coerceAtLeast(0) else exo.currentPosition + step)
        poll.read(exo)
        st.showPassive(); st.touch()
    }
    fun playPause(play: Boolean?) {
        exo.playWhenReady = play ?: !exo.playWhenReady
        poll.read(exo)
        st.showPassive()
    }
    fun step(d: Int) {
        when (kind) {
            PlayKind.LIVE, PlayKind.CATCHUP -> m.zap(d)
            PlayKind.EPISODE -> m.stepEpisode(d)
            else -> seekBy(exo, d * 600_000L).also { st.showPassive() }
        }
    }
    val below = if (tiles.isEmpty()) ICONS else QUICK

    BackHandler { if (st.interactive) st.hide() else onLeave() }
    val fr = remember { FocusRequester() }
    LaunchedEffect(popup, blocked) { if (!popup && !blocked) runCatching { fr.requestFocus() } }

    val swipeOut = with(androidx.compose.ui.platform.LocalDensity.current) { 120.dp.toPx() }
    Box(
        modifier.fillMaxSize()
            // touch screens: a tap shows / hides the banner, a swipe down leaves like Back
            .pointerInput(Unit) { detectTapGestures(onTap = { if (st.interactive) st.hide() else st.open(BANNER) }) }
            .pointerInput(Unit) {
                var dy = 0f
                detectVerticalDragGestures(onDragStart = { dy = 0f }, onDragEnd = { if (dy > swipeOut) { st.hide(); onLeave() } }) { _, d -> dy += d }
            }
            .focusRequester(fr).countFocus().focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || blocked || popup) return@onPreviewKeyEvent false
                val code = e.nativeKeyEvent.keyCode
                val rep = e.nativeKeyEvent.repeatCount
                // media keys, whatever is shown
                when (code) {
                    AKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { playPause(null); return@onPreviewKeyEvent true }
                    AKeyEvent.KEYCODE_MEDIA_PLAY -> { playPause(true); return@onPreviewKeyEvent true }
                    AKeyEvent.KEYCODE_MEDIA_PAUSE -> { playPause(false); return@onPreviewKeyEvent true }
                    AKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (kind.seekable) seek(false, rep); return@onPreviewKeyEvent true }
                    // Rewind on live TV: back to the channel watched before (one press; the first channel tile of the
                    // quick-access row, so no button of its own)
                    AKeyEvent.KEYCODE_MEDIA_REWIND -> {
                        if (kind.seekable) seek(true, rep)
                        else if (kind == PlayKind.LIVE && rep == 0) recent.firstOrNull()?.let { st.hide(); m.zapTo(it) }
                        return@onPreviewKeyEvent true
                    }
                    AKeyEvent.KEYCODE_MEDIA_NEXT -> { if (rep == 0) step(1); return@onPreviewKeyEvent true }
                    AKeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (rep == 0) step(-1); return@onPreviewKeyEvent true }
                    AKeyEvent.KEYCODE_CHANNEL_UP -> if (kind.zaps) { m.zap(1); return@onPreviewKeyEvent true }
                    AKeyEvent.KEYCODE_CHANNEL_DOWN -> if (kind.zaps) { m.zap(-1); return@onPreviewKeyEvent true }
                }
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.ButtonA
                // given up on a stream: OK tries again (from the banner, not from a row)
                if (ok && onRetry != null && st.level <= BANNER) { if (rep == 0) onRetry(); return@onPreviewKeyEvent true }
                // long press of OK (its first repeat) = the Menu key: the icon row, the player's extra options (it
                // used to open the banner on the press and close it again on the repeats)
                if (ok && rep > 0 && st.level <= BANNER) { if (rep == 1) { st.iconSel = 0; st.open(ICONS) }; return@onPreviewKeyEvent true }
                if (st.interactive) {
                    val handled = when (st.level) {
                        BANNER -> when {
                            // (a row opened with Down starts at its first item; Up back to it keeps the place)
                            e.key == Key.DirectionDown -> { if (below == QUICK) st.quickSel = 0 else st.iconSel = 0; st.open(below); true }
                            e.key == Key.DirectionUp || ok -> { st.hide(); true }
                            kind.seekable && e.key == Key.DirectionLeft -> { seek(true, rep); true }
                            kind.seekable && e.key == Key.DirectionRight -> { seek(false, rep); true }
                            else -> false
                        }
                        QUICK -> when {
                            e.key == Key.DirectionLeft -> { st.quickSel = (st.quickSel - 1).coerceAtLeast(0); true }
                            e.key == Key.DirectionRight -> { st.quickSel = (st.quickSel + 1).coerceAtMost(tiles.size - 1); true }
                            e.key == Key.DirectionDown -> { st.iconSel = 0; st.open(ICONS); true }
                            e.key == Key.DirectionUp -> { st.open(BANNER); true }
                            ok -> { if (rep == 0) tiles.getOrNull(st.quickSel)?.onClick?.invoke(); true }
                            else -> false
                        }
                        else -> when {
                            e.key == Key.DirectionLeft -> { st.iconSel = (st.iconSel - 1).coerceAtLeast(0); true }
                            e.key == Key.DirectionRight -> { st.iconSel = (st.iconSel + 1).coerceAtMost(items.size - 1); true }
                            e.key == Key.DirectionUp -> { st.open(if (tiles.isEmpty()) BANNER else QUICK); true }
                            e.key == Key.DirectionDown -> true
                            ok -> { if (rep == 0) items.getOrNull(st.iconSel)?.onClick?.invoke(); true }
                            else -> false
                        }
                    }
                    if (handled) { st.touch(); return@onPreviewKeyEvent true }
                }
                if (extraKeys(e)) return@onPreviewKeyEvent true
                when {
                    ok -> { if (rep == 0) st.open(BANNER); true }
                    e.key == Key.Menu -> { st.iconSel = 0; st.open(ICONS); true }
                    e.key == Key.DirectionUp -> { if (kind.zaps) m.zap(-1); true }
                    e.key == Key.DirectionDown -> { if (kind.zaps) m.zap(1); true }
                    kind.seekable && e.key == Key.DirectionLeft -> { seek(true, rep); true }
                    kind.seekable && e.key == Key.DirectionRight -> { seek(false, rep); true }
                    e.key == Key.DirectionLeft -> { st.hide(); m.leavePlayer(); true }
                    e.key == Key.DirectionRight -> { st.hide(); m.screen = Screen.GUIDE; true }
                    else -> false
                }
            },
    ) {
        content()
        // Everything stays composed while hidden and is only placed when shown: opening the banner or a row costs a
        // layout pass, not a composition (the icon row took ~140 ms to compose on the Fire TV Stick 4K).
        val withBanner = st.level == BANNER || st.level == QUICK
        // the rows are drawn once, invisibly, when playback starts: their first real opening then finds icons and
        // text already rasterised (the first icon row took 75 ms to draw on the Fire TV Stick 4K, later ones ~30 ms)
        var warm by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) { withFrameNanos { }; withFrameNanos { }; warm = false }
        if (kind.zaps && live != null) Text(m.prefs.groupNames[live.group] ?: live.group, color = TextMain, fontSize = 18.sp, maxLines = 1,
            modifier = Modifier.align(Alignment.TopStart).shownIf(shown).padding(24.dp).background(Color(0xAA000000), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
        Text(io.github.kmmarin.tvbro.data.TimeFmt.dayTime(now * 1000, "EEE d MMM "), color = TextMain, fontSize = 18.sp,
            modifier = Modifier.align(Alignment.TopEnd).shownIf(shown).padding(24.dp).background(Color(0xAA000000), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
        Column(Modifier.align(Alignment.BottomCenter).shownIf(shown).fillMaxWidth().background(Scrim).padding(start = 32.dp, end = 32.dp, top = 40.dp, bottom = 10.dp)) {
            Column(Modifier.shownIf(withBanner).enterAnim(withBanner)) { Banner(m, kind, now, poll, problem) }
            if (tiles.isNotEmpty()) Box(Modifier.shownIf(st.level == QUICK, warm).enterAnim(st.level == QUICK)) { QuickRow(tiles, st) { st.quickSel = it; tiles[it].onClick() } }
            Box(Modifier.shownIf(st.level == ICONS, warm).enterAnim(st.level == ICONS)) { IconRow(rowItems, st) { st.iconSel = it; itemsNow.value.getOrNull(it)?.onClick?.invoke() } }
            // more rows below
            Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_pm_more), "More below", Modifier.size(22.dp).shownIf(withBanner), colorFilter = ColorFilter.tint(TextDim))
            }
        }
    }
}

/**
 * Measured (and composed) always, placed and drawn only while [shown]; takes no space otherwise. While [warm] a hidden
 * item is drawn at 1/255 opacity without taking space (to rasterise it once, see the host).
 */
private fun Modifier.shownIf(shown: Boolean, warm: Boolean = false): Modifier = layout { m, c ->
    val p = m.measure(c)
    when {
        shown -> layout(p.width, p.height) { p.place(0, 0) }
        warm -> layout(0, 0) { p.placeWithLayer(0, 0) { alpha = 0.004f } }
        else -> layout(0, 0) {}
    }
}

/** Hides the overlay: 5 s when shown by itself, 8 s after the last key in a row; never while a chooser is open. */
@Composable
private fun AutoHide(st: OverlayState, popup: Boolean) {
    LaunchedEffect(st.level, st.passive, st.touched, popup) {
        if (st.level == HIDDEN || popup) return@LaunchedEffect
        delay(if (st.passive) PASSIVE_HIDE_MS else IDLE_HIDE_MS); st.hide()
    }
}

/**
 * The player's position, duration, resolution and play state, read every 500 ms while the overlay shows. State, so
 * the banner recomposes when they change (a plain parameter that the banner did not read was skipped by Compose).
 */
@Stable
private class PlayerPoll {
    var pos by mutableLongStateOf(0L)
    var dur by mutableLongStateOf(0L)
    var res by mutableStateOf("")
    var playing by mutableStateOf(true)
    var tick by mutableIntStateOf(0)
    fun read(exo: ExoPlayer) {
        pos = exo.currentPosition; dur = exo.duration.coerceAtLeast(0); res = resolutionLabel(exo); playing = exo.playWhenReady; tick++
    }
}

private fun seekBy(exo: ExoPlayer, ms: Long) { exo.seekTo((exo.currentPosition + ms).coerceAtLeast(0)) }

private fun clock(ms: Long): String = "%d:%02d:%02d".format(ms / 3600000, ms / 60000 % 60, ms / 1000 % 60)

private fun minutes(min: Long): String = if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"

/** History tile: every recent channel with its programme; choosing one switches at once. */
private fun historyMenu(m: AppModel, byKey: Map<String, Channel>, now: Long, pick: (Channel) -> Unit) {
    val l = m.recentCh.mapNotNull { byKey[it] }.filter { it.key != m.current?.key } // (not the one playing)
    if (l.isEmpty()) { m.toast("No channels watched yet"); return }
    m.menu = MenuState("History", l.map { c ->
        (c.name + (m.epg.nowNext(c, now).first?.let { "  ·  ${it.title}" } ?: "")) to { pick(c) }
    })
}

/** The icon row's items for [kind]: the same list builder for every screen ([tick]: re-read while shown). */
@Suppress("UNUSED_PARAMETER")
private fun overlayItems(m: AppModel, exo: ExoPlayer, pv: PlayerView?, kind: PlayKind, now: Long, tick: Int): List<OverlayItem> {
    val ch = m.current
    val vp = m.vodPlay
    val l = ArrayList<OverlayItem>()
    val nowMs = System.currentTimeMillis()
    if (kind == PlayKind.LIVE && ch != null) {
        val rec = m.activeRec(ch) != null
        l += OverlayItem(if (rec) R.drawable.ic_pm_stop else R.drawable.ic_nav_rec, if (rec) "Stop recording" else "Record", if (rec) "Recording" else "", RecRed) { pickRecord(m, ch) }
        l += OverlayItem(R.drawable.ic_pm_channels, "Channel list") { m.leavePlayer() }
        l += OverlayItem(R.drawable.ic_pm_multiview, "Multiview", m.freeStreams(ch.sourceId).let { n -> if (n < 2) "needs 2 streams" else "up to ${minOf(n, MvRules.MAX_TILES)}" }) { m.openMultiview() }
    }
    if (kind == PlayKind.CATCHUP) l += OverlayItem(R.drawable.ic_pm_live, "Back to live", "Live") { m.catchup = null }
    if (kind.seekable) l += OverlayItem(R.drawable.ic_pm_restart, "Restart", "From the start") { exo.seekTo(0) }
    if (kind == PlayKind.EPISODE) {
        l += OverlayItem(R.drawable.ic_pm_prev, "Previous episode") { m.stepEpisode(-1) }
        l += OverlayItem(R.drawable.ic_pm_next, "Next episode") { m.stepEpisode(1) }
    }
    val remember = kind == PlayKind.EPISODE
    l += OverlayItem(R.drawable.ic_pm_video, "Video quality", videoLabel(exo)) { pickVideo(m, exo) }
    l += OverlayItem(R.drawable.ic_pm_audio, "Audio track", audioLabel(exo)) { pickAudio(m, exo, remember) }
    val delayKey = if (kind.zaps) ch?.key else vp?.key
    l += OverlayItem(R.drawable.ic_pm_delay, "Audio delay", delayLabel(m.audioDelayMs)) { pickDelay(m, delayKey) }
    l += OverlayItem(R.drawable.ic_pm_cc, "Captions", captionLabel(exo)) { pickCaptions(m, exo, remember, live = kind.zaps) }
    l += OverlayItem(R.drawable.ic_pm_aspect, "Aspect", aspectLabel(m)) { pickAspect(m, pv) }
    l += OverlayItem(R.drawable.ic_pm_sleep, "Sleep timer", sleepLabel(m, nowMs)) { pickSleep(m) }
    // My list: the channel, the series / movie, or the recording
    when {
        kind.zaps && ch != null -> {
            val on = ch.key in m.favourites
            l += OverlayItem(if (on) R.drawable.ic_pm_fav_on else R.drawable.ic_pm_fav, if (on) "Remove from My list" else "Add to My list", "My list") { m.toggleFav(ch) }
        }
        kind == PlayKind.RECORDING -> io.github.kmmarin.tvbro.data.RecRepo.items.firstOrNull { "r|${it.id}" == vp?.key }?.let { r ->
            val on = "r|${r.id}" in m.favourites
            l += OverlayItem(if (on) R.drawable.ic_pm_fav_on else R.drawable.ic_pm_fav, if (on) "Remove from My list" else "Add to My list", "My list") { m.toggleFavRec(r) }
        }
        else -> (m.playingSeries() ?: m.detail)?.let { item ->
            val on = item.key in m.favourites
            l += OverlayItem(if (on) R.drawable.ic_pm_fav_on else R.drawable.ic_pm_fav, if (on) "Remove from My list" else "Add to My list", "My list") { m.toggleFavKey(item) }
        }
    }
    if (kind.zaps && ch != null) l += OverlayItem(R.drawable.ic_pm_options, "Channel options") { m.openChannelMenu(ch) }
    l += OverlayItem(R.drawable.ic_pm_info, "Stream info") { showStreamInfo(m, exo) }
    return l
}

/** Two compact lines like the reference: logo, title; times / position, channel, resolution chip; progress under. */
@Composable
private fun Banner(m: AppModel, kind: PlayKind, now: Long, poll: PlayerPoll, problem: String? = null) {
    val ch = m.current
    val vp = m.vodPlay
    val pos = poll.pos
    val dur = poll.dur
    val res = poll.res
    // a recording shows its channel's logo (looked up once per recording)
    val recLogo = remember(vp?.key) {
        if (kind != PlayKind.RECORDING) null
        else io.github.kmmarin.tvbro.data.RecRepo.items.firstOrNull { "r|${it.id}" == vp?.key }?.let { r ->
            m.channels.firstOrNull { (r.chKey.isNotEmpty() && it.key == r.chKey) || it.name == r.channel }?.logo
        }
    }
    var logo: String? = null
    var logoName = ""
    val title: String
    val line: String
    var chName: String? = null
    var progress: Float? = null
    when (kind) {
        PlayKind.LIVE -> {
            val cur = ch?.let { m.epg.nowNext(it, now).first }
            logo = ch?.logo; logoName = ch?.name ?: ""
            if (cur != null) {
                title = cur.title
                line = "${hhmm(cur.start)} – ${hhmm(cur.stop)}   ${minutes(((cur.stop - now) / 60).coerceAtLeast(0))} left"
                chName = ch?.name
                progress = ((now - cur.start).toFloat() / (cur.stop - cur.start).coerceAtLeast(1)).coerceIn(0f, 1f)
            } else { title = ch?.name ?: ""; line = "No guide information" }
            if (ch != null && m.showNumbers && chName != null) chName = "${ch.number}  $chName"
        }
        else -> {
            if (kind == PlayKind.CATCHUP) { logo = ch?.logo; logoName = ch?.name ?: ""; title = m.catchup?.title ?: ""; chName = ch?.name?.let { "Catch-up  ·  $it" } }
            else {
                title = vp?.title ?: ""
                logoName = title
                logo = if (kind == PlayKind.RECORDING) recLogo else (m.playingSeries() ?: m.detail?.takeIf { it.key == vp?.key })?.poster
            }
            // catch-up: the programme's own length (an archive stream runs on past its end, for hours)
            val len = if (kind == PlayKind.CATCHUP) m.catchup?.lenMs?.takeIf { it > 0 } ?: dur else dur
            line = if (len <= 0) "Loading..." else clock(pos) + " / " + clock(len) + "   ${minutes(((len - pos) / 60000).coerceAtLeast(0))} left"
            progress = if (len > 0) (pos.toFloat() / len).coerceIn(0f, 1f) else 0f
        }
    }
    val recording = ch != null && kind == PlayKind.LIVE && m.activeRec(ch) != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        Logo(logo, logoName, 64.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(line, color = TextMain, fontSize = 15.sp, maxLines = 1, softWrap = false)
                if (chName != null) Text("    $chName", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (res.isNotEmpty()) Chip(res, Accent)
                if (!poll.playing) Chip("Paused", TextMain)
                if (recording) Chip("● REC", RecRed)
            }
        }
    }
    if (problem != null) Text(problem, color = ProblemAmber, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
    if (progress != null) ProgressLine(progress, Modifier.padding(top = 10.dp))
}

private val ProblemAmber = Color(0xFFFFB74D)

@Composable
private fun Chip(text: String, color: Color) {
    Text(text, color = color, fontSize = 12.sp, maxLines = 1, softWrap = false,
        modifier = Modifier.padding(start = 10.dp).border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
}

/** First Down (live TV): TV guide, History, then the recent channels as logo tiles with their programme. */
@Composable
private fun QuickRow(tiles: List<QuickTile>, st: OverlayState, onTap: (Int) -> Unit) {
    val sel = st.quickSel // (read here, not in the host: a move recomposes this row only)
    // a plain scrolling Row: every tile is composed once (at most 12), moving only redraws the two that change
    val ss = rememberScrollState()
    val step = with(androidx.compose.ui.platform.LocalDensity.current) { (132 + 10).dp.roundToPx() }
    LaunchedEffect(sel) { ss.scrollTo(((sel - 2).coerceAtLeast(0) * step).coerceAtMost(ss.maxValue)) }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp).horizontalScroll(ss), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.forEachIndexed { i, t -> QuickCell(t, i == sel, i, onTap) }
    }
}

@Composable
private fun QuickCell(t: QuickTile, f: Boolean, i: Int, onTap: (Int) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.focusGrow(f).width(132.dp).height(96.dp).focusLook(f, shape, RowBg).pointerInput(i) { detectTapGestures { onTap(i) } }.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        val c = t.channel
        if (c != null) {
            Logo(c.logo, c.name, 40.dp)
            Text(t.sub.ifEmpty { c.name }, color = if (f) TextMain else TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            // how far the programme is
            if (t.progress >= 0f) ProgressLine(t.progress, Modifier.padding(top = 4.dp), if (f) Accent else TextDim)
        } else {
            Image(painterResource(t.icon), null, Modifier.size(34.dp), colorFilter = ColorFilter.tint(TextMain))
            Text(t.title, color = TextMain, fontSize = 15.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Second Down: icon buttons with a short value under each; scrolls sideways with the selection. */
@Composable
private fun IconRow(row: RowItems, st: OverlayState, onTap: (Int) -> Unit) {
    val sel = st.iconSel
    val ss = rememberScrollState()
    val step = with(androidx.compose.ui.platform.LocalDensity.current) { (116 + 4).dp.roundToPx() }
    LaunchedEffect(sel) { ss.scrollTo(((sel - 3).coerceAtLeast(0) * step).coerceAtMost(ss.maxValue)) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(ss), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        row.items.forEachIndexed { i, it -> IconCell(it, i == sel, i, onTap) }
    }
}

@Composable
private fun IconCell(it: OverlayItem, f: Boolean, i: Int, onTap: (Int) -> Unit) =
    IconCellContent(it.icon, it.name, it.value, it.tint, f, Modifier.focusLook(f, CircleShape, RowBg), Modifier.pointerInput(i) { detectTapGestures { onTap(i) } })