// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.kmmarin.tvbro.ui

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import io.github.kmmarin.tvbro.*
import io.github.kmmarin.tvbro.data.Channel
import java.util.Locale

// The player overlay's choosers (video quality, audio track, captions, audio delay, aspect, sleep timer, record,
// stream info) and their value labels. Every chooser is the app's one pop-up list (m.menu, see ActionMenu), shared
// by live TV, catch-up, movies, episodes and recordings.

internal val ASPECTS = listOf("Normal" to AspectRatioFrameLayout.RESIZE_MODE_FIT, "Fill" to AspectRatioFrameLayout.RESIZE_MODE_FILL, "Zoom" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM, "16:9" to AspectRatioFrameLayout.RESIZE_MODE_FILL, "4:3" to AspectRatioFrameLayout.RESIZE_MODE_FILL)

/** Applies the saved caption size and background to a player view. */
fun applyCaptions(pv: PlayerView, m: AppModel) {
    val size = when (m.store.captionSize) { 0 -> 0.04f; 2 -> 0.075f; else -> 0.0533f }
    val bg = when (m.store.captionBg) { 0 -> android.graphics.Color.TRANSPARENT; 2 -> 0xFF000000.toInt(); else -> 0x99000000.toInt() }
    pv.subtitleView?.setFractionalTextSize(size)
    pv.subtitleView?.setStyle(CaptionStyleCompat(android.graphics.Color.WHITE, bg, android.graphics.Color.TRANSPARENT,
        if (m.store.captionBg == 0) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_NONE, android.graphics.Color.BLACK, null))
    pv.resizeMode = ASPECTS[m.aspectMode.coerceIn(0, ASPECTS.size - 1)].second
}

// (cached: the overlay builds its value labels on every recomposition and ICU lookups are slow on a Fire TV)
private val langNames = java.util.concurrent.ConcurrentHashMap<String, String>()
private fun lang(code: String?): String? = code?.takeIf { it.isNotBlank() && it != "und" }?.let { c -> langNames.getOrPut(c) { Locale(c).displayLanguage.ifBlank { c } } }

private fun layout(n: Int) = when (n) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> if (n > 0) "${n}ch" else "" }

private fun isCc(f: Format) = f.sampleMimeType?.let { it.contains("cea-608", true) || it.contains("cea-708", true) } == true

/** "English CC1", "Spanish", "CC3": a caption / subtitle track's short name. */
private fun textName(f: Format, i: Int): String {
    Captions.name(f, lang(f.language))?.let { return it }
    val cc = isCc(f)
    val ch = if (cc) f.accessibilityChannel.takeIf { it != Format.NO_VALUE }?.let { "CC$it" } ?: "CC" else null
    return listOfNotNull(lang(f.language), f.label?.takeIf { it.isNotBlank() }, ch).distinct().joinToString(" ").ifBlank { "Track ${i + 1}" }
}

private fun trackRows(exo: ExoPlayer, type: Int, offLabel: String?, chosen: (lang: String?, on: Boolean) -> Unit = { _, _ -> }): List<Pair<String, () -> Unit>> {
    val rows = ArrayList<Pair<String, () -> Unit>>()
    val textOff = exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
    for (g in exo.currentTracks.groups) {
        if (g.type != type) continue
        for (i in 0 until g.length) {
            val f = g.getTrackFormat(i)
            val name = when (type) {
                C.TRACK_TYPE_VIDEO -> "${f.width}x${f.height}" + (if (f.frameRate > 0) " ${"%.0f".format(f.frameRate)} fps" else "") + (if (f.bitrate > 0) "  ${f.bitrate / 1000} kbps" else "")
                C.TRACK_TYPE_AUDIO -> listOfNotNull(f.label, lang(f.language), layout(f.channelCount).ifEmpty { null }).distinct().joinToString(" ").ifBlank { "Track ${i + 1}" }
                else -> textName(f, i) + if (isCc(f) && Captions.name(f, null) == null) "  (closed captions)" else ""
            }
            val sel = g.isTrackSelected(i) && !(type == C.TRACK_TYPE_TEXT && textOff)
            rows += ((if (sel) "* " else "  ") + name) to {
                var b = exo.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, listOf(i)))
                if (type == C.TRACK_TYPE_TEXT) b = b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                exo.trackSelectionParameters = b.build(); chosen(f.language, true)
            }
        }
    }
    if (type == C.TRACK_TYPE_VIDEO) rows.add(0, "  Automatic" to {
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
    })
    if (offLabel != null) rows += ((if (textOff || exo.currentTracks.groups.none { it.type == C.TRACK_TYPE_TEXT && it.isSelected }) "* " else "  ") + offLabel) to {
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build(); chosen(null, false)
    }
    return rows
}

private fun pick(m: AppModel, title: String, rows: List<Pair<String, () -> Unit>>, empty: String = "Nothing to choose", focus: Int = -1) {
    if (rows.isEmpty()) m.toast(empty) else m.menu = MenuState(title, rows, focus)
}

// ---- value labels (under the overlay's icons) ----

/**
 * Short resolution label of the playing video: "4K", "1080p", "720p"... (empty before the first frame). Classed by the
 * 16:9 frame it fills, so a letterboxed film (1920x1040, 1920x816) is "1080p", not "1040p".
 */
fun resolutionLabel(exo: ExoPlayer): String {
    val v = exo.videoFormat ?: return ""
    if (v.width <= 0 || v.height <= 0) return ""
    val w = maxOf(maxOf(v.width, v.height), minOf(v.width, v.height) * 16 / 9)
    return when { w >= 3200 -> "4K"; w >= 1800 -> "1080p"; w >= 1200 -> "720p"; else -> "${minOf(v.width, v.height)}p" }
}

fun videoLabel(exo: ExoPlayer): String = exo.videoFormat?.takeIf { it.width > 0 }?.let { "${it.width}×${it.height}" } ?: "Auto"

fun audioLabel(exo: ExoPlayer): String {
    val a = exo.audioFormat
    return listOfNotNull(lang(a?.language), a?.channelCount?.let { layout(it) }?.ifEmpty { null }).joinToString(" ").ifBlank { "Default" }
}

/** Closed captions / subtitles state: "Off", "English CC1", "Spanish"... */
fun captionLabel(exo: ExoPlayer): String {
    if (exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return "Off"
    for (g in exo.currentTracks.groups) {
        if (g.type != C.TRACK_TYPE_TEXT || !g.isSelected) continue
        for (i in 0 until g.length) if (g.isTrackSelected(i)) return textName(g.getTrackFormat(i), i)
    }
    return "Off"
}

fun delayLabel(ms: Int): String = if (ms == 0) "0 ms" else "%+d ms".format(ms)

fun aspectLabel(m: AppModel): String = ASPECTS[m.aspectMode.coerceIn(0, ASPECTS.size - 1)].first

fun sleepLabel(m: AppModel, nowMs: Long): String =
    if (m.sleepAt > 0) "${((m.sleepAt - nowMs) / 60000 + 1).toInt()} min" else "Off"

// ---- choosers ----

fun pickVideo(m: AppModel, exo: ExoPlayer) = pick(m, "Video quality", trackRows(exo, C.TRACK_TYPE_VIDEO, null))

/** [remember]: the choice is kept for the series (episodes). */
fun pickAudio(m: AppModel, exo: ExoPlayer, remember: Boolean) =
    pick(m, "Audio track", trackRows(exo, C.TRACK_TYPE_AUDIO, null, { l, _ -> if (remember) m.rememberTrack(true, l, true) }))

/**
 * Closed captions (CEA-608 / 708 of live TV) and subtitles: one list of every text track, plus Off. [remember]: the
 * choice is kept for the series (episodes); [live]: the choice (a language, or off) is kept and applied to every
 * channel (see [liveCaptionParams]).
 */
fun pickCaptions(m: AppModel, exo: ExoPlayer, remember: Boolean, live: Boolean = false) {
    if (exo.currentTracks.groups.none { it.type == C.TRACK_TYPE_TEXT }) { m.toast(if (live) "No captions on this channel" else "No captions or subtitles in this stream"); return }
    // captions off: the list opens on the first caption track (opening it is mostly to turn them on: OK, OK)
    val off = captionLabel(exo) == "Off"
    pick(m, "Captions and subtitles", focus = if (off) 0 else -1, rows = trackRows(exo, C.TRACK_TYPE_TEXT, "Off", { l, on ->
        if (live) {
            m.store.liveCaptions = if (on) (l?.takeIf { it.isNotBlank() && it != "und" } ?: "en") else ""
            exo.trackSelectionParameters = liveCaptionParams(exo.trackSelectionParameters, m.store.liveCaptions)
        } else if (remember) m.rememberTrack(false, l, on)
    }))
}

/** Live TV caption preference applied to the player: off, or captions in [lang] (also on the next channels). */
fun liveCaptionParams(p: androidx.media3.common.TrackSelectionParameters, lang: String): androidx.media3.common.TrackSelectionParameters =
    if (lang.isEmpty()) p.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
    else p.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(lang).setSelectUndeterminedTextLanguage(true).build()

/** Audio delay for [key] (a channel or title); the list stays open while stepping. */
fun pickDelay(m: AppModel, key: String?) {
    m.menu = MenuState("Audio delay: " + delayLabel(m.audioDelayMs), listOf(
        "+50 ms (audio later)" to { m.changeAudioDelay(50, key); pickDelay(m, key) },
        "-50 ms (audio earlier)" to { m.changeAudioDelay(-50, key); pickDelay(m, key) },
        "+250 ms" to { m.changeAudioDelay(250, key); pickDelay(m, key) },
        "-250 ms" to { m.changeAudioDelay(-250, key); pickDelay(m, key) },
        "Reset to 0" to { m.changeAudioDelay(0, key); pickDelay(m, key) },
    ))
}

fun pickAspect(m: AppModel, pv: PlayerView?) =
    pick(m, "Aspect / zoom", ASPECTS.mapIndexed { i, p -> ((if (i == m.aspectMode) "* " else "  ") + p.first) to { m.aspectMode = i; pv?.resizeMode = p.second } })

fun pickSleep(m: AppModel) = pick(m, "Sleep timer", listOf(0, 15, 30, 60, 90).map { (if (it == 0) "  Off" else "  $it minutes") to { m.setSleep(it) } })

fun showStreamInfo(m: AppModel, exo: ExoPlayer) {
    val l = techInfo(exo).split("  |  ").filter { it.isNotBlank() }
    m.menu = MenuState("Stream info", l.ifEmpty { listOf("No stream information yet") }.map { it to {} })
}

/** Red button / Rec item: stop this channel's running recording, or the app's one record chooser (AppModel.recordMenu). */
fun pickRecord(m: AppModel, ch: Channel) {
    val a = m.activeRec(ch)
    if (a != null) m.menu = MenuState("Recording ${ch.name}", listOf("Stop recording" to { m.stopRec(a) }))
    else m.recordMenu(ch)
}