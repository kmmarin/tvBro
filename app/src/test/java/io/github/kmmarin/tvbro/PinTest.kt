// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.kmmarin.tvbro.data.Pin
import io.github.kmmarin.tvbro.data.PinGate

/** The parental PIN as Android uses it (shared Pin / PinGate, taken from the desktop branch). */
class PinTest {
    @Test fun saltedAndVerifiable() {
        val a = Pin.hash("1234", iterations = 1000)
        val b = Pin.hash("1234", iterations = 1000)
        assertNotEquals(a, b) // salted
        assertTrue(Pin.verify("1234", a))
        assertFalse(Pin.verify("1235", a))
        assertTrue(a.startsWith("pbkdf2_sha256$"))
    }

    @Test fun oldUnsaltedHashStillAccepted() {
        assertTrue(Pin.verify("4321", Pin.legacy("4321")))
        assertFalse(Pin.verify("4320", Pin.legacy("4321")))
    }

    @Test fun wrongTriesWait() {
        var now = 0L
        val g = PinGate { now }
        val h = Pin.hash("2468", iterations = 1000)
        repeat(4) { assertTrue(g.check(h, "0000") is PinGate.Result.Wrong) }
        val fifth = g.check(h, "0000") as PinGate.Result.Wrong
        assertEquals(30, fifth.waitSec)
        assertTrue(g.check(h, "2468") is PinGate.Result.Wait) // even the right PIN waits
        now += 31_000
        assertTrue(g.check(h, "2468") is PinGate.Result.Ok)
    }
}
