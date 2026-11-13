// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

/** What a sync of one kind (channels, guide, catalogue) may do with its result. */
object SyncRules {
    /** A sync replaces stored data only when it succeeded and delivered at least one item; otherwise the old data stays. */
    fun accept(items: Int, error: Throwable? = null): Boolean = error == null && items > 0

    /** Retry delays after consecutive failures: 1, 5, then every 15 minutes. */
    fun retryDelayMin(failures: Int): Int = when { failures <= 0 -> 0; failures == 1 -> 1; failures == 2 -> 5; else -> 15 }

    /** Due when never synced, when the interval has passed, or when the last attempt failed and its backoff is over. */
    fun due(lastOkMs: Long, lastErrMs: Long, failures: Int, intervalH: Int, nowMs: Long): Boolean = when {
        failures > 0 && lastErrMs >= lastOkMs -> nowMs - lastErrMs >= retryDelayMin(failures) * 60_000L
        lastOkMs <= 0 -> true
        else -> nowMs - lastOkMs >= intervalH * 3_600_000L
    }
}
