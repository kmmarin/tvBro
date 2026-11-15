// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Mutation fuzzing of the Kotlin parsers (the fallback when the Rust core is off, and the desktop app's): damaged
 * provider data must never crash. M3U and Xtream must not throw at all; the XMLTV reader may only throw the pull
 * parser's own errors (the guide loaders catch every Exception and keep what was read before the damage). Deterministic; same seeds and
 * mutations as core/tvbro-core/tests/fuzz_mutate.rs. `TVBRO_FUZZ_ITERS=<n>` raises the count (default 2000).
 */
class ParserFuzzTest {
    private val iters = (System.getenv("TVBRO_FUZZ_ITERS") ?: "2000").toInt()

    private class Rng(var s: Long) {
        fun next(): Long { var x = s; x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17); s = x; return x }
        fun below(n: Int): Int = if (n <= 0) 0 else java.lang.Long.remainderUnsigned(next(), n.toLong()).toInt()
    }

    private val nasty = listOf(
        "\u0000", "￾", "﻿", "\r", "\n", "\r\n", "\"", "'", ",", ":", "=", "<", ">", "&", "&amp;", "&#xFFFFFF;",
        "&#99999999999;", "]]>", "<![CDATA[", "<!DOCTYPE x [<!ENTITY a \"aaaa\">]>", "&a;", "{", "}", "[", "]", "null",
        "true", "-1", "99999999999999999999999", "1e309", "-9223372036854775808", "\\u0000", "\\ud800", "#EXTINF:",
        "#EXTM3U", "#EXTGRP:", "tvg-id=\"", "catchup=\"", "catchup-source=\"", "catchup-days=\"", "{utc}", "{start}",
        "\${start}", "{utc:Y-m-d}", "../../", "file:///", "%00", "20301231235959 +2400", "99999999999999 -9999",
        "start=\"", "stop=\"", "channel=\"", "</programme>", "<programme ",
    ).map { it.toByteArray() }

    private fun mutate(r: Rng, seeds: List<ByteArray>): ByteArray {
        val d = seeds[r.below(seeds.size)].toMutableList()
        repeat(1 + r.below(8)) {
            when (r.below(9)) {
                0 -> if (d.isNotEmpty()) { val at = r.below(d.size); while (d.size > at) d.removeAt(d.size - 1) }
                1 -> { val at = r.below(d.size + 1); d.addAll(at, nasty[r.below(nasty.size)].toList()) }
                2 -> if (d.isNotEmpty()) d[r.below(d.size)] = r.next().toByte()
                3 -> if (d.isNotEmpty()) { val a = r.below(d.size); val b = minOf(a + r.below(64), d.size); val s = d.subList(a, b).toList(); repeat(r.below(20)) { d.addAll(s) } }
                4 -> if (d.isNotEmpty()) { val a = r.below(d.size); val b = minOf(a + r.below(32), d.size); repeat(b - a) { d.removeAt(a) } }
                5 -> { val o = seeds[r.below(seeds.size)]; if (o.isNotEmpty()) d.addAll(o.copyOfRange(r.below(o.size), o.size).toList()) }
                6 -> { val n = nasty[r.below(nasty.size)]; repeat(r.below(200)) { d.addAll(n.toList()) } }
                7 -> { val depth = r.below(3000); val at = r.below(d.size + 1); d.addAll(at, List(depth) { '['.code.toByte() } + List(depth) { ']'.code.toByte() }) }
                else -> d.add(r.below(d.size + 1), r.next().toByte())
            }
        }
        return d.toByteArray()
    }

    private val m3u = """#EXTM3U url-tvg="http://example.com/epg.xml.gz"
#EXTINF:-1 tvg-id="one.example" tvg-name="One" tvg-logo="http://example.com/1.png" group-title="News" tvg-chno="7" catchup="default" catchup-days="3" catchup-source="http://example.com/c/{utc}/{duration}?o={offset}",One HD
http://example.com/live/u/p/1.ts
#EXTGRP:Sport
#EXTINF:0 catchup="append" catchup-source="?s=${'$'}{start}",Two
http://example.com/2.m3u8
"""
    private val xmltv = """<?xml version="1.0" encoding="UTF-8"?><tv><channel id="one.example"><display-name>One HD</display-name></channel>""" +
        """<programme start="20301101120000 +0100" stop="20301101130000 +0100" channel="one.example"><title lang="en">News &amp; more</title>""" +
        """<sub-title>Part 1</sub-title><desc>A long text</desc><episode-num system="xmltv_ns">1.2.</episode-num></programme>""" +
        """<programme start="20301101130000" stop="20301101140000 -0500" channel="two"><title><![CDATA[Film]]></title></programme></tv>"""
    private val cats = """[{"category_id":"1","category_name":"News","parent_id":0},{"category_id":2,"category_name":"Sport"}]"""
    private val streams = """[{"num":1,"name":"One","stream_type":"live","stream_id":101,"epg_channel_id":"one.example","category_id":"1","tv_archive":1,"tv_archive_duration":"5"},""" +
        """{"num":"2","name":"Two","stream_id":"102","category_id":2,"tv_archive":"0"},{"name":null,"stream_id":null}]"""
    private val movies = """[{"num":1,"name":"Film (2020)","stream_id":5,"rating":"7.5","category_id":"1","container_extension":"mkv"},{"series_id":9,"name":"Show","category_id":"x"}]"""
    private val vodInfo = """{"info":{"name":"Film","plot":"Text","duration":"01:42:00","duration_secs":6120},"movie_data":{"stream_id":5,"container_extension":"mp4"}}"""
    private val seriesInfo = """{"info":{"name":"Show"},"seasons":[{"season_number":1}],"episodes":{"1":[{"id":"77","episode_num":1,"title":"Show - S01E01 - Pilot","info":{"duration":"00:44:10"}}]}}"""

    private fun noThrow(what: String, d: ByteArray, f: () -> Unit) {
        try { f() } catch (t: Throwable) { fail("$what threw ${t.javaClass.name}: ${t.message?.take(200)} on ${String(d).take(300)}") }
    }

    private fun onlyExceptions(what: String, d: ByteArray, f: () -> Unit) {
        try { f() } catch (e: Exception) { } catch (t: Throwable) { fail("$what threw ${t.javaClass.name} on ${String(d).take(300)}") }
    }

    @Test fun m3uNeverThrows() {
        val r = Rng(-0x61c8864680b583ebL)
        repeat(iters) {
            val d = mutate(r, listOf(m3u.toByteArray(), "#EXTM3U\n".toByteArray()))
            noThrow("M3uParser", d) {
                val res = M3uParser.parse(String(d).lineSequence(), "s")
                assertTrue(res.channels.size <= d.size)
            }
        }
    }

    @Test fun xtreamNeverThrows() {
        val seeds = listOf(cats, streams, movies, vodInfo, seriesInfo, "[]", "{}").map { it.toByteArray() }
        val r = Rng(0x2545F4914F6CDD1DL)
        repeat(iters) {
            val a = mutate(r, seeds); val b = mutate(r, seeds)
            noThrow("Xtream", b) {
                val c = Xtream.categories(ByteArrayInputStream(a))
                val s = Xtream.liveStreams(ByteArrayInputStream(b))
                Xtream.toChannels("s", "http://example.com:8080", "u", "p", c, s)
            }
            noThrow("VodApi.eachVod", b) { VodApi.eachVod(ByteArrayInputStream(b), r.below(2) == 0, "s", emptyList(), "fuzz") { } }
            // details: bad JSON is an exception the callers show as "Could not load details"; never an Error
            onlyExceptions("VodApi.parseVodInfo", b) { VodApi.parseVodInfo(String(b)) }
            onlyExceptions("VodApi.parseSeriesInfo", b) { VodApi.parseSeriesInfo(String(b)) }
        }
    }

    @Test fun xmltvThrowsOnlyParserErrors() {
        val r = Rng(-0x2e4ab5cd2e6d12fdL)
        repeat(iters) {
            val d = mutate(r, listOf(xmltv.toByteArray()))
            try {
                val p = KXmlParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false); setInput(ByteArrayInputStream(d), null) }
                XmltvParser.parse(p, { ch, _ -> ch }, 0, Long.MAX_VALUE) { }
                XmltvParser.parseTime(String(d).take(40))
            } catch (e: XmlPullParserException) {
            } catch (e: IOException) {
            } catch (e: NumberFormatException) { // kxml (also Android's framework parser) on "&#99999999999;"
            } catch (t: Throwable) { fail("XmltvParser threw ${t.javaClass.name}: ${t.message?.take(200)} on ${String(d).take(300)}") }
        }
    }
}
