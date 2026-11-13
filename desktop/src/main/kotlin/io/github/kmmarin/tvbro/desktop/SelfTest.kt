// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.use
import kotlinx.coroutines.*
import io.github.kmmarin.tvbro.data.Source
import java.io.File

/**
 * Unattended check without a display: plays [url] headless through libmpv and prints the stream info the
 * player overlay shows; with [png], also loads [url] as an M3U source and renders the TV screen off-screen.
 */
fun selfTest(url: String, png: String?, play: Boolean = true) {
    val lib = MpvLoader.lib ?: run { println("FAIL libmpv: ${MpvLoader.error}"); return }
    println("libmpv loaded")
    if (png != null) {
        SourceRepo(Source("selftest", "Self-test", "m3u", url = url)).cache.clear() // never a stale playlist from an earlier run
        val scope = CoroutineScope(Dispatchers.Default)
        val app = AppState(scope, listOf(Source("selftest", "Self-test", "m3u", url = url)))
        runBlocking { withTimeoutOrNull(60_000) { while (app.busy || app.channels.isEmpty()) delay(200) } }
        println("channels=${app.channels.size} status=${app.status}")
        val first = app.channels.firstOrNull()
        if (first != null) println("now/next: " + app.epg.nowNext(first, app.now).toList().map { it?.title })
        // -Dtvbro.tab=SETTINGS -Dtvbro.page=4 renders another screen
        System.getProperty("tvbro.tab")?.let { t -> Tab.entries.firstOrNull { it.name == t }?.let { app.tab = it } }
        System.getProperty("tvbro.page")?.toIntOrNull()?.let { app.settingsPage = it }
        ImageComposeScene(1280, 760) {
            MaterialTheme(colorScheme = darkColorScheme()) { Home(app) }
        }.use { s ->
            s.render(0); Thread.sleep(1500); s.render(2_000_000_000L)
            File(png).writeBytes(s.render(3_000_000_000L).encodeToData()!!.bytes)
        }
        println("rendered $png")
        val ch = first ?: return
        if (play) playInfo(lib, ch.url)
        scope.cancel()
    } else if (play) playInfo(lib, url)
}

/** Renders the "Video library missing" screen off-screen into [png] (as it looks with the current loader result). */
fun renderMissing(png: String) {
    val err = MpvLoader.lib?.let { "(libmpv is loaded here; this is how the screen looks without it)" } ?: MpvLoader.error
    ImageComposeScene(1280, 760) {
        MaterialTheme(colorScheme = darkColorScheme()) { MissingLibScreen(Platform.windows, MpvLoader.appDir, err, "", {}, {}, {}, {}) }
    }.use { s -> s.render(0); File(png).writeBytes(s.render(1_000_000_000L).encodeToData()!!.bytes) }
    println("rendered $png")
}

private fun playInfo(lib: MpvLib, url: String) {
    val m = Mpv(lib, 0, "auto-safe") { println("end: $it") }
    m.play(url, trustedLocal = true) // the developer's own test address
    for (i in 0 until 30) {
        Thread.sleep(1000)
        if (m.get("video-params/w") != null && m.get("time-pos")?.toDoubleOrNull()?.let { it > 2 } == true) break
    }
    println("video ${m.get("video-params/w")}x${m.get("video-params/h")} fps=${m.get("container-fps")} vcodec=${m.get("video-codec")} acodec=${m.get("audio-codec-name")} vbitrate=${m.get("video-bitrate")} hwdec=${m.get("hwdec-current")} tracks=${m.tracks().size} pos=${m.get("time-pos")}")
    m.set("audio-delay", "0.050"); println("audio-delay=${m.get("audio-delay")}")
    m.set("video-aspect-override", "4:3"); println("aspect=${m.get("video-aspect-override")}")
    m.destroy()
}
