// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.io.File

/** What the "Video library missing" screen says; plain text so tests can check it without a window. */
object MissingLibText {
    const val TITLE = "Video library missing"

    fun intro(windows: Boolean) = if (windows)
        "tvBro plays video with libmpv, a separate library that is not included in the tvBro download (the common " +
            "Windows builds of it are GPL-licensed; tvBro is Apache-2.0). Add your own copy once and tvBro remembers it. " +
            "tvBro never downloads it for you."
    else "tvBro plays video with libmpv, the system's mpv library. It is not part of the tvBro download. tvBro never installs it for you."

    fun steps(windows: Boolean, appDir: String?): List<String> = if (windows) listOf(
        "In your web browser, open the releases page of a Windows libmpv build: github.com/zhongfly/mpv-winbuild/releases " +
            "offers LGPL builds (prefer those), github.com/shinchiro/mpv-winbuild-cmake/releases GPL builds. Your own " +
            "copy of a GPL build is fine for personal use.",
        "Download the 64-bit developer package: its name starts with \"mpv-dev\", contains \"x86_64\" (and \"lgpl\" if " +
            "there is one) and ends in .7z. Not the i686 (32-bit) or aarch64 one. The \"x86_64-v3\" packages need a CPU " +
            "with AVX2; plain \"x86_64\" runs on every 64-bit PC.",
        "Open the .7z with 7-Zip and take out the one file libmpv-2.dll.",
        "Put libmpv-2.dll next to tvBro.exe" + (appDir?.let { " (in $it)" } ?: "") + ", or in any folder you like and " +
            "press \"Choose file…\" to point tvBro at it.",
        "Press \"Check again\".",
    ) else listOf(
        "Install the library: sudo apt install libmpv2 (Debian 12 / Raspberry Pi OS bookworm).",
        "Press \"Check again\". A libmpv.so.2 of your own build can be picked with \"Choose file…\".",
    )

    /** INSTALL.md next to the app (the downloads ship it), else the one in a source checkout. */
    fun installDoc(appDir: String?): File? =
        listOfNotNull(appDir?.let { File(it, "INSTALL.md") }, File("docs", "INSTALL.md"), File("../docs", "INSTALL.md")).firstOrNull { it.isFile }
}

/**
 * Shown at start when libmpv could not be loaded: step-by-step how to get it, and buttons to pick the file, check
 * again, open INSTALL.md or go on without video. [note] is the outcome of the last button (an error or a hint).
 */
@Composable
fun MissingLibScreen(
    windows: Boolean, appDir: String?, error: String?, note: String,
    onChoose: () -> Unit, onRetry: () -> Unit, onDocs: () -> Unit, onSkip: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { first.requestFocus() } catch (e: IllegalStateException) {} }
    val fm = LocalFocusManager.current
    Box(Modifier.fillMaxSize().background(Theme.bg).testTag("missing-lib").onKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) false else when (e.key) { // arrows walk the buttons (TV remote / keyboard)
            Key.DirectionLeft, Key.DirectionUp -> fm.moveFocus(FocusDirection.Previous)
            Key.DirectionRight, Key.DirectionDown -> fm.moveFocus(FocusDirection.Next)
            else -> false
        }
    }, contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 820.dp).verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(AppIcon, null, Modifier.size(40.dp)); Spacer(Modifier.width(12.dp))
                T(MissingLibText.TITLE, 28, bold = true)
            }
            T(MissingLibText.intro(windows), 15, Theme.dim, maxLines = 5)
            Column(Modifier.fillMaxWidth().background(Theme.surface, RoundedCornerShape(8.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MissingLibText.steps(windows, appDir).forEachIndexed { i, s ->
                    Row {
                        T("${i + 1}.", 15, Theme.accent, bold = true, modifier = Modifier.width(28.dp))
                        T(s, 15, maxLines = 6)
                    }
                }
            }
            if (error != null) T("Why: $error", 13, Theme.dim, maxLines = 4)
            if (note.isNotEmpty()) T(note, 14, Theme.live, maxLines = 4, modifier = Modifier.testTag("missing-lib-note"))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("Choose file…", requester = first, onClick = onChoose)
                Btn("Check again", onClick = onRetry)
                Btn("Open INSTALL.md", onClick = onDocs)
                Btn("Continue without video", onClick = onSkip)
            }
            val doc = MissingLibText.installDoc(appDir)
            T("Installation guide: " + (doc?.absolutePath ?: "INSTALL.md in the tvBro folder"), 12, Theme.dim, maxLines = 2)
        }
    }
}

/** The file picker for libmpv (a native dialog; nothing is downloaded). */
fun pickLibmpv(): File? {
    val d = java.awt.FileDialog(null as java.awt.Frame?, "Choose ${if (Platform.windows) MpvLocate.WIN_DLL else "libmpv.so.2"}", java.awt.FileDialog.LOAD)
    if (Platform.windows) d.file = "*.dll" else d.setFilenameFilter { _, n -> n.startsWith("libmpv") }
    MpvLoader.appDir?.let { d.directory = it }
    d.isVisible = true
    return d.file?.let { File(d.directory, it) }
}

/** Opens INSTALL.md with the system's viewer; returns a message when that is not possible. */
fun openInstallDoc(): String? {
    val f = MissingLibText.installDoc(MpvLoader.appDir) ?: return "INSTALL.md was not found next to tvBro"
    return try { java.awt.Desktop.getDesktop().open(f); null } catch (e: Exception) { "Could not open it here; open ${f.absolutePath} in a text editor" }
}
