# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""EPG on disk (SQLite) instead of in RAM: ~400k programmes for 15.8k channels would take
~300 MB as Python objects and twice that while a refresh builds the new copy. A sync
writes a fresh database file and swaps it in atomically (os.replace), so a failed sync
keeps the old guide and readers never see a half-written one."""
from __future__ import annotations

import os
import sqlite3
import threading
from typing import Optional

from . import parsers as P
from .swapdb import Readers

SCHEMA = ("CREATE TABLE prog (key TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, "
          "title TEXT NOT NULL, descr TEXT, sub TEXT, ep TEXT)")
GCH_SCHEMA = "CREATE TABLE IF NOT EXISTS gch (id TEXT PRIMARY KEY, name TEXT)"
NO_GUIDE = "__none"          # a mapping that says: this channel has no guide (a wrong automatic match)


class EpgWriter:
    def __init__(self, path: str):
        self.path = path
        if os.path.exists(path):
            os.remove(path)
        self.db = sqlite3.connect(path, isolation_level=None)
        self.db.execute("PRAGMA journal_mode=OFF")
        self.db.execute("PRAGMA synchronous=OFF")
        self.db.execute(SCHEMA)
        self.db.execute("BEGIN")
        self.buf: list = []
        self.count = 0

    def add(self, p: P.Programme):
        self.buf.append((p.key, p.start, p.stop, p.title, p.desc, p.sub, p.ep))
        self.count += 1
        if len(self.buf) >= 5000:
            self.flush()

    def flush(self):
        if self.buf:
            self.db.executemany("INSERT INTO prog VALUES (?,?,?,?,?,?,?)", self.buf)
            self.buf = []

    def guide_channels(self, names: dict):
        """The guide's own channel list (XMLTV <channel id> + display names), for the manual
        mapping picker: every guide channel, matched or not."""
        self.db.execute(GCH_SCHEMA)
        rows = [(cid, " / ".join(dict.fromkeys(n for n in dn if n))[:200]) for cid, dn in names.items() if cid]
        self.db.executemany("INSERT OR REPLACE INTO gch VALUES (?,?)", rows)

    def finish(self) -> dict:
        self.flush()
        self.db.execute("COMMIT")
        self.db.execute("CREATE INDEX prog_key ON prog(key, start)")
        row = self.db.execute("SELECT MIN(start), MAX(stop) FROM prog").fetchone()
        keys = {r[0] for r in self.db.execute("SELECT DISTINCT key FROM prog")}
        self.db.close()
        return {"from": row[0], "to": row[1], "keys": keys, "programmes": self.count}

    def abort(self):
        try:
            self.db.close()
        finally:
            try:
                os.remove(self.path)
            except OSError:
                pass


class EpgDb(Readers):
    """Read side. One connection per thread, reopened after a swap."""

    def __init__(self, path: str):
        super().__init__(path)
        self.keys: set[str] = set()

    def swap_in(self, tmp_path: str, keys: set[str]):
        self.replace_file(tmp_path)
        self.keys = keys
        self.gen += 1

    def load_keys(self):
        if os.path.exists(self.path):
            self.keys = {r[0] for r in self._conn().execute("SELECT DISTINCT key FROM prog")}

    def available(self) -> bool:
        return os.path.exists(self.path)

    def window(self, key: str, frm: int, to: int) -> list[P.Programme]:
        if not key or key not in self.keys:
            return []
        rows = self._conn().execute(
            "SELECT key, start, stop, title, descr, sub, ep FROM prog WHERE key=? AND stop>? AND start<? ORDER BY start",
            (key, frm, to)).fetchall()
        return [P.Programme(*r) for r in rows]

    def guide_channels(self, sid: str, q: str = "", limit: int = 60) -> Optional[list[dict]]:
        """Guide channels (XMLTV ids + names), filtered by q (every word, in the id or name). None when
        this guide was stored by an older version (no list yet: the next guide sync adds it)."""
        if not self.available():
            return None
        try:
            rows = self._conn().execute("SELECT id, name FROM gch ORDER BY name COLLATE NOCASE").fetchall()
        except sqlite3.Error:
            return None
        words = [w for w in (q or "").lower().split() if w]
        out = []
        for cid, name in rows:
            hay = f"{cid} {name or ''}".lower()
            if all(w in hay for w in words):
                out.append({"id": cid, "name": name or cid, "hasGuide": f"{sid}|{cid.lower()}" in self.keys})
                if len(out) >= limit:
                    break
        return out

    def guide_name(self, cid: str) -> Optional[str]:
        try:
            r = self._conn().execute("SELECT name FROM gch WHERE id=? COLLATE NOCASE", (cid,)).fetchone()
        except sqlite3.Error:
            return None
        return r[0] if r else None


def mapped_key(ch: P.Channel, gid: str) -> str:
    return f"{ch.source_id}|{gid.lower()}"


def key_for(db: EpgDb, ch: P.Channel, emap: Optional[dict] = None) -> Optional[str]:
    """The guide key of a channel: the household's manual mapping first (epgmap.json), else the
    playlist's tvg-id, else its name."""
    m = emap.get(ch.key) if emap else None
    if m:
        if m == NO_GUIDE:
            return None
        k = mapped_key(ch, m)
        return k if k in db.keys else None
    if ch.id_key and ch.id_key in db.keys:
        return ch.id_key
    return ch.name_key if ch.name_key in db.keys else None
