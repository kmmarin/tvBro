# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Who may use the server: browser sessions, and an optional password.

- By default no password is needed: every browser gets an ``open`` session on its first
  request to /api/session (the session is what CSRF tokens and signed media URLs are bound
  to). Anyone on the network can then use and change the app, as before.
- Settings ▸ Security ▸ "Require a password" (off by default) sets the admin password and
  turns the requirement on: open sessions end and every device must sign in. An optional
  viewer password then signs devices in for watching only.
- Passwords are stored only as salted PBKDF2-SHA256 hashes (600 000 iterations) in
  ``auth.json`` (0600). At least 8 characters.
- Signing in gives the browser a random 256-bit session id in an ``HttpOnly; SameSite=Strict``
  cookie (``Secure`` behind HTTPS); the server keeps only its SHA-256 (``sessions.json``, 0600),
  so a copy of the data folder does not give anyone a session. Sessions last 180 days and are
  renewed while used.
- Roles: ``admin`` (everything) and ``viewer`` (watch, lists, layout; no provider, settings,
  storage, restore or deletes).
- Wrong passwords are throttled per client address and overall: after 5 wrong ones each try
  waits 30 s, doubling up to 15 min (429 + Retry-After).
- CSRF: every write needs the session's token (HMAC of the session) in ``X-CSRF-Token``.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import secrets
import threading
import time
from typing import Callable, Optional

from .secfile import write_secret

ITERATIONS = 600_000
MIN_LEN = 8
MAX_LEN = 200
SESSION_TTL = 180 * 86400
RENEW_AFTER = 30 * 86400            # a used session gets a new expiry at most monthly
MAX_SESSIONS = 300
COOKIE = "tvbro_s"
ROLES = ("admin", "viewer")
SESSION_ROLES = ROLES + ("open",)          # open: no password required (acts as admin)


def hash_password(pw: str, salt: Optional[bytes] = None, iterations: Optional[int] = None) -> str:
    iterations = iterations or ITERATIONS
    salt = salt or secrets.token_bytes(16)
    dk = hashlib.pbkdf2_hmac("sha256", pw.encode(), salt, iterations)
    return f"pbkdf2_sha256${iterations}${salt.hex()}${dk.hex()}"


def verify_password(pw: str, stored: str) -> bool:
    try:
        algo, it, salt, want = stored.split("$")
        if algo != "pbkdf2_sha256":
            return False
        dk = hashlib.pbkdf2_hmac("sha256", pw.encode(), bytes.fromhex(salt), int(it))
        return hmac.compare_digest(dk.hex(), want)
    except (ValueError, AttributeError, TypeError):
        return False


def check_new_password(pw) -> str:
    if not isinstance(pw, str) or not (MIN_LEN <= len(pw) <= MAX_LEN):
        raise ValueError(f"the password must be {MIN_LEN} to {MAX_LEN} characters")
    return pw


class Wait(Exception):
    """Too many wrong passwords: try again after ``retry_after`` seconds."""

    def __init__(self, retry_after: int):
        super().__init__("too many wrong passwords")
        self.retry_after = max(1, int(retry_after))


class Throttle:
    """Wrong-password counter per key (client address) and overall."""
    FREE = 5
    BASE = 30
    MAX_WAIT = 900
    GLOBAL_FREE = 30

    def __init__(self, clock: Callable[[], float] = time.time):
        self.clock = clock
        self._lock = threading.Lock()
        self._d: dict[str, list] = {}         # key -> [fails, wait_until]

    def _wait(self, fails: int, free: int) -> float:
        if fails < free:
            return 0.0
        return min(self.MAX_WAIT, self.BASE * (2 ** min(10, fails - free)))

    def check(self, key: str):
        now = self.clock()
        with self._lock:
            for k in (key, "*"):
                e = self._d.get(k)
                if e and now < e[1]:
                    raise Wait(e[1] - now + 0.999)

    def fail(self, key: str):
        now = self.clock()
        with self._lock:
            for k, free in ((key, self.FREE), ("*", self.GLOBAL_FREE)):
                e = self._d.setdefault(k, [0, 0.0])
                e[0] += 1
                w = self._wait(e[0], free)
                if w:
                    e[1] = now + w
            if len(self._d) > 5000:            # many addresses: forget the oldest
                for k in list(self._d)[:1000]:
                    if k != "*":
                        self._d.pop(k, None)

    def ok(self, key: str):
        with self._lock:
            self._d.pop(key, None)
            g = self._d.get("*")
            if g:
                g[0] = max(0, g[0] - self.FREE)


class Auth:
    def __init__(self, data_dir: str, key: bytes, clock: Callable[[], float] = time.time):
        self.path = os.path.join(data_dir, "auth.json")
        self.sess_path = os.path.join(data_dir, "sessions.json")
        self.key = key
        self.clock = clock
        self.throttle = Throttle(clock)
        self._lock = threading.RLock()
        self.data = {"admin": None, "viewer": None, "required": False}
        self.sessions: dict[str, dict] = {}     # sha256(session id) -> {role, exp}
        try:
            with open(self.path, encoding="utf-8") as f:
                d = json.load(f)
            for r in ROLES:
                if isinstance(d.get(r), str):
                    self.data[r] = d[r]
            self.data["required"] = d.get("required") is True
        except (OSError, ValueError):
            pass
        try:
            with open(self.sess_path, encoding="utf-8") as f:
                d = json.load(f)
            now = self.clock()
            self.sessions = {h: s for h, s in d.items() if isinstance(s, dict) and s.get("role") in SESSION_ROLES
                             and float(s.get("exp") or 0) > now}
        except (OSError, ValueError):
            pass

    # ---- files ----
    def _save(self):
        write_secret(self.path, json.dumps(self.data).encode())

    def _save_sessions(self):
        write_secret(self.sess_path, json.dumps(self.sessions).encode())

    # ---- passwords ----
    def required(self) -> bool:
        """A password is required (the admin turned it on and an admin password exists)."""
        return bool(self.data.get("required")) and bool(self.data["admin"])

    def has_viewer(self) -> bool:
        return bool(self.data["viewer"])

    def open_session(self) -> str:
        """No password required: a new browser gets a session of its own (CSRF, signed URLs)."""
        if self.required():
            raise PermissionError("a password is required")
        with self._lock:
            return self._new_session("open")

    def set_required(self, on: bool, pw: Optional[str] = None, keep: Optional[str] = None):
        """Turn "Require a password" on (with the admin password: a new one, or the current one
        again) or off. On: every open session ends, ``keep`` (the caller's) becomes admin."""
        with self._lock:
            if on:
                if pw:
                    check_new_password(pw)
                    self.data["admin"] = hash_password(pw)
                if not self.data["admin"]:
                    raise ValueError("choose the admin password")
                self.data["required"] = True
                self._save()
                self.sessions = {h: s for h, s in self.sessions.items() if s["role"] != "open" or h == keep}
                if keep in self.sessions:
                    self.sessions[keep]["role"] = "admin"
            else:
                self.data["required"] = False
                self._save()
            self._save_sessions()

    def setup(self, pw: str) -> str:
        """Require a password from now on with this admin password; returns an admin session id
        (used by tests and the first sign-in of a fresh install that wants one)."""
        self.set_required(True, pw)
        with self._lock:
            return self._new_session("admin")

    def login(self, pw: str, client: str) -> tuple[str, str]:
        """(session id, role). Raises Wait while throttled, PermissionError when wrong."""
        self.throttle.check(client)
        pw = pw if isinstance(pw, str) else ""
        role = None
        if pw and len(pw) <= MAX_LEN:
            for r in ROLES:                      # both hashes are always computed (same time)
                st = self.data.get(r)
                if st and verify_password(pw, st) and role is None:
                    role = r
        if role is None:
            self.throttle.fail(client)
            raise PermissionError("wrong password")
        self.throttle.ok(client)
        with self._lock:
            return self._new_session(role), role

    def set_password(self, role: str, pw: Optional[str], keep: Optional[str] = None):
        """Admin: change the admin password (other sessions end) or set / remove ("" or None)
        the viewer password (viewer sessions end)."""
        if role not in ROLES:
            raise ValueError("role")
        if role == "admin" or pw:
            check_new_password(pw)
        with self._lock:
            self.data[role] = hash_password(pw) if pw else None
            self._save()
            self.sessions = {h: s for h, s in self.sessions.items()
                             if s["role"] != role or h == keep}
            self._save_sessions()

    # ---- sessions ----
    @staticmethod
    def _h(sid: str) -> str:
        return hashlib.sha256(sid.encode()).hexdigest()

    def _new_session(self, role: str) -> str:
        sid = secrets.token_urlsafe(32)
        self.sessions[self._h(sid)] = {"role": role, "exp": int(self.clock() + SESSION_TTL)}
        while len(self.sessions) > MAX_SESSIONS:
            oldest = min(self.sessions, key=lambda h: self.sessions[h]["exp"])
            self.sessions.pop(oldest)
        self._save_sessions()
        return sid

    def session(self, sid: Optional[str]) -> Optional[dict]:
        """{"h": hash, "role", "renew": bool} for a live session id, else None."""
        if not sid or len(sid) > 100:
            return None
        h = self._h(sid)
        with self._lock:
            s = self.sessions.get(h)
            if not s:
                return None
            now = self.clock()
            if s["exp"] <= now or (s["role"] == "open" and self.required()):
                self.sessions.pop(h, None)
                self._save_sessions()
                return None
            renew = s["exp"] - now < SESSION_TTL - RENEW_AFTER
            if renew:
                s["exp"] = int(now + SESSION_TTL)
                self._save_sessions()
            return {"h": h, "role": s["role"], "renew": renew}

    def logout(self, h: str):
        with self._lock:
            if self.sessions.pop(h, None) is not None:
                self._save_sessions()

    def tag_alive(self, tag: str) -> bool:
        """Signed media URLs carry the first 16 hex of the session hash: they stop working
        when that session ends."""
        if not tag or len(tag) != 16:
            return False
        now = self.clock()
        with self._lock:
            req = self.required()
            return any(h.startswith(tag) and s["exp"] > now and not (req and s["role"] == "open")
                       for h, s in self.sessions.items())

    # ---- CSRF ----
    def csrf(self, h: str) -> str:
        return hmac.new(self.key, ("csrf:" + h).encode(), hashlib.sha256).hexdigest()[:32]

    def csrf_ok(self, h: str, tok: str) -> bool:
        return bool(tok) and hmac.compare_digest(self.csrf(h), tok)
