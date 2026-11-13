// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.kmmarin.tvbro.data.Backup
import io.github.kmmarin.tvbro.data.BackupIo
import io.github.kmmarin.tvbro.data.ParentalLock
import io.github.kmmarin.tvbro.data.Pin
import io.github.kmmarin.tvbro.data.PinGate
import io.github.kmmarin.tvbro.data.Prefs

/** Audit M2: the lock rules behind every play path, backup and restore. */
class ParentalLockTest {
    private val pin = Pin.hash("2468", iterations = 1000)
    private val p = Prefs(locked = setOf("Adult", "Horror"), lockedCh = setOf("s|9"), pinHash = pin)

    @Test fun channelsOfLockedGroupsAndLockedChannels() {
        assertTrue(ParentalLock.channel(p, emptySet(), "Adult", "s|1"))
        assertTrue(ParentalLock.channel(p, emptySet(), "News", "s|9"))
        assertFalse(ParentalLock.channel(p, emptySet(), "News", "s|2"))
        // without a PIN nothing is locked
        assertFalse(ParentalLock.channel(p.copy(pinHash = ""), emptySet(), "Adult", "s|1"))
    }

    @Test fun rightPinUnlocksTheGroupAndTheChannel() {
        // a single locked channel must open after the PIN (it used to ask again for ever)
        val u = ParentalLock.unlockChannel(emptySet(), "News", "s|9")
        assertFalse(ParentalLock.channel(p, u, "News", "s|9"))
        val u2 = ParentalLock.unlockChannel(emptySet(), "Adult", "s|1")
        assertFalse(ParentalLock.channel(p, u2, "Adult", "s|5")) // the whole group for the session
        assertTrue(ParentalLock.channel(p, u2, "News", "s|9"))  // not other locked channels
    }

    @Test fun filmsAndShowsOfLockedCategories() {
        assertTrue(ParentalLock.category(p, emptySet(), "Horror"))
        assertFalse(ParentalLock.category(p, setOf("Horror"), "Horror"))
        assertFalse(ParentalLock.category(p, emptySet(), "Comedy"))
        assertFalse(ParentalLock.category(p, emptySet(), ""))     // old download without a category
        assertFalse(ParentalLock.category(p, emptySet(), null))
        assertFalse(ParentalLock.category(p.copy(pinHash = ""), emptySet(), "Horror"))
    }

    data class Ch(val key: String, val name: String, val own: String? = null)

    @Test fun recordingsFindTheirChannel() {
        val chs = listOf(Ch("s|1", "Film One"), Ch("s|2", "Kids", own = "My kids"), Ch("s|3", "Film One"))
        val find = { k: String, n: String -> ParentalLock.recChannel(k, n, chs, { it.key }, { listOfNotNull(it.name, it.own) }) }
        assertEquals("s|3", find("s|3", "Film One")?.key)   // by key first
        assertEquals("s|2", find("", "My kids")?.key)       // old recordings: by the name as recorded
        assertEquals("s|1", find("gone", "Film One")?.key)
        assertNull(find("", "Unknown"))
    }

    @Test fun backupHoldsNoPinHash() {
        val b = Backup(prefs = ParentalLock.forBackup(p))
        val text = BackupIo.encode(b)
        assertFalse(text.contains(pin))
        assertFalse(text.contains("pbkdf2"))
        assertEquals("", BackupIo.decode(text).prefs.pinHash)
        assertEquals(p.locked, BackupIo.decode(text).prefs.locked)
    }

    @Test fun restoreKeepsTheCurrentPinAndAsksForIt() {
        val fromFile = Prefs(locked = setOf("Kids"), pinHash = Pin.hash("0000", iterations = 1000), hiddenCh = setOf("x"))
        assertTrue(ParentalLock.restoreNeedsPin(p))
        val r = ParentalLock.restored(p, fromFile)
        assertEquals(pin, r.pinHash)           // a backup never brings in a PIN
        assertEquals(setOf("Kids"), r.locked)
        assertEquals(setOf("x"), r.hiddenCh)
        // no PIN here: no PIN prompt, and the backup's locks are dropped (they could never be removed)
        val none = Prefs()
        assertFalse(ParentalLock.restoreNeedsPin(none))
        val r2 = ParentalLock.restored(none, fromFile.copy(lockedCh = setOf("s|1")))
        assertEquals("", r2.pinHash); assertTrue(r2.locked.isEmpty()); assertTrue(r2.lockedCh.isEmpty())
    }

    @Test fun wrongPinWaitsGrowAndAreSharedByEveryPath() {
        var now = 0L
        val g = PinGate { now }
        repeat(4) { assertEquals(0, (g.check(pin, "1111") as PinGate.Result.Wrong).waitSec) }
        assertEquals(30, (g.check(pin, "1111") as PinGate.Result.Wrong).waitSec)
        assertEquals(30, g.waitSec())
        assertTrue(g.check(pin, "1111") is PinGate.Result.Wait) // tries during the wait do not count
        for (i in 6..10) { now += 30_000; assertTrue(g.check(pin, "1111") is PinGate.Result.Wrong) }
        assertEquals(60, g.waitSec()) // after 10 wrong PINs
        now += 60_000
        assertTrue(g.check(pin, "2468") is PinGate.Result.Ok)
        assertEquals(0, g.waitSec())
    }
}
