// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui

import androidx.media3.common.PlaybackException

/** What the player says when a live stream does not play (shown in the overlay's banner, see PlayerScreen). */
object StreamProblem {
    /** Tries before giving up (TiviMate: 5). */
    const val MAX_TRIES = 5

    /** A short reason in plain words, with the technical name in brackets (as TiviMate shows it). */
    fun reason(e: PlaybackException): String {
        var http: Int? = null
        var c: Throwable? = e
        while (c != null && http == null) {
            if (c is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) http = c.responseCode
            c = c.cause
        }
        // (class names are shortened in release builds: "f0" for ParserException; the error code's name is stable)
        val tech = when (e.cause) {
            is androidx.media3.common.ParserException -> "ParserException"
            is java.net.SocketTimeoutException -> "timeout"
            is java.net.UnknownHostException -> "unknown host"
            else -> e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')
        }
        return reason(e.errorCode, http, tech)
    }

    /** (unit tested) */
    fun reason(code: Int, http: Int?, tech: String): String = when {
        http == 401 || http == 403 -> "The provider refused this channel (HTTP $http: login or connection limit)"
        http == 404 -> "The provider has no stream for this channel (HTTP 404)"
        http != null -> "The provider answered with an error (HTTP $http)"
        code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "No connection to the provider ($tech)"
        code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "The provider did not answer in time ($tech)"
        code in 3000..3999 -> "No picture: the provider sent a stream that cannot be read ($tech)"
        code in 4000..4999 -> "This stream's format cannot be played on this device ($tech)"
        code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "The stream fell behind ($tech)"
        else -> "The stream stopped ($tech)"
    }

    fun retrying(why: String, attempt: Int): String = "$why. Trying again ($attempt of $MAX_TRIES)..."

    fun gaveUp(why: String): String = "$why. This channel is not working right now.   OK: try again   Up / Down: another channel   Back: the guide"
}
