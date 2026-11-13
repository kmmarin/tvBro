// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kmmarin.tvbro.ui.*

private val RESCUE_KEYS = setOf(
    android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT,
    android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER,
)

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onSearchRequested(): Boolean { model.section = Section.SEARCH; model.screen = Screen.LIST; return true }

    // Long-press Back in the TV / guide view: straight to full-screen playback. Detected by key repeat, with a timer
    // as fallback for remotes that do not repeat; the release after a long press is swallowed so it is not a short Back.
    private val backHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var backLongDone = false
    private var backDown = false
    private val backLong = Runnable { if (backDown && !backLongDone && model.backLongPress()) backLongDone = true }

    private fun handleBack(e: android.view.KeyEvent): Boolean {
        if (e.action == android.view.KeyEvent.ACTION_DOWN) {
            if (e.repeatCount == 0) {
                backDown = true; backLongDone = false
                backHandler.removeCallbacks(backLong)
                if (model.backLongApplies()) backHandler.postDelayed(backLong, 600)
                return false
            }
            if (!backLongDone && backDown && model.backLongPress()) { backLongDone = true; backHandler.removeCallbacks(backLong) }
            return backLongDone
        }
        if (e.action == android.view.KeyEvent.ACTION_UP) {
            backDown = false
            backHandler.removeCallbacks(backLong)
            if (backLongDone) { backLongDone = false; return true }
        }
        return false
    }

    override fun dispatchKeyEvent(e: android.view.KeyEvent): Boolean {
        val k = e.keyCode
        if (k == android.view.KeyEvent.KEYCODE_BACK) {
            if (handleBack(e)) return true
            // Back goes straight to the screens' Back handlers. Through Compose it first moved focus out of the focused
            // group (FocusDirection.Exit) and was used up: in full screen with the player bar open Back did nothing once,
            // then closed the bar, and the next Back did nothing again. (The app handles Back only with BackHandler.)
            if (e.action == android.view.KeyEvent.ACTION_UP && !e.isCanceled) onBackPressedDispatcher.onBackPressed()
            return true
        }
        if (e.action == android.view.KeyEvent.ACTION_DOWN && e.repeatCount == 0 &&
            (k == android.view.KeyEvent.KEYCODE_PROG_YELLOW || k == android.view.KeyEvent.KEYCODE_BOOKMARK || k == android.view.KeyEvent.KEYCODE_BUTTON_Y) && model.favShortcut()) return true
        // Nothing focused (focus was lost): the key brings focus back instead of going nowhere.
        if (e.action == android.view.KeyEvent.ACTION_DOWN && !FocusMemory.anyFocus && k in RESCUE_KEYS && model.focusRescue?.invoke(true) == true) return true
        // Last resort: a focus bug (e.g. a FocusRequester whose target was disposed) must never close the app.
        return try { super.dispatchKeyEvent(e) } catch (x: IllegalStateException) {
            io.github.kmmarin.tvbro.data.Diag.log("key ${e.keyCode} ignored: ${x.javaClass.simpleName}: ${x.message?.take(120)}")
            true
        }
    }

    override fun onStop() { model.stopLink(); super.onStop() }
    override fun onResume() { super.onResume(); io.github.kmmarin.tvbro.data.Reminders.appVisible = true }
    override fun onPause() { io.github.kmmarin.tvbro.data.Reminders.appVisible = false; super.onPause() }

    override fun onUserLeaveHint() {
        if (android.os.Build.VERSION.SDK_INT >= 26 && (model.screen == Screen.PLAYER || model.screen == Screen.VODPLAY)) {
            try { enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build()) } catch (e: Exception) { }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("play")?.let { model.playByKey(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build())
        }
        io.github.kmmarin.tvbro.data.Diag.sink = { android.util.Log.i("tvbro", it) }
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent {
            val m: AppModel = viewModel()
            LaunchedEffect(Unit) { intent?.getStringExtra("play")?.let { m.playByKey(it) } }
            LaunchedEffect(Unit) { io.github.kmmarin.tvbro.update.Updater.checkIfDue(applicationContext) } // opt-out in Settings > About
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Surface)) {
                val fm = androidx.compose.ui.platform.LocalFocusManager.current
                // Focus must never be lost: back to the item the user was on, else the TV screen's channel, else the
                // first item in reach (also on the next D-pad key, see dispatchKeyEvent).
                LaunchedEffect(Unit) {
                    m.focusRescue = { hard ->
                        // on the TV screen the focused channel first: when a programme cell goes (it ended at the
                        // guide window's edge) the newest remembered item still on screen was the side menu entry
                        (m.screen == Screen.LIST && m.section == Section.TV && m.railRight?.request() == true) ||
                        FocusMemory.restore() || m.railRight?.request() == true ||
                            (hard && runCatching {
                                fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Next) || fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
                            }.getOrDefault(false))
                    }
                }
                // (not while the screen is being touched: Compose gives no focus in touch mode, and the rescue looped
                // every 400 ms; the next remote key brings focus back, see dispatchKeyEvent)
                val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
                LaunchedEffect(FocusMemory.anyFocus, inputMode.inputMode) {
                    while (!FocusMemory.anyFocus && inputMode.inputMode != androidx.compose.ui.input.InputMode.Touch) {
                        kotlinx.coroutines.delay(400)
                        if (!FocusMemory.anyFocus) { val ok = m.focusRescue?.invoke(true); io.github.kmmarin.tvbro.data.Diag.log("focus lost: rescue $ok") }
                    }
                }
                Box(Modifier.fillMaxSize().background(Bg)) {
                    when (m.screen) {
                        Screen.LIST -> HomeScreen(m)
                        Screen.PLAYER -> {} // drawn above the live video, below
                        Screen.GUIDE -> GuideScreen(m)
                        Screen.SETTINGS -> HomeScreen(m)
                        Screen.ADD_SOURCE -> AddSourceScreen(m)
                        Screen.GUIDE_SEARCH -> SearchScreen(m) { m.screen = Screen.GUIDE }
                        Screen.MAPPING -> MappingScreen(m)
                        Screen.VOD -> HomeScreen(m)
                        Screen.RECORDINGS -> RecordingsScreen(m)
                        Screen.EDITOR -> EditorScreen(m)
                        Screen.DETAIL -> DetailPage(m)
                        Screen.VODPLAY -> androidx.compose.runtime.key(m.vodPlay?.key) { VodPlayerScreen(m) }
                    }
                    // One live video surface: full screen or in the TV preview spot, animated between them.
                    LiveVideo(m)
                    if (m.screen == Screen.PLAYER) PlayerScreen(m)
                    ReminderPopup(m)
                    ProgMenu(m)
                    ActionMenu(m)
                    PromptDialog(m)
                    io.github.kmmarin.tvbro.update.UpdatePrompt() // "Update available" after the daily check; Back = Later
                }
            }
        }
    }
}
