// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Builds archive (catch-up) stream URLs. All times are epoch seconds. */
object CatchupUrl {
    // Both braces escaped: Android's ICU regex rejects a bare "}" (the JVM accepts it).
    private val ph = Regex("""\$?\{(\w+)\}""")

    fun supports(ch: Channel, startSec: Long, nowSec: Long): Boolean =
        ch.catchup != null && ch.catchupDays > 0 && startSec >= nowSec - ch.catchupDays * 86400L

    fun build(ch: Channel, startSec: Long, endSec: Long, nowSec: Long): String? {
        val c = ch.catchup ?: return null
        val durSec = (endSec - startSec).coerceAtLeast(60)
        return when (c.type) {
            "xc" -> c.template?.replace("{duration}", (durSec / 60).toString())
                ?.replace("{start}", fmt(startSec, "yyyy-MM-dd:HH-mm"))
            "append" -> c.template?.let { ch.url + subst(it, startSec, endSec, nowSec) }
            "shift" -> ch.url + (if ('?' in ch.url) "&" else "?") + "utc=$startSec&lutc=$nowSec"
            else -> (c.template ?: return null).let { subst(it, startSec, endSec, nowSec) }
        }
    }

    private fun subst(t: String, s: Long, e: Long, now: Long): String = ph.replace(t) { m ->
        when (m.groupValues[1]) {
            "start", "utc" -> s.toString()
            "end", "utcend" -> e.toString()
            "timestamp", "lutc", "now" -> now.toString()
            "offset" -> (now - s).toString()
            "duration" -> (e - s).toString()
            else -> m.value
        }
    }

    private fun fmt(sec: Long, pattern: String): String {
        val f = SimpleDateFormat(pattern, Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(sec * 1000))
    }
}
