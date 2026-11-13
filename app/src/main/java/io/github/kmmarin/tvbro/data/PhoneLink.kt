// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom

/** One-time code: expires, can be used once, locks after too many wrong guesses. */
class CodeGate(
    val code: String,
    private val expiresAtMs: Long,
    private val maxWrong: Int = 5,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    enum class Result { OK, WRONG, LOCKED, EXPIRED, USED }

    private var wrong = 0
    private var used = false

    @Synchronized
    fun check(input: String, consume: Boolean): Result {
        if (used) return Result.USED
        if (clock() > expiresAtMs) return Result.EXPIRED
        if (wrong >= maxWrong) return Result.LOCKED
        val a = code.toByteArray()
        val b = input.trim().uppercase().toByteArray()
        if (!MessageDigest.isEqual(a, b)) { wrong++; return Result.WRONG }
        if (consume) used = true
        return Result.OK
    }

    companion object {
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        fun create(ttlMs: Long = 10 * 60_000L, rnd: SecureRandom = SecureRandom()) =
            CodeGate((1..6).map { ALPHABET[rnd.nextInt(ALPHABET.length)] }.joinToString(""), System.currentTimeMillis() + ttlMs)
    }
}

class HttpReq(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray) {
    /** Parses an application/x-www-form-urlencoded body. */
    fun form(): Map<String, String> {
        val m = HashMap<String, String>()
        for (kv in String(body, Charsets.UTF_8).split('&')) {
            if (kv.isEmpty()) continue
            val i = kv.indexOf('=')
            val k = URLDecoder.decode(if (i < 0) kv else kv.substring(0, i), "UTF-8")
            m[k] = if (i < 0) "" else URLDecoder.decode(kv.substring(i + 1), "UTF-8")
        }
        return m
    }

    companion object {
        /** Minimal, bounded HTTP/1.x request parser. Returns null for malformed or oversized input. */
        fun parse(i: InputStream, maxBody: Int = 8192): HttpReq? {
            fun line(): String? {
                val o = ByteArrayOutputStream()
                while (o.size() < 4096) {
                    val c = i.read()
                    if (c < 0) return if (o.size() == 0) null else String(o.toByteArray(), Charsets.ISO_8859_1)
                    if (c == '\n'.code) return String(o.toByteArray(), Charsets.ISO_8859_1).trimEnd('\r')
                    o.write(c)
                }
                return null
            }
            val first = line() ?: return null
            val p = first.split(' ')
            if (p.size < 2) return null
            val headers = HashMap<String, String>()
            var n = 0
            while (true) {
                val l = line() ?: return null
                if (l.isEmpty()) break
                if (++n > 50) return null
                val c = l.indexOf(':')
                if (c > 0) headers[l.substring(0, c).trim().lowercase()] = l.substring(c + 1).trim()
            }
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            if (len < 0 || len > maxBody) return null
            val body = ByteArray(len)
            var r = 0
            while (r < len) { val k = i.read(body, r, len - r); if (k < 0) return null; r += k }
            return HttpReq(p[0].uppercase(), p[1].substringBefore('?'), headers, body)
        }
    }
}

object LinkNet {
    fun lanAddress(): Inet4Address? = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }
    } catch (e: Exception) { null }
}

/**
 * Tiny single-threaded HTTP server used only while the "use your phone" screen is open.
 * Routes: GET / (page), POST /info (non-secret current values, edit mode), POST /submit.
 * Secrets travel only in POST bodies, never in URLs, and are never logged.
 */
class LinkServer(
    private val gate: CodeGate,
    private val page: String,
    private val info: () -> String,
    private val onSubmit: (Map<String, String>) -> String?, // returns an error message, or null on success
    private val onDone: () -> Unit,
) {
    private var ss: ServerSocket? = null
    @Volatile private var running = false

    // a few connections at once, each with a short read timeout: one slow LAN client cannot hold the page up
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(4) { r -> Thread(r, "tvbro-link-c").apply { isDaemon = true } }

    fun start(bind: InetAddress): Int {
        val s = ServerSocket(0, 8, bind)
        ss = s; running = true
        Thread({
            while (running) {
                val c = try { s.accept() } catch (e: Exception) { break }
                try {
                    pool.execute { try { c.soTimeout = 2000; handle(c) } catch (e: Exception) { } finally { try { c.close() } catch (e: Exception) { } } }
                } catch (e: java.util.concurrent.RejectedExecutionException) { try { c.close() } catch (x: Exception) { } }
            }
        }, "tvbro-link").apply { isDaemon = true }.start()
        return s.localPort
    }

    fun stop() { running = false; try { ss?.close() } catch (e: Exception) { }; pool.shutdownNow() }

    private fun reply(c: java.net.Socket, status: String, type: String, body: String) {
        val b = body.toByteArray(Charsets.UTF_8)
        val h = "HTTP/1.1 $status\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${b.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        c.getOutputStream().apply { write(h.toByteArray()); write(b); flush() }
    }

    private fun json(c: java.net.Socket, s: String) = reply(c, "200 OK", "application/json", s)
    private fun err(msg: String) = "{\"ok\":false,\"error\":\"" + msg.replace("\"", "'") + "\"}"

    private fun codeError(r: CodeGate.Result) = when (r) {
        CodeGate.Result.WRONG -> "Wrong code"
        CodeGate.Result.LOCKED -> "Too many wrong codes. Restart on the TV."
        CodeGate.Result.EXPIRED -> "Code expired. Restart on the TV."
        CodeGate.Result.USED -> "Code already used."
        else -> ""
    }

    private fun handle(c: java.net.Socket) {
        val req = HttpReq.parse(c.getInputStream())
        if (req == null) { reply(c, "400 Bad Request", "text/plain", "bad request"); return }
        when {
            req.method == "GET" && req.path == "/" -> reply(c, "200 OK", "text/html", page)
            req.method == "POST" && req.path == "/info" -> {
                val r = gate.check(req.form()["code"] ?: "", false)
                if (r != CodeGate.Result.OK) { Thread.sleep(600); json(c, err(codeError(r))) } else json(c, info())
            }
            req.method == "POST" && req.path == "/submit" -> {
                val f = req.form()
                val r = gate.check(f["code"] ?: "", true)
                if (r != CodeGate.Result.OK) { Thread.sleep(600); json(c, err(codeError(r))); return }
                val e = onSubmit(f)
                if (e != null) json(c, err(e)) else { json(c, "{\"ok\":true}"); onDone() }
            }
            else -> reply(c, "404 Not Found", "text/plain", "not found")
        }
    }
}

object LinkPage {
    fun html(edit: Boolean, name: String): String = TEMPLATE.replace("%%EDIT%%", if (edit) "true" else "false")
        .replace("%%TITLE%%", if (edit) "Edit source" else "Add source").replace("%%NAME%%", name.replace("<", "").replace("\"", ""))

    private const val TEMPLATE = """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark">
<title>tvBro</title><style>
:root{color-scheme:dark}body{margin:0;background:#0e1116;color:#e6eaf0;font:16px system-ui,sans-serif;padding:20px;max-width:520px}
h1{color:#4fc3f7;font-size:22px}label{display:block;margin:14px 0 4px;color:#8a94a3;font-size:13px}
input,select{width:100%;box-sizing:border-box;background:#171c24;color:#e6eaf0;border:1px solid #3a4452;border-radius:8px;padding:12px;font-size:16px}
button{margin-top:20px;width:100%;padding:14px;border:0;border-radius:8px;background:#4fc3f7;color:#0e1116;font-size:17px;font-weight:600}
#msg{margin-top:14px;min-height:20px}.err{color:#ff8a80}.ok{color:#a5d6a7}.hide{display:none}
</style></head><body><h1>%%TITLE%% on your TV</h1>
<label>Code shown on the TV</label><input id="c" autocapitalize="characters" autocomplete="off" maxlength="6">
<label>Type</label><select id="k"><option value="xtream" selected>Xtream login (server, username, password)</option><option value="m3u">M3U playlist URL</option></select>
<label>Name</label><input id="n" value="%%NAME%%" autocomplete="off">
<div id="m3u" class="hide"><label>Playlist URL</label><input id="u" type="url" autocomplete="off"><label>Guide (XMLTV) URL(s), optional, space separated</label><input id="e" autocomplete="off"></div>
<div id="xt"><label>Server (http://host:port)</label><input id="s" autocomplete="off"><label>Username</label><input id="us" autocomplete="off"><label>Password</label><input id="pw" type="password" autocomplete="off"></div>
<button id="go">Send to TV</button><div id="msg"></div>
<script>
var EDIT=%%EDIT%%,g=function(i){return document.getElementById(i)};
var q=new URLSearchParams(location.search);if(q.get('c'))g('c').value=q.get('c');
function sh(){var x=g('k').value=='xtream';g('xt').className=x?'':'hide';g('m3u').className=x?'hide':''}
g('k').onchange=sh;
function post(p,d){return fetch(p,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams(d).toString()}).then(function(r){return r.json()})}
function say(t,ok){g('msg').className=ok?'ok':'err';g('msg').textContent=t}
if(EDIT){say('Enter the code, then fields load. Leave password/URL empty to keep them.',true);g('c').onchange=function(){post('/info',{code:g('c').value}).then(function(j){if(j.error){say(j.error);return}
g('k').value=j.kind;sh();g('n').value=j.name||'';g('e').value=j.epg||'';g('s').value=j.server||'';g('us').value=j.user||'';say('Loaded.',true)})}}
g('go').onclick=function(){say('Sending...',true);post('/submit',{code:g('c').value,kind:g('k').value,name:g('n').value,url:g('u').value,epg:g('e').value,server:g('s').value,user:g('us').value,pass:g('pw').value})
.then(function(j){if(j.ok){say('Done. You can close this page.',true);g('go').disabled=true}else say(j.error||'Failed')}).catch(function(){say('Could not reach the TV')})};
</script></body></html>"""
}
