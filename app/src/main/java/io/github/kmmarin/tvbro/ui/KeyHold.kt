// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui

import android.os.SystemClock
import android.view.KeyEvent

/**
 * Whether the user is flying through a list: an arrow key held (auto-repeat) or pressed again within [IDLE_MS]. The TV
 * screen loads guide data only once this settles, so rows that scroll past during a hold are never queried.
 * Fed from MainActivity.dispatchKeyEvent (main thread only).
 */
object KeyHold {
    /** Quiet time after the last arrow event before the hold counts as over. */
    const val IDLE_MS = 150L
    private var held = false
    private var last = 0L
    private var prev = 0L

    fun on(e: KeyEvent) {
        if (e.keyCode !in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) return
        when (e.action) {
            KeyEvent.ACTION_DOWN -> { held = e.repeatCount > 0; prev = last; last = SystemClock.uptimeMillis() }
            KeyEvent.ACTION_UP -> held = false
        }
    }

    /** True while an arrow is held, or while presses come faster than [IDLE_MS] apart and the last one was just now. */
    fun busy(now: Long = SystemClock.uptimeMillis()): Boolean =
        (held && now - last < 500) || (now - last < IDLE_MS && last - prev < IDLE_MS) // (a lost key-up never sticks)
}
