// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import io.github.kmmarin.tvbro.data.*
import io.github.kmmarin.tvbro.data.TvNav.Zone
import java.io.File

/**
 * Headless UI test of the TV screen (no video engine: the player only remembers what it should play). Drives the real
 * Home composable with arrow keys and Enter and checks the guide window, the focus zones and what the preview plays.
 */
@OptIn(ExperimentalTestApi::class)
class TvScreenUiTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun done() { scope.cancel() }

    private fun press(k: Key) { rule.onRoot().performKeyInput { pressKey(k) }; rule.waitForIdle() }

    private fun app(): AppState {
        File(Paths.config, "settings.json").delete() // a clean start: no restored UI from another test run
        val app = AppState(scope, emptyList())
        rule.waitUntil(10_000) { app.status.startsWith("Add a source") } // the (empty) load is done
        val now = System.currentTimeMillis() / 1000
        val chs = (0 until 4).map { i -> Channel("s|c$i", "s", "C$i", null, "G", "http://127.0.0.1:9/c$i", "c$i", i + 1, Catchup("shift", null), 1) }
        val map = chs.associate { c ->
            c.idKey!! to arrayOf(
                Programme(c.idKey!!, now - 3 * 3600, now - 3600, "Earlier ${c.name}", null),
                Programme(c.idKey!!, now - 3600, now + 1200, "Now ${c.name}", null),
                Programme(c.idKey!!, now + 1200, now + 3 * 3600, "Next ${c.name}", null),
                Programme(c.idKey!!, now + 3 * 3600, now + 9 * 3600, "Later ${c.name}", null),
            )
        }
        rule.runOnIdle { app.tab = Tab.TV; app.channels = chs; app.epg = EpgIndex(map) }
        return app
    }

    @Test fun guideFocusZonesAndPreview() {
        val app = app()
        rule.setContent { Home(app) }
        rule.waitUntil(10_000) { app.focusCh != null && app.tvZone == Zone.LABEL && app.homeHasFocus }
        val now = System.currentTimeMillis() / 1000
        val nowWin = GuideWindow.initialStart(now)
        assertEquals(nowWin, app.winStart)
        // nothing played yet: the preview starts the current (first) channel
        rule.waitUntil(5_000) { app.playing != null }
        assertEquals("s|c0", app.playing?.live?.key)
        assertFalse(app.full)

        // 3. moving focus never changes what the preview plays
        press(Key.DirectionDown); press(Key.DirectionDown)
        assertEquals("s|c2", app.focusCh?.key)
        assertEquals("s|c0", app.playing?.live?.key)
        // Enter on another channel switches the preview; Enter on the playing one goes full screen
        press(Key.Enter)
        assertEquals("s|c2", app.playing?.live?.key); assertFalse(app.full)
        press(Key.Enter)
        assertTrue(app.full)

        // 4. Esc in full screen (AWT key in the app, here the same call): back in the preview, still playing, focus on it
        rule.runOnIdle { app.leavePlayer() }
        rule.waitForIdle()
        assertFalse(app.full); assertTrue(app.docked)
        assertEquals("s|c2", app.playing?.live?.key)
        rule.waitUntil(5_000) { app.focusCh?.key == "s|c2" && app.tvZone == Zone.LABEL }

        // 1. Right from the name: the programme on now; Left at the window start: the name again, no scroll back
        press(Key.DirectionRight)
        assertEquals(Zone.GRID, app.tvZone); assertEquals("Now C2", app.focusProg?.title)
        press(Key.DirectionLeft)
        assertEquals(Zone.LABEL, app.tvZone); assertEquals(nowWin, app.winStart)
        // Right walks forward (the window moves ahead), Left walks back and ends on the name with the window at now
        repeat(4) { press(Key.DirectionRight); assertTrue(app.homeHasFocus) } // the window moves under the focus, the focus stays
        assertEquals(Zone.GRID, app.tvZone)
        assertTrue(app.winStart > nowWin)
        repeat(12) { press(Key.DirectionLeft); assertTrue(app.winStart >= nowWin); assertTrue(app.homeHasFocus) }
        assertEquals(nowWin, app.winStart)
        assertTrue(app.homeHasFocus) // never lost, never trapped

        // 2. zones: name -> groups (the selected group) -> side menu, and Right back the same way
        rule.waitUntil(2_000) { app.tvZone == Zone.LABEL || app.tvZone == Zone.GROUPS }
        if (app.tvZone == Zone.LABEL) press(Key.DirectionLeft)
        assertEquals(Zone.GROUPS, app.tvZone)
        press(Key.DirectionLeft) // the side menu
        press(Key.DirectionRight)
        rule.waitUntil(2_000) { app.tvZone == Zone.GROUPS }
        press(Key.DirectionRight)
        rule.waitUntil(2_000) { app.tvZone == Zone.LABEL }
        assertEquals("s|c2", app.focusCh?.key) // the same channel as before
        assertEquals("s|c2", app.playing?.live?.key) // and the preview never followed any of it
    }

    @Test fun resumesTheLastChannelInThePreviewFocused() {
        val app = app()
        rule.runOnIdle { app.update { it.copy(lastChannel = "s|c2") } }
        rule.setContent { Home(app) }
        rule.waitUntil(5_000) { app.playing != null }
        assertEquals("s|c2", app.playing?.live?.key); assertFalse(app.full)
        rule.waitUntil(5_000) { app.focusCh?.key == "s|c2" && app.tvZone == Zone.LABEL && app.homeHasFocus }
    }

    @Test fun lockedChannelNeedsThePin() {
        val app = app()
        val h = Pin.hash("2468", iterations = 1000)
        rule.runOnIdle { app.editPrefs { it.copy(pinHash = h, lockedCh = setOf("s|c0")) } }
        rule.setContent { Home(app) }
        rule.waitUntil(10_000) { app.focusCh != null && app.homeHasFocus }
        // the preview does not start a locked channel; channel up / down passes over it
        Thread.sleep(500); rule.waitForIdle()
        assertNull(app.playing)
        assertTrue(app.isLocked(app.channels[0])); assertFalse(app.isLocked(app.channels[1]))
        // a locked channel is no alternate source
        assertTrue(app.alternates(app.channels[1]).none { it.key == "s|c0" })
        // Enter asks for the PIN; a wrong one plays nothing, the right one plays it and opens the locks
        press(Key.Enter)
        assertNotNull(app.prompt)
        rule.runOnIdle { val p = app.prompt!!; app.prompt = null; p.done("1111") }
        assertNull(app.playing); assertFalse(app.pinOpen)
        press(Key.Enter)
        rule.runOnIdle { val p = app.prompt!!; app.prompt = null; p.done("2468") }
        rule.waitForIdle()
        assertTrue(app.pinOpen); assertEquals("s|c0", app.playing?.live?.key)
        assertFalse(app.isLocked(app.channels[0]))
        rule.runOnIdle { app.lockAgain() }
        assertTrue(app.isLocked(app.channels[0]))
        rule.runOnIdle { app.zap(1); app.zap(-1) } // c1, then back: the locked c0 is passed over, c3
        assertEquals("s|c3", app.playing?.live?.key)
    }

    @Test fun catchupIsInTheMenusNotTheGrid() {
        val app = app()
        rule.setContent { Home(app) }
        rule.waitUntil(10_000) { app.focusCh != null && app.tvZone == Zone.LABEL }
        rule.onAllNodesWithText("Earlier C0", substring = true).assertCountEquals(0)
        rule.runOnIdle { app.catchupMenu(app.channels[0]) }
        assertEquals(listOf("Earlier C0"), app.menu?.items?.map { it.first.substringAfter(" · ") })
    }
}
