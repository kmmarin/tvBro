// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import io.github.kmmarin.tvbro.data.Channel
import io.github.kmmarin.tvbro.data.Core
import io.github.kmmarin.tvbro.data.CoreShadow
import io.github.kmmarin.tvbro.data.CoreSwitch
import io.github.kmmarin.tvbro.data.Feed
import io.github.kmmarin.tvbro.data.Fold
import io.github.kmmarin.tvbro.data.Guard
import io.github.kmmarin.tvbro.data.M3uParser
import io.github.kmmarin.tvbro.data.M3uResult
import io.github.kmmarin.tvbro.data.Pin
import io.github.kmmarin.tvbro.data.Programme
import io.github.kmmarin.tvbro.data.SafeIOException
import io.github.kmmarin.tvbro.data.SyncRules
import io.github.kmmarin.tvbro.data.Xtream
import io.github.kmmarin.tvbro.data.XmltvParser
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Comparison harness for the call sites behind [io.github.kmmarin.tvbro.data.CoreSwitch] (docs/RUST_CORE.md, phase 2):
 * the Kotlin implementation and the Rust core (host build, loaded through JNA from `core/target/debug`) run on the
 * same inputs and must give identical output. Inputs: the testenv mock provider (`testenv/www`, when generated), the
 * desktop test playlist, a generated mock-style playlist / Xtream list (PPV, 4K, no-guide groups, catch-up), a
 * hostile-input corpus, and any real playlists in the git-ignored `testdata/private/` (never committed).
 *
 * A difference fails the test unless its case id is in `core-parity-allow.txt` with a reason; an allow-list entry
 * whose case no longer differs fails too, so the list stays exact. A summary goes to build/reports/core-parity.txt.
 */
class CoreParityTest {
    companion object {
        private val root = File(System.getProperty("tvbro.root") ?: "..")
        private val allow: Map<String, String> = CoreParityTest::class.java.getResource("/core-parity-allow.txt")!!.readText().lines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.associate { l -> l.substringBefore('\t').trim() to l.substringAfter('\t').trim() }
        private val accepted = sortedSetOf<String>()
        private val same = sortedSetOf<String>()
        private val counts = sortedMapOf<String, Int>()
        private val log = StringBuilder()

        private fun note(s: String) { synchronized(log) { log.append(s).append('\n') }; println("PARITY $s") }

        @JvmStatic @AfterClass fun summary() {
            note("identical cases: ${same.size}, accepted differences: ${accepted.size} (${accepted.joinToString()})")
            for ((k, v) in counts) note("  $k: $v")
            File("build/reports").mkdirs()
            File("build/reports/core-parity.txt").writeText(log.toString())
            val stale = allow.keys.filter { it in same }
            if (stale.isNotEmpty()) fail("allow-list entries that no longer differ (remove them): $stale")
        }

        private fun count(k: String, n: Int) = synchronized(counts) { counts[k] = (counts[k] ?: 0) + n }
    }

    /** Equal, or a listed difference. */
    private fun <T> compare(case: String, kotlin: List<T>, core: List<T>) {
        if (kotlin == core) { synchronized(same) { same += case }; return }
        val i = (0 until maxOf(kotlin.size, core.size)).first { kotlin.getOrNull(it) != core.getOrNull(it) }
        val msg = "$case: ${kotlin.size} vs ${core.size} items, first difference at $i\n  kotlin: ${kotlin.getOrNull(i)}\n  core:   ${core.getOrNull(i)}"
        if (case in allow) { synchronized(accepted) { accepted += case }; note("accepted $msg\n  reason: ${allow[case]}") }
        else fail(msg)
    }

    /** Every field, strings quoted (so null and the text "null" differ). */
    private fun row(c: Channel) = listOf(c.key, c.sourceId, c.name, c.logo, c.group, c.url, c.tvgId, c.number, c.catchup?.type, c.catchup?.template, c.catchupDays, c.idKey, c.nameKey)
        .joinToString(" | ") { v -> if (v is String) "\"$v\"" else v.toString() }

    // ------------------------------------------------------------------ M3U

    private fun kotlinM3u(b: ByteArray, src: String): M3uResult = b.inputStream().bufferedReader().use { M3uParser.parse(it.lineSequence(), src) }

    private fun m3uCase(case: String, b: ByteArray, src: String = "s1") {
        val k = kotlinM3u(b, src)
        val c = Core.m3u(b, src)
        compare("m3u:$case", k.channels.map(::row) + k.epgUrls.map { "epg $it" }, c.channels.map(::row) + c.epgUrls.map { "epg $it" })
        count("m3u channels compared", k.channels.size)
    }

    private fun mockPlaylist(n: Int): String = buildString {
        append("#EXTM3U url-tvg=\"http://192.0.2.1:8765/epg.xml\"\n")
        val groups = listOf("News", "Sports", "PPV Events", "4K | UHD", "Kids", "Films", "No Guide", "Música", "Ελληνικά", "日本")
        for (i in 0 until n) {
            val g = groups[i % groups.size]
            val cu = if (i % 7 == 0) " catchup=\"append\" catchup-days=\"${i % 5}\" catchup-source=\"?utc={utc}&lutc={lutc}\"" else ""
            val id = if (g == "No Guide") "" else " tvg-id=\"ch$i.example\""
            append("#EXTINF:-1$id tvg-name=\"Ch $i\" tvg-logo=\"http://192.0.2.1:8765/logo/$i.png\" group-title=\"$g\" tvg-chno=\"${i + 1}\"$cu,$g Channel $i${if (i % 11 == 0) ", Extra" else ""}\n")
            append("http://192.0.2.1:8766/live/user/pass/$i.m3u8\n")
        }
    }

    @Test fun m3uFiles() {
        val files = listOfNotNull(
            File(root, "testenv/www/playlist.m3u").takeIf { it.exists() },
            File(root, "desktop/testdata/test.m3u").takeIf { it.exists() },
        ) + (File(root, "testdata/private").listFiles { f -> f.name.endsWith(".m3u", true) || f.name.endsWith(".m3u8", true) }?.sortedBy { it.name } ?: emptyList())
        assertTrue("no playlist files found under $root", files.isNotEmpty())
        // file names of private playlists are not logged
        for ((i, f) in files.withIndex()) m3uCase(if (f.path.contains("private")) "private-$i" else f.name, f.readBytes())
        note("m3u files: ${files.size} (${files.count { it.path.contains("private") }} private)")
        m3uCase("generated-5000", mockPlaylist(5000).toByteArray())
    }

    @Test fun m3uHostile() {
        val cases = linkedMapOf(
            "bom-crlf" to "﻿#EXTM3U x-tvg-url=\"http://a.example/1.xml, http://b.example/2.xml\"\r\n#EXTINF:-1 tvg-name=\"From Attr\",\r\nhttp://example.com/a\r\n",
            "lone-cr" to "#EXTM3U\r#EXTINF:-1,One\rhttp://example.com/1\r#EXTINF:-1,Two\rhttp://example.com/2",
            "comma-names" to "#EXTINF:-1 group-title=\"News, World\",News, Weather, Sport\nhttp://example.com/n\n#EXTINF:-1,,leading comma\nhttp://example.com/l\n",
            "no-attrs-colon" to "#EXTINF:-1 ,  spaced  \nhttp://example.com/s\n#EXTINF:5\nhttp://example.com/nocomma\n",
            "empty-group" to "#EXTINF:-1 group-title=\"\",X\nhttp://example.com/x\n#EXTGRP:Sports\n#EXTINF:-1,Y\nhttp://example.com/y\n#EXTGRP:  \nhttp://example.com/z\n",
            "case-of-tags" to "#extm3u URL-TVG=\"http://example.com/g.xml\"\n#extinf:-1 TVG-ID=\"Up.Example\" Group-Title=\"G\",N\nhttp://example.com/u\n#EXTGRP:Q\n#ExtInf:-1,M\nhttp://example.com/m\n",
            "bare-urls" to "http://example.com/1\nhttp://example.com/2\n# comment\nhttp://example.com/3\n",
            "catchup-kinds" to "#EXTINF:-1 catchup=\"Shift\" tvg-rec=\"2\",A\nhttp://example.com/a\n#EXTINF:-1 catchup-type=\"default\" catchup-days=\"bad\",B\nhttp://example.com/b\n#EXTINF:-1 catchup=\"\" catchup-days=\"4\",C\nhttp://example.com/c\n#EXTINF:-1 catchup=\"xc\" catchup-source=\"  \",D\nhttp://example.com/d\n",
            "numbers" to "#EXTINF:-1 tvg-chno=\"+5\",A\nhttp://e.example/a\n#EXTINF:-1 tvg-chno=\" 7\",B\nhttp://e.example/b\n#EXTINF:-1 tvg-chno=\"007\",C\nhttp://e.example/c\n#EXTINF:-1 tvg-chno=\"2147483648\" channel-number=\"-3\",D\nhttp://e.example/d\n#EXTINF:-1 tvg-chno=\"-\",E\nhttp://e.example/e\n",
            "unicode-digits" to "#EXTINF:-1 tvg-chno=\"٣\",A\nhttp://e.example/a\n",
            "unicode-case" to "#EXTINF:-1 tvg-id=\"İSTANBUL.tr\",ΟΔΟΣ Straße\nhttp://e.example/i\n",
            "unicode-space-trim" to " #EXTINF:-1,A \nhttp://e.example/nbsp \n#EXTINF:-1,B　\n http://e.example/ls\n",
            "kotlin-only-ws" to "#EXTINF:-1,A\u001F\nhttp://e.example/us\u001C\n",
            "nel" to "#EXTINF:-1,A\u0085\nhttp://e.example/nel\u0085\n",
            "quotes-and-junk" to "#EXTINF:-1 tvg-id=\"a\"b\" x=\"1\" y=unquoted group-title=\"G\"junk,Name\nhttp://e.example/q\n#EXTINF:-1 a.b_c-d=\"v\",N2\nhttp://e.example/q2\n",
            "duplicate-attrs" to "#EXTINF:-1 group-title=\"A\" group-title=\"B\" tvg-logo=\"\",N\nhttp://e.example/d\n",
            "extinf-without-url" to "#EXTINF:-1,Lost\n#EXTINF:-1,Found\nhttp://e.example/f\n#EXTINF:-1,Trailing\n",
            "tabs" to "\t#EXTINF:-1\tgroup-title=\"T\",\tTabbed\t\n\thttp://e.example/t\t\n",
        )
        for ((k, v) in cases) m3uCase(k, v.toByteArray())
        val bad = mapOf(
            "bad-utf8-ff" to byteArrayOf(0xff.toByte()),
            "bad-utf8-trunc2" to byteArrayOf(0xc3.toByte()),
            "bad-utf8-trunc3" to byteArrayOf(0xe2.toByte(), 0x82.toByte()),
            "bad-utf8-surrogate" to byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            "bad-utf8-trunc4" to byteArrayOf(0xf0.toByte(), 0x9f.toByte(), 0x98.toByte()),
            "bad-utf8-overlong" to byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
            "bad-utf8-overlong3" to byteArrayOf(0xe0.toByte(), 0x80.toByte(), 0x80.toByte()),
            "bad-utf8-surrogate2" to byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x41),
            "bad-utf8-surrogate-pair" to byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0xbd.toByte(), 0xed.toByte(), 0xb8.toByte(), 0x80.toByte()),
            "bad-utf8-above-max" to byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
            "bad-utf8-f5" to byteArrayOf(0xf5.toByte(), 0x80.toByte()),
            "bad-utf8-lone-cont" to byteArrayOf(0x80.toByte(), 0xbf.toByte(), 0x41),
            "latin1" to "Café".toByteArray(Charsets.ISO_8859_1),
        )
        for ((k, v) in bad) m3uCase(k, "#EXTINF:-1 group-title=\"G".toByteArray() + v + "\",N".toByteArray() + v + "\nhttp://e.example/".toByteArray() + v + "\n".toByteArray())
        // bad bytes at the very end of the file (no newline)
        for ((k, v) in bad) m3uCase("$k-at-eof", "#EXTINF:-1,N\nhttp://e.example/".toByteArray() + v)
        // a line over the core's 1 MB cap (the core cuts it; Kotlin keeps it whole)
        m3uCase("huge-line", ("#EXTINF:-1,Big\nhttp://e.example/" + "a".repeat(1_100_000) + "\n#EXTINF:-1,After\nhttp://e.example/after\n").toByteArray())
        m3uCase("empty", ByteArray(0))
        m3uCase("source-id-with-pipe", "#EXTINF:-1 tvg-id=\"x\",N\nhttp://e.example/p\n".toByteArray(), "a|b")
    }

    // ------------------------------------------------------------------ Xtream live

    private fun xtCase(case: String, cats: String, streams: String) {
        val k = Xtream.toChannels("s1", "http://192.0.2.1:8080/", "user", "pass", Xtream.categories(cats.byteInputStream()), Xtream.liveStreams(streams.byteInputStream()))
        val c = Core.xtreamLive(cats.toByteArray(), streams.toByteArray(), "s1", "http://192.0.2.1:8080/", "user", "pass")
        compare("xtream:$case", k.map(::row), c.map(::row))
        count("xtream channels compared", k.size)
    }

    private val cats = """[{"category_id":"1","category_name":"News"},{"category_id":"2","category_name":"PPV Events"},{"category_id":3,"category_name":"4K | UHD"},{"category_id":"4","category_name":""}]"""

    @Test fun xtreamGenerated() {
        val s = (0 until 3000).joinToString(",", "[", "]") { i ->
            val arch = if (i % 7 == 0) ""","tv_archive":1,"tv_archive_duration":"${i % 6}"""" else ""","tv_archive":0"""
            """{"num":${i + 1},"name":"Channel $i","stream_type":"live","stream_id":${1000 + i},"stream_icon":"http://192.0.2.1/i/$i.png","epg_channel_id":"ch$i.example","category_id":"${1 + i % 5}"$arch}"""
        }
        xtCase("generated-3000", cats, s)
    }

    @Test fun xtreamHostile() {
        val cases = linkedMapOf(
            "strings-and-numbers" to """[{"num":"7","name":"A","stream_id":"11","category_id":1},{"num":8,"name":"B","stream_id":12,"category_id":"2","tv_archive":"1","tv_archive_duration":5}]""",
            "nulls-and-blanks" to """[{"num":null,"name":null,"stream_id":"13","stream_icon":"","epg_channel_id":null,"category_id":null},{"name":"  Spaced  ","stream_id":"14","stream_icon":" ","epg_channel_id":" "}]""",
            "null-strings" to """[{"name":"C","stream_id":"15","stream_icon":"null","epg_channel_id":"null"}]""",
            "missing-id" to """[{"name":"No id"},{"name":"Has id","stream_id":"16"}]""",
            "unknown-category" to """[{"name":"Z","stream_id":"17","category_id":"99"},{"name":"Y","stream_id":"18","category_id":"1"}]""",
            "archive-default-days" to """[{"name":"D","stream_id":"19","tv_archive":"1"},{"name":"E","stream_id":"20","tv_archive":"1","tv_archive_duration":"x"}]""",
            "decimal-num" to """[{"num":1.0,"name":"F","stream_id":"21"}]""",
            "bom" to "﻿" + """[{"name":"G","stream_id":"22"}]""",
            "map-root" to """{"a":{"name":"H","stream_id":"23"},"b":{"name":"I","stream_id":"24"}}""",
            "truncated" to """[{"name":"J","stream_id":"25"},{"name":"K","stream_id":"26"},{"name":"L","str""",
            "not-json" to "<html>error</html>",
            "empty-list" to "[]",
            "extra-fields" to """[{"name":"M","stream_id":"27","added":"1700000000","custom_sid":null,"direct_source":"","thumbnail":"","is_adult":"0"}]""",
        )
        for ((k, v) in cases) xtCase(k, cats, v)
        xtCase("no-categories", "", """[{"name":"N","stream_id":"28","category_id":"1"}]""")
        xtCase("bad-categories", "{oops", """[{"name":"O","stream_id":"29","category_id":"1"}]""")
        // the download check (keep-old-data rule, Repository.xtreamHasStreams): accepted or refused the same way
        val gen = (0 until 50).joinToString(",", "[", "]") { """{"name":"C$it","stream_id":"$it"}""" }
        val checks = cases + mapOf("cut-mid-list" to gen.take(gen.length / 2), "cut-before-end" to gen.dropLast(1), "cut-after-comma" to gen.substring(0, gen.indexOf("},{") + 2), "whole-50" to gen)
        for ((k, v) in checks) {
            val kotlin = SyncRules.accept(try { Xtream.liveStreams(v.byteInputStream()).size } catch (e: Exception) { 0 })
            compare("xtream-check:$k", listOf(kotlin), listOf(SyncRules.accept(Core.xtreamCountStrict(v.toByteArray()))))
        }
        count("xtream download checks", checks.size)
    }

    // ------------------------------------------------------------------ PIN

    @Test fun pinVectors() {
        val pins = listOf("0000", "1234", "2468", "12345678", "99999999", "١٢٣٤")
        val salts = listOf(ByteArray(16), "0123456789abcdef".toByteArray(), ByteArray(16) { (it * 37 + 11).toByte() }, byteArrayOf(1), ByteArray(32) { (255 - it).toByte() })
        var n = 0
        for (pin in pins) for (salt in salts) for (it in listOf(1, 1000)) {
            val k = Pin.hash(pin, salt, it)
            val c = io.github.kmmarin.tvbro.core.pinHashWith(pin, salt, it.toUInt())
            assertEquals("hash $pin/${salt.size}/$it", k, c)
            assertTrue(Core.pinVerify(pin, k) && Pin.verify(pin, c))
            assertTrue(!Core.pinVerify(pin + "0", k) && !Pin.verify(pin + "0", c))
            n++
        }
        // today's Kotlin default (120k) against the core's PIN hash, both ways
        val kd = Pin.hash("2468")
        val cd = Core.pinHash("2468")
        assertTrue(Core.pinVerify("2468", kd) && Pin.verify("2468", cd) && !Pin.verify("2469", cd))
        assertTrue(cd.startsWith("pbkdf2_sha256\$${Pin.ITERATIONS}\$"))
        assertTrue("Kotlin's 120k hash is current for the core", !Core.pinNeedsRehash(kd))
        // the legacy unsalted hash verifies in the core and is marked for re-hashing
        for (pin in pins) {
            val legacy = Pin.legacy(pin)
            assertTrue(Core.pinVerify(pin, legacy) && Core.pinNeedsRehash(legacy) && !Core.pinVerify("5555", legacy))
            n++
        }
        compare("pin:verify-results", pinStored.map { (pin, s) -> "${Pin.verify(pin, s)}" }, pinStored.map { (pin, s) -> "${Core.pinVerify(pin, s)}" })
        compare("pin:long-hash", listOf(Pin.verify("1234", longHash)), listOf(Core.pinVerify("1234", longHash)))
        count("pin vectors", n + pinStored.size)
    }

    private val longHash = "pbkdf2_sha256\$10\$${"00".repeat(16)}\$" + Pin.pbkdf2("1234".toByteArray(), ByteArray(16), 10, 100).joinToString("") { "%02x".format(it) }

    /** Stored values from the field and malformed ones: both sides must answer the same. */
    private val pinStored: List<Pair<String, String>> = listOf(
        "1234" to Pin.legacy("1234"), "1234" to Pin.legacy("1234").uppercase(), "1234" to "", "" to Pin.legacy(""),
        "1234" to "x", "1234" to "pbkdf2_sha256\$0\$00\$00", "1234" to "pbkdf2_sha256\$99999999\$00\$00", "1234" to "md5\$1\$00\$00",
        "1234" to "pbkdf2_sha256\$1\$zz\$00", "1234" to "pbkdf2_sha256\$1\$00\$", "1234" to "pbkdf2_sha256\$1\$\$00", "1234" to "a\$b\$c",
        "1234" to "pbkdf2_sha256\$1000\$0\$00", "1234" to Pin.hash("1234", iterations = 1000).uppercase(),
        "1234" to Pin.hash("1234", iterations = 1000).replace("pbkdf2_sha256\$1000", "pbkdf2_sha256\$+1000"),
        "1234" to Pin.hash("1234", iterations = 1000) + "\$",
    )

    // ------------------------------------------------------------------ URL guard (no Kotlin guard exists: the web's net.py rules)

    @Test fun urlGuardCases() {
        val refused = listOf(
            "http://127.0.0.1:8765/playlist.m3u", "http://127.1.2.3/", "http://localhost:8080/", "http://LOCALHOST/", "http://tv.localhost/",
            "http://[::1]:8080/", "http://0x7f.1/", "http://2130706433/", "http://0177.0.0.1/", "http://[::ffff:127.0.0.1]/",
            "http://169.254.169.254/latest/meta-data", "http://[fe80::1]/", "http://0.0.0.0/", "http://224.0.0.1/", "http://255.255.255.255/",
            "http://[64:ff9b::7f00:1]/", "http://[2002:7f00:1::1]/", "file:///etc/passwd", "ftp://example.com/x", "rtsp://example.com/x",
            "http://example.com:22/", "http://example.com:25/", "http://example.com:1023/", "http://exa mple.com/", "http://", "", "not a url",
        )
        val allowed = listOf(
            "http://example.com/a.m3u", "https://example.com:8443/x", "http://example.com:80/", "HTTP://EXAMPLE.COM/", "http://10.0.2.2:8765/playlist.m3u",
            "http://192.168.1.20:9981/playlist", "http://172.16.0.5:8080/", "http://100.64.0.1:8000/", "http://[fd00::1]:8080/", "http://192.0.2.10:8080/live.m3u",
            "http://8.8.8.8/", "http://[2606:4700:4700::1111]/", "http://example.com:1024/", "http://user:pw@example.com:25461/get.php?username=u&password=p",
        )
        for (u in refused) assertTrue("refuses $u", Core.urlRefusal(u) != null)
        for (u in allowed) assertEquals("allows $u", null, Core.urlRefusal(u))
        val addrRefused = listOf(listOf("127.0.0.1"), listOf("8.8.8.8", "127.0.0.1"), listOf("::1"), listOf("169.254.169.254"), listOf("fe80::1%wlan0"), listOf(), listOf("junk"))
        val addrAllowed = listOf(listOf("8.8.8.8"), listOf("10.0.2.2"), listOf("192.168.1.5", "2a00:1450:4001::1"), listOf("fd00::1%eth0"))
        for (a in addrRefused) assertTrue("refuses $a", Core.addrRefusal(a) != null)
        for (a in addrAllowed) assertEquals("allows $a", null, Core.addrRefusal(a))
        count("url guard cases", refused.size + allowed.size + addrRefused.size + addrAllowed.size)
    }

    /** The guard as installed on the app's OkHttp client: refused before any connection, with a message safe to show. */
    @Test fun urlGuardOnOkHttp() {
        val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).dns(Guard.dns).addInterceptor(Guard).addNetworkInterceptor(Guard).build()
        for (u in listOf("http://127.0.0.1:9/x", "http://[::1]:9/", "http://169.254.169.254/", "http://example.com:22/", "http://localhost:9/")) {
            try { client.newCall(Request.Builder().url(u).build()).execute().close(); fail("not refused: $u") }
            catch (e: SafeIOException) { assertTrue(e.message!!, e.message!!.startsWith("blocked: ") && !e.message!!.contains("127.0.0.1")) }
        }
        // a host name is resolved by the guard's Dns and every answer checked
        try { Guard.dns.lookup("localhost"); fail("localhost resolved") } catch (e: SafeIOException) { assertTrue(e.message!!.startsWith("blocked: ")) }
        count("url guard OkHttp cases", 6)
    }

    // ------------------------------------------------------------------ search folding (groundwork for row 7, not switched yet)

    @Test fun searchFolding() {
        val words = listOf(
            "Café", "CAFÉ crème", "Straße", "Æsir Œuvre", "Øresund", "Đorđe Łódź", "Þór", "ıstanbul İSTANBUL", "naïve—résumé",
            "Ελληνικά ΟΔΟΣ", "日本語のテレビ", "Ünïcödé  spaces\t\nand-dashes", "S01E02: The \"Title\"", "  leading/trailing  ", "ＦＵＬＬＷＩＤＴＨ ２",
            "Ǆemal ǅ", "ﬁnal ﬂow", "x²³", "١٢٣", "🎬 Movie", "", "...", "Mötley Crüe", "Ångström", "Nguyễn", "Ça va", "Dvořák",
        )
        compare("search:fold", words.map { Fold.text(it) }, words.map { io.github.kmmarin.tvbro.core.foldText(it) })
        count("search folding words", words.size)
    }

    // ------------------------------------------------------------------ XMLTV + gzip + epg.db (row EPG)

    // on the local temp drive: SQLite's many small writes can be far slower on the build tree's drive (6x on the build machine)
    private val tmp = File(System.getProperty("java.io.tmpdir"), "tvbro-core-parity").apply { mkdirs() }

    private class KotlinGuide(val rows: Map<String, List<Programme>>?, val digest: CoreShadow.Digest, val parsed: Int, val names: Map<String, String>, val ms: Long, val heapMb: Long)

    /** Peak Java heap in use while [f] runs (sampled every 5 ms), in MB above the level before it. */
    private fun <T> heapPeak(f: () -> T): Pair<T, Long> {
        val rt = Runtime.getRuntime()
        System.gc(); Thread.sleep(50)
        val base = rt.totalMemory() - rt.freeMemory()
        val peak = java.util.concurrent.atomic.AtomicLong(base)
        val run = java.util.concurrent.atomic.AtomicBoolean(true)
        val th = Thread { while (run.get()) { peak.accumulateAndGet(rt.totalMemory() - rt.freeMemory()) { a, b -> maxOf(a, b) }; Thread.sleep(5) } }.apply { isDaemon = true; start() }
        val r = try { f() } finally { run.set(false); th.join() }
        return r to (peak.get() - base) / 1_048_576
    }

    /**
     * Repository.refreshEpg's Kotlin path without the Android database: gzip sniff, the pull parser (as Android's
     * Xml.newPullParser), the resolver, the 160-character description cut, and the store's INSERT OR IGNORE rule
     * (the first programme of a (key, start) pair wins).
     */
    private fun kotlinGuide(file: File, src: String, wanted: Set<String>, minStop: Long, maxStart: Long, keepRows: Boolean): KotlinGuide {
        val t0 = System.nanoTime()
        val (res, heap) = heapPeak {
            val seen = HashSet<String>()
            val rows = if (keepRows) HashMap<String, MutableList<Programme>>() else null
            val d = CoreShadow.Digest()
            var n = 0
            val raw = BufferedInputStream(FileInputStream(file), 32 * 1024)
            raw.mark(2); val b0 = raw.read(); val b1 = raw.read(); raw.reset()
            val names = (if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(raw, 32 * 1024) else raw).use {
                val p = KXmlParser()
                p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                p.setInput(it, null)
                XmltvParser.parse(p, { ch, nm ->
                    val l = ch.lowercase()
                    if (l in wanted) "$src|$l" else nm?.firstNotNullOfOrNull { x -> x.lowercase().takeIf { y -> y in wanted } }?.let { "$src|$it" }
                }, minStop, maxStart) { pr ->
                    val dd = pr.desc
                    val q = if (dd != null && dd.length > 160) pr.copy(desc = dd.take(160)) else pr
                    n++
                    if (seen.add(q.key + '\u0000' + q.start)) { d.add(q); rows?.getOrPut(q.key) { ArrayList() }?.add(q) }
                }
            }
            Triple(rows, d, n) to names.mapValues { (id, v) -> v.firstOrNull() ?: id }
        }
        val (t, names) = res
        return KotlinGuide(t.first, t.second, t.third, names, (System.nanoTime() - t0) / 1_000_000, heap)
    }

    private class CoreGuide(val store: io.github.kmmarin.tvbro.core.CoreEpgDb, val load: io.github.kmmarin.tvbro.core.FfiGuideLoad, val digest: io.github.kmmarin.tvbro.core.FfiDigest, val ms: Long, val heapMb: Long, val walPeak: Long, val walEnd: Long, val dbBytes: Long)

    /** The core's path: one generation loaded from the file (streamed through [Feed]) and committed, like the app. */
    private fun coreGuide(name: String, file: File, src: String, wanted: Set<String>, minStop: Long, maxStart: Long): CoreGuide {
        val db = File(tmp, "epg-$name.db")
        for (x in listOf(db, File(db.path + "-wal"), File(db.path + "-shm"))) x.delete()
        val t0 = System.nanoTime()
        val (r, heap) = heapPeak {
            val store = io.github.kmmarin.tvbro.core.CoreEpgDb.open(db.path)
            val gen = store.beginRefresh(src)
            val load = FileInputStream(file).use { store.loadGuide(src, gen, Feed(it), wanted.toList(), minStop, maxStart) }
            store.commit(src, gen, if (load.programmes == 0uL) 0 else load.minStart, load.maxStop, 0)
            store to load
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val (store, load) = r
        return CoreGuide(store, load, store.digest(src), ms, heap, maxOf(load.walPeak.toLong(), store.walPeak().toLong()), File(db.path + "-wal").length(), db.length())
    }

    private fun progRow(p: Programme) = listOf(p.key, p.start, p.stop, p.title, p.desc, p.sub, p.ep).joinToString(" | ") { v -> if (v is String) "\"$v\"" else v.toString() }

    /** Both paths on one guide file: programme count, every stored row (or the row digest), the guide channel names. */
    private fun guideCase(case: String, file: File, src: String, wanted: Set<String>, minStop: Long = Long.MIN_VALUE / 4, maxStart: Long = Long.MAX_VALUE / 4, fullRows: Boolean = true): Pair<KotlinGuide, CoreGuide> {
        val c = coreGuide(case.replace(Regex("[^a-z0-9-]"), "_"), file, src, wanted, minStop, maxStart)
        // a guide Kotlin cannot read fails its refresh as a whole (the stored guide is kept): compare that outcome
        val k = try { kotlinGuide(file, src, wanted, minStop, maxStart, fullRows) } catch (e: Exception) {
            compare("xmltv:$case", listOf("refused (${e.javaClass.simpleName})"), listOf(c.load.error?.let { "refused" } ?: "read ${c.digest.rows} rows"))
            return KotlinGuide(emptyMap(), CoreShadow.Digest(), 0, emptyMap(), 0, 0) to c
        }
        assertEquals("$case: core error ${c.load.error}", null, c.load.error)
        if (fullRows) {
            val keys = k.rows!!.keys.sorted()
            val kr = keys.flatMap { key -> k.rows[key]!!.sortedBy { it.start }.map(::progRow) }
            val cr = keys.flatMap { key -> c.store.load(src, key, Long.MIN_VALUE, Long.MAX_VALUE).map { progRow(Programme(it.key, it.start, it.stop, it.title, it.desc, it.sub, it.ep)) } }
            compare("xmltv:$case", listOf("rows ${k.digest.rows}") + kr, listOf("rows ${c.store.count(src)}") + cr)
        }
        compare("xmltv:$case:digest", listOf(k.parsed.toLong(), k.digest.rows) + k.digest.differing(c.digest), listOf(c.load.programmes.toLong(), c.digest.rows.toLong()))
        compare("xmltv:$case:names", k.names.entries.map { "${it.key} = ${it.value}" }.sorted(), c.load.names.map { "${it.id} = ${it.name}" }.sorted())
        count("guide programmes compared", k.parsed)
        return k to c
    }

    private fun wantedOf(chs: List<Channel>): Set<String> = HashSet<String>().apply { for (c in chs) { c.tvgId?.let { add(it.lowercase()) }; add(c.name.lowercase()) } }

    private fun gz(f: File): File = File(f.path + ".gz").also { g -> GZIPOutputStream(g.outputStream()).use { o -> f.inputStream().use { it.copyTo(o) } } }

    private fun report(case: String, k: KotlinGuide, c: CoreGuide) = note(
        "$case: ${k.parsed} programmes parsed, ${c.digest.rows} stored; kotlin parse ${k.ms} ms (heap +${k.heapMb} MB), " +
            "core parse + store ${c.ms} ms (heap +${c.heapMb} MB), core log peak ${c.walPeak / 1_048_576.0} MB, log after commit ${c.walEnd} B, epg.db ${c.dbBytes / 1_048_576} MB",
    )

    @Test fun guideMock() {
        val xml = File(root, "testenv/www/epg.xml")
        val m3u = File(root, "testenv/www/playlist.m3u")
        if (!xml.exists() || !m3u.exists()) { note("guide mock: testenv/www not generated (make_mock.py), skipped"); return }
        val chs = M3uParser.parse(m3u.readLines().asSequence(), "s1").channels
        val (k, c) = guideCase("mock", xml, "s1", wantedOf(chs))
        assertTrue("mock guide matched no channel", k.digest.rows > 1000)
        report("guide mock", k, c)
        guideCase("mock-gz", gz(xml), "s1", wantedOf(chs))
    }

    @Test fun guideHostile() {
        val head = """<?xml version="1.0" encoding="UTF-8"?><tv><channel id="a.example"><display-name>Alpha</display-name></channel><channel id="named"><display-name>Not Wanted</display-name><display-name>Beta TV</display-name></channel>"""
        fun p(ch: String, s: String, e: String, body: String) = """<programme start="$s" stop="$e" channel="$ch">$body</programme>"""
        val t = "20261113120000 +0000"; val u = "20261113130000 +0000"; val v = "20261113140000 +0100"
        val cases = linkedMapOf(
            "entities-cdata" to p("a.example", t, u, "<title>Tom &amp; Jerry &#233;&#x41;</title><desc><![CDATA[<b>raw</b> & more]]></desc>"),
            "display-name-fallback" to p("named", t, u, "<title>By name</title>") + p("NAMED", u, v, "<title>Upper id</title>"),
            "case-of-ids" to p("A.EXAMPLE", t, u, "<title>Upper</title>"),
            "duplicate-start" to p("a.example", t, u, "<title>First</title>") + p("a.example", t, v, "<title>Second</title>"),
            "desc-cut" to p("a.example", t, u, "<title>T</title><desc>${"d".repeat(500)}</desc><sub-title>${"s".repeat(200)}</sub-title>"),
            "accents-cut" to p("a.example", t, u, "<title>Café</title><desc>${"é".repeat(170)}</desc>"),
            "astral-cut" to p("a.example", t, u, "<title>🎬</title><desc>${"d".repeat(159)}🎬 more</desc>"),
            "episodes" to p("a.example", t, u, "<title>S</title><episode-num system=\"onscreen\">S03E12</episode-num><episode-num system=\"xmltv_ns\">1.3.0/1</episode-num>") +
                p("a.example", u, v, "<title>E</title><episode-num system=\"dd_progid\">EP1</episode-num><episode-num>Ep. 9</episode-num>"),
            "first-title-wins" to p("a.example", t, u, "<title lang=\"en\">One</title><title lang=\"de\">Eins</title><desc>A</desc><desc>B</desc>"),
            "bad-times" to p("a.example", "garbage", u, "<title>X</title>") + p("a.example", "20261313120000 +0000", u, "<title>Month 13</title>") + p("a.example", "202611131200", "202611131300", "<title>No seconds</title>"),
            "nested-and-empty" to p("a.example", t, u, "<title>T<b>bold</b></title><desc/><sub-title>  </sub-title><credits><actor>X</actor></credits>"),
            "whitespace" to p("a.example", t, u, "<title>\n  Spaced \t</title><desc>  d  </desc>"),
            "unknown-channel" to p("nobody", t, u, "<title>Dropped</title>"),
        )
        val wanted = setOf("a.example", "beta tv")
        for ((k, body) in cases) {
            val f = File(tmp, "hostile-$k.xml").apply { writeText("$head$body</tv>") }
            guideCase("hostile-$k", f, "s1", wanted)
        }
        val latin = File(tmp, "hostile-latin1.xml").apply { writeBytes("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><tv>${p("a.example", t, u, "<title>Café crème</title>")}</tv>".toByteArray(Charsets.ISO_8859_1)) }
        guideCase("hostile-latin1", latin, "s1", wanted)
        // two gzip members back to back (some providers concatenate)
        val two = File(tmp, "hostile-multi.xml.gz").apply {
            val a = java.io.ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write("$head${p("a.example", t, u, "<title>Part</title>")}".toByteArray()) } }
            val b = java.io.ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write("${p("a.example", u, v, "<title>Two</title>")}</tv>".toByteArray()) } }
            writeBytes(a.toByteArray() + b.toByteArray())
        }
        guideCase("hostile-multi-gzip", two, "s1", wanted)
        // the time window (Repository: past days by catch-up, 7 days ahead)
        val win = File(tmp, "hostile-window.xml").apply { writeText(head + p("a.example", "20261101000000 +0000", "20261101010000 +0000", "<title>Old</title>") + p("a.example", t, u, "<title>In</title>") + p("a.example", "20261201000000 +0000", "20261201010000 +0000", "<title>Late</title>") + "</tv>") }
        guideCase("hostile-window", win, "s1", wanted, minStop = 1_794_000_000L, maxStart = 1_794_700_000L)
    }

    /** A large guide: 2000 channels x 200 programmes (400,000), gzip, the real time window. */
    @Test fun guideGenerated400k() {
        val base = 1_794_528_000L // 2026-11-13 00:00 UTC
        val xml = File(tmp, "guide-400k.xml")
        val fmt = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        fun ts(sec: Long) = fmt.format(java.util.Date(sec * 1000))
        if (!xml.exists() || xml.length() < 100_000_000) xml.bufferedWriter().use { w ->
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv generator-info-name=\"tvbro-parity\">\n")
            for (c in 0 until 2000) w.write("<channel id=\"ch$c.example\"><display-name>Channel $c</display-name></channel>\n")
            for (c in 0 until 2000) for (j in 0 until 200) {
                val s = base - 12 * 3600 + j * 1800L
                w.write("<programme start=\"${ts(s)}\" stop=\"${ts(s + 1800)}\" channel=\"ch$c.example\"><title>Programme $c-$j ${if (j % 9 == 0) "Café Ελληνικά" else "News"}</title>")
                if (j % 3 == 0) w.write("<sub-title>Part ${j / 3}</sub-title>")
                if (j % 4 == 0) w.write("<episode-num system=\"xmltv_ns\">${j % 7}.${j % 13}.0/1</episode-num>")
                w.write("<desc>${"Description of programme $j on channel $c. ".repeat(5)}</desc></programme>\n")
            }
            w.write("</tv>\n")
        }
        val gzf = File(xml.path + ".gz").takeIf { it.exists() && it.lastModified() >= xml.lastModified() } ?: gz(xml)
        val wanted = (0 until 2000).flatMap { listOf("ch$it.example", "channel $it") }.toSet()
        val (k, c) = guideCase("generated-400k", gzf, "s1", wanted, minStop = base - 86400, maxStart = base + 7 * 86400, fullRows = false)
        assertEquals(400_000L, k.digest.rows)
        report("guide generated-400k (gzip ${gzf.length() / 1_048_576} MB, XML ${xml.length() / 1_048_576} MB)", k, c)
        // a second refresh of the same source: the old generation goes in 5000-row chunks, the log ends truncated
        val gen = c.store.beginRefresh("s1")
        val t0 = System.nanoTime()
        val again = FileInputStream(gzf).use { c.store.loadGuide("s1", gen, Feed(it), wanted.toList(), base - 86400, base + 7 * 86400) }
        c.store.commit("s1", gen, again.minStart, again.maxStop, 1)
        val db = File(tmp, "epg-generated-400k.db")
        note("guide generated-400k second refresh: ${(System.nanoTime() - t0) / 1_000_000} ms, log peak ${maxOf(again.walPeak.toLong(), c.store.walPeak().toLong()) / 1_048_576.0} MB, log after commit ${File(db.path + "-wal").length()} B, epg.db ${db.length() / 1_048_576} MB")
        assertEquals(400_000L, c.store.count("s1").toLong())
        assertEquals("log truncated after the refresh", 0L, File(db.path + "-wal").length())
        assertTrue(c.store.digest("s1").let { it.rows == c.digest.rows && it.row == c.digest.row })
    }

    // ------------------------------------------------------------------ shadow mode and the switch

    @Test fun shadowSummaries() {
        val a = Core.m3u(mockPlaylist(50).toByteArray(), "s1").channels
        val b = a.toMutableList().apply { this[7] = this[7].copy(name = "x", group = "y"); removeAt(40) }
        val same = CoreShadow.compareChannels("m3u", a, a, 1, 2)
        val diff = CoreShadow.compareChannels("m3u", a, b, 1, 2)
        assertEquals("m3u: kotlin 50 / core 50 channels, identical; kotlin 1 ms, core 2 ms", same)
        assertTrue(diff, diff.startsWith("m3u: kotlin 50 / core 49 channels, 11 differ, first at 7: name,group;"))
        for (s in listOf(same, diff)) assertTrue("no URLs or names in the summary", !s.contains("http") && !s.contains("Channel"))
        // the Kotlin digest equals the core's on the same rows
        val f = File(tmp, "shadow.xml").apply { writeText("""<tv><programme start="20261113120000 +0000" stop="20261113130000 +0000" channel="a"><title>Ünï 🎬</title><desc>d</desc><sub-title>s</sub-title><episode-num system="onscreen">S1E2</episode-num></programme><programme start="20261113130000 +0000" stop="20261113140000 +0000" channel="a"><title>T2</title></programme></tv>""") }
        val d = CoreShadow.Digest()
        d.add(Programme("s|a", 1794571200, 1794574800, "Ünï 🎬", "d", "s", "S1 E2")); d.add(Programme("s|a", 1794574800, 1794578400, "T2", null))
        val c = f.inputStream().use { Core.guideDigest(it, "s", listOf("a"), 0, Long.MAX_VALUE) }
        assertEquals(emptyList<String>(), d.differing(c))
        val d2 = CoreShadow.Digest().apply { add(Programme("s|a", 1794571200, 1794574800, "Ünï 🎬", "other", "s", "S1 E2")); add(Programme("s|a", 1794574800, 1794578400, "T2", null)) }
        assertEquals(listOf("desc"), d2.differing(c))
    }

    @Test fun switchModes() {
        val m = CoreSwitch.Row.entries.associateWith { CoreSwitch.Mode.KOTLIN }.toMutableMap()
        CoreSwitch.applyProp("shadow", m, true)
        assertEquals(listOf("shadow", "shadow", "shadow", "shadow", "shadow", "shadow"), CoreSwitch.Row.entries.map { m[it]!!.id })
        CoreSwitch.applyProp("epg=rust, pin=rust,urlguard=kotlin,search=kotlin,bogus=rust", m, true)
        assertEquals(listOf("kotlin", "rust", "shadow", "shadow", "rust", "kotlin"), CoreSwitch.Row.entries.map { m[it]!!.id })
        val before = m.toMap()
        CoreSwitch.applyProp("shadow", m, false)
        assertEquals("release builds never shadow", before, m.toMap())
        assertEquals(null, CoreSwitch.parse("shadow", CoreSwitch.Row.PIN, false))
        // build defaults: per row (all on the core since phase 2 step 3), or every row forced either way
        assertEquals(CoreSwitch.Row.entries.map { CoreSwitch.Mode.CORE }, CoreSwitch.defaults("default").let { d -> CoreSwitch.Row.entries.map { d[it] } })
        assertEquals(CoreSwitch.Row.entries.map { CoreSwitch.Mode.KOTLIN }, CoreSwitch.defaults("kotlin").let { d -> CoreSwitch.Row.entries.map { d[it] } })
        assertEquals(CoreSwitch.Row.entries.map { CoreSwitch.Mode.CORE }, CoreSwitch.defaults("rust").let { d -> CoreSwitch.Row.entries.map { d[it] } })
    }

    // ------------------------------------------------------------------ search index (row SEARCH)

    /** SqlDb over JDBC SQLite (FTS4 like Android's framework SQLite): Kotlin's [SearchIndex] runs on it. */
    private class JdbcDb(val c: java.sql.Connection) : io.github.kmmarin.tvbro.data.SqlDb {
        init { c.autoCommit = true }
        private fun bind(st: java.sql.PreparedStatement, a: Array<out Any?>) = a.forEachIndexed { i, v -> st.setObject(i + 1, v) }
        override fun exec(sql: String, args: Array<Any?>) { c.prepareStatement(sql).use { bind(it, args); it.execute() } }
        override fun query(sql: String, args: Array<String>, row: (io.github.kmmarin.tvbro.data.SqlRow) -> Unit) {
            c.prepareStatement(sql).use { st ->
                bind(st, args)
                st.executeQuery().use { rs -> val r = object : io.github.kmmarin.tvbro.data.SqlRow { override fun str(i: Int) = rs.getString(i + 1); override fun long(i: Int) = rs.getLong(i + 1) }; while (rs.next()) row(r) }
            }
        }
        override fun prepare(sql: String): io.github.kmmarin.tvbro.data.SqlStmt { val st = c.prepareStatement(sql); return object : io.github.kmmarin.tvbro.data.SqlStmt { override fun insert(vararg a: Any?): Long { bind(st, a); st.executeUpdate(); return 0 } } }
        override fun begin() { c.autoCommit = false }
        override fun commit() { c.commit(); c.autoCommit = true }
    }

    private sealed class Row {
        class Ch(val key: String, val name: String, val src: String) : Row()
        class Vod(val v: io.github.kmmarin.tvbro.data.VodItem) : Row()
        class Prog(val title: String, val ch: String, val start: Long, val stop: Long, val sub: String?, val ep: String?) : Row()
    }

    private fun feed(s: io.github.kmmarin.tvbro.data.IndexSink, rows: List<Row>) = rows.forEach {
        when (it) { is Row.Ch -> s.channel(it.key, it.name, it.src); is Row.Vod -> s.vod(it.v); is Row.Prog -> s.programme(it.title, it.ch, it.start, it.stop, it.sub, it.ep) }
    }

    /** Builds both indexes from [rows] (Kotlin on JDBC SQLite, the core on its own SQLite), in temp files. */
    private fun bothIndexes(name: String, rows: List<Row>): Triple<io.github.kmmarin.tvbro.data.SearchReader, io.github.kmmarin.tvbro.data.SearchReader, String> {
        val kf = File(tmp, "search-$name-kotlin.db").apply { delete() }
        val cf = File(tmp, "search-$name-core.db").apply { delete() }
        val t0 = System.nanoTime()
        val jdbc = JdbcDb(java.sql.DriverManager.getConnection("jdbc:sqlite:${kf.path}"))
        jdbc.exec("PRAGMA journal_mode=OFF"); jdbc.exec("PRAGMA synchronous=OFF")
        io.github.kmmarin.tvbro.data.SearchIndex.create(jdbc)
        val ks = io.github.kmmarin.tvbro.data.KotlinIndexSink(jdbc)
        feed(ks, rows); val kc = ks.finish(1_794_528_000_000L)
        val km = (System.nanoTime() - t0) / 1_000_000
        val t1 = System.nanoTime()
        val cs = io.github.kmmarin.tvbro.data.CoreIndexSink(cf.path)
        feed(cs, rows); val cc = cs.finish(1_794_528_000_000L)
        val cm = (System.nanoTime() - t1) / 1_000_000
        compare("search:$name:counts", listOf(kc), listOf(cc))
        return Triple(io.github.kmmarin.tvbro.data.SqlSearch(jdbc) { jdbc.c.close() }, io.github.kmmarin.tvbro.data.CoreSearch(cf.path), "$kc, kotlin build $km ms, core build $cm ms")
    }

    private fun hitRow(h: io.github.kmmarin.tvbro.data.SearchHit) = listOf(h.kind, h.key, h.name, h.img, h.src, h.xid, h.cat, h.rating, h.ext, h.url).joinToString(" | ") { v -> if (v is String) "\"$v\"" else v.toString() }
    private fun progHitRow(h: io.github.kmmarin.tvbro.data.ProgHit) = listOf(h.title, h.ch, h.start, h.stop, h.sub, h.ep).joinToString(" | ") { v -> if (v is String) "\"$v\"" else v.toString() }
    private fun resultRows(r: io.github.kmmarin.tvbro.data.SearchResult) =
        r.channels.map { "ch " + hitRow(it) } + r.movies.map { "mov " + hitRow(it) } + r.series.map { "ser " + hitRow(it) } + r.programmes.map { "prog " + progHitRow(it) }

    /** Every query on both indexes, every hit compared field by field and in order; then the catalogue pages. */
    private fun searchCase(name: String, rows: List<Row>, queries: List<String>, pastFrom: Long) {
        val (k, c, info) = bothIndexes(name, rows)
        var hits = 0
        var kms = 0L; var cms = 0L
        for ((i, q) in queries.withIndex()) for ((per, maxProg) in listOf(30 to 300, 5 to 20)) {
            val t0 = System.nanoTime(); val kr = k.search(q, pastFrom, per, maxProg); val t1 = System.nanoTime(); val cr = c.search(q, pastFrom, per, maxProg); kms += t1 - t0; cms += System.nanoTime() - t1
            compare("search:$name:q$i-$per", resultRows(kr), resultRows(cr))
            hits += resultRows(kr).size
            // the shadow summary of the same pair
            assertTrue(CoreShadow.compareSearch(kr, cr, 0, 0).contains("identical") || "search:$name:q$i-$per" in allow)
        }
        for (kind in listOf(io.github.kmmarin.tvbro.data.SearchIndex.MOVIE, io.github.kmmarin.tvbro.data.SearchIndex.SERIES)) {
            compare("search:$name:categories-$kind", k.categories(kind), c.categories(kind))
            compare("search:$name:count-$kind", listOf(k.count(kind)), listOf(c.count(kind)))
            for (cat in listOf<String?>(null) + k.categories(kind).take(5)) for (off in listOf(0, 100))
                compare("search:$name:page-$kind-${cat?.hashCode()}-$off", k.page(kind, cat, off, 100).map { it.toString() }, c.page(kind, cat, off, 100).map { it.toString() })
        }
        compare("search:$name:meta", listOf(k.meta("counts"), k.meta("built"), k.meta("none")), listOf(c.meta("counts"), c.meta("built"), c.meta("none")))
        assertTrue("no stored stream address", (k.page(1, null, 0, 100000) + c.page(1, null, 0, 100000)).all { it.url == null })
        note("search $name: $info; ${queries.size * 2} queries, $hits hits compared; kotlin queries ${kms / 1_000_000} ms, core ${cms / 1_000_000} ms")
        count("search hits compared", hits)
        k.close(); c.close()
    }

    private val searchQueries = listOf(
        "news", "News", "NEWS ", "ne", "n", "bbc news", "sky newsroom", "channel 4", "channel 4999", "4k", "uhd", "ppv", "sport", "sports",
        "cafe", "café", "CAFÉ", "creme", "ελλην", "ελληνικά", "日本", "música", "musica", "pokemon", "pokémon", "amelie", "strasse", "straße",
        "part 3", "late edition", "late edit", "matchday", "programme 12", "description", "film 1", "star", "barc mad", "the", "a b", "--", "  ",
        "🎬", "x🎬y", "s01e02", "1", "12", "ǆ", "ﬁnal", "ıstanbul", "istanbul", "øresund", "oresund", "đorđe", "dorde", "a.b.c", "o'neil", "oneil",
        "q1 q2 q3 q4 q5 q6 q7 q8 q9", "zzzz", "extra", "kids", "no guide", "evening", "drama", "action",
    )

    private val NOW = 1_794_528_000L

    /** A playlist-and-catalogue style corpus: the generated playlist, VOD in it, movies / series, a guide. */
    private fun searchRows(): List<Row> {
        val rows = ArrayList<Row>()
        val chs = Core.m3u(mockPlaylist(5000).toByteArray(), "s1").channels
        for (c in chs) rows += Row.Ch(c.key, c.name, "s1")
        val names = listOf("Café crème", "Pokémon: La Série", "Amélie", "Straße", "Ελληνικά νέα", "日本のドラマ", "Música en vivo", "Star Film", "Barcelona vs Real Madrid",
            "News at Ten", "Ǆemal", "ﬁnal cut", "İstanbul nights", "Øresund", "Đorđe", "O'Neil", "S01E02 Pilot", "🎬 Movie night", "x🎬y", "A.B.C. Murders", "The The", "News", "Newsroom")
        for (i in 0 until 3000) {
            val series = i % 3 == 0
            val nm = "${names[i % names.size]}${if (i % 4 == 0) "" else " $i"}"
            rows += Row.Vod(io.github.kmmarin.tvbro.data.VodItem(if (series) "s|x|$i" else "m|x|$i", "x", if (series) "series" else "movie", "$i", nm,
                if (i % 5 == 0) null else "http://192.0.2.1/p/$i.jpg", listOf("Drama", "Action", "Kids", "Música", "Ελληνικά", "")[i % 6], if (i % 7 == 0) null else "${i % 10}.5", if (i % 2 == 0) "mkv" else "", "http://192.0.2.1/movie/u/p/$i.mkv"))
            if (i % 50 == 0) rows += Row.Ch("x|late$i", "Late channel $i", "x") // channels after VOD: the id order interleaves
        }
        for (c in 0 until 300) for (j in 0 until 40) {
            val s = NOW - 3 * 86400 + j * 3 * 3600L + c
            val title = when (j % 8) { 0 -> "Evening News"; 1 -> "evening news"; 2 -> "Programme $c-$j Café Ελληνικά"; 3 -> "Matchday ${j % 3}"; 4 -> " "; 5 -> names[(c + j) % names.size]; 6 -> "Late Show"; else -> "Programme $c" }
            rows += Row.Prog(title, "s1|ch$c.example", s, s + 1800, if (j % 3 == 0) "Part ${j / 3}" else if (j % 5 == 0) "Late edition" else null, if (j % 4 == 0) "S${j % 7} E${j % 13}" else null)
        }
        return rows
    }

    @Test fun searchGenerated() = searchCase("generated", searchRows(), searchQueries, NOW - 86400)

    @Test fun searchMock() {
        val m3u = File(root, "testenv/www/playlist.m3u")
        val xml = File(root, "testenv/www/epg.xml")
        if (!m3u.exists() || !xml.exists()) { note("search mock: testenv/www not generated, skipped"); return }
        val chs = M3uParser.parse(m3u.readLines().asSequence(), "s1").channels
        val rows = ArrayList<Row>()
        for (c in chs) rows += if (io.github.kmmarin.tvbro.data.VodApi.isVodUrl(c.url)) Row.Vod(io.github.kmmarin.tvbro.data.VodApi.fromChannel(c)) else Row.Ch(c.key, c.name, "s1")
        val k = kotlinGuide(xml, "s1", wantedOf(chs), Long.MIN_VALUE / 4, Long.MAX_VALUE / 4, true)
        for (p in k.rows!!.values.flatten()) rows += Row.Prog(p.title, p.key, p.start, p.stop, p.sub, p.ep)
        val titles = k.rows.values.flatten().map { it.title }.distinct().take(40)
        searchCase("mock", rows, searchQueries + titles + titles.map { it.take(3) }, 0)
    }

    /** Folding of every BMP character (alone and between letters), the index's and the queries' first step. */
    @Test fun searchFoldingBmp() {
        val ks = ArrayList<String>(); val cs = ArrayList<String>()
        for (u in 0 until 0x10000) {
            if (u in 0xD800..0xDFFF) continue
            val s = "a${u.toChar()}b"
            ks += "${"%04x".format(u)} ${Fold.text(s)}"; cs += "${"%04x".format(u)} ${io.github.kmmarin.tvbro.core.foldText(s)}"
        }
        val diff = ks.indices.filter { ks[it] != cs[it] }
        if (diff.isNotEmpty()) {
            // grouped by the JVM's general category and Unicode block (the JVM's tables are the Kotlin side)
            val by = diff.groupBy { val u = ks[it].substringBefore(' ').toInt(16); "type ${Character.getType(u.toChar())} ${Character.UnicodeBlock.of(u)} kotlin-${if (ks[it].endsWith("a b")) "sep" else "keep"}" }
            File("build/reports").mkdirs()
            File("build/reports/fold-bmp-diff.txt").writeText(by.entries.joinToString("\n") { (k, v) -> "$k: ${v.size}: ${v.joinToString(" ") { ks[it].substringBefore(' ') }}" })
            note("search fold BMP: ${diff.size} differ in ${by.size} groups (build/reports/fold-bmp-diff.txt)")
        }
        // characters this JVM's Unicode tables do not assign yet are a separate case (their folding follows the runtime)
        val unassigned = ks.indices.filter { Character.getType(ks[it].substringBefore(' ').toInt(16).toChar()) == Character.UNASSIGNED.toInt() }.toSet()
        compare("search:fold-bmp", ks.filterIndexed { i, _ -> i !in unassigned }, cs.filterIndexed { i, _ -> i !in unassigned })
        compare("search:fold-bmp-unassigned", ks.filterIndexed { i, _ -> i in unassigned }, cs.filterIndexed { i, _ -> i in unassigned })
        count("search folding characters", ks.size - unassigned.size)
    }
}