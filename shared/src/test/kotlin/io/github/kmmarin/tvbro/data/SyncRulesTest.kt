// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import org.junit.Assert.*
import org.junit.Test

class SyncRulesTest {
    @Test fun keepOldDataOnEmptyOrFailed() {
        assertFalse(SyncRules.accept(0))
        assertFalse(SyncRules.accept(500, java.io.IOException("HTTP 503")))
        assertTrue(SyncRules.accept(1))
    }

    @Test fun backoff() {
        assertEquals(listOf(0, 1, 5, 15, 15), (0..4).map { SyncRules.retryDelayMin(it) })
        val t = 10_000_000_000L
        // failed once 30 s ago: not yet; after a minute: due again
        assertFalse(SyncRules.due(t - 3_600_000, t - 30_000, 1, 12, t))
        assertTrue(SyncRules.due(t - 3_600_000, t - 61_000, 1, 12, t))
        assertFalse(SyncRules.due(t - 3_600_000, t - 4 * 60_000, 2, 12, t))
        assertTrue(SyncRules.due(t - 3_600_000, t - 16 * 60_000, 3, 12, t))
        // healthy: only after the interval
        assertFalse(SyncRules.due(t - 3_600_000, 0, 0, 12, t))
        assertTrue(SyncRules.due(t - 13 * 3_600_000L, 0, 0, 12, t))
        assertTrue(SyncRules.due(0, 0, 0, 12, t))
    }
}
