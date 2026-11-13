// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import org.junit.Assert.*
import org.junit.Test
import io.github.kmmarin.tvbro.update.UpdateLogic

class UpdateLogicTest {
    private val repo = "o/r"

    @Test fun versions() {
        assertTrue(UpdateLogic.isNewer("v0.2.0", "0.1.0"))
        assertTrue(UpdateLogic.isNewer("1.0", "0.9.9"))
        assertTrue(UpdateLogic.isNewer("0.1.10", "0.1.9"))
        assertFalse(UpdateLogic.isNewer("0.1.0", "0.1.0"))
        assertFalse(UpdateLogic.isNewer("v0.1.0", "0.2.0"))
        assertFalse(UpdateLogic.isNewer("nightly", "0.1.0"))
        assertFalse(UpdateLogic.isNewer("0.2.0-beta1", "0.2.0"))
    }

    private val sample = """
        {"tag_name":"v0.2.0","draft":false,"prerelease":false,"html_url":"https://github.com/o/r/releases/tag/v0.2.0",
         "body":"Fixes","assets":[
           {"name":"tvbro-0.2.0-debug.apk","size":1,"browser_download_url":"https://github.com/o/r/releases/download/v0.2.0/tvbro-0.2.0-debug.apk"},
           {"name":"tvbro-windows-0.2.0.zip","size":2,"browser_download_url":"https://github.com/o/r/releases/download/v0.2.0/tvbro-windows-0.2.0.zip"},
           {"name":"tvbro-0.2.0.apk","size":3,"digest":"sha256:${"ab".repeat(32)}","browser_download_url":"https://github.com/o/r/releases/download/v0.2.0/tvbro-0.2.0.apk"}]}
    """.trimIndent()

    @Test fun parsesLatest() {
        val r = UpdateLogic.parseLatest(sample, repo)!!
        assertEquals("0.2.0", r.version)
        assertEquals("tvbro-0.2.0.apk", r.apkName)
        assertEquals(3L, r.apkSize)
        assertEquals("ab".repeat(32), r.sha256)
        assertEquals("tvbro-0.2.0.apk", UpdateLogic.fileName(r))
        assertEquals(3L + 1_048_576, UpdateLogic.maxDownloadBytes(r))
    }

    @Test fun skipVersion() {
        assertTrue(UpdateLogic.shouldPrompt("0.2.0", "0.1.0", null))
        assertFalse(UpdateLogic.shouldPrompt("0.2.0", "0.1.0", "0.2.0"))
        assertFalse(UpdateLogic.shouldPrompt("0.2.0", "0.1.0", "v0.2.0"))
        assertTrue(UpdateLogic.shouldPrompt("0.3.0", "0.1.0", "0.2.0")) // a newer release is offered again
        assertFalse(UpdateLogic.shouldPrompt("0.1.0", "0.1.0", null))
    }

    @Test fun checksumsFileAndMismatchRefused() {
        val h = "cd".repeat(32)
        val sums = "${"ef".repeat(32)}  tvbro-windows-x64.zip\n$h  tvbro-0.2.0.apk\n$h *tvbro.apk\n"
        assertEquals(h, UpdateLogic.parseSums(sums, "tvbro-0.2.0.apk"))
        assertEquals(h, UpdateLogic.parseSums(sums, "tvbro.apk"))
        assertNull(UpdateLogic.parseSums(sums, "other.apk"))
        assertTrue(UpdateLogic.hashOk(h, h.uppercase()))
        assertFalse(UpdateLogic.hashOk(h, "ab".repeat(32)))   // mismatch: refused
        assertFalse(UpdateLogic.hashOk(null, h))               // no published hash: refused
        assertFalse(UpdateLogic.hashOk("abc", "abc"))          // malformed: refused
        val withSums = sample.replace("\"assets\":[", "\"assets\":[{\"name\":\"SHA256SUMS.txt\",\"size\":9,\"browser_download_url\":\"https://github.com/o/r/releases/download/v0.2.0/SHA256SUMS.txt\"},")
        assertEquals("https://github.com/o/r/releases/download/v0.2.0/SHA256SUMS.txt", UpdateLogic.parseLatest(withSums, repo)!!.sumsUrl)
    }

    @Test fun signatureMismatchRefused() {
        val a = setOf("aa"); val b = setOf("bb")
        assertTrue(UpdateLogic.certsMatch(a, setOf("aa")))
        assertFalse(UpdateLogic.certsMatch(a, b))
        assertFalse(UpdateLogic.certsMatch(emptySet(), emptySet()))
        assertFalse(UpdateLogic.certsMatch(a, setOf("aa", "bb")))
    }

    @Test fun rejectsPrereleaseNoApkAndForeignUrls() {
        assertNull(UpdateLogic.parseLatest(sample.replace("\"prerelease\":false", "\"prerelease\":true"), repo))
        assertNull(UpdateLogic.parseLatest("""{"tag_name":"v1.0.0","assets":[]}""", repo))
        assertNull(UpdateLogic.parseLatest(sample, "other/repo"))
        val bad = listOf(
            "http://github.com/o/r/releases/download/v1/tvbro.apk",
            "https://evil.example/o/r/releases/download/v1/tvbro.apk",
            "https://github.com/o/r2/releases/download/v1/tvbro.apk",
            "https://github.com/o/r/releases/download/../../x/tvbro.apk",
            "https://github.com/o/r/archive/tvbro.apk",
        )
        for (u in bad) assertFalse(u, UpdateLogic.isAllowedAssetUrl(u, repo))
        assertTrue(UpdateLogic.isAllowedAssetUrl("https://github.com/O/R/releases/download/v1/tvbro.apk", repo))
    }
}
