// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * How focus moves scroll the lists (Settings > Appearance > Scrolling). The one place that decides it: every vertical
 * and horizontal list, row and grid in the app scrolls through this (provided app-wide by [FocusScrollProvider];
 * explicit jumps to an item go through [reveal]).
 *
 *  - centred ("List moves", the default): the focused item stays in the middle and the list moves under it; at the
 *    start and end of a list the list stops (the scroll is clamped) and the focus walks to the edge item, so there is
 *    never empty space beyond the ends;
 *  - edge ("Selection moves"): the least scroll that shows the focused item, the list only moves at its edges.
 *
 * Both scroll in the same frame (no animation): an animated scroll per key piled up while a key was held, the list
 * fell behind and the focused row left the screen.
 */
object FocusScroll {
    var centred by mutableStateOf(true)

    /** Pixels to scroll so an item at [offset] (from the viewport's start) of [size] shows as the mode wants. */
    fun distance(centred: Boolean, offset: Float, size: Float, container: Float): Float {
        // (an item as large as the viewport or larger: its start, like the edge mode, so its beginning is readable)
        if (!centred || size >= container) return edge(offset, size, container)
        // in the middle slot of whole items, not the exact middle: the items before it then start at the viewport's
        // edge (the exact middle cut the top guide row in half under the time ruler, e.g. 6.3 rows of room)
        val slot = kotlin.math.round((container / size - 1f) / 2f).coerceAtLeast(0f)
        val d = offset - slot * size
        return if (kotlin.math.abs(d) < 1f) 0f else d
    }

    /** The least scroll that shows the item (Compose's default distances). */
    fun edge(offset: Float, size: Float, container: Float): Float {
        val trailing = offset + size
        return when {
            offset >= 0 && trailing <= container -> 0f // fully visible
            offset < 0 && trailing > container -> 0f // larger than the viewport and covering it
            kotlin.math.abs(offset) < kotlin.math.abs(trailing - container) -> offset
            else -> trailing - container
        }
    }

    /**
     * First visible index for a list opening at [index] before anything is measured: [edgeLead] items above it in the
     * edge mode, half of the [perScreen] items that fit above it when centred (focus arriving then centres it exactly).
     */
    fun first(index: Int, edgeLead: Int, perScreen: Int): Int =
        (index - if (centred) (perScreen - 1) / 2 else edgeLead).coerceAtLeast(0)

    @OptIn(ExperimentalFoundationApi::class)
    private class Spec(val centred: Boolean) : BringIntoViewSpec {
        @Suppress("OVERRIDE_DEPRECATION")
        override val scrollAnimationSpec: AnimationSpec<Float> = snap()
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = distance(centred, offset, size, containerSize)
    }

    @OptIn(ExperimentalFoundationApi::class)
    private val Centred: BringIntoViewSpec = Spec(true)
    @OptIn(ExperimentalFoundationApi::class)
    private val Edge: BringIntoViewSpec = Spec(false)

    @OptIn(ExperimentalFoundationApi::class)
    fun spec(centred: Boolean = this.centred): BringIntoViewSpec = if (centred) Centred else Edge
}

/** Every scrollable inside [content] scrolls to the focused item the [FocusScroll] way (set once, at the app's root). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusScrollProvider(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides FocusScroll.spec(), content = content)
}

/** Scrolls item [index] to where a focus move would put it: centred, else [lead] items below the top. */
suspend fun LazyListState.reveal(index: Int, lead: Int = 0) {
    if (!FocusScroll.centred) { scrollToItem((index - lead).coerceAtLeast(0)); return }
    scrollToItem(index) // (measured at once: its size and place are known below)
    val li = layoutInfo
    val it = li.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val d = FocusScroll.distance(true, (it.offset - li.viewportStartOffset).toFloat(), it.size.toFloat(), (li.viewportEndOffset - li.viewportStartOffset).toFloat())
    if (d != 0f) scrollBy(d)
}

/** [LazyListState.reveal] for grids (the row of item [index]). */
suspend fun LazyGridState.reveal(index: Int, lead: Int = 0) {
    if (!FocusScroll.centred) { scrollToItem((index - lead).coerceAtLeast(0)); return }
    scrollToItem(index)
    val li = layoutInfo
    val it = li.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val v = li.orientation == Orientation.Vertical
    val off = (if (v) it.offset.y else it.offset.x) - li.viewportStartOffset
    val size = if (v) it.size.height else it.size.width
    val d = FocusScroll.distance(true, off.toFloat(), size.toFloat(), (li.viewportEndOffset - li.viewportStartOffset).toFloat())
    if (d != 0f) scrollBy(d)
}
