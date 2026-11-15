// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * Debug-only latency probe for the TV guide (off in release builds: [enabled] is set from the debuggable flag).
 * Logs to logcat tag "tvbro-lat":
 *  - `focus <kind> key=<code> rep=<n> ms=<t>`: key event time to the first frame drawn after focus moved
 *  - `rows ms=<t>`: key to the first frame with a new group's channel rows
 *  - `data ms=<t>`: key to the first frame after the guide store went idle (all requested rows loaded)
 * Also a key injector for measurements ([inject]): a held key with auto-repeat, timed like a remote's.
 */
object Lat {
    @JvmField var enabled = false
    private var keyAt = 0L
    private var keyCode = 0
    private var rep = 0
    private var focusKind: String? = null
    private var rowsPending = false
    private var dataPending = false
    private const val TAG = "tvbro-lat"

    fun key(e: KeyEvent) {
        if (!enabled || e.action != KeyEvent.ACTION_DOWN) return
        if (e.keyCode !in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) return
        keyAt = e.eventTime; keyCode = e.keyCode; rep = e.repeatCount
    }

    /** Set by the TV screen: "" when the focused row is fully inside the list's viewport, else a description. */
    @JvmField var visible: (() -> String)? = null

    @JvmField var focusIdx = -1
    @JvmField var lastList: Any? = null

    /** "" when row [focusIdx] lies fully inside the list's viewport (or nothing in the list is focused). */
    fun offscreen(s: androidx.compose.foundation.lazy.LazyListState): String {
        if (focusIdx < 0) return ""
        val li = s.layoutInfo
        val it = li.visibleItemsInfo.firstOrNull { it.index == focusIdx } ?: return "row $focusIdx not laid out"
        // (5 px of slack: the row's 2 dp padding around the focusable)
        return if (it.offset < li.viewportStartOffset - 5 || it.offset + it.size > li.viewportEndOffset + 5) "row $focusIdx at ${it.offset}..${it.offset + it.size} of ${li.viewportStartOffset}..${li.viewportEndOffset}" else ""
    }

    private var lastOff = ""

    fun focus(kind: String) { if (kind == "group") focusIdx = -1; if (enabled && keyAt != 0L) focusKind = kind }
    fun rows() { if (enabled && keyAt != 0L) { rowsPending = true; guideWait = true } }
    /** `guide ms=<t>`: key to the first frame in which a new group's rows show guide cells. */
    private var guideWait = false
    private var guidePending = false
    fun guide() { if (enabled && guideWait) { guideWait = false; guidePending = true } }
    /** Debug: `setprop debug.tvbro.noprefetch 1` turns the neighbouring groups' guide prefetch off (for comparison). */
    @JvmField var noPrefetch = false
    fun data() { if (enabled && keyAt != 0L) dataPending = true }

    /** Called for every frame drawn (an OnDrawListener on the window). */
    fun onDraw() {
        if (!enabled) return
        val t = SystemClock.uptimeMillis() - keyAt
        focusKind?.let { Log.i(TAG, "focus $it key=$keyCode rep=$rep ms=$t"); focusKind = null }
        val off = visible?.invoke() ?: ""
        if (off.isNotEmpty() && off != lastOff) Log.i(TAG, "offscreen $off")
        lastOff = off
        if (rowsPending) { rowsPending = false; Log.i(TAG, "rows ms=$t") }
        if (dataPending) { dataPending = false; Log.i(TAG, "data ms=$t") }
        if (guidePending) { guidePending = false; Log.i(TAG, "guide ms=$t") }
    }

    /**
     * Sends [code] like a remote: one press ([n] = 0) or held for [n] repeats every [ms] ms after the usual 400 ms
     * repeat delay, then released. Runs on the main thread's queue, so a busy UI delays events like real input.
     */
    fun inject(act: android.app.Activity, code: Int, n: Int, ms: Long) {
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val down = SystemClock.uptimeMillis()
        fun ev(a: Int, at: Long, r: Int) = KeyEvent(down, at, a, code, r, 0, -1, 0, 0, android.view.InputDevice.SOURCE_DPAD)
        h.post { act.dispatchKeyEvent(ev(KeyEvent.ACTION_DOWN, down, 0)) }
        var at = down + if (n > 0) 400 else 80
        for (i in 1..n) { val t = at; h.postAtTime({ act.dispatchKeyEvent(ev(KeyEvent.ACTION_DOWN, t, i)) }, t); at += ms }
        val up = at
        h.postAtTime({ act.dispatchKeyEvent(ev(KeyEvent.ACTION_UP, up, 0)) }, up)
    }
}
