// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

/** Default order rules for channel groups (the user's own moves always win). */
object GroupOrder {
    private val PPV = Regex("""\bppv\b""", RegexOption.IGNORE_CASE)

    /** Pay-per-view groups ("PPV Events | 1", "UK: PPV"): shown last by default. */
    fun isPpv(group: String) = PPV.containsMatchIn(group)

    /**
     * Groups in display order: the user's saved order first ([saved], kept as is), then the rest in the provider's
     * order with PPV groups moved to the bottom.
     */
    fun order(provider: List<String>, saved: List<String>): List<String> {
        val rest = provider.filter { it !in saved }
        return saved.filter { it in provider } + rest.filterNot(::isPpv) + rest.filter(::isPpv)
    }

    /** [l] with [key] moved to the top (d < 0) or the bottom (d > 0). */
    fun <T> sendTo(l: List<T>, key: T, d: Int): List<T> {
        if (key !in l) return l
        val r = l.filter { it != key }
        return if (d < 0) listOf(key) + r else r + key
    }
}
