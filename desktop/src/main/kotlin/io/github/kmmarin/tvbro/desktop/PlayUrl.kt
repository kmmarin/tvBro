// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import java.io.File

/**
 * Which addresses tvBro hands to mpv or an external player. Playlist addresses come from the provider: only network
 * streams pass (no file://, edl://, av://, lavfi, memory://, nothing that starts with '-'). Local files are accepted only
 * where tvBro made the address itself (recordings: [trustedLocal]).
 */
object PlayUrl {
    private val network = Regex("^(https?|rtmps?|rtmpt|rtsp|rtsps|rtp|udp|srt)://[^\\s]+$", RegexOption.IGNORE_CASE)

    /** A network stream address from a playlist (no spaces or control characters). */
    fun isNetwork(url: String): Boolean = url.none { it.isISOControl() } && network.matches(url)

    /** An absolute local path (a recording), not an option and not a protocol address. */
    fun isLocalFile(url: String): Boolean {
        if (url.isEmpty() || url.startsWith("-") || url.contains("://") || url.any { it.isISOControl() }) return false
        val windowsAbs = Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(url) || url.startsWith("\\\\")
        return windowsAbs || url.startsWith("/")
    }

    /** May [url] be played? [trustedLocal]: tvBro built the address (a recording file), so a local path is fine. */
    fun allowed(url: String, trustedLocal: Boolean = false): Boolean = isNetwork(url) || (trustedLocal && isLocalFile(url))

    /** Why [url] is refused, for the toast (never the address itself: it may carry the login). */
    fun refusal(url: String): String = when {
        url.startsWith("-") -> "This address looks like a player option and was not played"
        url.contains("://") -> "This kind of address (${url.substringBefore("://").take(10)}) is not played, only network streams"
        else -> "This address is not a network stream and was not played"
    }
}

/** Command line for the external player: an argument list (no shell), with `--` before the address for mpv / VLC. */
object ExternalPlayer {
    /** The program and its fixed arguments from Settings (a path with spaces that exists stays one argument). */
    fun parts(cmd: String, exists: (String) -> Boolean = { File(it).exists() }): List<String> =
        cmd.trim().let { c -> if (c.isEmpty()) emptyList() else if (exists(c)) listOf(c) else c.split(Regex("\\s+")) }

    /** mpv, VLC and mplayer stop reading options at `--`, so even a strange address is never an option. */
    fun knowsDoubleDash(program: String): Boolean {
        val n = program.replace('\\', '/').substringAfterLast('/').lowercase().removeSuffix(".exe")
        return n in setOf("mpv", "vlc", "cvlc", "mplayer", "smplayer", "celluloid", "mpv.com")
    }

    /** The full command, or null when [url] may not be played ([PlayUrl.allowed]). */
    fun command(cmd: String, url: String, trustedLocal: Boolean = false, exists: (String) -> Boolean = { File(it).exists() }): List<String>? {
        if (!PlayUrl.allowed(url, trustedLocal)) return null
        val p = parts(cmd, exists)
        if (p.isEmpty()) return null
        return if (knowsDoubleDash(p[0])) p + "--" + url else p + url
    }
}
