// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.kmmarin.tvbro.ui.StreamProblem

class StreamProblemTest {
    @Test fun httpRefusalNamesTheLimit() {
        assertTrue(StreamProblem.reason(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 403, "InvalidResponseCodeException").contains("connection limit"))
        assertEquals("The provider has no stream for this channel (HTTP 404)", StreamProblem.reason(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 404, "x"))
    }

    @Test fun unreadableStreamSaysNoPicture() {
        assertEquals("No picture: the provider sent a stream that cannot be read (ParserException)",
            StreamProblem.reason(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, null, "ParserException"))
    }

    @Test fun retryCountsToTheLimit() {
        assertEquals("X. Trying again (2 of 5)...", StreamProblem.retrying("X", 2))
        assertTrue(StreamProblem.gaveUp("X").contains("OK: try again"))
    }
}
