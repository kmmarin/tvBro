// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Headless UI test of the "Video library missing" screen: texts, buttons, keyboard. */
@OptIn(ExperimentalTestApi::class)
class MissingLibUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun windowsScreen() {
        val calls = mutableListOf<String>()
        var note = ""
        rule.setContent {
            MissingLibScreen(true, "C:\\Apps\\tvBro", "libmpv-2.dll was not found next to tvBro.exe or on PATH", note,
                onChoose = { calls += "choose" }, onRetry = { calls += "retry" }, onDocs = { calls += "docs" }, onSkip = { calls += "skip" })
        }
        rule.onNodeWithText("Video library missing").assertExists()
        rule.onNodeWithText("libmpv-2.dll was not found", substring = true).assertExists()
        rule.onNodeWithText("lgpl", substring = true).assertExists()
        rule.onNodeWithText("next to tvBro.exe (in C:\\Apps\\tvBro)", substring = true).assertExists()
        rule.onNodeWithText("never downloads", substring = true).assertExists()
        rule.onNodeWithText("Installation guide:", substring = true).assertExists()
        for (b in listOf("Choose file…", "Check again", "Open INSTALL.md", "Continue without video")) rule.onNodeWithText(b).assertExists()
        rule.onNodeWithText("Check again").performClick()
        rule.onNodeWithText("Open INSTALL.md").performClick()
        rule.onNodeWithText("Choose file…").performClick()
        rule.waitForIdle()
        assertEquals(listOf("retry", "docs", "choose"), calls)
        // keyboard: the first button has focus at start; Right then Enter is "Check again"
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.onRoot().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals("retry", calls.last())
    }

    @Test fun linuxScreenAndNote() {
        rule.setContent { MissingLibScreen(false, null, null, "Still not found. Check the steps above.", {}, {}, {}, {}) }
        rule.onNodeWithText("sudo apt install libmpv2", substring = true).assertExists()
        rule.onNodeWithTag("missing-lib-note").assertExists()
        assertTrue(MissingLibText.steps(false, null).none { "libmpv-2.dll" in it })
    }
}
