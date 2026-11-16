// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

/**
 * The OK key on one focusable item ([io.github.kmmarin.tvbro.ui.tvFocus]): one press, one action.
 *
 * - A press belongs to the item it went down on. A pop-up that opens on a press (player icon row, Menu, long press)
 *   takes focus while the key is still down; the key's repeats and its release then reached the pop-up's focused
 *   item, and Compose 1.7 clicks on a release without a press: the pop-up opened and at once chose its item and
 *   closed, depending on how long the key was held (user bug 2026-11-15, TCL: "loads then unloads the menu"). Repeats
 *   and releases of a press that went down elsewhere are swallowed (matched by the press's down time).
 * - Long press: Compose's own timer, or the key's first repeat (Android marks it FLAG_LONG_PRESS; remotes and TVs
 *   whose timing differs from Compose's still get it), whichever comes first, once per press. The release after a
 *   long press is not a click.
 */
class PressGate {
    enum class Act { PASS, SWALLOW, LONG }

    private var downAt = NONE
    private var longAt = NONE
    /** The long press of [longAt] came from a key repeat (Compose did not see it and would click on the release). */
    private var longByRepeat = false

    /** A key event of OK. [hasLong]: the item has a long-press action. */
    fun key(down: Boolean, repeat: Int, downTime: Long, hasLong: Boolean): Act {
        if (down && repeat == 0) { downAt = downTime; return Act.PASS }
        if (downTime != downAt) { if (!down) downAt = NONE; return Act.SWALLOW }
        if (down) {
            if (!hasLong) return Act.PASS
            if (longAt == downTime) return Act.SWALLOW
            longAt = downTime; longByRepeat = true
            return Act.LONG
        }
        downAt = NONE
        return if (longAt == downTime && longByRepeat) Act.SWALLOW else Act.PASS
    }

    /** Compose's long-press timer fired (key or touch): true when the long-press action should run now. */
    fun timerLong(): Boolean {
        if (downAt == NONE) return true // touch, or no key press in progress
        if (longAt == downAt) return false
        longAt = downAt; longByRepeat = false
        return true
    }

    private companion object { const val NONE = Long.MIN_VALUE }
}
