// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The Add / Edit network share form: input clean-up and the phone entry (over [LinkServer], like a source). */
object SmbSetup {
    /**
     * Cleans typed values: "\\nas\rec\tv", "smb://nas/rec" or "//nas/rec" in the server field fill the share (and
     * folder) when those are empty; "OFFICE\ann" as the user name fills the domain. The password is kept as typed.
     */
    fun normalize(c: SmbConfig): SmbConfig {
        var host = c.host.trim()
        var share = c.share.trim().trim('/', '\\')
        var path = c.path.trim().trim('/', '\\')
        host = host.removePrefix("smb://").removePrefix("SMB://").trimStart('/', '\\')
        val parts = host.split('/', '\\').filter { it.isNotBlank() }
        if (parts.size > 1) {
            host = parts[0]
            if (share.isEmpty()) {
                share = parts[1]
                if (parts.size > 2) path = parts.drop(2).joinToString("/")
            }
        } else host = parts.firstOrNull() ?: ""
        var user = c.user.trim()
        var domain = c.domain.trim()
        val bs = user.indexOf('\\')
        if (bs > 0 && domain.isEmpty()) { domain = user.substring(0, bs); user = user.substring(bs + 1) }
        return SmbConfig(host, share, path, user, c.pass, domain)
    }

    /** Non-secret values for the phone page (never the password: only whether one is set). */
    fun info(c: SmbConfig): String = buildJsonObject {
        put("ok", true); put("host", c.host); put("share", c.share); put("path", c.path); put("user", c.user)
        put("domain", c.domain); put("haspass", c.pass.isNotEmpty())
    }.toString()

    /** A phone submission applied to the form [cur]: the new form, or an error message for the phone. */
    fun fromPhone(f: Map<String, String>, cur: SmbConfig): Pair<SmbConfig?, String?> {
        val n = normalize(SmbConfig(
            host = f["host"] ?: "", share = f["share"] ?: "", path = f["path"] ?: cur.path,
            user = f["user"] ?: "", pass = (f["pass"] ?: "").ifEmpty { cur.pass }, domain = f["domain"] ?: "",
        ))
        if (!n.ok) return null to "Fill in the server and the share name"
        return n to null
    }
}

object SmbLinkPage {
    fun html(): String = TEMPLATE

    private const val TEMPLATE = """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark">
<title>tvBro</title><style>
:root{color-scheme:dark}body{margin:0;background:#0e1116;color:#e6eaf0;font:16px system-ui,sans-serif;padding:20px;max-width:520px}
h1{color:#4fc3f7;font-size:22px}label{display:block;margin:14px 0 4px;color:#8a94a3;font-size:13px}
input{width:100%;box-sizing:border-box;background:#171c24;color:#e6eaf0;border:1px solid #3a4452;border-radius:8px;padding:12px;font-size:16px}
button{margin-top:20px;width:100%;padding:14px;border:0;border-radius:8px;background:#4fc3f7;color:#0e1116;font-size:17px;font-weight:600}
summary{margin-top:14px;color:#8a94a3}#msg{margin-top:14px;min-height:20px}.err{color:#ff8a80}.ok{color:#a5d6a7}
</style></head><body><h1>Network share on your TV</h1>
<label>Code shown on the TV</label><input id="c" autocapitalize="characters" autocomplete="off" maxlength="6">
<label>Server (name or IP address)</label><input id="h" autocapitalize="none" autocomplete="off" placeholder="nas or 192.168.1.x">
<label>Share name</label><input id="s" autocapitalize="none" autocomplete="off" placeholder="recordings">
<label>Folder inside the share (created if missing; empty: the share itself)</label><input id="p" autocapitalize="none" autocomplete="off" value="tvBro">
<label>User name (empty for guest access)</label><input id="u" autocapitalize="none" autocomplete="off">
<label>Password</label><input id="pw" type="password" autocomplete="off">
<details id="more"><summary>More</summary><label>Domain or workgroup (usually empty)</label><input id="d" autocapitalize="characters" autocomplete="off"></details>
<button id="go">Send to TV</button><div id="msg"></div>
<script>
var g=function(i){return document.getElementById(i)};
var q=new URLSearchParams(location.search);if(q.get('c'))g('c').value=q.get('c');
function post(p,d){return fetch(p,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams(d).toString()}).then(function(r){return r.json()})}
function say(t,ok){g('msg').className=ok?'ok':'err';g('msg').textContent=t}
function load(){if(g('c').value.length<6)return;post('/info',{code:g('c').value}).then(function(j){if(j.error){say(j.error);return}
if(j.host)g('h').value=j.host;if(j.share)g('s').value=j.share;if(j.host||j.share)g('p').value=j.path||'';g('u').value=j.user||'';g('d').value=j.domain||'';if(j.domain)g('more').open=true;
g('pw').placeholder=j.haspass?'Leave empty to keep the one on the TV':'';say('',true)}).catch(function(){say('Could not reach the TV')})}
g('c').onchange=load;load();
g('go').onclick=function(){if(g('c').value.trim().length<6){say('Enter the code shown on the TV');return}
if(!g('h').value.trim()||!g('s').value.trim()){say('Fill in the server and the share name');return}say('Sending...',true);post('/submit',{code:g('c').value,host:g('h').value,share:g('s').value,path:g('p').value,user:g('u').value,pass:g('pw').value,domain:g('d').value})
.then(function(j){if(j.ok){g('pw').value='';say('Sent. The TV is testing the share now; you can close this page.',true);g('go').disabled=true}else say(j.error||'Failed')}).catch(function(){say('Could not reach the TV')})};
</script></body></html>"""
}
