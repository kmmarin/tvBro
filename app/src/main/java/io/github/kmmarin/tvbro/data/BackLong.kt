// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

/**
 * Long-press Back (TV screen, guide): which channel opens full screen. The one playing (in the preview), never the
 * focused row: moving focus does not change channel (user 2026-11-11), and the focus can still sit on the channel that
 * was full screen before (user bug 2026-11-15). The focused channel only when nothing has played yet.
 */
object BackLong {
    fun <T : Any> target(playing: T?, focused: T?): T? = playing ?: focused
}
