// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

/**
 * Platform-neutral focus rules of the TV screen and the layers above it, shared by the desktop app (and usable by
 * any front end): zones left to right are side menu < groups < channel name < programmes.
 */
object TvNav {
    enum class Zone { RAIL, GROUPS, LABEL, GRID }

    /**
     * Left from [z]. In the grid, Left only leaves for the channel name when [GuideWindow.leftOf] says so (at the
     * window start), so GRID -> LABEL here is that last step. The side menu is the end: focus stays there.
     */
    fun left(z: Zone): Zone = when (z) { Zone.GRID -> Zone.LABEL; Zone.LABEL -> Zone.GROUPS; Zone.GROUPS, Zone.RAIL -> Zone.RAIL }

    /** Right from [z]; from the side menu back to the zone the screen had focused last ([last]). */
    fun right(z: Zone, last: Zone): Zone = when (z) {
        Zone.RAIL -> if (last == Zone.RAIL) Zone.LABEL else last
        Zone.GROUPS -> Zone.LABEL
        Zone.LABEL, Zone.GRID -> Zone.GRID
    }

    /** What Back / Escape closes, topmost first. */
    enum class Layer { MENU, DIALOG, FULL_SCREEN, PAGE, SCREEN }

    /** The topmost open layer: a menu, then a dialog (question, reminder), then the full-screen player, then a page (details, a settings page). */
    fun top(menu: Boolean, dialog: Boolean, fullScreen: Boolean, page: Boolean): Layer = when {
        menu -> Layer.MENU
        dialog -> Layer.DIALOG
        fullScreen -> Layer.FULL_SCREEN
        page -> Layer.PAGE
        else -> Layer.SCREEN
    }

    /** Enter / OK on a live channel (its name or its programme on now). */
    enum class Enter { FULL_SCREEN, SWITCH_PREVIEW, PLAY_FULL_SCREEN }

    /**
     * The preview plays the current channel and never follows focus: Enter on the channel already playing goes full
     * screen; on another one the preview switches to it, or (preview off) it plays full screen.
     */
    fun enter(channel: String, playing: String?, previewOn: Boolean): Enter = when {
        channel == playing -> Enter.FULL_SCREEN
        previewOn -> Enter.SWITCH_PREVIEW
        else -> Enter.PLAY_FULL_SCREEN
    }

    /** Entering a channel list: the focused channel if it is in [keys], else the playing one, else the first. */
    fun entry(keys: List<String>, focused: String?, playing: String?): Int =
        keys.indexOf(focused).takeIf { it >= 0 } ?: keys.indexOf(playing).takeIf { it >= 0 } ?: if (keys.isEmpty()) -1 else 0
}
