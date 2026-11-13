# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Attack cases (security audit 2026-11-12): local files and other schemes, loopback / link-local /
private / metadata addresses (also after a redirect and with a DNS answer that changes), the image
proxy, sign-in, CSRF, Host header, password / PIN brute force, request size, roles.

Unit tests for net.py / auth.py / storage.py / remux.py, then the real server (run.py) on a free
port with a temp data dir and a mock "attacker" playlist on loopback (loopback is allowed for the
playlist fetch only through TVBRO_ALLOW_LOOPBACK_SOURCES, which the stream tests then do not use)."""
import http.server
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "server"))
from tvbro_server import net as NET  # noqa: E402
from tvbro_server import auth as AU  # noqa: E402
from tvbro_server import storage as STO  # noqa: E402
from tvbro_server import remux as R  # noqa: E402

PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 64
HTML = b"<html><script>alert(document.cookie)</script></html>"


class Mock(http.server.BaseHTTPRequestHandler):
    """The attacker's / provider's server on loopback."""
    m3u = ""

    def log_message(self, *a):
        pass

    def do_GET(self):
        p = self.path.split("?", 1)[0]
        if p == "/playlist.m3u":
            return self._send(200, "audio/x-mpegurl", Mock.m3u.encode())
        if p == "/logo.png":
            return self._send(200, "image/png", PNG)
        if p == "/evil.png":                      # says it is an image, is HTML
            return self._send(200, "image/png", HTML)
        if p == "/evil.html":
            return self._send(200, "text/html", HTML)
        if p.startswith("/redir"):                # a redirect to the metadata address / a private host
            target = {"/redir-meta": "http://169.254.169.254/latest/meta-data/",
                      "/redir-lan": "http://192.168.1.1/", "/redir-file": "file:///etc/passwd",
                      "/redir-local": "http://127.0.0.1:%d/ok.ts" % self.server.server_address[1]}.get(p, "/")
            self.send_response(302)
            self.send_header("Location", target)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if p == "/ok.ts":
            return self._send(200, "video/mp2t", b"G" + b"\x00" * 187)
        self._send(404, "text/plain", b"")

    def _send(self, code, ctype, body):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def start_mock():
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Mock)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


# --------------------------------------------------------------------------- net.py

class Net(unittest.TestCase):
    def setUp(self):
        self._env = {k: os.environ.pop(k, None) for k in ("TVBRO_ALLOW_LOOPBACK_SOURCES", "TVBRO_ALLOW_PRIVATE_SOURCES")}
        NET.set_allow_private(lambda: False)

    def tearDown(self):
        for k, v in self._env.items():
            if v is not None:
                os.environ[k] = v
        NET.set_allow_private(lambda: False)

    def test_only_http_and_https(self):
        for u in ("file:///etc/passwd", "file://localhost/var/lib/tvbro/key.bin", "ftp://example.com/x",
                  "gopher://example.com:70/_x", "data:text/html,<script>", "concat:/etc/passwd|/etc/shadow",
                  "dict://example.com:11211/", "jar:http://x/!/", "/etc/passwd", "", "http://",
                  "http://example.com:22/", "http://example.com:25/", "http://exa mple.com/"):
            with self.assertRaises(NET.Blocked, msg=u):
                NET.check_url(u)
        for u in ("http://example.com/a.m3u", "https://example.com:8443/x", "http://example.com:80/"):
            NET.check_url(u)

    def test_address_rules(self):
        never = ["127.0.0.1", "127.1.2.3", "::1", "169.254.169.254", "169.254.0.1", "fe80::1", "0.0.0.0", "::",
                 "224.0.0.1", "255.255.255.255", "240.0.0.1", "::ffff:127.0.0.1", "::ffff:169.254.169.254",
                 "64:ff9b::7f00:1", "2002:7f00:1::1"]
        lan = ["10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.1", "192.168.1.1", "100.64.0.1",
               "fd00::1", "::ffff:192.168.0.1"]
        for ip in never:
            self.assertFalse(NET.ip_allowed(ip, False), ip)
            self.assertFalse(NET.ip_allowed(ip, True), ip + " (even with LAN sources allowed)")
        for ip in lan:
            self.assertFalse(NET.ip_allowed(ip, False), ip)
            self.assertTrue(NET.ip_allowed(ip, True), ip + " with LAN sources allowed")
        for ip in ("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111", "172.32.0.1", "192.169.0.1"):
            self.assertTrue(NET.ip_allowed(ip, False), ip)

    def test_admin_setting_allows_lan(self):
        self.assertFalse(NET.ip_allowed("192.168.1.10"))
        NET.set_allow_private(lambda: True)
        self.assertTrue(NET.ip_allowed("192.168.1.10"))
        self.assertFalse(NET.ip_allowed("127.0.0.1"))
        self.assertFalse(NET.ip_allowed("169.254.169.254"))

    def test_loopback_server_refused(self):
        srv = start_mock()
        try:
            with self.assertRaises(Exception) as cm:
                NET.open_url("http://127.0.0.1:%d/ok.ts" % srv.server_address[1], timeout=5)
            self.assertTrue(NET.is_blocked(cm.exception), cm.exception)
            with self.assertRaises(Exception) as cm:
                NET.open_url("http://localhost:%d/ok.ts" % srv.server_address[1], timeout=5)
            self.assertTrue(NET.is_blocked(cm.exception), cm.exception)
        finally:
            srv.shutdown()

    def test_redirects_are_checked(self):
        srv = start_mock()
        os.environ["TVBRO_ALLOW_LOOPBACK_SOURCES"] = "1"        # the first hop only
        base = "http://127.0.0.1:%d" % srv.server_address[1]
        try:
            with NET.open_url(base + "/redir-local", timeout=5) as r:      # an allowed hop is followed
                self.assertEqual(r.read(1), b"G")
            for p in ("/redir-meta", "/redir-lan", "/redir-file"):
                with self.assertRaises(Exception, msg=p) as cm:
                    NET.open_url(base + p, timeout=5).read()
                # (urllib itself already refuses a redirect to file:)
                self.assertTrue(NET.is_blocked(cm.exception) or "not allowed" in str(cm.exception), (p, cm.exception))
        finally:
            srv.shutdown()

    def test_dns_rebinding(self):
        """The address checked is the address connected to: a name that answers with a private /
        metadata address (first or later) is refused, and no second lookup happens."""
        calls = []
        real = socket.getaddrinfo

        def fake(host, port, *a, **k):
            calls.append(host)
            if host == "rebind.example":
                ip = "93.184.216.34" if len(calls) == 1 else "169.254.169.254"
                return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (ip, port))]
            if host == "mixed.example":
                return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("93.184.216.34", port)),
                        (socket.AF_INET, socket.SOCK_STREAM, 6, "", ("10.0.0.5", port))]
            if host == "inside.example":
                return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("192.168.0.10", port))]
            return real(host, port, *a, **k)

        connected = []

        class FakeSock:
            def __init__(self, *a):
                pass

            def settimeout(self, t):
                pass

            def connect(self, sa):
                connected.append(sa[0])
                raise OSError("no network in tests")

            def close(self):
                pass

        with mock.patch.object(socket, "getaddrinfo", fake), mock.patch.object(socket, "socket", FakeSock):
            for host in ("mixed.example", "inside.example"):
                with self.assertRaises(Exception) as cm:
                    NET.open_url(f"http://{host}/x", timeout=2)
                self.assertTrue(NET.is_blocked(cm.exception), host)
            self.assertEqual(connected, [])                         # never connected
            # rebinding: the check runs on the same answer the socket uses
            calls.clear()
            with self.assertRaises(Exception):
                NET.open_url("http://rebind.example/x", timeout=2)
            self.assertEqual(connected, ["93.184.216.34"])         # the checked (public) address
            self.assertEqual(calls, ["rebind.example"])           # one lookup only
            calls.clear()
            with self.assertRaises(Exception) as cm:
                NET.check_host("http://rebind.example/x")         # ffmpeg pre-check: second answer
                NET.check_host("http://rebind.example/x")
            self.assertTrue(NET.is_blocked(cm.exception))


# --------------------------------------------------------------------------- remux / storage / auth units

class Units(unittest.TestCase):
    def test_ffmpeg_protocol_whitelist(self):
        a = R.probe_args("https://example.com/movie.mkv")
        self.assertEqual(a[a.index("-protocol_whitelist") + 1], "http,https,tcp,tls,crypto")
        self.assertNotIn("file", a[a.index("-protocol_whitelist") + 1].split(","))
        a = R.live_probe_args("http://example.com/live.ts")
        self.assertIn("-protocol_whitelist", a)
        a = R.stream_args("http://example.com/live.ts", {"v": "hevc", "a": "aac"}, "/tmp/x")
        self.assertLess(a.index("-protocol_whitelist"), a.index("-i"))
        a = R.ffmpeg_args("/var/lib/tvbro/recordings/abc.ts", {"video": {"index": 0}, "a": None, "subs": []}, "/tmp/r")
        self.assertEqual(a[a.index("-protocol_whitelist") + 1], "file")
        for bad in ("file:///etc/passwd", "concat:/a|/b", "subfile:,start,0,end,10,:/etc/passwd", "data:,x",
                    "ftp://x/y", "gopher://x/", "crypto:file:///x", "relative/path.ts"):
            with self.assertRaises(ValueError, msg=bad):
                R.probe_args(bad)

    def test_remux_manager_checks_addresses(self):
        with self.assertRaises(Exception) as cm:
            R.RemuxManager._check_input("http://169.254.169.254/x.mkv", False)
        self.assertTrue(NET.is_blocked(cm.exception))
        with self.assertRaises(ValueError):
            R.RemuxManager._check_input("http://example.com/x.ts", True)   # "local" must be a path

    def test_share_values_are_strict_and_quoted(self):
        class Sec:
            def seal_bytes(self, b):
                return b

            def open_bytes(self, b):
                return b
        d = tempfile.mkdtemp()
        try:
            st = STO.ShareStore(d, Sec())
            for bad in ({"mountpoint": "/mnt/rec -dev0 /dev/sda"}, {"mountpoint": "/mnt/a b"}, {"mountpoint": "/srv/x"},
                        {"options": "rw 0 0\n//evil /mnt cifs"}, {"options": "a b"}, {"server": "nas;reboot"},
                        {"server": "nas$(id)"}, {"share": "a b"}, {"user": "u'x"}, {"mountpoint": "/mnt/.."}):
                with self.assertRaises(ValueError, msg=str(bad)):
                    st.save({"type": "smb", "server": "nas.lan", "share": "rec", **bad})
            st.save({"type": "smb", "server": "nas.lan", "share": "tv/rec", "user": "me", "options": "vers=3.0",
                     "mountpoint": "/mnt/recordings"})
            steps = STO.host_steps(st.get(), None, 999, 996)
            self.assertTrue(steps[0].startswith("CTID="))
            self.assertTrue(any("pct set \"$CTID\" -mp0 " in s for s in steps))
            self.assertFalse(any("122" in s for s in steps))
            steps = STO.host_steps(st.get(), 345, 999, 996)
            self.assertTrue(any(s.startswith("pct set 345 -mp0 ") for s in steps))
            # values stored before this check (an old share.enc) are never printed into commands
            self.assertEqual(len(STO.host_steps({"type": "smb", "server": "x", "share": "y", "mountpoint": "/mnt/a -dev0 /dev/sda"}, 1)), 1)
        finally:
            shutil.rmtree(d, ignore_errors=True)

    def test_password_hash_and_throttle(self):
        h = AU.hash_password("correct horse", iterations=1000)
        self.assertTrue(h.startswith("pbkdf2_sha256$1000$"))
        self.assertTrue(AU.verify_password("correct horse", h))
        self.assertFalse(AU.verify_password("correct hors", h))
        self.assertEqual(AU.ITERATIONS, 600_000)
        with self.assertRaises(ValueError):
            AU.check_new_password("short")
        t = [1000.0]
        th = AU.Throttle(lambda: t[0])
        for _ in range(5):
            th.check("1.2.3.4")
            th.fail("1.2.3.4")
        with self.assertRaises(AU.Wait) as cm:
            th.check("1.2.3.4")
        self.assertGreaterEqual(cm.exception.retry_after, 29)
        th.check("5.6.7.8")                                   # another address is not held up
        t[0] += 31
        th.check("1.2.3.4")
        th.fail("1.2.3.4")
        with self.assertRaises(AU.Wait) as cm:                # doubles
            th.check("1.2.3.4")
        self.assertGreaterEqual(cm.exception.retry_after, 59)

    def test_sessions_are_hashed_and_expire(self):
        d = tempfile.mkdtemp()
        t = [1000.0]
        try:
            old = AU.ITERATIONS
            AU.ITERATIONS = 1000
            a = AU.Auth(d, b"k", lambda: t[0])
            sid = a.setup("password1")
            with open(os.path.join(d, "sessions.json"), encoding="utf-8") as f:
                self.assertNotIn(sid, f.read())                # only its hash is stored
            self.assertEqual(a.session(sid)["role"], "admin")
            with self.assertRaises(PermissionError):
                a.open_session()                               # a password is required now
            a.set_required(False)
            o = a.open_session()
            self.assertEqual(a.session(o)["role"], "open")
            a.set_required(True)                               # the same admin password again
            self.assertIsNone(a.session(o))                    # open sessions end
            self.assertEqual(a.session(sid)["role"], "admin")
            a.set_password("viewer", "viewer-pass")
            vs, role = a.login("viewer-pass", "x")
            self.assertEqual(role, "viewer")
            t[0] += AU.SESSION_TTL + 1
            self.assertIsNone(a.session(vs))
            AU.ITERATIONS = old
        finally:
            shutil.rmtree(d, ignore_errors=True)


# --------------------------------------------------------------------------- the real server

def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


class Server(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mock = start_mock()
        mb = "http://127.0.0.1:%d" % cls.mock.server_address[1]
        cls.mock_base = mb
        entries = [("Local file", "file:///etc/passwd"), ("Key", "file://localhost/var/lib/tvbro/key.bin"),
                   ("Gopher", "gopher://127.0.0.1:6379/_INFO"), ("Ftp", "ftp://127.0.0.1/x"),
                   ("Meta", "http://169.254.169.254/latest/meta-data/"), ("Ten", "http://10.0.0.1/x.ts"),
                   ("OneSeven", "http://172.16.0.1/x.ts"), ("OneNine", "http://192.168.1.1/x.ts"),
                   ("Cgnat", "http://100.64.0.1/x.ts"), ("V6local", "http://[::1]:8750/api/session"),
                   ("RedirMeta", mb + "/redir-meta"), ("RedirLan", mb + "/redir-lan"), ("RedirFile", mb + "/redir-file"),
                   ("Concat", "concat:/etc/passwd|/etc/hosts")]
        m3u = "#EXTM3U\n"
        for i, (name, url) in enumerate(entries):
            logo = mb + ("/logo.png" if i == 0 else "/evil.png" if i == 1 else "/evil.html")
            m3u += f'#EXTINF:-1 tvg-id="c{i}" tvg-logo="{logo}" group-title="Attack",{name}\n{url}\n'
        Mock.m3u = m3u
        cls.tmp = tempfile.mkdtemp()
        cls.port = free_port()
        env = dict(os.environ, TVBRO_PORT=str(cls.port), TVBRO_DATA=os.path.join(cls.tmp, "data"),
                   TVBRO_ALLOW_LOOPBACK_SOURCES="1", PYTHONDONTWRITEBYTECODE="1")
        env.pop("TVBRO_ALLOW_PRIVATE_SOURCES", None)
        env.pop("TVBRO_KEY", None)
        cls.log = open(os.path.join(cls.tmp, "server.log"), "wb")
        cls.proc = subprocess.Popen([sys.executable, "run.py"], cwd=os.path.join(HERE, "..", "server"),
                                    env=env, stdout=cls.log, stderr=subprocess.STDOUT)
        cls.base = "http://127.0.0.1:%d" % cls.port
        for _ in range(100):
            try:
                cls.req("GET", "/api/health")
                break
            except Exception:                       # noqa: BLE001
                time.sleep(0.1)
        cls.cookie = cls.csrf = None

    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate()
        try:
            cls.proc.wait(10)
        except subprocess.TimeoutExpired:
            cls.proc.kill()
        cls.log.close()
        cls.mock.shutdown()
        shutil.rmtree(cls.tmp, ignore_errors=True)

    @classmethod
    def req(cls, method, path, body=None, headers=None, raw=None):
        h = {}
        data = None
        if body is not None or raw is not None:
            data = raw if raw is not None else json.dumps(body).encode()
            h["Content-Type"] = "application/json"
        h.update(headers or {})
        r = urllib.request.Request(cls.base + path, data=data, method=method, headers=h)
        opener = urllib.request.build_opener(urllib.request.HTTPRedirectHandler())
        try:
            with opener.open(r, timeout=30) as f:
                return f.status, dict(f.headers), f.read()
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), e.read()

    def authed(self, method, path, body=None, extra=None, raw=None):
        h = {"Cookie": self.cookie, "X-CSRF-Token": self.csrf}
        h.update(extra or {})
        return self.req(method, path, body, h, raw=raw)

    def j(self, r):
        return json.loads(r[2] or b"{}")

    def test_all(self):
        # one ordered flow (a fresh server: first visit, then everything else)
        self._first_visit_and_setup()
        self._csrf_and_host()
        self._sources_refused()
        self._streams_refused()
        self._images()
        self._roles_and_sizes()
        self._parental_brute_force()
        self._login_brute_force()          # last: it holds this address up

    def _first_visit_and_setup(self):
        # no password by default: a browser gets a session of its own (no sign-in)
        for p in ("/api/channels", "/api/state", "/api/backup", "/api/sync/status", "/img?u=http://x/&s=0",
                  "/api/recordings/dirs", "/api/play?kind=live&key=p0|x"):
            self.assertEqual(self.req("GET", p)[0], 401, p)       # but never without that session
        self.assertEqual(self.req("POST", "/api/signin", {"kind": "m3u", "url": "http://x/"}, {"X-TvBro": "1"})[0], 401)
        code, hdr, b = self.req("GET", "/api/session")
        j = json.loads(b)
        self.assertEqual((code, j["auth"], j["role"], j["required"]), (200, "ok", "admin", False))
        sc = hdr.get("Set-Cookie", "")
        self.assertIn("HttpOnly", sc)
        self.assertIn("SameSite=Strict", sc)
        self.assertNotIn("Secure", sc)                           # plain http here
        type(self).cookie = sc.split(";", 1)[0]
        type(self).csrf = j["csrf"]
        code, _, b = self.req("GET", "/api/session", headers={"Cookie": self.cookie})
        self.assertEqual(json.loads(b)["csrf"], self.csrf)
        _, hdr, _ = self.req("GET", "/api/session", headers={"X-Forwarded-Proto": "https"})
        self.assertIn("Secure", hdr.get("Set-Cookie", ""))      # behind HTTPS (nginx)
        # the old first-run endpoint does not exist; the encryption key is never served
        self.assertNotEqual(self.req("POST", "/api/auth/setup", {"password": "attacker-pw"}, {"X-TvBro": "1"})[0], 200)
        with open(os.path.join(self.tmp, "data", "key.bin"), "rb") as f:
            key = f.read().strip()
        for p in ("/key.bin", "/data/key.bin", "/../data/key.bin", "/api/../key.bin", "/rec/../key.bin", "/img?u=file:///key.bin",
                  "/api/backup", "/api/state", "/api/sync/status", "/api/recordings"):
            code, _, body = self.authed("GET", p)
            self.assertNotIn(key, body, p)
            self.assertNotIn(b"gAAAA", body, p)                    # nor anything Fernet-encrypted
    def _csrf_and_host(self):
        body = {"op": "put", "key": "x", "value": 1}
        self.assertEqual(self.req("POST", "/api/state/prefs", body, {"Cookie": self.cookie})[0], 403)          # no token
        self.assertEqual(self.req("POST", "/api/state/prefs", body, {"Cookie": self.cookie, "X-CSRF-Token": "0" * 32})[0], 403)
        self.assertEqual(self.req("POST", "/api/state/prefs", raw=json.dumps(body).encode(),
                                  headers={"Cookie": self.cookie, "X-CSRF-Token": self.csrf, "Content-Type": "text/plain"})[0], 403)
        self.assertEqual(self.authed("POST", "/api/state/prefs", body, {"Origin": "https://evil.example"})[0], 403)
        self.assertEqual(self.authed("POST", "/api/state/prefs", body, {"Sec-Fetch-Site": "cross-site"})[0], 403)
        self.assertEqual(self.authed("POST", "/api/state/prefs", body)[0], 200)
        self.assertEqual(self.req("POST", "/api/stream/stop?sid=abc", raw=b'{"sid":"abc"}',      # a beacon (text/plain)
                                  headers={"Cookie": self.cookie, "Content-Type": "text/plain"})[0], 403)
        # DNS rebinding: another Host name is refused
        self.assertEqual(self.req("GET", "/api/session", headers={"Host": "evil.example"})[0], 421)
        self.assertEqual(self.req("GET", "/api/channels", headers={"Host": "rebind.evil.example:8750", "Cookie": self.cookie})[0], 421)
        # security headers
        _, hdr, _ = self.req("GET", "/api/health")
        self.assertEqual(hdr.get("X-Content-Type-Options"), "nosniff")
        self.assertIn("frame-ancestors 'none'", hdr.get("Content-Security-Policy", ""))

    def _sources_refused(self):
        for bad in ({"kind": "m3u", "url": "file:///etc/passwd"}, {"kind": "m3u", "url": "gopher://127.0.0.1:6379/_x"},
                    {"kind": "m3u", "url": "ftp://example.com/list.m3u"}, {"kind": "m3u", "url": "data:,#EXTM3U"},
                    {"kind": "m3u", "url": "http://example.com/a.m3u", "epgUrl": "file:///var/lib/tvbro/key.bin"},
                    {"kind": "xtream", "server": "file:///var/lib/tvbro", "user": "u", "pass": "p"},
                    {"kind": "demo", "url": "concat:/etc/passwd"}):
            code, _, b = self.authed("POST", "/api/signin", bad)
            self.assertEqual(code, 400, (bad, b))
        code, _, b = self.authed("POST", "/api/signin", {"kind": "m3u", "name": "Attack", "url": self.mock_base + "/playlist.m3u"})
        self.assertEqual(code, 200, b)
        for _ in range(100):
            items = self.j(self.authed("GET", "/api/channels?group=__all&size=100")).get("items") or []
            if len(items) >= 14:
                break
            time.sleep(0.2)
        type(self).chans = {c["name"]: c for c in items}
        self.assertGreaterEqual(len(self.chans), 14, items)

    def _streams_refused(self):
        for name in ("Local file", "Key", "Gopher", "Ftp", "Meta", "Ten", "OneSeven", "OneNine", "Cgnat", "V6local",
                     "RedirMeta", "RedirLan", "RedirFile", "Concat"):
            key = self.chans[name]["key"]
            code, _, b = self.authed("GET", "/api/play?kind=live&key=" + urllib.request.quote(key))
            self.assertEqual(code, 200, b)
            url = json.loads(b)["url"]
            for fmt in ("", "&fmt=hls&client=ios"):
                code, hdr, body = self.req("GET", url + "&sid=t1" + fmt)
                self.assertIn(code, (403, 404, 502), (name, code, body[:80]))
                self.assertNotIn(b"root:", body)
                self.assertNotIn(b"gAAAA", body)              # no Fernet data
            # catch-up / recordings of it copy nothing either (the recorder uses the same rules)
        # signed URLs: tampering or another key refused, a URL from a signed-out session refused
        key = self.chans["Local file"]["key"]
        url = self.j(self.authed("GET", "/api/play?kind=live&key=" + urllib.request.quote(key)))["url"]
        self.assertEqual(self.req("GET", url.replace("sig=", "sig=0"))[0], 403)
        self.assertEqual(len(url.split("sig=", 1)[1].split("&", 1)[0]), 32)          # 128-bit signature
        # /seg only takes URLs the server sealed
        self.assertEqual(self.req("GET", "/seg?e=" + "A" * 40)[0], 403)

    def _images(self):
        logo = self.chans["Local file"]["logo"]               # a real PNG
        self.assertTrue(logo.startswith("/img?"), logo)
        code, hdr, body = self.authed("GET", logo)
        self.assertEqual((code, hdr.get("Content-Type")), (200, "image/png"))
        self.assertEqual(hdr.get("X-Content-Type-Options"), "nosniff")
        self.assertIn("sandbox", hdr.get("Content-Security-Policy", ""))
        for name in ("Key", "Gopher"):                          # HTML, as image/png and as text/html
            code, hdr, body = self.authed("GET", self.chans[name]["logo"])
            self.assertEqual(code, 415, name)
            self.assertNotIn(b"<script", body)
        # unsigned / changed URL: not fetched at all (no open proxy)
        for u in ("/img?u=" + urllib.request.quote(self.mock_base + "/logo.png"),
                  "/img?u=" + urllib.request.quote("http://169.254.169.254/") + "&s=" + "0" * 32,
                  logo.replace("logo.png", "evil.html")):
            self.assertEqual(self.authed("GET", u)[0], 403, u)
        self.assertEqual(self.req("GET", logo)[0], 401)            # needs a session

    def _roles_and_sizes(self):
        self.assertEqual(self.authed("POST", "/api/state/prefs", raw=b"{" + b" " * (1024 * 1024 + 10) + b"}")[0], 413)
        # "Require a password" (off by default): another browser works without one until it is on
        other = self.req("GET", "/api/session")[1]["Set-Cookie"].split(";", 1)[0]
        self.assertEqual(self.req("GET", "/api/channels?group=__all", headers={"Cookie": other})[0], 200)
        self.assertEqual(self.authed("POST", "/api/auth/require", {"enabled": True, "password": "short"})[0], 400)
        self.assertEqual(self.req("POST", "/api/auth/require", {"enabled": True, "password": "household-pw"},
                                  {"Cookie": self.cookie})[0], 403)          # CSRF token needed here too
        self.assertEqual(self.authed("POST", "/api/auth/require", {"enabled": True, "password": "household-pw"})[0], 200)
        self.assertEqual(self.req("GET", "/api/channels?group=__all", headers={"Cookie": other})[0], 401)
        j = json.loads(self.req("GET", "/api/session")[2])
        self.assertEqual((j["auth"], j["required"]), ("login", True))        # a new browser must sign in
        self.assertEqual(json.loads(self.req("GET", "/api/session", headers={"Cookie": self.cookie})[2])["role"], "admin")
        self.assertEqual(self.authed("POST", "/api/auth/require", {"enabled": False, "current": "wrong-pass"})[0], 403)
        self.assertEqual(self.authed("POST", "/api/auth/viewer", {"password": "viewer-pw1"})[0], 200)
        code, hdr, b = self.req("POST", "/api/auth/login", {"password": "viewer-pw1"}, {"X-TvBro": "1"})
        self.assertEqual((code, json.loads(b)["role"]), (200, "viewer"))
        vh = {"Cookie": hdr["Set-Cookie"].split(";", 1)[0], "X-CSRF-Token": json.loads(b)["csrf"]}
        self.assertEqual(self.req("GET", "/api/channels?group=__all", headers=vh)[0], 200)
        for p, body in (("/api/settings", {"maxStreams": 2}), ("/api/signin", {"kind": "m3u", "url": "http://example.com/x.m3u"}),
                        ("/api/sources/p0/delete", {}), ("/api/signout", {}), ("/api/restore", {}),
                        ("/api/recordings/share", {"type": "smb"}), ("/api/recordings/delete", {"id": "x"}),
                        ("/api/auth/viewer", {"password": ""}), ("/api/auth/password", {"current": "x", "password": "y" * 9})):
            self.assertEqual(self.req("POST", p, body, vh)[0], 403, p)
        self.assertEqual(self.req("GET", "/api/recordings/dirs", headers=vh)[0], 403)
        # the private-address setting is admin only and off by default
        st = self.j(self.authed("GET", "/api/sync/status"))
        self.assertIs(st["settings"]["allowLanSources"], False)
        self.assertEqual(self.authed("POST", "/api/auth/logout", {})[0], 200)
        # a signed URL of that session stops working once it signed out ... (admin logs in again)
        code, hdr, b = self.req("POST", "/api/auth/login", {"password": "household-pw"}, {"X-TvBro": "1"})
        type(self).cookie = hdr["Set-Cookie"].split(";", 1)[0]
        type(self).csrf = json.loads(b)["csrf"]

    def _parental_brute_force(self):
        code, _, b = self.authed("POST", "/api/parental/pin", {"pin": "", "newPin": "4321"})
        self.assertEqual(code, 200, b)
        for _ in range(5):
            self.assertEqual(self.authed("POST", "/api/parental/unlock", {"pin": "0000"})[0], 403)
        code, _, _ = self.authed("POST", "/api/parental/unlock", {"pin": "4321"})
        self.assertEqual(code, 429)                           # even the right PIN waits

    def _login_brute_force(self):
        for i in range(5):
            self.assertEqual(self.req("POST", "/api/auth/login", {"password": f"guess-{i}xx"}, {"X-TvBro": "1"})[0], 403)
        code, hdr, b = self.req("POST", "/api/auth/login", {"password": "household-pw"}, {"X-TvBro": "1"})
        self.assertEqual(code, 429, b)                        # the right one too, while waiting
        self.assertGreaterEqual(int(hdr.get("Retry-After", "0")), 1)


if __name__ == "__main__":
    unittest.main()
