// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    /** A release with per-ABI APKs (listed first: the release workflow uploads the dist folder in name order) and the universal one. */
    private val withSplits = sample.replace("\"assets\":[", "\"assets\":[" + listOf("arm64-v8a" to 11, "armeabi-v7a" to 12, "x86_64" to 13).joinToString("") { (abi, size) ->
        """{"name":"abi-$abi-0.2.0.apk","size":$size,"browser_download_url":"https://github.com/o/r/releases/download/v0.2.0/abi-$abi-0.2.0.apk"},"""
    })

    @Test fun picksTheApkForTheDeviceAbi() {
        assertEquals("abi-arm64-v8a-0.2.0.apk", UpdateLogic.parseLatest(withSplits, repo, listOf("arm64-v8a", "armeabi-v7a", "armeabi"))!!.apkName)
        // Fire TV sticks with a 32-bit user space
        assertEquals("abi-armeabi-v7a-0.2.0.apk", UpdateLogic.parseLatest(withSplits, repo, listOf("armeabi-v7a", "armeabi"))!!.let { assertEquals(12L, it.apkSize); it.apkName })
        // the x86 TV emulator runs the ARM build through its translation: the first ABI with an APK
        assertEquals("abi-armeabi-v7a-0.2.0.apk", UpdateLogic.parseLatest(withSplits, repo, listOf("x86", "armeabi-v7a", "armeabi"))!!.apkName)
        assertEquals("abi-x86_64-0.2.0.apk", UpdateLogic.parseLatest(withSplits, repo, listOf("x86_64", "x86", "arm64-v8a"))!!.apkName)
        // unknown ABI, no ABI list, or a release without splits: the universal APK
        assertEquals("tvbro-0.2.0.apk", UpdateLogic.parseLatest(withSplits, repo, listOf("mips"))!!.apkName)
        assertEquals("tvbro-0.2.0.apk", UpdateLogic.parseLatest(withSplits, repo)!!.apkName)
        assertEquals("tvbro-0.2.0.apk", UpdateLogic.parseLatest(sample, repo, listOf("arm64-v8a"))!!.apkName)
        assertEquals("arm64-v8a", UpdateLogic.abiOf("ABI-arm64-v8a-1.2.3.apk"))
        assertNull(UpdateLogic.abiOf("tvbro-arm64-v8a.apk"))
        assertNull(UpdateLogic.abiOf("abi-riscv64-1.0.apk"))
    }

    /** The asset rule of updaters released before the splits: the per-ABI names must never be picked by it. */
    @Test fun olderUpdatersKeepTheUniversalApk() {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(withSplits).jsonObject
        val apks = o["assets"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.filter { it.endsWith(".apk", true) && !it.contains("debug", true) }
        assertEquals("tvbro-0.2.0.apk", apks.firstOrNull { it.contains("tvbro", true) } ?: apks.first())
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
