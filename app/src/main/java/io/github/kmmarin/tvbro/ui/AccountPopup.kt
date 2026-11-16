// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.data.Source
import io.github.kmmarin.tvbro.data.StreamLimits
import io.github.kmmarin.tvbro.data.StreamRules

private val Good = Color(0xFF81C784)
private val Bad = Color(0xFFE57373)
private val Warn = Color(0xFFFFB74D)

/** A date of the provider's account (unix seconds) in the user's locale ("January 29, 2028"). */
private fun accountDate(sec: Long): String =
    java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG, java.util.Locale.getDefault()).format(java.util.Date(sec * 1000))

/**
 * A provider's stream limit as a setting row: "3 (from provider)"; a provider that does not report one: the user's
 * own number, Left / Right change it (1 to [StreamRules.USER_MAX]).
 */
@Composable
fun MaxStreamsRow(m: AppModel, s: Source, title: String, mod: Modifier = Modifier, onClick: () -> Unit) {
    val l = StreamLimits.items[s.id]
    if (StreamRules.fromProvider(l)) {
        SettingRow(title, StreamRules.label(l), mod = mod, onClick = onClick)
    } else {
        val n = StreamRules.limit(l)
        SettingRow(title, "not reported, set to $n  (left/right)",
            { m.setUserStreams(s, (n - 1).coerceAtLeast(1)) }, { m.setUserStreams(s, (n + 1).coerceAtMost(StreamRules.USER_MAX)) }, mod = mod, onClick = onClick)
    }
}

/**
 * The provider's account (Xtream `user_info`), asked again as it opens: username, status (green when active), expiry
 * (a warning under [StreamRules.EXPIRY_WARN_DAYS] days or when expired), trial, connections in use, created, max
 * streams. The password is never shown. M3U playlists have no account to show, only their stream limit.
 */
@Composable
fun AccountPopup(m: AppModel) {
    val s = m.accountOf ?: return
    val fr = remember(s) { FocusRequester() }
    val l = StreamLimits.items[s.id]
    val busy = s.id in m.accountBusy
    Popup(s, "${s.name}: account", { m.accountOf = null }, m, width = 640.dp, focus = fr) {
        if (!s.isXtream) {
            Text("Account info not available for M3U playlists", color = TextDim, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
            MaxStreamsRow(m, s, "Max streams", Modifier.focusRequester(fr)) { }
            return@Popup
        }
        SettingRow("Username", l?.username ?: s.user, mod = Modifier.focusRequester(fr)) { }
        SettingRow("Account status", l?.status ?: if (busy) "checking..." else "unknown", valueColor = if (l?.status == null) TextDim else if (StreamRules.statusOk(l.status)) Good else Bad) { }
        SettingRow("Expiry date", l?.exp?.let { accountDate(it) } ?: if (l?.checkedAt ?: 0L > 0L) "Unlimited" else "unknown") { }
        StreamRules.expiryWarning(l?.exp, System.currentTimeMillis())?.let { w ->
            SettingRow("Expiry", if (w == "Expired") "Expired: renew the subscription" else w, valueColor = if (w == "Expired") Bad else Warn) { }
        }
        SettingRow("Is trial", when (l?.trial) { true -> "Yes"; false -> "No"; null -> "unknown" }) { }
        SettingRow("Active connections", l?.active?.toString() ?: "unknown") { }
        SettingRow("Created at", l?.created?.let { accountDate(it) } ?: "unknown") { }
        MaxStreamsRow(m, s, "Max streams") { }
        SettingRow("Refresh account info", if (busy) "Checking..." else l?.checkedAt?.takeIf { it > 0 }?.let { "checked " + io.github.kmmarin.tvbro.data.TimeFmt.dayTime(it, "d MMM") } ?: "not checked yet") { m.refreshAccount(s) }
    }
}
