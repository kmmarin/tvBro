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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
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
    rest: Color = Color.Transparent,
    outline: Boolean = false,
    onFocus: ((Boolean) -> Unit)? = null,
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
        if (f != was[0]) { was[0] = f; onFocus?.invoke(f); if (f) { FocusMemory.gained(); FocusMemory.focused(me, inRail); if (inRail && Lat.enabled) android.util.Log.i("tvbro-lat", "side menu focused") } else FocusMemory.lost() }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (was[0]) { was[0] = false; FocusMemory.lost(); FocusMemory.disposedAt = android.os.SystemClock.uptimeMillis(); if (Lat.enabled) android.util.Log.i("tvbro-lat", "focused item disposed") } } }
    val gate = remember { io.github.kmmarin.tvbro.data.PressGate() }
    this.safeFocus(me)
        .onPreviewKeyEvent { e ->
            val down = e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown
            val n = e.nativeKeyEvent
            when (e.key) {
                // the remote's Menu key opens the same menu as a long press of OK (once per press)
                androidx.compose.ui.input.key.Key.Menu -> if (onLongClick != null && down) { if (n.repeatCount == 0) onLongClick(); true } else false
                // OK: one press, one action on the item it went down on; long press also by key repeat (see PressGate)
                androidx.compose.ui.input.key.Key.DirectionCenter, androidx.compose.ui.input.key.Key.Enter,
                androidx.compose.ui.input.key.Key.NumPadEnter ->
                    if (!down && e.type != androidx.compose.ui.input.key.KeyEventType.KeyUp) false
                    else when (gate.key(down, n.repeatCount, n.downTime, onLongClick != null)) {
                        io.github.kmmarin.tvbro.data.PressGate.Act.PASS -> false
                        io.github.kmmarin.tvbro.data.PressGate.Act.SWALLOW -> true
                        io.github.kmmarin.tvbro.data.PressGate.Act.LONG -> { onLongClick?.invoke(); true }
                    }
                else -> false
            }
        }
        .then(if (outline) Modifier.fieldLook(f, shape, rest) else Modifier.focusLook(f, shape, rest))
        .clip(shape)
        .combinedClickable(interactionSource = src, indication = null,
            onLongClick = onLongClick?.let { l -> { if (gate.timerLong()) l() } }, onClick = onClick)
}

/**
 * The app's one focus highlight (filled background plus a thick accent border), for items that hold real focus
 * ([tvFocus]) and for the player overlay's rows, whose selection moves while focus stays on the player.
 * [rest] is the background when not focused.
 */
fun Modifier.focusLook(focused: Boolean, shape: Shape, rest: Color = Color.Transparent): Modifier =
    this.background(if (focused) FocusBg else rest, shape)
        .border(if (focused) 3.dp else 0.dp, if (focused) Accent else Color.Transparent, shape)

/** A text field's look (search, forms, rename): outlined at rest, the focus highlight when focused, also while typing. */
fun Modifier.fieldLook(focused: Boolean, shape: Shape, rest: Color = Surface): Modifier =
    this.focusLook(focused, shape, rest).then(if (focused) Modifier else Modifier.border(1.dp, TextDim.copy(alpha = 0.4f), shape))

/**
 * The app's one entrance animation (pop-ups, player overlay rows): 110 ms from 50 % opacity and 16 dp lower. The first
 * frame already shows the content, so it adds no latency; only the layer is redrawn while it runs (no recomposition).
 * A new [key] starts it again (a row that stays composed while hidden).
 */
fun Modifier.enterAnim(key: Any? = Unit): Modifier = composed {
    val a = remember(key) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(key) { a.animateTo(1f, androidx.compose.animation.core.tween(110, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    val dy = with(androidx.compose.ui.platform.LocalDensity.current) { 16.dp.toPx() }
    graphicsLayer { val v = a.value; alpha = 0.5f + 0.5f * v; translationY = (1f - v) * dy }
}

/** The app's one focus growth (cards, posters, overlay tiles and icon buttons): 6 % in 110 ms, layer only. */
fun Modifier.focusGrow(focused: Boolean): Modifier = composed {
    val s by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.06f else 1f, androidx.compose.animation.core.tween(110), label = "focus")
    graphicsLayer { scaleX = s; scaleY = s }
}

/** The heading of a section or screen (TV, Movies, Shows, Recordings, My list, Settings...): one size everywhere. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) = Text(text, color = Accent, fontSize = 24.sp, maxLines = 1, modifier = modifier)

/** Background of the round icon buttons at rest (player overlay, detail pages). */
val IconRest = Color(0xCC171C24)

/**
 * The look of one icon button with its label underneath, shared by the player overlay's icon row (selection moves,
 * focus stays on the player) and focusable icon buttons ([ActionIcon]): [circle] carries the focus look. Focused: the
 * name in bold and the value under it; at rest the value, or the name when there is none.
 */
@Composable
fun IconCellContent(icon: Int, name: String, value: String, tint: Color?, focused: Boolean, circle: Modifier, modifier: Modifier = Modifier) {
    Column(modifier.width(116.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.focusGrow(focused).then(circle).size(54.dp), contentAlignment = Alignment.Center) {
            Image(androidx.compose.ui.res.painterResource(icon), name, Modifier.size(28.dp), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint ?: TextMain))
        }
        val top = if (focused) name else value.ifEmpty { name }
        Text(top, color = if (focused) TextMain else TextDim, fontSize = 13.sp, fontWeight = if (focused) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Text(if (focused && value.isNotEmpty()) value else "", color = TextMain, fontSize = 12.sp, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** A round icon button without a label (search row): the circle of [ActionIcon]. */
@Composable
fun RoundIconButton(icon: Int, description: String, modifier: Modifier = Modifier, tint: Color = TextMain, onClick: () -> Unit) {
    Box(modifier.size(54.dp).tvFocus(CircleShape, rest = IconRest, onClick = onClick), contentAlignment = Alignment.Center) {
        Image(androidx.compose.ui.res.painterResource(icon), description, Modifier.size(28.dp), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint))
    }
}

/** A focusable icon button with its label (detail pages): the same look as the player overlay's icon row. */
@Composable
fun ActionIcon(icon: Int, name: String, value: String = "", modifier: Modifier = Modifier, tint: Color? = null, circleMod: Modifier = Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    IconCellContent(icon, name, value, tint, f, circleMod.tvFocus(CircleShape, rest = IconRest, onFocus = { f = it }, onClick = onClick), modifier)
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

    // The last key pressed (MainActivity.dispatchKeyEvent): the side menu takes focus only from Left or Back.
    private var lastKey = 0
    private var lastKeyAt = 0L
    fun key(e: android.view.KeyEvent) { if (e.action == android.view.KeyEvent.ACTION_DOWN) { lastKey = e.keyCode; lastKeyAt = e.eventTime } }

    /**
     * Whether focus entering the side menu now was the user's doing: Back just before (closing Settings or a layer
     * returns there too), Left just before unless the focused item had just gone away, or no key yet (at start).
     * Otherwise focus fell there by itself (the focused programme went away while a key was held, and Compose
     * re-entered the screen at its first focusable item).
     */
    fun railEntryOk(): Boolean {
        val t = android.os.SystemClock.uptimeMillis()
        // at start, unless the focused item just went away: the start snapshot's rows replaced by the loaded list left
        // focus on the side menu, where it stayed
        if (lastKeyAt == 0L) return t - disposedAt > 1500
        if (t - lastKeyAt >= 2000) return false
        if (lastKey == android.view.KeyEvent.KEYCODE_BACK || lastKey == android.view.KeyEvent.KEYCODE_ESCAPE) return true
        return lastKey == android.view.KeyEvent.KEYCODE_DPAD_LEFT && t - disposedAt > 300
    }

    /**
     * Whether Up or Down was pressed just now: focus arriving on a group (or category) by itself (Compose's first
     * focus, a rescue) must not select it, only the user moving there.
     */
    fun upDownJustNow(): Boolean =
        (lastKey == android.view.KeyEvent.KEYCODE_DPAD_UP || lastKey == android.view.KeyEvent.KEYCODE_DPAD_DOWN) &&
            android.os.SystemClock.uptimeMillis() - lastKeyAt < 1500

    /** When a focused item last went away (focus entering the side menu right after is a fallback, never a move). */
    internal var disposedAt = 0L

    /** The current section's own way back to its focused item (the TV screen: the focused row's programme). */
    var contentBack: (() -> Boolean)? = null

    /**
     * Set by a menu action that decides where focus goes next (a group sent to the bottom or hidden: the group now in
     * its place); used instead of the item focused before, when the last layer closes.
     */
    var nextFocus: (() -> Boolean)? = null

    /** Popups open now (menus, prompts, the programme pop-up, reminder, undo bar): see [canLeaveTrap]. */
    var popups = 0

    /** Until when a closing layer is still putting focus back: the lost-focus rescues wait for it meanwhile. */
    internal var restoringUntil = 0L
    val restoring: Boolean get() = android.os.SystemClock.uptimeMillis() < restoringUntil
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
            FocusMemory.restoringUntil = android.os.SystemClock.uptimeMillis() + 900
            lateinit var step: Runnable
            step = Runnable {
                if (layerOpen()) return@Runnable // the next layer takes focus (and puts it back in turn)
                val next = FocusMemory.nextFocus
                val done = if (next != null) next() else back?.request() == true
                if (done) { FocusMemory.nextFocus = null; FocusMemory.restoringUntil = 0; return@Runnable }
                if (++tries < 10) h.postDelayed(step, 80)
                else { FocusMemory.nextFocus = null; FocusMemory.restoringUntil = 0; FocusMemory.restoreAny() }
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

/**
 * A layer (menu, dialog, settings panel) that D-pad focus cannot leave for the screen underneath. Only the D-pad / Tab /
 * Back moves are held: Compose 1.7 also asks [exit] when focus is *requested* elsewhere (FocusDirection.Enter), and
 * cancelling that kept every popup opened from Settings from taking focus (the focus box stayed on the Settings row
 * behind the "Save recordings to" chooser).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.focusTrap(level: Int = 0): Modifier =
    this.focusProperties { exit = { d -> if (trapsMove(d) || !canLeaveTrap(level)) FocusRequester.Cancel else FocusRequester.Default } }.focusGroup()

/**
 * A focus request may take focus out of a trap of [level] (0: a panel such as Settings, 1: a popup) only for a popup
 * opened above it: the screen underneath asking for focus by itself (the TV screen's first focus at start) must not
 * pull it out from under Settings or a menu.
 */
fun canLeaveTrap(level: Int): Boolean = FocusMemory.popups > level

/** Counts an open popup while composed (see [canLeaveTrap]); called by [TakeLayerFocus]. */
@Composable
private fun CountPopup() {
    androidx.compose.runtime.DisposableEffect(Unit) { FocusMemory.popups++; onDispose { FocusMemory.popups-- } }
}

/** The moves a [focusTrap] holds inside: the user's own (arrows, Tab, Back), never a programmatic focus request. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun trapsMove(d: androidx.compose.ui.focus.FocusDirection): Boolean {
    val D = androidx.compose.ui.focus.FocusDirection
    return d == D.Left || d == D.Right || d == D.Up || d == D.Down || d == D.Next || d == D.Previous || d == D.Exit
}

/** Whether focus is inside a popup layer (see [popupLayer]); its opening effect requests focus until this holds. */
class LayerFocus { var inside = false }

/**
 * A popup's box: holds the D-pad inside ([focusTrap]) and records whether focus is inside, for [TakeLayerFocus].
 */
fun Modifier.popupLayer(l: LayerFocus): Modifier =
    this.onFocusChanged { l.inside = it.hasFocus }.focusTrap(level = 1)

/**
 * Moves focus into a popup at [fr] (its current choice, else its first item) when it opens: retried for a moment, since
 * the item may not be composed / placed yet in the first frames and a single request could be lost.
 */
@Composable
fun TakeLayerFocus(key: Any, l: LayerFocus, fr: FocusRequester) {
    androidx.compose.runtime.key(key) { CountPopup() }
    LaunchedEffect(key) {
        for (i in 0 until 12) {
            delay(if (i == 0) 16 else 60)
            if (l.inside) break
            runCatching { fr.requestFocus() }
        }
    }
}

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
    // (a poster is ~0.5 MB decoded: 12 MB held one screen of posters, scrolling back decoded them all again)
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt().coerceIn(12 * 1024, 64 * 1024)) {
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

    /** The logo if it is in memory already (no I/O): rows scrolled into view show it in their first frame. */
    fun peek(url: String?, target: Int = 128): Bitmap? = url?.let { cache.get(it + "#" + target) }

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
    val bmp by produceState(Logos.peek(url), url) { value = Logos.peek(url) ?: if (url != null) Logos.load(url) else null }
    Box(Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(Surface), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.size(size).padding(3.dp), contentScale = ContentScale.Fit)
        else Text(name.take(1).uppercase(), color = TextDim, fontSize = 18.sp)
    }
}

/** Clock time in the chosen format (Settings > General > Time format). */
fun hhmm(sec: Long): String = io.github.kmmarin.tvbro.data.TimeFmt.time(sec)
