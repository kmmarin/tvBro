// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import org.junit.Assert.*
import org.junit.Test

class ChannelViewTest {
    private fun ch(k: String, g: String, n: Int) = Channel("s|$k", "s", k.uppercase(), null, g, "http://x/$k", null, n, null, 0)
    private val chans = listOf(ch("a", "News", 101), ch("b", "Sport", 201), ch("c", "News", 102), ch("d", "Kids", 301))

    @Test fun hiddenRenamedAndOrdered() {
        val p = Prefs(hiddenCh = setOf("s|b"), names = mapOf("s|c" to "My C"), order = listOf("s|d", "s|c"))
        assertEquals(listOf("D", "My C", "A"), ChannelView.shown(chans, p).map { it.name })
    }

    @Test fun lockedGroupNeedsUnlock() {
        val p = Prefs(locked = setOf("Kids"))
        assertFalse(ChannelView.shown(chans, p).any { it.group == "Kids" })
        assertTrue(ChannelView.shown(chans, p, unlocked = setOf("Kids")).any { it.group == "Kids" })
    }

    @Test fun groupsInProviderOrderWithUserOrderFirst() {
        assertEquals(listOf(ChannelView.FAV, ChannelView.ALL, "News", "Sport", "Kids"), ChannelView.groups(chans, Prefs()))
        val p = Prefs(groupOrder = listOf("Kids"), hiddenGroups = setOf("Sport"), custom = mapOf("Mine" to listOf("s|d")))
        assertEquals(listOf(ChannelView.FAV, ChannelView.ALL, "Mine", "Kids", "News"), ChannelView.groups(chans, p))
    }

    @Test fun numberModes() {
        val shown = ChannelView.shown(chans, Prefs())
        val news = ChannelView.filter(shown, "News", Prefs(), emptyList(), ChannelView.NUM_PROVIDER)
        assertEquals(listOf(101, 102), news.map { it.number })
        val per = ChannelView.filter(shown, "News", Prefs(), emptyList(), ChannelView.NUM_PER_GROUP)
        assertEquals(listOf(1, 2), per.map { it.number })
        assertEquals("C", ChannelView.byNumber(per, 2)?.name)
    }

    @Test fun favouritesAndOwnGroups() {
        val shown = ChannelView.shown(chans, Prefs())
        assertEquals(listOf("B"), ChannelView.filter(shown, ChannelView.FAV, Prefs(), listOf("s|b"), 0).map { it.name })
        val p = Prefs(custom = mapOf("Mine" to listOf("s|d", "s|a")))
        assertEquals(listOf("D", "A"), ChannelView.filter(shown, "Mine", p, emptyList(), 0).map { it.name }) // own order
    }

    @Test fun moveUpDownAndEnds() {
        val keys = chans.map { it.key }
        assertEquals(listOf("s|b", "s|a", "s|c", "s|d"), ChannelView.moveChannel(emptyList(), keys, "s|b", -1))
        assertEquals(keys, ChannelView.moveChannel(emptyList(), keys, "s|a", -1)) // already first
        assertEquals(keys, ChannelView.move(keys, "nope", 1))
        val groups = ChannelView.groups(chans, Prefs())
        assertEquals(listOf("Sport", "News", "Kids"), ChannelView.moveGroup(groups, Prefs(), "Sport", -1))
    }
}
