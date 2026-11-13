// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import org.junit.Assert.*
import org.junit.Test

class LiveRecoveryTest {
    private fun ch(key: String, name: String, tvg: String? = null, src: String = "s1", url: String = "http://x/$key") =
        Channel(key, src, name, null, "G", url, tvg, 0, null, 0)

    @Test fun reconnectsThenAlternatesThenRests() {
        val r = LiveRecovery("a")
        val steps = (1..3).map { r.failed() }
        assertEquals(listOf(1000L, 3000L, 6000L), steps.map { (it as LiveRecovery.Step.Retry).delayMs })
        assertEquals("Reconnecting… (2/3)", LiveRecovery.notice(steps[1], "A"))
        assertSame(LiveRecovery.Step.NeedAlts, r.failed())
        r.setAlts(listOf(ch("a", "A"), ch("b", "A HD"), ch("c", "A backup")))
        val alt = r.failed() as LiveRecovery.Step.Alt
        assertEquals("b", alt.alt.key); assertEquals("b", r.key)
        assertEquals("Trying another source: A HD", LiveRecovery.notice(alt, "A"))
        // an alternate gets one reconnect, then the next one
        assertEquals(LiveRecovery.Step.Retry("b", 1000, 1, 1), r.failed())
        assertEquals("c", (r.failed() as LiveRecovery.Step.Alt).alt.key)
        r.failed() // its reconnect
        val rest = r.failed() as LiveRecovery.Step.Rest
        assertEquals(30_000L, rest.delayMs); assertEquals("a", r.key); assertNull(r.alt); assertEquals(1, r.rounds)
        assertTrue(LiveRecovery.notice(rest, "A").startsWith("A is not available"))
        // after the rest: the channel itself again, with its reconnects; the alternates are kept
        assertEquals(LiveRecovery.Step.Retry("a", 1000, 1, 3), r.failed())
    }

    @Test fun noAlternatesRestsAtOnce() {
        val r = LiveRecovery("a")
        repeat(3) { r.failed() }
        assertSame(LiveRecovery.Step.NeedAlts, r.failed())
        r.setAlts(emptyList())
        assertTrue(r.failed() is LiveRecovery.Step.Rest)
    }

    @Test fun healthyStartsWithReconnectsAgain() {
        val r = LiveRecovery("a")
        r.failed(); r.failed()
        r.healthy()
        assertEquals(1, (r.failed() as LiveRecovery.Step.Retry).attempt)
    }

    @Test fun stallWatch() {
        val w = StallWatch(15_000, 20_000)
        var t = 0L
        w.reset(t)
        // starting: nothing plays for 15 s = stalled (once)
        assertEquals(StallWatch.Event.NONE, w.tick(null, false, 14_000))
        assertEquals(StallWatch.Event.STALL, w.tick(null, false, 15_000))
        assertEquals(StallWatch.Event.NONE, w.tick(null, false, 40_000))
        // restarted: plays steadily, healthy after 20 s, then a frozen position stalls after 15 s
        w.reset(50_000); t = 50_000
        var pos = 0.0
        val events = ArrayList<StallWatch.Event>()
        repeat(30) { t += 1000; pos += 1; events += w.tick(pos, false, t) }
        assertEquals(1, events.count { it == StallWatch.Event.HEALTHY })
        repeat(14) { t += 1000; assertEquals(StallWatch.Event.NONE, w.tick(pos, false, t)) }
        t += 1000; assertEquals(StallWatch.Event.STALL, w.tick(pos, false, t))
    }

    @Test fun pausedIsNotAStall() {
        val w = StallWatch(15_000, 20_000)
        w.reset(0)
        w.tick(1.0, false, 1000)
        for (s in 2..60) assertEquals(StallWatch.Event.NONE, w.tick(1.0, true, s * 1000L))
        assertEquals(StallWatch.Event.NONE, w.tick(1.0, false, 61_000))
        assertEquals(StallWatch.Event.STALL, w.tick(1.0, false, 76_000))
    }

    @Test fun names() {
        assertEquals("bbc one", Alternates.normName("BBC One HD"))
        assertEquals("bbc one", Alternates.normName("BBC One (backup)"))
        assertEquals("bbc one", Alternates.normName("BBC ONE [FHD]"))
        assertEquals("bbc one", Alternates.normName("BBC One HD 2"))
        assertEquals("bbc one", Alternates.normName("BBC One (1080p 50fps)"))
        assertEquals("bbc one wales", Alternates.normName("BBC One (Wales)"))
        assertEquals("film4 +1", Alternates.normName("Film4 +1"))
        assertEquals("uk fox", Alternates.normName("UK: Fox HD"))
        assertEquals("tele zuri", Alternates.normName("Tele Züri"))
        assertEquals("channel 5", Alternates.normName("Channel 5"))
        assertEquals("", Alternates.normName("HD"))
    }

    @Test fun alternates() {
        val list = listOf(
            ch("1", "BBC One", "bbc1.uk"), ch("2", "BBC One HD"), ch("3", "BBC One", src = "s2"),
            ch("4", "One BBC", "BBC1.UK", src = "s2"), ch("5", "Film4"), ch("6", "Film4 +1"),
            ch("7", "BBC One backup", url = "http://x/1"), ch("8", "BBC One FHD"),
        )
        val idx = AltIndex(list)
        // same tvg-id first, then the same name (own playlist before the others); the same URL is no alternate
        assertEquals(listOf("4", "2", "8", "3"), idx.alternates("1").map { it.key })
        assertEquals(listOf("2", "3"), idx.alternates("1", usable = { it.key != "4" && it.key != "8" }).map { it.key })
        assertEquals(emptyList<String>(), idx.alternates("5").map { it.key })
        assertEquals(2, idx.alternates("1", limit = 2).size)
        assertEquals(emptyList<Channel>(), idx.alternates("missing"))
    }
}
