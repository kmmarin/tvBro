// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.kmmarin.tvbro.data.GroupOrder

class GroupOrderTest {
    @Test fun ppvMatchesWholeWordAnyCase() {
        assertTrue(GroupOrder.isPpv("PPV Events | 1"))
        assertTrue(GroupOrder.isPpv("UK: ppv"))
        assertTrue(GroupOrder.isPpv("Sports (PPV)"))
        assertFalse(GroupOrder.isPpv("PPVX Movies"))
        assertFalse(GroupOrder.isPpv("Happv"))
    }

    @Test fun ppvGoesLastByDefaultKeepingProviderOrder() {
        val p = listOf("PPV Events | 1", "News", "4K / UHD", "PPV Events | 2", "Kids")
        assertEquals(listOf("News", "4K / UHD", "Kids", "PPV Events | 1", "PPV Events | 2"), GroupOrder.order(p, emptyList()))
    }

    @Test fun savedOrderWins() {
        val p = listOf("PPV Events | 1", "News", "Kids")
        // the user sent PPV to the top: it stays there; groups new to the saved order follow
        assertEquals(listOf("PPV Events | 1", "Kids", "News"), GroupOrder.order(p, listOf("PPV Events | 1", "Kids")))
    }

    @Test fun sendToTopAndBottom() {
        val l = listOf("a", "b", "c", "d")
        assertEquals(listOf("c", "a", "b", "d"), GroupOrder.sendTo(l, "c", -1))
        assertEquals(listOf("a", "b", "d", "c"), GroupOrder.sendTo(l, "c", 1))
        assertEquals(l, GroupOrder.sendTo(l, "x", 1))
    }
}
