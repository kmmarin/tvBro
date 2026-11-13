// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

/**
 * The parental-lock rules every play path goes through (live, guide, catch-up, search, the "play" launch extra,
 * reminders, recordings, downloads, My list, Continue watching), kept apart from the UI so they can be unit-tested.
 * [unlocked] holds the group / category names and channel keys the PIN was entered for in this session.
 * Without a PIN nothing is locked (a lock cannot be set without one).
 */
object ParentalLock {
    /**
     * The wrong-PIN limiter (shared [PinGate]: after 5 wrong PINs every try waits 30 s, longer later). One per process,
     * not per screen: the "play" launch extra opens a new activity (and a new AppModel) each time, and a fresh
     * limiter per activity would give 5 more tries per launch. In memory like the desktop's: ending the process
     * (force stop) starts over.
     */
    val gate = PinGate()

    /** A channel of a locked group, or a single locked channel, not unlocked yet. */
    fun channel(p: Prefs, unlocked: Set<String>, group: String, key: String): Boolean =
        p.pinHash.isNotEmpty() && ((group in p.locked && group !in unlocked) || (key in p.lockedCh && key !in unlocked))

    /** A film / show of a locked category not unlocked yet. */
    fun category(p: Prefs, unlocked: Set<String>, cat: String?): Boolean =
        p.pinHash.isNotEmpty() && !cat.isNullOrEmpty() && cat in p.locked && cat !in unlocked

    /** What a right PIN for this channel unlocks for the session (its group and the channel itself). */
    fun unlockChannel(unlocked: Set<String>, group: String, key: String): Set<String> = unlocked + group + key

    /**
     * A recording's channel: by the channel key stored with it, else (recordings of older versions) by its name as
     * recorded (the provider's or the user's own name).
     */
    fun <C> recChannel(chKey: String, name: String, channels: List<C>, keyOf: (C) -> String, namesOf: (C) -> List<String>): C? =
        channels.firstOrNull { chKey.isNotEmpty() && keyOf(it) == chKey } ?: channels.firstOrNull { name in namesOf(it) }

    /** The organisation written to a backup file (shared storage): never the PIN hash. */
    fun forBackup(p: Prefs): Prefs = p.copy(pinHash = "")

    /**
     * Prefs after a restore: the current PIN always stays (a backup never brings in or removes a PIN). Without a PIN
     * on this device the backup's locks are dropped, rather than left in place with no way to remove them.
     */
    fun restored(current: Prefs, backup: Prefs): Prefs {
        val pin = current.pinHash
        return backup.copy(pinHash = pin, locked = if (pin.isEmpty()) emptySet() else backup.locked, lockedCh = if (pin.isEmpty()) emptySet() else backup.lockedCh)
    }

    /** A restore changes the locks, so with a PIN set it asks for it first. */
    fun restoreNeedsPin(current: Prefs): Boolean = current.pinHash.isNotEmpty()
}
