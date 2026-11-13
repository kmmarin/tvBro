// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.kmmarin.tvbro.ui

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer

/**
 * Shifts audio against video. A positive target inserts silence (audio later); a negative target drops
 * samples (audio earlier). The change is relative to what was already applied, so it works live while
 * playing. Only 16-bit PCM is handled (passthrough or float output is left untouched).
 */
class AudioDelayProcessor : BaseAudioProcessor() {
    @Volatile var targetMs: Int = 0
    private var applied = 0L
    private var pending = 0L
    private var drop = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val fmt = inputAudioFormat
        val frame = fmt.bytesPerFrame
        val want = targetMs.toLong() * fmt.sampleRate / 1000
        if (want != applied) {
            val d = want - applied
            if (d > 0) pending += d else drop += -d
            applied = want
        }
        if (drop > 0) {
            val n = (minOf(drop * frame, inputBuffer.remaining().toLong()).toInt() / frame) * frame
            inputBuffer.position(inputBuffer.position() + n)
            drop -= (n / frame).toLong()
        }
        val silence = (minOf(pending, 2L * fmt.sampleRate) * frame).toInt()
        pending = 0
        val out = replaceOutputBuffer(silence + inputBuffer.remaining())
        if (silence > 0) out.put(ByteArray(silence))
        out.put(inputBuffer)
        out.flip()
    }

    override fun onFlush() { applied = 0; pending = 0; drop = 0 }
    override fun onReset() { onFlush() }
}

/** Renderers factory whose audio sink runs [p] (so the audio delay can be changed live). */
fun delayFactory(ctx: Context, p: AudioDelayProcessor): DefaultRenderersFactory = object : DefaultRenderersFactory(ctx) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf<AudioProcessor>(p))
            .build()
}
