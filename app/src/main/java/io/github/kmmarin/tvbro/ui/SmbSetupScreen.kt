// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import io.github.kmmarin.tvbro.AppModel

/**
 * Add / Edit network share (Settings > Recording): all fields on one screen, typed with the remote or sent from a phone
 * (QR code, same one-time-code link as Add source), Test with the values on screen before Save.
 */
@Composable
fun SmbSetupScreen(m: AppModel) {
    BackHandler { m.closeSmb() }
    val ls = m.smbLink
    // the phone entry runs only while this screen is open; its one-time code lasts 10 minutes and is renewed before
    LaunchedEffect(ls?.code) {
        if (ls == null) m.startSmbLink()
        else { delay(9 * 60_000L + 30_000L); if (m.smbLink === ls) m.startSmbLink() }
    }
    DisposableEffect(Unit) { onDispose { m.stopSmbLink() } }
    val f = m.smbForm
    val editing = remember { m.store.smb().ok } // (read once: the store decrypts on each read)
    var more by remember { mutableStateOf(f.domain.isNotBlank()) }
    if (f.domain.isNotBlank() && !more) more = true
    val firstFr = remember { FocusRequester() }
    val domainFr = remember { FocusRequester() }
    val saveFr = remember { FocusRequester() }
    var wantDomain by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(200)
        runCatching { firstFr.requestFocus() }
        repeat(15) { delay(250); if (FocusMemory.anyFocus) return@LaunchedEffect; runCatching { firstFr.requestFocus() } }
    }
    LaunchedEffect(wantDomain) { if (wantDomain) { delay(80); runCatching { domainFr.requestFocus() }; wantDomain = false } }
    // filled in from the phone: on to Save (Test already runs)
    val filled = m.smbFilled
    val seen = remember { intArrayOf(filled) }
    LaunchedEffect(filled) { if (filled != seen[0]) { seen[0] = filled; delay(80); runCatching { saveFr.requestFocus() } } }

    Row(Modifier.fillMaxSize().background(Bg).padding(horizontal = 40.dp, vertical = 24.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(if (editing) "Edit network share" else "Add network share")
            Text("Windows, NAS or Samba (SMB). Recordings are copied there when they end. The password is stored encrypted on this device.",
                color = TextDim, fontSize = 13.sp)
            TvField("Server (name or IP address)", f.host, { m.smbForm = m.smbForm.copy(host = it) }, requester = firstFr)
            TvField("Share name", f.share, { m.smbForm = m.smbForm.copy(share = it) })
            TvField("Folder inside the share (created if missing; empty: the share itself)", f.path, { m.smbForm = m.smbForm.copy(path = it) })
            TvField("User name (empty for guest access)", f.user, { m.smbForm = m.smbForm.copy(user = it) })
            TvField("Password", f.pass, { m.smbForm = m.smbForm.copy(pass = it) }, password = true)
            if (more) TvField("Domain or workgroup (usually empty)", f.domain, { m.smbForm = m.smbForm.copy(domain = it) }, requester = domainFr)
            else TvButton("More: domain or workgroup") { more = true; wantDomain = true }
            // the result sits above the buttons: in view while focus is on them
            val t = m.smbTest
            if (t != null) Text(t.text, fontSize = 18.sp, color = when { t.running -> TextDim; t.ok -> Color(0xFFA5D6A7); else -> Color(0xFFE57373) })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                TvButton(if (m.smbTest?.running == true) "Testing..." else "Test") { m.testSmbForm() }
                TvButton("Save", Modifier.focusRequester(saveFr)) { m.saveSmbForm() }
                TvButton("Cancel") { m.closeSmb() }
            }
        }
        Column(Modifier.padding(start = 28.dp).width(260.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (ls != null) {
                Text("Or type it on your phone", color = TextMain, fontSize = 17.sp, maxLines = 1)
                QrCode(ls.url + "?c=" + ls.code, Modifier.padding(top = 8.dp).size(220.dp))
                Text("Home network only: same Wi-Fi as this TV. Or open ${ls.url}", color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                Text("Code: ${ls.code}", color = TextMain, fontSize = 20.sp, modifier = Modifier.padding(top = 2.dp))
            } else Text("Phone entry needs a home network connection.", color = TextDim, fontSize = 13.sp)
            if (m.smbLinkStatus.isNotEmpty()) Text(m.smbLinkStatus, color = Color(0xFFA5D6A7), fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
