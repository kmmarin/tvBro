// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/**
 * Closed captions in live TV. US channels carry CEA-608 / CEA-708 captions inside the video (H.264 / H.265 SEI), which
 * Media3 only exposes when told which caption channels to look for: an MPEG-TS stream with no caption descriptor (most
 * IPTV streams) showed no caption track at all, and HLS only with a CLOSED-CAPTIONS declaration. DVB subtitles and
 * WebVTT tracks need nothing extra.
 */
@UnstableApi
object Captions {
    /** The caption channels looked for in the video: CEA-608 CC1 and CC3, CEA-708 service 1. */
    val formats: List<Format> = listOf(
        Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).setAccessibilityChannel(1).setLanguage("en").build(),
        Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).setAccessibilityChannel(3).setLanguage("en").build(),
        Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA708).setAccessibilityChannel(1).setLanguage("en").build(),
    )

    /** (only the caption formats change: the TS reader flags stay as they were, so playback is unchanged) */
    fun extractors(): DefaultExtractorsFactory = DefaultExtractorsFactory().setTsSubtitleFormats(formats)

    /** Media sources with captions exposed: HLS through its own extractor factory, everything else as before. */
    fun factory(ds: DataSource.Factory): MediaSource.Factory = Factory(ds)

    private class Factory(private val ds: DataSource.Factory) : MediaSource.Factory {
        private val def = DefaultMediaSourceFactory(ds, extractors())
        private val hls = HlsMediaSource.Factory(ds)
            // CC1 even when the playlist declares no CLOSED-CAPTIONS (IPTV playlists rarely do)
            .setExtractorFactory(DefaultHlsExtractorFactory(0, true))

        override fun setDrmSessionManagerProvider(p: DrmSessionManagerProvider): MediaSource.Factory { def.setDrmSessionManagerProvider(p); hls.setDrmSessionManagerProvider(p); return this }
        override fun setLoadErrorHandlingPolicy(p: LoadErrorHandlingPolicy): MediaSource.Factory { def.setLoadErrorHandlingPolicy(p); hls.setLoadErrorHandlingPolicy(p); return this }
        override fun getSupportedTypes(): IntArray = def.supportedTypes

        override fun createMediaSource(item: MediaItem): MediaSource {
            val c = item.localConfiguration
            val type = if (c == null) C.CONTENT_TYPE_OTHER else Util.inferContentTypeForUriAndMimeType(c.uri, c.mimeType)
            return if (type == C.CONTENT_TYPE_HLS) hls.createMediaSource(item) else def.createMediaSource(item)
        }
    }

    /** "English CC1", "English CC (708, service 1)": caption tracks named for the menu. */
    fun name(f: Format, lang: String?): String? {
        val m = f.sampleMimeType ?: return null
        val l = lang ?: "English"
        return when {
            m == MimeTypes.APPLICATION_CEA608 -> "$l CC${if (f.accessibilityChannel != Format.NO_VALUE) f.accessibilityChannel else 1}"
            m == MimeTypes.APPLICATION_CEA708 -> "$l CC (708, service ${if (f.accessibilityChannel != Format.NO_VALUE) f.accessibilityChannel else 1})"
            m == MimeTypes.APPLICATION_DVBSUBS -> "$l (DVB subtitles)"
            else -> null
        }
    }
}
