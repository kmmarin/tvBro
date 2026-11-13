// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import android.util.Log
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Test hooks, compiled into DEBUG builds only (the release source set has a no-op copy). Driven with system properties
 * from adb, so no saved setting is ever changed:
 *  - `adb shell setprop debug.tvbro.noautoplay 1`: the next start resumes nothing (no channel, no full screen), so the
 *    UI can be inspected with uiautomator (it cannot dump while video plays on some devices);
 *  - `adb shell setprop debug.tvbro.stop <any new value>`: stops live playback now (full screen closes, preview off);
 *  - logcat tag `tvbro-dbg`: screen / section / playing channel / player state on every change, and every new media
 *    item (a re-buffer) of the live player. Stream addresses are never logged.
 */
object DebugHooks {
    private const val TAG = "tvbro-dbg"

    private fun prop(k: String): String = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, k) as? String ?: ""
    } catch (e: Throwable) { "" }

    fun noAutoplay(): Boolean = prop("debug.tvbro.noautoplay") == "1"

    fun attach(m: AppModel) {
        Log.i(TAG, "debug hooks on, noautoplay=${noAutoplay()}")
        m.viewModelScope.launch {
            var stopSeen = prop("debug.tvbro.stop")
            var last = ""
            var watched: Player? = null
            while (true) {
                delay(400)
                val s = prop("debug.tvbro.stop")
                if (s != stopSeen) { stopSeen = s; if (s.isNotEmpty()) stop(m) }
                val exo = m.liveExo
                if (exo != null && exo !== watched) { watched = exo; exo.addListener(listener) }
                val st = "screen=${m.screen} section=${m.section} current=${m.current?.name} focus=${m.focusCh?.name} " +
                    "player=${exo?.let { "state=${it.playbackState} playing=${it.isPlaying} vol=${it.volume}" }} loaded=${m.liveKey != null} fg=${m.foreground} liveplayers=${io.github.kmmarin.tvbro.ui.LivePlayer.built} models=${io.github.kmmarin.tvbro.ui.LivePlayer.users} conns=${io.github.kmmarin.tvbro.ui.LiveConns.open}"
                if (st != last) { last = st; Log.i(TAG, st) }
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { Log.i(TAG, "player: new media item (reason $reason)") }
        override fun onPlaybackStateChanged(state: Int) { Log.i(TAG, "player: state $state") }
        override fun onIsPlayingChanged(isPlaying: Boolean) { Log.i(TAG, "player: playing=$isPlaying") }
    }

    private fun stop(m: AppModel) {
        Log.i(TAG, "stop requested")
        if (m.screen == Screen.PLAYER) m.leavePlayer()
        else if (m.screen == Screen.VODPLAY) m.screen = Screen.LIST
        m.current = null
        m.liveExo?.let { it.stop(); it.clearMediaItems() }
        m.liveUrl = null; m.liveKey = null
    }
}
