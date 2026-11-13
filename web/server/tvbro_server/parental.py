# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Parental PIN: locked live groups, channels and VOD categories (parental.json).

- The PIN is stored only as a salted PBKDF2-SHA256 hash; it is never returned or logged.
- Wrong PINs are counted: after 5 in a row every further try waits 30 s (60 s after 10, …), so a
  4-digit PIN cannot be walked through from a browser.
- A right PIN gives the browser an unlock token (HMAC-signed, 4 h); the server asks for it before it
  signs a stream URL or hands out film / show details of something locked. Changing or removing the
  PIN ends every unlock.
- Locks only bite while a PIN is set. Lists of locks survive a PIN change (and a backup / restore).

Pure logic plus an atomic JSON file; the HTTP layer decides what is locked for a request.
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

ITERATIONS = 120_000
TOKEN_TTL = 4 * 3600
MAX_LOCKS = 5000
KINDS = {"group": "groups", "channel": "channels", "vodcat": "vodCats"}


def hash_pin(pin: str, salt: Optional[bytes] = None, iterations: int = ITERATIONS) -> str:
    salt = salt or secrets.token_bytes(16)
    dk = hashlib.pbkdf2_hmac("sha256", pin.encode(), salt, iterations)
    return f"pbkdf2_sha256${iterations}${salt.hex()}${dk.hex()}"


def verify_pin(pin: str, stored: str) -> bool:
    try:
        algo, it, salt, want = stored.split("$")
        if algo != "pbkdf2_sha256":
            return False
        dk = hashlib.pbkdf2_hmac("sha256", pin.encode(), bytes.fromhex(salt), int(it))
        return hmac.compare_digest(dk.hex(), want)
    except (ValueError, AttributeError):
        return False


def valid_pin_format(pin: str) -> bool:
    return isinstance(pin, str) and 4 <= len(pin) <= 8 and pin.isdigit()


class Locked(Exception):
    """Too many wrong PINs: try again after `retry_after` seconds."""

    def __init__(self, retry_after: int):
        super().__init__("too many wrong PINs")
        self.retry_after = retry_after


class Parental:
    def __init__(self, path: str, key: bytes, clock: Callable[[], float] = time.time):
        self.path = path
        self.key = key
        self.clock = clock
        self._lock = threading.RLock()
        self.data = {"pin": None, "gen": 0, "groups": [], "channels": [], "vodCats": []}
        self._fails = 0
        self._wait_until = 0.0
        try:
            with open(path, encoding="utf-8") as f:
                d = json.load(f)
            for k in ("groups", "channels", "vodCats"):
                if isinstance(d.get(k), list):
                    self.data[k] = [str(x) for x in d[k]][:MAX_LOCKS]
            if isinstance(d.get("pin"), str):
                self.data["pin"] = d["pin"]
            if isinstance(d.get("gen"), int):
                self.data["gen"] = d["gen"]
        except (OSError, ValueError):
            pass

    def _save(self):
        write_secret(self.path, json.dumps(self.data).encode())

    # ---- PIN ----
    def has_pin(self) -> bool:
        return bool(self.data["pin"])

    def active(self) -> bool:
        """Locks apply: a PIN is set and something is locked."""
        d = self.data
        return bool(d["pin"]) and bool(d["groups"] or d["channels"] or d["vodCats"])

    def check(self, pin: str) -> bool:
        """Raises Locked while waiting after too many wrong PINs."""
        with self._lock:
            now = self.clock()
            if now < self._wait_until:
                raise Locked(int(self._wait_until - now) + 1)
            ok = bool(self.data["pin"]) and verify_pin(str(pin or ""), self.data["pin"])
            if ok:
                self._fails = 0
                return True
            self._fails += 1
            if self._fails >= 5:
                self._wait_until = now + 30 * (2 ** min(4, (self._fails - 5) // 5))
            return False

    def set_pin(self, new: str, current: Optional[str] = None):
        """Set, change ("" removes) the PIN. With a PIN set, the current one (or a valid unlock
        token checked by the caller, passed as current=True) is needed. Raises ValueError /
        PermissionError / Locked."""
        with self._lock:
            if self.has_pin() and current is not True and not self.check(current or ""):
                raise PermissionError("wrong PIN")
            if new and not valid_pin_format(new):
                raise ValueError("the PIN must be 4-8 digits")
            self.data["pin"] = hash_pin(new) if new else None
            self.data["gen"] += 1                     # every unlock token ends
            self._save()

    # ---- unlock tokens ----
    def token(self, ttl: int = TOKEN_TTL) -> str:
        exp = int(self.clock()) + ttl
        g = self.data["gen"]
        sig = hmac.new(self.key, f"parental:{exp}:{g}".encode(), hashlib.sha256).hexdigest()[:32]
        return f"{exp}.{g}.{sig}"

    def valid(self, tok: str) -> bool:
        try:
            exp, g, sig = str(tok or "").split(".")
            if int(exp) < self.clock() or int(g) != self.data["gen"]:
                return False
        except ValueError:
            return False
        want = hmac.new(self.key, f"parental:{exp}:{g}".encode(), hashlib.sha256).hexdigest()[:32]
        return hmac.compare_digest(sig, want)

    # ---- locks ----
    def lock(self, kind: str, ident: str, on: bool):
        field = KINDS.get(kind)
        if not field or not ident:
            raise ValueError("what to lock?")
        with self._lock:
            lst = self.data[field]
            if on and ident not in lst:
                if len(lst) >= MAX_LOCKS:
                    raise ValueError("too many locks")
                lst.append(ident)
            elif not on and ident in lst:
                lst.remove(ident)
            self._save()

    def locks(self) -> dict:
        return {k: list(self.data[k]) for k in ("groups", "channels", "vodCats")}

    def set_locks(self, d: dict):
        """Restore (backup): replaces the lists, keeps the PIN."""
        with self._lock:
            for k in ("groups", "channels", "vodCats"):
                self.data[k] = [str(x)[:300] for x in (d.get(k) or [])][:MAX_LOCKS]
            self._save()

    def public(self) -> dict:
        return {"pin": self.has_pin(), "active": self.active(), **self.locks()}
