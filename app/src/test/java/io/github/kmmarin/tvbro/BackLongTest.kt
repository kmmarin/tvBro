// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import io.github.kmmarin.tvbro.data.BackLong

class BackLongTest {
    @Test fun playingChannelWinsOverFocus() {
        // focus still on the channel that was full screen before, the preview plays another one
        assertEquals("playing", BackLong.target("playing", "lastFullScreen"))
    }

    @Test fun focusOnlyWhenNothingPlayed() {
        assertEquals("focused", BackLong.target(null, "focused"))
        assertNull(BackLong.target<String>(null, null))
    }
}
