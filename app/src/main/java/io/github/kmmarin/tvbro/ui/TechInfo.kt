// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.kmmarin.tvbro.ui

import androidx.media3.exoplayer.ExoPlayer

/** One-line technical summary of the current stream, read from the player's formats and decoder counters. */
fun techInfo(p: ExoPlayer): String {
    val v = p.videoFormat
    val a = p.audioFormat
    val parts = ArrayList<String>()
    if (v != null && v.width > 0) {
        var s = "${v.width}x${v.height}"
        if (v.height > 0) s += " (${v.height}p)"
        if (v.frameRate > 0) s += "  " + "%.2f".format(v.frameRate).trimEnd('0').trimEnd('.') + " fps"
        parts += s
        (v.sampleMimeType ?: v.codecs)?.substringAfter('/')?.uppercase()?.let { parts += it }
        if (v.bitrate > 0) parts += "%.1f Mbps".format(v.bitrate / 1_000_000.0)
    }
    if (a != null) {
        val codec = (a.sampleMimeType ?: a.codecs)?.substringAfter('/')?.uppercase() ?: "audio"
        val ch = when (a.channelCount) { 1 -> "1.0"; 2 -> "2.0"; 6 -> "5.1"; 8 -> "7.1"; 0, -1 -> ""; else -> "${a.channelCount}ch" }
        parts += "$codec $ch".trim()
    }
    p.videoDecoderCounters?.let { it.ensureUpdated(); parts += "dropped ${it.droppedBufferCount}" }
    return parts.joinToString("  |  ")
}