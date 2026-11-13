# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Server-side user state in SQLite, shared by every device on the LAN: My list, resume
positions / watched marks, reminders, search history and preferences."""
from __future__ import annotations

import json
import sqlite3
import threading
import time

SCOPES = {"mylist": 2000, "resume": 2000, "reminders": 500, "history": 20, "prefs": 200}
MAX_VALUE = 16 * 1024


class StateStore:
    def __init__(self, path: str):
        self._lock = threading.Lock()
        self.db = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("CREATE TABLE IF NOT EXISTS kv (scope TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL,"
                        " updated REAL NOT NULL, PRIMARY KEY (scope, key))")

    def all(self) -> dict:
        out: dict = {"mylist": [], "resume": {}, "reminders": [], "history": [], "prefs": {}}
        with self._lock:
            rows = self.db.execute("SELECT scope, key, value FROM kv ORDER BY updated DESC").fetchall()
        for scope, key, value in rows:
            try:
                v = json.loads(value)
            except ValueError:
                continue
            if scope in ("resume", "prefs"):
                out[scope][key] = v
            elif scope in out:
                out[scope].append(v)
        return out

    def put(self, scope: str, key: str, value) -> bool:
        if scope not in SCOPES or not key or len(key) > 300:
            return False
        raw = json.dumps(value)
        if len(raw) > MAX_VALUE:
            return False
        with self._lock:
            self.db.execute("INSERT INTO kv(scope, key, value, updated) VALUES (?,?,?,?) "
                            "ON CONFLICT(scope, key) DO UPDATE SET value=excluded.value, updated=excluded.updated",
                            (scope, key, raw, time.time()))
            # keep each scope bounded (oldest go first)
            self.db.execute("DELETE FROM kv WHERE scope=? AND key NOT IN (SELECT key FROM kv WHERE scope=? "
                            "ORDER BY updated DESC LIMIT ?)", (scope, scope, SCOPES[scope]))
        return True

    def delete(self, scope: str, key: str):
        with self._lock:
            self.db.execute("DELETE FROM kv WHERE scope=? AND key=?", (scope, key))

    def clear(self, scope: str):
        if scope in SCOPES:
            with self._lock:
                self.db.execute("DELETE FROM kv WHERE scope=?", (scope,))
