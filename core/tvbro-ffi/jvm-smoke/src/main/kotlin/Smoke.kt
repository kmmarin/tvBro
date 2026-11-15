// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
// JVM smoke test of the UniFFI bindings: loads the native library, parses a small M3U, refuses a loopback URL.

import io.github.kmmarin.tvbro.core.CoreException
import io.github.kmmarin.tvbro.core.checkUrl
import io.github.kmmarin.tvbro.core.coreVersion
import io.github.kmmarin.tvbro.core.foldText
import io.github.kmmarin.tvbro.core.isNewer
import io.github.kmmarin.tvbro.core.parseM3u
import kotlin.system.exitProcess

private fun check(ok: Boolean, what: String) {
    if (!ok) {
        System.err.println("FAIL: $what")
        exitProcess(1)
    }
    println("ok: $what")
}

fun main() {
    println("core version ${coreVersion()}")

    val m3u = "#EXTM3U url-tvg=\"http://example.com/g.xml\"\n" +
        "#EXTINF:-1 tvg-id=\"a\" group-title=\"News\",One, Two\nhttp://example.com/1.ts\n" +
        "#EXTINF:-1,Plain\nhttp://example.com/2.ts\n"
    val r = parseM3u(m3u, "p0")
    check(r.channels.size == 2, "two channels")
    check(r.channels[0].name == "One, Two", "name keeps its comma")
    check(r.channels[0].group == "News", "group")
    check(r.channels[1].group == "Ungrouped", "fallback group")
    check(r.channels[0].tvgId == "a", "tvg-id")
    // Kotlin key rule (M3uParser.kt): "$sourceId|$tvgId|${urlLine.hashCode().toString(16)}" (signed hex).
    check(r.channels[0].key == "p0|a|${"http://example.com/1.ts".hashCode().toString(16)}", "channel key = Kotlin key (${r.channels[0].key})")
    check(r.channels[1].key == "p0||${"http://example.com/2.ts".hashCode().toString(16)}", "channel key without tvg-id")
    check(r.epgUrls == listOf("http://example.com/g.xml"), "url-tvg")

    check(isNewer("1.2.10", "1.2.9"), "version compare")
    check(foldText("Café") == "cafe", "search folding")

    val refused = listOf(
        "http://127.0.0.1/", "http://[::1]:8080/", "http://0x7f.1/", "http://[::ffff:127.0.0.1]/",
        "http://169.254.169.254/", "file:///etc/passwd", "http://192.0.2.1:22/", "http://192.0.2.10:8080/",
    )
    for (u in refused) {
        val blocked = try {
            checkUrl(u, false)
            false
        } catch (e: CoreException.Blocked) {
            println("   $u -> ${e.message}")
            true
        }
        check(blocked, "refuses $u")
    }
    // TEST-NET counts as a LAN range: allowed only with allow-LAN.
    check(checkUrl("http://192.0.2.10:8080/live.m3u", true) == listOf("192.0.2.10:8080"), "allow-LAN admits 192.0.2.10")
    println("JVM SMOKE OK")
}
