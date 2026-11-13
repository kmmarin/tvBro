// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import io.github.kmmarin.tvbro.data.Net

val Bg = Color(0xFF0E1116)
val Surface = Color(0xFF171C24)
val FocusBg = Color(0xFF263447)
val Accent = Color(0xFF4FC3F7)
val TextMain = Color(0xFFE6EAF0)
val TextDim = Color(0xFF8A94A3)

/** Clearly visible D-pad focus: filled background plus a thick accent border. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tvFocus(
    shape: Shape = RoundedCornerShape(8.dp),
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    // Focus is read from the clickable's own interactions: an onFocusChanged here missed the focus of rows that
    // recomposed while it moved in (TV screen channel list), leaving a focused item without its border.
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    val me = remember { SafeFocus() }
    val was = remember { booleanArrayOf(false) }
    val inRail = LocalInRail.current
    androidx.compose.runtime.SideEffect {
        if (f != was[0]) { was[0] = f; if (f) { FocusMemory.gained(); FocusMemory.focused(me, inRail) } else FocusMemory.lost() }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (was[0]) { was[0] = false; FocusMemory.lost() } } }
    this.safeFocus(me)
        // the remote's Menu key opens the same menu as a long press of OK
        .onPreviewKeyEvent { e ->
            if (onLongClick != null && e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.Menu) { onLongClick(); true } else false
        }
        .background(if (f) FocusBg else Color.Transparent, shape)
        .border(if (f) 3.dp else 0.dp, if (f) Accent else Color.Transparent, shape)
        .clip(shape)
        .combinedClickable(interactionSource = src, indication = null, onLongClick = onLongClick, onClick = onClick)
}

/**
 * A FocusRequester that knows whether its target is composed. Lazy rows dispose items scrolled off screen, and
 * requesting focus (or naming it in focusProperties) for a disposed target throws IllegalStateException.
 * Use [target] in focusProperties and [request] instead of FocusRequester.requestFocus().
 */
class SafeFocus {
    val fr = FocusRequester()
    // observable count for composables (LaunchedEffect keys); focus code (focusProperties lambdas, key handlers) reads
    // the plain count, since a state object created in a composition not yet applied must not be read from there
    private var attached by androidx.compose.runtime.mutableIntStateOf(0)
    private var count = 0
    val isAttached: Boolean get() = attached > 0
    val attachedNow: Boolean get() = count > 0
    fun target(): FocusRequester = if (count > 0) fr else FocusRequester.Default
    fun request(): Boolean = count > 0 && runCatching { fr.requestFocus() }.isSuccess
    internal fun attach() { count++; attached = count }
    internal fun detach() { count--; attached = count }
}

/**
 * Attaches [s] (or nothing when null) without changing the modifier chain's shape: when an item became or stopped
 * being the target while it held focus (the TV screen's focused row, the selected group, the remembered title), a
 * chain that gained or lost a node recreated the focus node and focus fell to an invisible parent: the "focus lost /
 * stuck" bug.
 */
fun Modifier.safeFocus(s: SafeFocus?): Modifier = composed {
    val own = remember { FocusRequester() }
    if (s != null) androidx.compose.runtime.DisposableEffect(s) { s.attach(); onDispose { s.detach() } }
    focusRequester(s?.fr ?: own)
}

/**
 * Recently focused items, newest first. When focus is lost (a menu or dialog closed, the focused item scrolled or timed
 * out of the screen) it goes back to the newest one that is still on screen: the item the user was on.
 */
object FocusMemory {
    private val recent = ArrayList<SafeFocus>()
    // the same without the side menu: where Right from the side menu goes back to
    private val content = ArrayList<SafeFocus>()
    // rescue attempts since focus was last seen: each one tries the next older item (a request cannot report failure)
    private var tries = 0
    // Focused items that show it (tvFocus, the player surfaces, text fields: see countFocus). The root's own focus
    // state cannot tell: when a focused item goes away Compose leaves focus on an invisible parent group.
    private var count by androidx.compose.runtime.mutableIntStateOf(0)
    /** Whether a visible item holds focus. */
    val anyFocus: Boolean get() = count > 0
    internal fun gained() { count++; tries = 0 }
    internal fun lost() { if (count > 0) count-- }

    fun focused(s: SafeFocus, inRail: Boolean = false) {
        fun push(l: ArrayList<SafeFocus>) { l.remove(s); l.add(0, s); if (l.size > 24) l.removeAt(l.size - 1) }
        push(recent); if (!inRail) push(content)
    }

    /**
     * Focuses a remembered item still composed: the newest on the first try, older ones on later tries while focus
     * stays lost (a request cannot report failure); false when none is left (the caller then moves focus anywhere).
     */
    fun restore(): Boolean {
        val s = recent.filter { it.attachedNow }.getOrNull(tries++) ?: return false
        return s.request()
    }

    /** Focuses the newest remembered item still composed. */
    fun restoreAny(): Boolean = recent.firstOrNull { it.attachedNow }?.request() == true

    /** The item focused most recently. */
    fun latest(): SafeFocus? = recent.firstOrNull()

    /** The newest remembered item outside the side menu still composed (Right from the side menu). */
    fun restoreContent(): Boolean = content.firstOrNull { it.attachedNow }?.request() == true
}

/**
 * For a pop-up layer (menu, dialog, programme pop-up): when it closes, focus goes back to the item that was focused
 * when it opened, unless another layer took over. Without this Compose re-entered the screen at its first focusable,
 * the side menu.
 */
@Composable
fun RestoreFocusOnClose(key: Any, layerOpen: () -> Boolean) {
    val back = remember(key) { FocusMemory.latest() }
    androidx.compose.runtime.DisposableEffect(key) {
        onDispose {
            // the item may first have to scroll back into view (a group just moved to the bottom): retry a moment
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            var tries = 0
            lateinit var step: Runnable
            step = Runnable {
                if (layerOpen()) return@Runnable
                if (back?.request() == true) return@Runnable
                if (++tries < 8) h.postDelayed(step, 80) else FocusMemory.restoreAny()
            }
            h.postDelayed(step, 60)
        }
    }
}

/** True inside the side menu: its items are not remembered as places in the content. */
val LocalInRail = androidx.compose.runtime.staticCompositionLocalOf { false }

/** Counts this item in [FocusMemory.anyFocus] while it holds focus; put it right before the item's focus target. */
fun Modifier.countFocus(): Modifier = composed {
    val f = remember { booleanArrayOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (f[0]) { f[0] = false; FocusMemory.lost() } } }
    onFocusChanged { if (it.isFocused != f[0]) { f[0] = it.isFocused; if (f[0]) FocusMemory.gained() else FocusMemory.lost() } }
}

/** A layer (menu, dialog, settings panel) that D-pad focus cannot leave for the screen underneath. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.focusTrap(): Modifier =
    this.focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()

/**
 * A column / panel that focus enters at [s] (its selected or last-focused item) instead of the item that happens to be
 * spatially nearest; falls back to normal search when [s] is not composed. Use on lists entered from the side.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.enterAt(s: SafeFocus): Modifier =
    this.focusProperties { enter = { s.target() } }.focusGroup()

/** [fr] on this item while [on]; the chain keeps its shape either way (see [safeFocus]). */
fun Modifier.requesterIf(on: Boolean, fr: FocusRequester): Modifier = composed {
    val own = remember { FocusRequester() }
    focusRequester(if (on) fr else own)
}

@Composable
fun TvButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.tvFocus(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(text, color = TextMain, fontSize = 16.sp)
    }
}

@Composable
fun rememberNow(): Long {
    var t by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); t = System.currentTimeMillis() / 1000 } }
    return t
}

object Logos {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val gate = Semaphore(4)
    private val failed = HashSet<String>()
    @Volatile var dir: java.io.File? = null

    private fun diskFile(url: String) = dir?.let { java.io.File(it, Integer.toHexString(url.hashCode()) + "_" + url.length) }

    private fun trim(d: java.io.File) {
        val fs = d.listFiles() ?: return
        var total = fs.sumOf { it.length() }
        if (total <= 40L * 1024 * 1024) return
        for (f in fs.sortedBy { it.lastModified() }) { total -= f.length(); f.delete(); if (total <= 30L * 1024 * 1024) break }
    }

    suspend fun load(url: String, target: Int = 128): Bitmap? {
        cache.get(url + "#" + target)?.let { return it }
        if (url in failed) return null
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                try {
                    val df = diskFile(url)
                    val bytes = (if (df != null && df.exists()) df.readBytes() else null) ?: (Net.client.newCall(Net.request(url)).execute().use { r ->
                        if (!r.isSuccessful) null else r.body?.bytes()
                    }?.also { b -> if (df != null && b.size < 2_000_000) { df.parentFile?.mkdirs(); df.writeBytes(b); if (Math.random() < 0.02) trim(df.parentFile!!) } })
                        ?: run { failed += url; return@withContext null }
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                    var s = 1
                    while (o.outWidth / (s * 2) >= target) s *= 2
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
                    if (bmp != null) cache.put(url + "#" + target, bmp) else failed += url
                    bmp
                } catch (e: Exception) { failed += url; null }
            }
        }
    }
}

@Composable
fun Logo(url: String?, name: String, size: Dp = 44.dp) {
    val bmp by produceState<Bitmap?>(null, url) { value = if (url != null) Logos.load(url) else null }
    Box(Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(Surface), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.size(size).padding(3.dp), contentScale = ContentScale.Fit)
        else Text(name.take(1).uppercase(), color = TextDim, fontSize = 18.sp)
    }
}

/** Clock time in the chosen format (Settings > General > Time format). */
fun hhmm(sec: Long): String = io.github.kmmarin.tvbro.data.TimeFmt.time(sec)
