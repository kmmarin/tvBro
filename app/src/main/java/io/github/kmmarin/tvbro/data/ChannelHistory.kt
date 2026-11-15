// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

/** The live channels watched lately (channel keys): newest first, each once, at most [MAX]. */
object ChannelHistory {
    const val MAX = 20

    fun add(list: List<String>, key: String): List<String> =
        if (list.firstOrNull() == key) list else (listOf(key) + list.filter { it != key }).take(MAX)

    fun encode(list: List<String>): String = list.joinToString("\n")

    fun decode(s: String?): List<String> =
        s.orEmpty().split('\n').filter { it.isNotEmpty() }.distinct().take(MAX)
}
