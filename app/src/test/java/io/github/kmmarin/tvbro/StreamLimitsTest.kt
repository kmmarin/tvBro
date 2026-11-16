// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.kmmarin.tvbro.data.Source
import io.github.kmmarin.tvbro.data.StreamLimit
import io.github.kmmarin.tvbro.data.StreamRules

class StreamLimitsTest {
    @Test fun accountFromPlayerApi() {
        val a = StreamRules.parseAccount("""{"user_info":{"username":"u1","password":"secret","message":"","auth":1,"status":"Active",
            "exp_date":"1832716800","is_trial":"0","active_cons":"0","created_at":"1777507200","max_connections":"3",
            "allowed_output_formats":["m3u8","ts"]},"server_info":{"url":"x"}}""", now = 5)!!
        assertEquals(3, a.reported); assertEquals(0, a.active); assertEquals("Active", a.status)
        assertEquals(1832716800L, a.exp); assertEquals(1777507200L, a.created); assertEquals(false, a.trial)
        assertEquals("u1", a.username); assertEquals(5L, a.checkedAt)
        assertFalse("the password is never kept", a.toString().contains("secret"))
        // numbers as numbers, trial as "1", no expiry (null / blank): unlimited
        val b = StreamRules.parseAccount("""{"user_info":{"max_connections":4,"active_cons":2,"is_trial":"1","exp_date":null,"created_at":""}}""")!!
        assertEquals(4, b.reported); assertEquals(2, b.active); assertEquals(true, b.trial); assertNull(b.exp); assertNull(b.created)
    }

    @Test fun limitNotReported() {
        for (v in listOf("\"\"", "\"0\"", "0", "null", "\"abc\"")) assertNull(v, StreamRules.parseAccount("""{"user_info":{"max_connections":$v}}""")!!.reported)
        assertNull(StreamRules.parseAccount("""[]"""))
        assertNull(StreamRules.parseAccount("""{"user_info":"x"}"""))
        assertNull(StreamRules.parseAccount("not json"))
        assertNull(StreamRules.parseAccount("[".repeat(20000)))
    }

    @Test fun limitIsTheProvidersThenTheUsersThenOne() {
        assertEquals(1, StreamRules.limit(null))
        assertEquals(3, StreamRules.limit(StreamLimit(reported = 3, user = 1)))
        assertEquals(2, StreamRules.limit(StreamLimit(user = 2)))
        assertEquals("3 (from provider)", StreamRules.label(StreamLimit(reported = 3)))
        assertEquals("not reported, set to 2", StreamRules.label(StreamLimit(user = 2)))
        assertEquals("not reported, set to 1", StreamRules.label(null))
        // a fresh answer keeps the user's own number
        assertEquals(StreamLimit(reported = 2, user = 3, checkedAt = 9), StreamRules.merge(StreamLimit(reported = 1, user = 3), StreamLimit(reported = 2, checkedAt = 9)))
    }

    @Test fun recordingsTakeTheirProvidersStreams() {
        val src = StreamRules.recSource("a|123", "Ch") { null }
        assertEquals("a", src)
        assertEquals("b", StreamRules.recSource("", "Old") { if (it == "Old") "b" else null }) // older recordings: by name
        assertNull(StreamRules.recSource("", "Gone") { null })
        assertEquals(2, StreamRules.free(3, "a", listOf("a"))) // a recording of the provider: 2 left
        assertEquals(3, StreamRules.free(3, "a", listOf("b"))) // another provider's recording: all 3
        assertEquals(2, StreamRules.free(3, "a", listOf(null))) // unknown provider: counted everywhere
        assertEquals(0, StreamRules.free(1, "a", listOf("a", "a")))
    }

    @Test fun oldSettingMovesToTheSources() {
        val old = mapOf("a" to StreamLimit(reported = 3), "b" to StreamLimit(user = 1))
        val m = StreamRules.migrate(old, listOf("a", "b", "c"), 2)
        assertEquals(StreamLimit(reported = 3, user = 2), m["a"]) // the provider's own number still wins (limit 3)
        assertEquals(3, StreamRules.limit(m["a"]))
        assertEquals(1, m["b"]!!.user) // already set: kept
        assertEquals(2, StreamRules.limit(m["c"]))
        assertEquals(old, StreamRules.migrate(old, listOf("a", "c"), 1)) // the default: nothing to move
        assertEquals(old, StreamRules.migrate(old, listOf("a", "c"), null))
        assertEquals(StreamRules.USER_MAX, StreamRules.migrate(emptyMap(), listOf("x"), 9)["x"]!!.user)
    }

    @Test fun expiryWarning() {
        val now = 1_800_000_000_000L
        val day = 86_400L
        assertNull(StreamRules.expiryWarning(null, now))
        assertNull(StreamRules.expiryWarning(now / 1000 + 30 * day, now))
        assertNull(StreamRules.expiryWarning(now / 1000 + 14 * day, now))
        assertEquals("Expires in 13 days", StreamRules.expiryWarning(now / 1000 + 13 * day + 60, now))
        assertEquals("Expires in 1 day", StreamRules.expiryWarning(now / 1000 + day + 60, now))
        assertEquals("Expires today", StreamRules.expiryWarning(now / 1000 + 3600, now))
        assertEquals("Expired", StreamRules.expiryWarning(now / 1000 - 1, now))
        assertTrue(StreamRules.statusOk("Active")); assertFalse(StreamRules.statusOk("Expired")); assertFalse(StreamRules.statusOk(null))
    }

    @Test fun datesOutOfRangeAreIgnored() {
        // a provider (or anyone in the middle on plain http) sending absurd dates must not make the popup lie
        val a = StreamRules.parseAccount("""{"user_info":{"max_connections":"2","exp_date":"1000000000000000","created_at":"10000000000000000"}}""")!!
        assertNull(a.exp); assertNull(a.created); assertEquals(2, a.reported)
        val now = 1_800_000_000_000L
        assertNull(StreamRules.expiryWarning(1_000_000_000_000_000L, now))
        assertNull(StreamRules.expiryWarning(Long.MAX_VALUE, now))
        assertEquals(1832716800L, StreamRules.parseAccount("""{"user_info":{"exp_date":"1832716800"}}""")!!.exp)
    }

    @Test fun downloadsCountAgainstTheirProvider() {
        val srcs = listOf(
            Source("a", "A", "xtream", server = "http://a.example:8080/", user = "u1", pass = "p1"),
            Source("b", "B", "xtream", server = "http://b.example", user = "u2", pass = "p2"),
            Source("m", "M", "m3u", url = "http://a.example:8080/list.m3u"),
        )
        assertEquals("a", StreamRules.dlSource("http://a.example:8080/movie/u1/p1/123.mkv", srcs))
        assertEquals("b", StreamRules.dlSource("http://b.example/series/u2/p2/9.mp4", srcs))
        assertNull("unknown source", StreamRules.dlSource("http://c.example/movie/u9/p/1.mkv", srcs))
        assertNull("host prefix only", StreamRules.dlSource("http://a.example:8080.evil/movie/u1/p1/1.mkv", srcs))
        // limit 2, one download from a: one stream left for a, b untouched; an unknown download takes one from both
        assertEquals(1, StreamRules.free(2, "a", listOf("a")))
        assertEquals(2, StreamRules.free(2, "b", listOf("a")))
        assertEquals(1, StreamRules.free(2, "b", listOf(null)))
    }
}
