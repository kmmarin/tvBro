// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import org.junit.Assert.*
import org.junit.Test
import io.github.kmmarin.tvbro.data.*

class SmbSetupTest {
    @Test fun defaults() {
        val c = SmbConfig()
        assertEquals("tvBro", c.path); assertFalse(c.ok)
        assertEquals("smb://nas/rec/tvBro/", c.copy(host = "nas", share = "rec").dirUrl)
    }

    @Test fun normalizeSplitsPastedAddresses() {
        assertEquals(SmbConfig("nas", "rec", "tv/new", "", "", ""), SmbSetup.normalize(SmbConfig(host = " \\\\nas\\rec\\tv\\new ", path = "")))
        assertEquals(SmbConfig("nas", "rec", "tvBro", "", "", ""), SmbSetup.normalize(SmbConfig(host = "smb://nas/rec/")))
        assertEquals(SmbConfig("10.0.0.5", "rec", "x", "", "", ""), SmbSetup.normalize(SmbConfig(host = "//10.0.0.5/rec", path = "/x/")))
        // a typed share wins over the one in the server field
        assertEquals("mine", SmbSetup.normalize(SmbConfig(host = "nas\\rec", share = "mine")).share)
        assertEquals("nas", SmbSetup.normalize(SmbConfig(host = "nas\\rec", share = "mine")).host)
        assertEquals("rec", SmbSetup.normalize(SmbConfig(host = "nas", share = " /rec\\ ")).share)
    }

    @Test fun normalizeUserAndKeepsPassword() {
        val n = SmbSetup.normalize(SmbConfig(host = "nas", share = "r", user = " OFFICE\\ann ", pass = " p w "))
        assertEquals("ann", n.user); assertEquals("OFFICE", n.domain); assertEquals(" p w ", n.pass)
        assertEquals("WG", SmbSetup.normalize(SmbConfig(user = "OFFICE\\ann", domain = "WG")).domain)
        assertEquals("ann@corp.example", SmbSetup.normalize(SmbConfig(user = "ann@corp.example")).user)
    }

    @Test fun infoNeverHoldsThePassword() {
        val j = SmbSetup.info(SmbConfig("nas", "rec", "tvBro", "ann", "s3cret-pw", "WG"))
        assertFalse(j.contains("s3cret-pw"))
        assertTrue(j.contains("\"haspass\":true")); assertTrue(j.contains("\"host\":\"nas\""))
        assertTrue(SmbSetup.info(SmbConfig()).contains("\"haspass\":false"))
    }

    @Test fun fromPhone() {
        val cur = SmbConfig("old", "r", "tvBro", "ann", "kept", "")
        val (a, e) = SmbSetup.fromPhone(mapOf("host" to "nas", "share" to "rec", "path" to "", "user" to "bob", "pass" to ""), cur)
        assertNull(e); assertEquals(SmbConfig("nas", "rec", "", "bob", "kept", ""), a) // empty password keeps the one on the TV
        assertEquals("new", SmbSetup.fromPhone(mapOf("host" to "nas", "share" to "rec", "pass" to "new"), cur).first!!.pass)
        val (b, e2) = SmbSetup.fromPhone(mapOf("host" to "nas", "share" to ""), cur)
        assertNull(b); assertNotNull(e2)
    }

    @Test fun sizes() {
        assertEquals("1.2 TB", SmbShare.size(1_319_413_953_331L))
        assertEquals("512 MB", SmbShare.size(512L * 1024 * 1024))
        assertEquals("9.5 GB", SmbShare.size((9.5 * 1024 * 1024 * 1024).toLong()))
        assertEquals("100 bytes", SmbShare.size(100))
    }

    @Test fun describeWalksCauses() {
        assertEquals("server not found", SmbShare.describe(Exception("wrapped", java.net.UnknownHostException("nas"))))
        assertEquals("server did not answer", SmbShare.describe(java.net.SocketTimeoutException("connect timed out")))
        assertEquals("connection refused", SmbShare.describe(java.net.ConnectException("Connection refused")))
        assertEquals("wrong user name or password", SmbShare.describe(Exception("x", Exception("STATUS_LOGON_FAILURE"))))
        assertEquals("share not found", SmbShare.describe(Exception("The network name cannot be found (STATUS_BAD_NETWORK_NAME)")))
        assertTrue(SmbShare.explain(java.net.SocketTimeoutException()).startsWith("server did not answer. Check"))
        assertEquals("IllegalStateException", SmbShare.describe(IllegalStateException("odd")))
    }

    @Test fun checkFailsFastAndClearly() {
        assertFalse(SmbShare.check(SmbConfig()).ok)
        // a closed local port: refused at once, before any SMB traffic
        val port = java.net.ServerSocket(0).use { it.localPort }
        val t0 = System.currentTimeMillis()
        val r = SmbShare.check(SmbConfig("127.0.0.1", "rec", pass = "s3cret-pw"), port)
        assertFalse(r.ok); assertTrue(r.text, r.text.startsWith("Could not connect: connection refused"))
        assertFalse(r.text.contains("s3cret-pw"))
        assertTrue(System.currentTimeMillis() - t0 < 5000)
        assertTrue(SmbShare.check(SmbConfig("no-such-host.invalid", "rec")).text.contains("server not found"))
    }

    /** The phone entry over the same one-time-code link server as Add source. */
    @Test fun phoneLink() {
        var form = SmbConfig(pass = "on-tv")
        val gate = CodeGate("ABC234", System.currentTimeMillis() + 60000)
        var done = 0
        val srv = LinkServer(gate, SmbLinkPage.html(), { SmbSetup.info(form) }, { f -> SmbSetup.fromPhone(f, form).let { (n, e) -> if (n != null) form = n; e } }, { done++ })
        val port = srv.start(java.net.InetAddress.getLoopbackAddress())
        fun post(p: String, b: String): String {
            val c = java.net.URL("http://127.0.0.1:$port$p").openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            c.outputStream.write(b.toByteArray()); return c.inputStream.readBytes().decodeToString()
        }
        try {
            val page = java.net.URL("http://127.0.0.1:$port/?c=ABC234").readText()
            assertTrue(page.contains("color-scheme:dark")); assertTrue(page.contains("type=\"password\""))
            assertTrue(post("/info", "code=WRONG1").contains("Wrong code"))
            assertFalse(post("/info", "code=ABC234").contains("on-tv"))
            assertTrue(post("/submit", "code=abc234&host=nas&share=rec&path=tvBro&user=&pass=p%26w&domain=").contains("\"ok\":true"))
            assertEquals(SmbConfig("nas", "rec", "tvBro", "", "p&w", ""), form)
            assertEquals(1, done)
            assertTrue(post("/submit", "code=ABC234&host=x&share=y").contains("already used"))
        } finally { srv.stop() }
    }
}
