// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

data class M3uResult(val channels: List<Channel>, val epgUrls: List<String>)

/** Parser for extended M3U / M3U8 playlists (#EXTM3U, #EXTINF with key="value" attributes). */
object M3uParser {
    private val attrRe = Regex("([A-Za-z0-9_.-]+)=\"([^\"]*)\"")

    private fun attrs(line: String): Map<String, String> {
        val m = HashMap<String, String>()
        for (r in attrRe.findAll(line)) m[r.groupValues[1].lowercase()] = r.groupValues[2]
        return m
    }

    private fun nameOf(line: String): String {
        val last = attrRe.findAll(line).lastOrNull()?.range?.last ?: line.indexOf(':')
        val comma = line.indexOf(',', maxOf(last, 0))
        return if (comma < 0) "" else line.substring(comma + 1).trim()
    }

    fun parse(lines: Sequence<String>, sourceId: String): M3uResult {
        val out = ArrayList<Channel>()
        val epg = ArrayList<String>()
        var a: Map<String, String>? = null
        var name = ""
        var grp: String? = null
        for (raw in lines) {
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U", true) -> {
                    val h = attrs(line)
                    (h["url-tvg"] ?: h["x-tvg-url"])?.split(',')?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }?.let { epg += it }
                }
                line.startsWith("#EXTINF", true) -> { a = attrs(line); name = nameOf(line) }
                line.startsWith("#EXTGRP:", true) -> grp = line.substring(8).trim()
                line.startsWith("#") -> {}
                else -> {
                    val at = a ?: emptyMap()
                    val tvgId = at["tvg-id"]?.takeIf { it.isNotBlank() }
                    val title = name.ifBlank { at["tvg-name"] ?: "Channel ${out.size + 1}" }
                    val type = (at["catchup"] ?: at["catchup-type"])?.lowercase()?.takeIf { it.isNotBlank() }
                    val src = at["catchup-source"]?.takeIf { it.isNotBlank() }
                    val days = at["catchup-days"]?.toIntOrNull() ?: at["tvg-rec"]?.toIntOrNull()
                        ?: if (type != null) 7 else 0
                    out += Channel(
                        key = "$sourceId|${tvgId ?: ""}|${line.hashCode().toString(16)}",
                        sourceId = sourceId,
                        name = title,
                        logo = at["tvg-logo"]?.takeIf { it.isNotBlank() },
                        group = (at["group-title"] ?: grp)?.takeIf { it.isNotBlank() } ?: "Ungrouped",
                        url = line,
                        tvgId = tvgId,
                        number = at["tvg-chno"]?.toIntOrNull() ?: at["channel-number"]?.toIntOrNull() ?: 0,
                        catchup = type?.let { Catchup(it, src) },
                        catchupDays = days,
                    )
                    a = null; name = ""; grp = null
                }
            }
        }
        return M3uResult(out, epg)
    }
}
