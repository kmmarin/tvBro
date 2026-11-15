// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.kmmarin.tvbro.ui

import android.os.SystemClock
import android.util.Log
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData

/**
 * Where a channel change spends its time (debug builds, with [Lat] on): logcat tag "tvbro-zap", each line with the
 * uptime in ms (the clock key events and frames use) and the ms since the change started. Never logs an address.
 */
object ZapTrace {
    private var t0 = 0L
    /**
     * Debug builds: with [Lat] on, or `adb shell setprop debug.tvbro.zaptrace 1` (read once, at the first use). Release
     * builds never read the property (no debug.tvbro.* switch reaches them; their logging is stripped anyway).
     */
    fun on() = Lat.enabled || prop
    private val prop: Boolean by lazy {
        io.github.kmmarin.tvbro.BuildConfig.DEBUG &&
            runCatching { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.tvbro.zaptrace") == "1" }.getOrDefault(false)
    }

    fun start(what: String) { if (!on()) return; t0 = SystemClock.uptimeMillis(); log("start $what") }

    fun log(what: String) {
        if (!on()) return
        val t = SystemClock.uptimeMillis()
        Log.i("tvbro-zap", "$t +${t - t0} $what")
    }

    /** OkHttp's events of the live player's requests (DNS, connect, response headers). */
    val http = object : okhttp3.EventListener() {
        override fun callStart(call: okhttp3.Call) = log("http call ${call.request().url.host.hashCode().toString(16)}")
        override fun dnsStart(call: okhttp3.Call, domainName: String) = log("http dns start")
        override fun dnsEnd(call: okhttp3.Call, domainName: String, inetAddressList: List<java.net.InetAddress>) = log("http dns end")
        override fun connectStart(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) = log("http connect start")
        override fun connectEnd(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: okhttp3.Protocol?) = log("http connect end")
        override fun connectionAcquired(call: okhttp3.Call, connection: okhttp3.Connection) = log("http connection acquired")
        override fun responseHeadersEnd(call: okhttp3.Call, response: okhttp3.Response) = log("http headers ${response.code}")
        override fun responseBodyStart(call: okhttp3.Call) = log("http body start")
        override fun callEnd(call: okhttp3.Call) = log("http call end")
        override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) = log("http call failed ${ioe.javaClass.simpleName}")
    }

    /** The player's side: loads, formats, decoder, first frame. */
    val player = object : AnalyticsListener {
        override fun onLoadStarted(e: AnalyticsListener.EventTime, l: LoadEventInfo, d: MediaLoadData) = log("load started type ${d.dataType}")
        override fun onLoadCompleted(e: AnalyticsListener.EventTime, l: LoadEventInfo, d: MediaLoadData) = log("load completed type ${d.dataType} ${l.bytesLoaded} B")
        override fun onDownstreamFormatChanged(e: AnalyticsListener.EventTime, d: MediaLoadData) = log("format ${d.trackType}")
        override fun onVideoDecoderInitialized(e: AnalyticsListener.EventTime, name: String, initializedTimestampMs: Long, initializationDurationMs: Long) = log("video decoder ($initializationDurationMs ms)")
        override fun onRenderedFirstFrame(e: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) = log("first frame")
        override fun onPlaybackStateChanged(e: AnalyticsListener.EventTime, state: Int) = log("state $state")
        override fun onIsLoadingChanged(e: AnalyticsListener.EventTime, isLoading: Boolean) = log("loading $isLoading")
    }
}
