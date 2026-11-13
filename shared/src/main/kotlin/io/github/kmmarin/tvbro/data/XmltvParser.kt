// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

/** A guide refused before parsing ([XmlGuard]). */
class XmlRejected(why: String) : IOException(why)

/**
 * Guard against hostile XML (entity expansion "billion laughs", external entities / XXE) for parsers that would
 * process an internal DTD subset (Android's KXmlParser does). Real XMLTV files have at most
 * `<!DOCTYPE tv SYSTEM "xmltv.dtd">`. The prolog (everything before the root element) is read, up to [LIMIT] bytes:
 * any `<!ENTITY` declaration, or a prolog longer than that, refuses the document. Call [check] on the stream
 * before handing it to the parser, and keep DTD processing off in the parser.
 */
object XmlGuard {
    const val LIMIT = 64 * 1024

    /** Returns a stream with the same bytes; throws [XmlRejected] for a hostile prolog. */
    fun check(input: InputStream): InputStream {
        val b = if (input.markSupported()) input else BufferedInputStream(input, LIMIT + 1)
        b.mark(LIMIT + 1)
        val buf = ByteArray(LIMIT)
        var n = 0
        while (n < LIMIT) { val r = b.read(buf, n, LIMIT - n); if (r < 0) break; n += r }
        val eof = n < LIMIT
        b.reset()
        checkProlog(decode(buf, n), eof)
        return b
    }

    private fun decode(buf: ByteArray, n: Int): String = when {
        n >= 2 && buf[0] == 0xFE.toByte() && buf[1] == 0xFF.toByte() -> String(buf, 0, n, Charsets.UTF_16BE)
        n >= 2 && buf[0] == 0xFF.toByte() && buf[1] == 0xFE.toByte() -> String(buf, 0, n, Charsets.UTF_16LE)
        n >= 2 && buf[0] == 0.toByte() && buf[1] == '<'.code.toByte() -> String(buf, 0, n, Charsets.UTF_16BE)
        n >= 2 && buf[0] == '<'.code.toByte() && buf[1] == 0.toByte() -> String(buf, 0, n, Charsets.UTF_16LE)
        else -> String(buf, 0, n, Charsets.ISO_8859_1) // markup is ASCII in UTF-8 and the 8-bit encodings
    }

    /** Scans [s] (the start of a document; [complete]: nothing follows) up to the root element. */
    fun checkProlog(s: String, complete: Boolean) {
        var i = 0
        while (true) {
            val lt = s.indexOf('<', i)
            val next: Int? = when {
                lt < 0 || lt + 1 >= s.length -> null
                s.startsWith("<?", lt) -> s.indexOf("?>", lt + 2).takeIf { it >= 0 }?.plus(2)
                s.startsWith("<!--", lt) -> s.indexOf("-->", lt + 4).takeIf { it >= 0 }?.plus(3)
                s.startsWith("<!DOCTYPE", lt, ignoreCase = true) -> doctypeEnd(s, lt + 9)
                s.startsWith("<!", lt) -> throw XmlRejected("guide refused: it declares entities or other DTD markup")
                s[lt + 1].isLetter() || s[lt + 1] == '_' || s[lt + 1] == ':' -> return // the root element: the prolog is clean
                else -> lt + 1
            }
            // the prolog does not end in what was read: fine for a short (complete) document, refused otherwise
            if (next == null) { if (complete) return else throw XmlRejected("guide refused: the XML prolog is longer than ${LIMIT / 1024} KB") }
            i = next
        }
    }

    /** Index after the DOCTYPE's closing '>', null if it does not end in [s]; an internal subset with entities refuses. */
    private fun doctypeEnd(s: String, from: Int): Int? {
        var i = from
        var quote = 0.toChar()
        var depth = 0
        while (i < s.length) {
            val c = s[i]
            if (quote != 0.toChar()) { if (c == quote) quote = 0.toChar() }
            else when (c) {
                '"', '\'' -> quote = c
                '[' -> depth++
                ']' -> depth--
                '<' -> if (s.startsWith("<!ENTITY", i, ignoreCase = true)) throw XmlRejected("guide refused: it declares XML entities")
                '>' -> if (depth <= 0) return i + 1
            }
            i++
        }
        return null
    }
}

/**
 * Streaming XMLTV parser. Reads with a pull parser so memory stays flat regardless of file size;
 * programmes are handed to [sink] one by one and anything outside the time window or not
 * resolved by [resolve] is dropped immediately.
 */
object XmltvParser {
    /** XMLTV time ("20240101120000 +0100") to epoch seconds, or -1 if malformed. */
    fun parseTime(s: String): Long {
        val t = s.trim()
        if (t.length < 12) return -1
        return try {
            val n = { a: Int, b: Int -> t.substring(a, b).toInt() }
            val hasSec = t.length >= 14 && t[12].isDigit()
            val sec = if (hasSec) n(12, 14) else 0
            val rest = t.substring(if (hasSec) 14 else 12).trim()
            var off = 0L
            if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
                val sign = if (rest[0] == '-') -1 else 1
                off = sign * (rest.substring(1, 3).toLong() * 3600 + rest.substring(3, 5).toLong() * 60)
            }
            daysFromCivil(n(0, 4), n(4, 6), n(6, 8)) * 86400 + n(8, 10) * 3600 + n(10, 12) * 60 + sec - off
        } catch (e: NumberFormatException) { -1 }
    }

    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468
    }

    /**
     * @param resolve maps (channel attribute, display names seen so far for it) to an EPG key, or null to drop.
     */
    fun parse(
        p: XmlPullParser,
        resolve: (String, List<String>?) -> String?,
        minStop: Long,
        maxStart: Long,
        sink: (Programme) -> Unit,
    ): Map<String, List<String>> {
        val names = HashMap<String, MutableList<String>>()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "channel" -> readChannel(p, p.getAttributeValue(null, "id") ?: "", names)
                    "programme" -> readProgramme(p, names, resolve, minStop, maxStart, sink)
                }
            }
            ev = p.next()
        }
        return names
    }

    private fun readChannel(p: XmlPullParser, id: String, names: MutableMap<String, MutableList<String>>) {
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG ->
                    if (p.name == "display-name") names.getOrPut(id) { ArrayList() }.add(p.nextText().trim()) else depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun readProgramme(
        p: XmlPullParser, names: Map<String, List<String>>,
        resolve: (String, List<String>?) -> String?, minStop: Long, maxStart: Long,
        sink: (Programme) -> Unit,
    ) {
        val ch = p.getAttributeValue(null, "channel") ?: ""
        val start = parseTime(p.getAttributeValue(null, "start") ?: "")
        val stop = parseTime(p.getAttributeValue(null, "stop") ?: "")
        val key = if (start < 0 || stop < 0 || stop < minStop || start > maxStart) null else resolve(ch, names[ch])
        var title: String? = null
        var desc: String? = null
        var sub: String? = null
        var epNs: String? = null
        var epOn: String? = null
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> {
                    if (key != null && p.name == "title") { val s = p.nextText(); if (title == null) title = s.trim() }
                    else if (key != null && p.name == "desc") { val s = p.nextText(); if (desc == null) desc = s.trim() }
                    else if (key != null && p.name == "sub-title") { val s = p.nextText(); if (sub == null) sub = s.trim().takeIf { it.isNotEmpty() } }
                    else if (key != null && p.name == "episode-num") {
                        val sys = p.getAttributeValue(null, "system"); val s = p.nextText()
                        if (sys.equals("xmltv_ns", true)) { if (epNs == null) epNs = EpisodeNum.fromXmltvNs(s) }
                        else if (sys == null || sys.equals("onscreen", true)) { if (epOn == null) epOn = EpisodeNum.fromOnscreen(s) }
                    }
                    else depth++
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
        if (key != null) sink(Programme(key, start, stop, title ?: "", desc?.take(400), sub?.take(120), epNs ?: epOn))
    }
}
