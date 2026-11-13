// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import org.junit.Assert.*
import org.junit.Test

/** https first for a server typed without a scheme; the old default (http) stays for saved servers. */
class XtreamServerTest {
    @Test fun candidatesTryHttpsFirst() {
        assertEquals(listOf("https://tv.example:8080", "http://tv.example:8080"), Xtream.serverCandidates(" tv.example:8080/ "))
        assertEquals(listOf("http://tv.example"), Xtream.serverCandidates("http://tv.example"))
        assertEquals(listOf("HTTPS://tv.example"), Xtream.serverCandidates("HTTPS://tv.example/"))
        assertEquals(emptyList<String>(), Xtream.serverCandidates("  "))
    }

    @Test fun pickServerKeepsTheFirstThatAnswersElsePlainHttp() {
        val tried = ArrayList<String>()
        assertEquals("https://a.example", Xtream.pickServer("a.example") { tried += it; true })
        assertEquals(listOf("https://a.example"), tried)
        assertEquals("http://b.example", Xtream.pickServer("b.example") { it.startsWith("http://") })
        assertEquals("http://c.example", Xtream.pickServer("c.example") { false })
        // a scheme the user typed is never changed and never probed
        assertEquals("http://d.example", Xtream.pickServer("http://d.example") { fail("no probe"); true })
    }

    @Test fun cleartextHint() {
        assertTrue(Xtream.isCleartext("tv.example")) // saved without a scheme = http, as before
        assertTrue(Xtream.isCleartext("http://tv.example"))
        assertFalse(Xtream.isCleartext("https://tv.example"))
        assertEquals("http://tv.example", Xtream.normalizeServer("tv.example/"))
    }

    @Test fun authUrlHasNoAction() {
        assertEquals("https://t.example/player_api.php?username=u+1&password=p%26w", Xtream.authUrl("https://t.example", "u 1", "p&w"))
    }
}
