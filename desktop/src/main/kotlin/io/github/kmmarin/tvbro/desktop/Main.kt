// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.focusGroup
import kotlinx.coroutines.launch
import io.github.kmmarin.tvbro.data.TvNav
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import io.github.kmmarin.tvbro.data.Diag
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager

object Theme {
    val bg = Color(0xFF0E1116)
    val surface = Color(0xFF161B22)
    val surface2 = Color(0xFF1F2630)
    val accent = Color(0xFF33B5FF)
    val text = Color(0xFFE8EDF2)
    val dim = Color(0xFF9AA6B2)
    val live = Color(0xFFFF5A5F)
    val ok = Color(0xFF5AD17A)
}

/** Original tvBro mark: a rounded screen with a play wedge. */
object AppIcon : Painter() {
    override val intrinsicSize = Size(64f, 64f)
    override fun DrawScope.onDraw() {
        val w = size.width
        drawRoundRect(Theme.accent, Offset(w * .06f, w * .14f), Size(w * .88f, w * .66f), CornerRadius(w * .12f))
        drawRect(Theme.accent, Offset(w * .3f, w * .82f), Size(w * .4f, w * .06f))
        val p = Path().apply { moveTo(w * .42f, w * .3f); lineTo(w * .64f, w * .47f); lineTo(w * .42f, w * .64f); close() }
        drawPath(p, Theme.bg)
    }
}

/**
 * Test options: --m3u <file|url> adds a temporary (not saved) playlist source; --play <n> plays its n-th channel;
 * --screenshot <png> --exit-after <s> saves a screen capture and quits (used for unattended smoke tests).
 */
fun main(args: Array<String>) {
    Diag.sink = { System.err.println("tvbro: $it") }
    Thread({ Perms.tighten(Paths.cache) }, "tighten-cache").apply { isDaemon = true; start() } // 0700 / 0600 (Linux)
    fun arg(n: String) = args.indexOf(n).takeIf { it >= 0 && it + 1 < args.size }?.let { args[it + 1] }
    arg("--render-missing")?.let { png -> renderMissing(png); return }
    arg("--selftest")?.let { url -> selfTest(url, arg("--render"), "--no-play" !in args); return }
    application {
        val scope = rememberCoroutineScope()
        val app = remember {
            AppState(scope, arg("--m3u")?.let { listOf(io.github.kmmarin.tvbro.data.Source("test-m3u", "Test playlist", "m3u", url = it)) })
                .also { it.videoSetup = MpvLoader.lib == null } // tvBro does not ship libmpv: explain how to add it
        }
        LaunchedEffect(Unit) {
            arg("--play")?.toIntOrNull()?.let { n -> while (app.channels.isEmpty()) kotlinx.coroutines.delay(200); app.channels.getOrNull(n)?.let(app::playChannel) }
            arg("--exit-after")?.toLongOrNull()?.let { s ->
                kotlinx.coroutines.delay(s * 1000)
                arg("--screenshot")?.let { f ->
                    val img = java.awt.Robot().createScreenCapture(java.awt.Rectangle(java.awt.Toolkit.getDefaultToolkit().screenSize))
                    javax.imageio.ImageIO.write(img, "png", java.io.File(f))
                }
                app.shutdown(); exitApplication()
            }
        }
        val ws = rememberWindowState(size = androidx.compose.ui.unit.DpSize(1280.dp, 760.dp))
        LaunchedEffect(app.fullscreen) { ws.placement = if (app.fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating }
        // An arrow or Enter that reached no focused item (the focused one went away): focus returns where it was.
        Window(onCloseRequest = { app.shutdown(); exitApplication() }, state = ws, title = "tvBro", icon = AppIcon, onKeyEvent = { e ->
            if (e.type != KeyEventType.KeyDown || app.videoSetup || app.homeHasFocus || app.full || app.overlayOpen) false
            else if (e.key in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.Enter)) { app.refocus(); true }
            else false
        }) {
            // Full-screen player keys come straight from AWT: the native video surface never has Compose focus.
            DisposableEffect(Unit) {
                val d = KeyEventDispatcher { e ->
                    val mine = javax.swing.SwingUtilities.getWindowAncestor(e.component) === window || e.component === window
                    if (!app.full || app.playing == null || app.overlayOpen || !mine) false
                    else if (e.id == java.awt.event.KeyEvent.KEY_PRESSED) app.player.key(e)
                    else true // releases and typed characters belong to the player too
                }
                KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(d)
                onDispose { KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(d) }
            }
            MaterialTheme(colorScheme = darkColorScheme(primary = Theme.accent, background = Theme.bg, surface = Theme.surface, onSurface = Theme.text)) {
                if (app.videoSetup) VideoSetup(app) else {
                    Box(Modifier.fillMaxSize().background(Theme.bg)) {
                        Home(app)
                        VideoHost(app) // last: drawn above Home, at a stable place in the tree
                    }
                    Overlays(app)
                }
            }
        }
    }
}

/** The "Video library missing" screen wired to the loader: pick the file, check again, the guide, or go on. */
@Composable
fun VideoSetup(app: AppState) {
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(MpvLoader.error) }
    MissingLibScreen(Platform.windows, MpvLoader.appDir, error, note,
        onChoose = {
            val f = pickLibmpv()
            if (f != null) {
                val why = MpvLoader.choose(f)
                if (why == null) { app.videoSetup = false; app.toast("Video library loaded: ${f.name}") } else { note = why; error = MpvLoader.error }
            }
        },
        onRetry = {
            if (MpvLoader.reload() != null) { app.videoSetup = false; app.toast("Video library loaded") }
            else { error = MpvLoader.error; note = "Still not found. Check the steps above." }
        },
        onDocs = { openInstallDoc()?.let { note = it } },
        onSkip = { app.videoSetup = false },
    )
}

/** Left with nothing further left inside a screen: the side menu (never a dead end). */
private fun inScreenEdge(app: AppState, scope: kotlinx.coroutines.CoroutineScope): Boolean { scope.launch { app.railFr.settle() }; return true }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Home(app: AppState) {
    val fm = LocalFocusManager.current
    var inRail by remember { mutableStateOf(false) }
    // Leaving a section for another: a preview that is not on screen stops.
    LaunchedEffect(app.tab, app.detail) { if (!app.full && app.playing != null && (app.tab != Tab.TV || app.detail != null)) app.stopPlayback() }
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxSize().background(Theme.bg).onFocusChanged { app.homeHasFocus = it.hasFocus }.onKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
        when {
            // Back closes the topmost layer; menus and questions are their own windows and close themselves first,
            // full screen takes its keys from AWT. Here: a page (details, a settings page), else the side menu.
            e.key == Key.Escape || (e.key == Key.Backspace && !typing) -> when (TvNav.top(false, false, false, app.detail != null || (app.tab == Tab.SETTINGS && app.settingsPage != 0))) {
                TvNav.Layer.PAGE -> { if (app.detail != null) app.detail = null else app.settingsPage = 0; true }
                else -> if (inRail) false else { typing = false; scope.launch { app.railFr.settle() }; true }
            }
            typing -> false
            e.key == Key.DirectionUp -> fm.moveFocus(FocusDirection.Up)
            e.key == Key.DirectionDown -> fm.moveFocus(FocusDirection.Down)
            e.key == Key.DirectionLeft -> if (inRail) true else fm.moveFocus(FocusDirection.Left) || inScreenEdge(app, scope)
            e.key == Key.DirectionRight -> {
                val t = app.railReturn
                if (inRail && t != null && app.detail == null && app.tab == Tab.TV) t() else fm.moveFocus(FocusDirection.Right)
            }
            e.key == Key.F11 -> { app.fullscreen = !app.fullscreen; true }
            e.key == Key.Y || e.key == Key.F || e.key == Key.Bookmark -> app.favAction?.let { it(); true } ?: false
            else -> false
        }
    }) {
        // entering the side menu from any screen lands on the current section
        Column(Modifier.width(150.dp).fillMaxHeight().background(Theme.surface).padding(8.dp).onFocusChanged { inRail = it.hasFocus }
            .focusProperties { enter = { app.railFr } }.focusGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(6.dp, 10.dp)) {
                Image(AppIcon, null, Modifier.size(28.dp)); Spacer(Modifier.width(8.dp)); T("tvBro", 20, bold = true)
            }
            for (t in Tab.entries) {
                val sel = app.tab == t && app.detail == null
                Box(Modifier.fillMaxWidth().tvItem(requester = if (app.tab == t) app.railFr else null) { app.detail = null; app.tab = t; if (t == Tab.SETTINGS) app.settingsPage = 0 }.padding(10.dp, 8.dp)) {
                    T(t.label + if (t == Tab.RECORDINGS && app.recorder.items.any { it.status == "recording" }) "  ●" else "", 16, if (sel) Theme.accent else Theme.text, bold = sel)
                }
            }
            Spacer(Modifier.weight(1f))
            if (app.toastMsg.isNotEmpty()) T(app.toastMsg, 12, Theme.accent, maxLines = 5)
            if (app.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Theme.accent)
            T(app.status, 11, Theme.dim, maxLines = 4)
        }
        Box(Modifier.weight(1f).fillMaxHeight().padding(12.dp)) {
            val d = app.detail
            // each screen is a focus group that restores its last focused item when focus comes back from the side menu
            if (d != null) DetailScreen(app, d) else key(app.settings.timeFmt, app.tab) { Box(Modifier.fillMaxSize().focusRestorer().focusGroup()) { when (app.tab) {
                Tab.TV -> TvScreen(app)
                Tab.MOVIES -> VodBrowse(app, app.movies, false)
                Tab.SHOWS -> VodBrowse(app, app.series, true)
                Tab.MYLIST -> MyListScreen(app)
                Tab.RECORDINGS -> RecordingsScreen(app)
                Tab.SEARCH -> SearchScreen(app)
                Tab.SETTINGS -> SettingsScreen(app)
            } } }
        }
    }
}
