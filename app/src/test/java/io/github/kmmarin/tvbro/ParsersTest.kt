// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import org.junit.Assert.*
import org.junit.Test
import org.kxml2.io.KXmlParser
import io.github.kmmarin.tvbro.data.*
import io.github.kmmarin.tvbro.ui.GuideLayout
import java.io.StringReader

class ParsersTest {
    private val m3u = """
        #EXTM3U url-tvg="http://example.invalid/guide.xml.gz"
        #EXTINF:-1 tvg-id="one.example" tvg-name="One HD" tvg-logo="http://example.invalid/1.png" group-title="News, World" tvg-chno="7" catchup="append" catchup-source="?start=${'$'}{start}&end=${'$'}{end}" catchup-days="3",Channel One
        http://example.invalid/live/one.m3u8
        #EXTGRP:Sports
        #EXTINF:-1,Channel Two
        http://example.invalid/live/two.ts
    """.trimIndent()

    @Test fun m3u() {
        val r = M3uParser.parse(m3u.lineSequence(), "s1")
        assertEquals(listOf("http://example.invalid/guide.xml.gz"), r.epgUrls)
        assertEquals(2, r.channels.size)
        val a = r.channels[0]
        assertEquals("Channel One", a.name)
        assertEquals("one.example", a.tvgId)
        assertEquals("News, World", a.group)
        assertEquals(7, a.number)
        assertEquals(3, a.catchupDays)
        assertEquals("append", a.catchup?.type)
        val b = r.channels[1]
        assertEquals("Sports", b.group)
        assertNull(b.catchup)
        assertEquals("http://example.invalid/live/two.ts", b.url)
    }

    @Test fun xtreamJson() {
        val cats = """[{"category_id":"1","category_name":"News"},{"category_id":2,"category_name":"Sport"}]"""
        val streams = """[{"num":1,"name":"A","stream_id":101,"stream_icon":"","epg_channel_id":"a.x","category_id":"1","tv_archive":1,"tv_archive_duration":"5","extra":{}},
            {"num":"2","name":"B","stream_id":"102","epg_channel_id":null,"category_id":2,"tv_archive":0}]"""
        val c = Xtream.categories(cats.byteInputStream())
        val s = Xtream.liveStreams(streams.byteInputStream())
        assertEquals(2, c.size); assertEquals(2, s.size)
        val ch = Xtream.toChannels("x", "host:8080/", "u", "p", c, s)
        assertEquals("http://host:8080/live/u/p/101.ts", ch[0].url)
        assertEquals("News", ch[0].group)
        assertEquals("Sport", ch[1].group)
        assertEquals(5, ch[0].catchupDays)
        assertNull(ch[1].catchup)
        assertNull(ch[0].logo)
        assertEquals("a.x", ch[0].tvgId)
        assertTrue(Xtream.liveStreams("{\"user_info\":{}}".byteInputStream()).isEmpty())
    }

    @Test fun xmltvTime() {
        assertEquals(0L, XmltvParser.parseTime("19700101000000 +0000"))
        assertEquals(1704110400L, XmltvParser.parseTime("20240101120000 +0000"))
        assertEquals(1704110400L - 3600, XmltvParser.parseTime("20240101120000 +0100"))
        assertEquals(-1L, XmltvParser.parseTime("garbage"))
    }

    @Test fun xmltv() {
        val xml = """<?xml version="1.0"?><tv>
            <channel id="one.example"><display-name>Channel One</display-name></channel>
            <channel id="x2"><display-name>Channel Two</display-name><icon src="a"/></channel>
            <programme start="20240101120000 +0000" stop="20240101130000 +0000" channel="one.example"><title lang="en">News</title><desc>Daily</desc></programme>
            <programme start="20240101130000 +0000" stop="20240101140000 +0000" channel="x2"><title>Film</title></programme>
            <programme start="20240101130000 +0000" stop="20240101140000 +0000" channel="other"><title>Dropped</title></programme>
            <programme start="20200101130000 +0000" stop="20200101140000 +0000" channel="one.example"><title>Old</title></programme>
            </tv>"""
        val p = KXmlParser(); p.setInput(StringReader(xml))
        val got = ArrayList<Programme>()
        XmltvParser.parse(p, { ch, names ->
            when {
                ch == "one.example" -> "s|one.example"
                names?.contains("Channel Two") == true -> "s|channel two"
                else -> null
            }
        }, 1704000000L, 1705000000L) { got += it }
        assertEquals(2, got.size)
        assertEquals("News", got[0].title); assertEquals("Daily", got[0].desc)
        assertEquals("s|channel two", got[1].key); assertNull(got[1].desc)
        assertEquals(3600L, got[0].stop - got[0].start)
    }

    @Test fun catchupAndEpg() {
        val ch = M3uParser.parse(m3u.lineSequence(), "s1").channels[0]
        val u = CatchupUrl.build(ch, 1000, 4600, 5000)
        assertEquals("http://example.invalid/live/one.m3u8?start=1000&end=4600", u)
        val xc = Xtream.toChannels("x", "http://h", "u", "p", emptyList(),
            listOf(XtreamLiveStream(name = "A", streamId = "9", tvArchive = "1", tvArchiveDuration = "2")))[0]
        assertEquals("http://h/timeshift/u/p/60/1970-01-01:00-16/9.ts", CatchupUrl.build(xc, 1000, 4600, 5000))
        assertFalse(CatchupUrl.supports(xc, 0, 3 * 86400L))
        val idx = EpgIndex(mapOf("x|a" to arrayOf(
            Programme("x|a", 0, 100, "p1", null), Programme("x|a", 100, 200, "p2", null))))
        val (c, n) = idx.nowNext(xc, 150)
        assertEquals("p2", c?.title); assertNull(n)
        assertEquals("p1", idx.nowNext(xc, 50).first?.title)
    }

    private fun ins(s: String) = s.byteInputStream()

    @Test fun vodQuirksMovies() {
        val cats = VodApi.parseCategories(ins("""[{"category_id":1,"category_name":"Act","parent_id":0},{"category_id":"2","category_name":null},{"name":"no id"}]"""))
        assertEquals(2, cats.size); assertEquals("1", cats[0].categoryId); assertEquals("2", cats[1].categoryName)
        val json = """[{"num":1,"name":"A","stream_id":101,"stream_icon":null,"rating":7.5,"rating_5based":3.8,"added":"1700000000","category_id":1,"container_extension":"mkv"},
          {"name":"B","stream_id":"102","stream_icon":"","rating":"","category_id":"","container_extension":null},
          "garbage", 5, {"name":"no id"}, {"stream_id":103,"name":null,"category_id":99,"rating":null}]"""
        val l = VodApi.parseMovies(ins(json), "s", cats)
        assertEquals(3, l.size)
        assertEquals("Act", l[0].category); assertEquals("7.5", l[0].rating); assertEquals("mkv", l[0].ext); assertNull(l[0].poster)
        assertEquals("Other", l[1].category); assertEquals("mp4", l[1].ext); assertNull(l[1].rating)
        assertEquals("Movie 103", l[2].name)
        assertEquals(1, VodApi.parseMovies(ins(json), "s", cats, 1).size)
    }

    @Test fun vodQuirksShapes() {
        assertTrue(VodApi.parseMovies(ins("""{"user_info":{"auth":0}}"""), "s", emptyList()).isEmpty())
        assertTrue(VodApi.parseMovies(ins("[]"), "s", emptyList()).isEmpty())
        assertTrue(VodApi.parseMovies(ins("not json"), "s", emptyList()).isEmpty())
        assertEquals(2, VodApi.parseMovies(ins("""{"0":{"stream_id":1,"name":"a"},"1":{"stream_id":2,"name":"b"}}"""), "s", emptyList()).size)
        assertEquals(1, VodApi.parseMovies(ins("\uFEFF [{\"stream_id\":1,\"name\":\"a\"}]"), "s", emptyList()).size)
        assertTrue(VodApi.parseMovies(ins("""[{"stream_id":1,"name":"a"},{"stream_id":2,"na"""), "s", emptyList()).size <= 1) // truncated: no crash, partial data at best
        val s = VodApi.parseSeriesList(ins("""[{"series_id":7,"name":"S","cover":null,"category_id":3,"rating":"8"}]"""), "s", listOf(XtreamCategory("3", "Drama")))
        assertEquals("Drama", s[0].category); assertEquals("s|s|7", s[0].key)
    }

    @Test fun vodInfoQuirks() {
        val d = VodApi.parseVodInfo("""{"info":{"plot":"P","releasedate":"2020-05-01","rating":7,"youtube_trailer":"abc","director":"D","duration_secs":3600},"movie_data":{"container_extension":"avi"}}""")
        assertEquals("2020", d.year); assertEquals("7", d.rating); assertEquals("avi", d.ext); assertEquals("D", d.director)
        assertEquals("https://www.youtube.com/watch?v=abc", d.trailer)
        assertNull(VodApi.parseVodInfo("""{"info":[]}""").plot)
        assertNull(VodApi.parseVodInfo("[]").plot)
        val obj = VodApi.parseSeriesInfo("""{"info":[],"seasons":[],"episodes":{"2":[{"id":"22","episode_num":"1","season":2,"title":"B","container_extension":"mkv","info":[]}],"1":[{"id":11,"episode_num":2,"title":"A2"},{"id":12,"episode_num":1.0,"info":{"plot":"x","duration":"40:00"}}]}}""")
        assertEquals(listOf("12", "11", "22"), obj.episodes.map { it.id }); assertEquals("mkv", obj.episodes[2].ext); assertEquals(2, obj.episodes[2].season)
        val arr = VodApi.parseSeriesInfo("""{"episodes":[[{"id":"1","episode_num":1}],[{"id":"2","episode_num":1}]]}""")
        assertEquals(listOf(1, 2), arr.episodes.map { it.season })
        val flat = VodApi.parseSeriesInfo("""{"episodes":[{"id":"9","season":3,"episode_num":4}]}""")
        assertEquals(3, flat.episodes[0].season)
        assertTrue(VodApi.parseSeriesInfo("""{"episodes":[]}""").episodes.isEmpty())
        assertTrue(VodApi.parseSeriesInfo("""{"info":{},"episodes":null}""").episodes.isEmpty())
    }
    @Test fun prefsAndBackup() {
        val p = Prefs(pinHash = Pin.hash("1234"), locked = setOf("Adult"), custom = mapOf("Mine" to listOf("k1")))
        assertTrue(Pin.check(p, "1234")); assertFalse(Pin.check(p, "0000")); assertFalse(Pin.check(Prefs(), ""))
        val b = BackupIo.decode(BackupIo.encode(Backup(epgHours = 48, favourites = setOf("a"), prefs = p)))
        assertEquals(48, b.epgHours); assertEquals(setOf("a"), b.favourites); assertEquals(listOf("k1"), b.prefs.custom["Mine"])
    }

    @Test fun codeGate() {
        var now = 1000L
        val g = CodeGate("ABC234", 2000L, 3) { now }
        assertEquals(6, CodeGate.create().code.length)
        assertEquals(CodeGate.Result.WRONG, g.check("zzz", false))
        assertEquals(CodeGate.Result.OK, g.check(" abc234 ", false))
        assertEquals(CodeGate.Result.OK, g.check("ABC234", true))
        assertEquals(CodeGate.Result.USED, g.check("ABC234", true))
        val l = CodeGate("ABC234", 2000L, 2) { now }
        l.check("x", false); l.check("y", false)
        assertEquals(CodeGate.Result.LOCKED, l.check("ABC234", true))
        now = 3000L
        assertEquals(CodeGate.Result.EXPIRED, CodeGate("ABC234", 2000L) { now }.check("ABC234", true))
    }

    @Test fun httpParse() {
        val body = "code=AB&pass=p%26w%3Dx&name=My+TV"
        val raw = "POST /submit?x=1 HTTP/1.1\r\nHost: a\r\nContent-Length: ${body.length}\r\nContent-Type: application/x-www-form-urlencoded\r\n\r\n$body"
        val r = HttpReq.parse(raw.byteInputStream())!!
        assertEquals("POST", r.method); assertEquals("/submit", r.path)
        assertEquals("p&w=x", r.form()["pass"]); assertEquals("My TV", r.form()["name"])
        assertNull(HttpReq.parse("POST / HTTP/1.1\r\nContent-Length: 999999\r\n\r\n".byteInputStream()))
        assertNull(HttpReq.parse("garbage".byteInputStream()))
    }

    @Test fun linkServer() {
        val got = ArrayList<Map<String, String>>()
        val gate = CodeGate("ABC234", System.currentTimeMillis() + 60000)
        val srv = LinkServer(gate, "<html>page</html>", { "{}" }, { f -> got += f; null }, {})
        val port = srv.start(java.net.InetAddress.getLoopbackAddress())
        fun post(p: String, b: String): String {
            val c = java.net.URL("http://127.0.0.1:$port$p").openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            c.outputStream.write(b.toByteArray()); return c.inputStream.readBytes().decodeToString()
        }
        try {
            assertTrue(java.net.URL("http://127.0.0.1:$port/").readText().contains("page"))
            assertTrue(post("/submit", "code=NOPE00&name=a").contains("Wrong code"))
            assertTrue(got.isEmpty())
            assertTrue(post("/submit", "code=abc234&name=a&pass=s3cret").contains("\"ok\":true"))
            assertEquals("s3cret", got[0]["pass"])
            assertTrue(post("/submit", "code=ABC234&name=b").contains("already used"))
        } finally { srv.stop() }
    }

    @Test fun vodFormatting() {
        assertEquals("Pilot", VodFmt.cleanTitle("My Show", "My Show - S01E02 - Pilot"))
        assertEquals("Pilot", VodFmt.cleanTitle("My Show", "S01E02 - Pilot"))
        assertEquals("Pilot", VodFmt.cleanTitle("My Show", "My Show (2019) S01E02 Pilot"))
        assertEquals("Plain", VodFmt.cleanTitle("X", "Plain"))
        assertEquals("My Show S01E02", VodFmt.cleanTitle("My Show", "My Show S01E02"))
        assertEquals("1h 32m", VodFmt.runtime("01:32:00")); assertEquals("45m", VodFmt.runtime("2700")); assertNull(VodFmt.runtime(""))
        val d = VodApi.parseVodInfo("""{"info":{"backdrop_path":["http://x/b.jpg"],"duration_secs":5520}}""")
        assertEquals("http://x/b.jpg", d.backdrop); assertEquals("5520", d.duration)
    }

    /** Generates XMLTV text on the fly so the test itself never holds the document in memory. */
    private class GenReader(val channels: Int, val perChannel: Int) : java.io.Reader() {
        private var ch = -1
        private var buf = "<?xml version=\"1.0\"?><tv>"
        private var pos = 0
        private fun ts(min: Int) = "202611%02d%02d%02d00 +0000".format(11 + min / 1440, (min % 1440) / 60, min % 60)
        private fun next(): Boolean {
            ch++
            if (ch > channels) return false
            if (ch == channels) { buf = "</tv>"; pos = 0; return true }
            val sb = StringBuilder("<channel id=\"c$ch\"><display-name>Channel $ch</display-name></channel>")
            for (i in 0 until perChannel) sb.append("<programme start=\"${ts(i * 90)}\" stop=\"${ts(i * 90 + 90)}\" channel=\"c$ch\"><title>Programme $ch-$i</title><desc>${"d".repeat(120)}</desc></programme>")
            buf = sb.toString(); pos = 0
            return true
        }
        override fun read(c: CharArray, off: Int, len: Int): Int {
            if (pos >= buf.length && !next()) return -1
            val n = minOf(len, buf.length - pos)
            buf.toCharArray(pos, pos + n).copyInto(c, off)
            pos += n
            return n
        }
        override fun close() {}
    }

    @Test fun streamingGuideStaysSmall() {
        // 2000 channels x 7 days (112 programmes of 90 min each) streamed through the parser without keeping it in memory.
        val rt = Runtime.getRuntime()
        System.gc()
        val base = rt.totalMemory() - rt.freeMemory()
        var peak = 0L
        var n = 0
        val p = KXmlParser(); p.setInput(GenReader(2000, 112))
        XmltvParser.parse(p, { c, _ -> "s|$c" }, 0L, Long.MAX_VALUE) { if (++n % 20000 == 0) { System.gc(); peak = maxOf(peak, rt.totalMemory() - rt.freeMemory() - base) } }
        assertEquals(2000 * 112, n)
        assertTrue("heap grew by ${peak / 1048576} MB", peak < 96L * 1048576)
    }

    @Test fun guideLayoutMath() {
        val w0 = 10_000L * 1800 // multiple of 30 min
        val w1 = w0 + 150 * 60
        val dpm = 2f
        // inside: 30 min starting 30 min into the window -> x = 60, width = 60 - 2 gap
        val a = GuideLayout.block(w0 + 1800, w0 + 3600, w0, w1, dpm)!!
        assertEquals(60f, a.first, 0.01f); assertEquals(58f, a.second, 0.01f)
        // 15 and 90 minute items are proportional
        assertEquals(28f, GuideLayout.block(w0, w0 + 900, w0, w1, dpm)!!.second, 0.01f)
        assertEquals(178f, GuideLayout.block(w0, w0 + 5400, w0, w1, dpm)!!.second, 0.01f)
        // started before the window: drawn from the left edge, clipped
        val b = GuideLayout.block(w0 - 3600, w0 + 1800, w0, w1, dpm)!!
        assertEquals(0f, b.first, 0.01f); assertEquals(58f, b.second, 0.01f)
        // running past the end is clipped at the right edge
        val c = GuideLayout.block(w1 - 600, w1 + 3600, w0, w1, dpm)!!
        assertEquals(280f, c.first, 0.01f); assertEquals(18f, c.second, 0.01f)
        // entirely before or after: not drawn
        assertNull(GuideLayout.block(w0 - 3600, w0, w0, w1, dpm)); assertNull(GuideLayout.block(w1, w1 + 900, w0, w1, dpm))
        // ruler tick and now line use the same scale
        assertEquals(60f, GuideLayout.tickX(w0 + 1800, w0, dpm), 0.01f)
        assertEquals(w0, GuideLayout.floor30(w0 + 1799)); assertEquals(w0 - 1800, GuideLayout.floor30(w0 - 1))
    }
}