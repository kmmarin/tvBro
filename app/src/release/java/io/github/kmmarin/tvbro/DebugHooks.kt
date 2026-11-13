// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

/** Release build: no test hooks (the debug source set has the real ones). */
object DebugHooks {
    @Suppress("NOTHING_TO_INLINE") inline fun noAutoplay(): Boolean = false
    @Suppress("UNUSED_PARAMETER", "NOTHING_TO_INLINE") inline fun attach(m: AppModel) {}
}
