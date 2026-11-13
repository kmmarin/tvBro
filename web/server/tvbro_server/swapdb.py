# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Read-only SQLite connections, one per thread, for a database file that a sync replaces
atomically (epg.db, search.db).

On Linux os.replace() swaps the file under open readers (they keep the old one until they reopen).
Windows refuses to replace a file SQLite has open, so there (dev machines) the readers' connections
are closed and the replace is tried again; a reader opens the new file on its next query."""
from __future__ import annotations

import os
import sqlite3
import threading
import time


class Readers:
    def __init__(self, path: str):
        self.path = path
        self.gen = 0
        self._local = threading.local()
        self._reg_lock = threading.Lock()
        self._open: list[sqlite3.Connection] = []
        self._closed_at = 0          # bumped when every reader was closed (Windows swap)

    def _conn(self) -> sqlite3.Connection:
        L = self._local
        c = getattr(L, "c", None)
        if c is None or L.gen != self.gen or getattr(L, "closed_at", 0) != self._closed_at:
            if c is not None:
                self._forget(c)
            c = sqlite3.connect(f"file:{self.path}?mode=ro", uri=True, check_same_thread=False)
            L.c, L.gen, L.closed_at = c, self.gen, self._closed_at
            with self._reg_lock:
                self._open.append(c)
        return c

    def _forget(self, c):
        with self._reg_lock:
            try:
                self._open.remove(c)
            except ValueError:
                pass
        try:
            c.close()
        except sqlite3.Error:
            pass

    def close_thread_conn(self):
        c = getattr(self._local, "c", None)
        if c is not None:
            self._forget(c)
            self._local.c = None

    def _close_all(self):
        with self._reg_lock:
            conns, self._open = self._open, []
            self._closed_at += 1
        for c in conns:
            try:
                c.close()
            except sqlite3.Error:
                pass

    def replace_file(self, tmp_path: str):
        """os.replace(tmp_path, self.path), closing the readers when Windows refuses it."""
        self.close_thread_conn()
        for i in range(80):
            try:
                os.replace(tmp_path, self.path)
                return
            except PermissionError:
                if os.name != "nt" or i == 79:
                    raise
                self._close_all()
                time.sleep(0.05)
