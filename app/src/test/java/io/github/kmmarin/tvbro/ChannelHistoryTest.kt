// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import io.github.kmmarin.tvbro.data.ChannelHistory

class ChannelHistoryTest {
    @Test fun newestFirstAndDeduped() {
        var l = emptyList<String>()
        for (k in listOf("a", "b", "c", "a")) l = ChannelHistory.add(l, k)
        assertEquals(listOf("a", "c", "b"), l)
    }

    @Test fun sameChannelAgainKeepsTheList() {
        val l = listOf("a", "b")
        assertSame(l, ChannelHistory.add(l, "a"))
    }

    @Test fun cappedAtMax() {
        var l = emptyList<String>()
        for (i in 0 until ChannelHistory.MAX + 5) l = ChannelHistory.add(l, "k$i")
        assertEquals(ChannelHistory.MAX, l.size)
        assertEquals("k${ChannelHistory.MAX + 4}", l.first())
    }

    @Test fun encodeDecodeRoundTrip() {
        val l = listOf("src1|ch 1", "src2|ch:2")
        assertEquals(l, ChannelHistory.decode(ChannelHistory.encode(l)))
        assertEquals(emptyList<String>(), ChannelHistory.decode(null))
        assertEquals(listOf("a", "b"), ChannelHistory.decode("a\n\nb\na"))
    }
}
