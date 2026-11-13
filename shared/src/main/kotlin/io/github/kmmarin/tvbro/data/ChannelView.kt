// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

/**
 * The user's organisation of channels applied to the provider's list (platform-neutral, same rules as the Android
 * AppModel): hidden channels and groups, own names, own order, own groups, number modes and Move up / down.
 */
object ChannelView {
    const val ALL = "All channels"
    const val FAV = "My list"
    const val NUM_PROVIDER = 0
    const val NUM_PER_GROUP = 1

    /** Visible channels with the user's names, in the user's order (unordered ones keep provider order, after). */
    fun shown(channels: List<Channel>, p: Prefs, unlocked: Set<String> = emptySet()): List<Channel> {
        val pos = p.order.withIndex().associate { it.value to it.index }
        val l = channels.filter { it.key !in p.hiddenCh && it.group !in p.hiddenGroups && (it.group !in p.locked || it.group in unlocked) }
            .map { c -> p.names[c.key]?.let { c.copy(name = it) } ?: c }
        return if (pos.isEmpty()) l else l.sortedBy { pos[it.key] ?: Int.MAX_VALUE } // stable: provider order within ties
    }

    /** Group list: My list, All, own groups, then provider groups (user order first, the rest in provider order). */
    fun groups(channels: List<Channel>, p: Prefs): List<String> {
        val g = channels.filter { it.group !in p.hiddenGroups }.map { it.group }.distinct()
        return listOf(FAV, ALL) + p.custom.keys.toList() + p.groupOrder.filter { it in g } + g.filter { it !in p.groupOrder }
    }

    fun label(g: String, p: Prefs): String = (p.groupNames[g] ?: g) + if (g in p.locked) " (locked)" else ""

    /** Channels of [group]; with [NUM_PER_GROUP] they are numbered 1, 2, 3 in the group. */
    fun filter(shown: List<Channel>, group: String, p: Prefs, favourites: Collection<String>, numberMode: Int): List<Channel> {
        val fav = favourites.toHashSet()
        val l = when (group) {
            ALL -> shown
            FAV -> shown.filter { it.key in fav }
            else -> if (group in p.custom) { val ks = p.custom[group].orEmpty(); val by = shown.associateBy { it.key }; ks.mapNotNull { by[it] } }
                else shown.filter { it.group == group }
        }
        return if (numberMode == NUM_PER_GROUP) l.mapIndexed { i, c -> c.copy(number = i + 1) } else l
    }

    /** Swaps [key] with its neighbour in direction [d] (-1 up, +1 down); unchanged at either end or when absent. */
    fun move(list: List<String>, key: String, d: Int): List<String> {
        val l = list.toMutableList(); val i = l.indexOf(key); val j = i + d
        if (i < 0 || j < 0 || j >= l.size) return list
        l[i] = l[j].also { l[j] = l[i] }
        return l
    }

    /** New channel order after moving [key] within the visible list [shownKeys] (keys not yet ordered are appended). */
    fun moveChannel(order: List<String>, shownKeys: List<String>, key: String, d: Int): List<String> {
        val have = order.toHashSet()
        return move(order + shownKeys.filter { it !in have }, key, d)
    }

    /** New group order after moving the provider group [g] among the plain (non-special, non-custom) groups. */
    fun moveGroup(groups: List<String>, p: Prefs, g: String, d: Int): List<String> =
        move(groups.filter { it != ALL && it != FAV && it !in p.custom }, g, d)

    /** Channel that a typed number selects: the provider number (or position with per-group numbers). */
    fun byNumber(list: List<Channel>, n: Int): Channel? = list.firstOrNull { it.number == n }
}
