# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Search index on disk: SQLite FTS5 over channel names, movie / series titles (+ year) and
programme titles (+ episode title), built in the background after every sync and swapped in
atomically (os.replace), like epg.db. Nothing big is loaded into RAM: programmes are copied
from epg.db by SQL, and a query touches only the index pages it needs.

Matching: every word of the query is a prefix ("barc" finds "Barcelona"), accents are
ignored (unicode61 remove_diacritics 2), all words must match. Ranking: bm25, with names that
start with the first word first. Programmes are classified server-side (on now / upcoming /
past with catch-up; past without catch-up is dropped) and grouped by channel."""
from __future__ import annotations

import os
import re
import sqlite3
import threading
import time
from typing import Iterable, Optional

from . import parsers as P
from .swapdb import Readers

TOK = "tokenize='unicode61 remove_diacritics 2', prefix='2 3 4'"
BACK = 7 * 86400                 # programmes older than this are never offered
LIMITS = {"movies": 20, "series": 20, "channels": 30, "progChannels": 10, "progPerChannel": 5}
ST_NAMES = {1: "now", 2: "future", 3: "catchup"}

_WORD = re.compile(r"\w+", re.UNICODE)


def fts_query(q: str) -> Optional[str]:
    """User text -> FTS5 query: every word as a quoted prefix term, all required."""
    words = _WORD.findall((q or "").lower())[:8]
    if not words:
        return None
    return " ".join(f'"{w}"*' for w in words)


def _first_word(q: str) -> str:
    w = _WORD.findall((q or "").lower())
    return w[0] if w else ""


def build(path: str, channels: list[P.Channel], movies: Iterable[P.VodItem], series: Iterable[P.VodItem],
          epg_path: Optional[str], epg_key_of=None) -> dict:
    """Writes a complete index to `path` (removed first). Returns counts. epg_key_of(channel): the
    manual guide mapping (None = automatic, "" = no guide, else the guide key)."""
    if os.path.exists(path):
        os.remove(path)
    db = sqlite3.connect(path, isolation_level=None)
    try:
        db.execute("PRAGMA journal_mode=OFF")
        db.execute("PRAGMA synchronous=OFF")
        db.execute("PRAGMA temp_store=FILE")
        db.execute("BEGIN")
        db.execute("CREATE TABLE ch (id INTEGER PRIMARY KEY, key TEXT NOT NULL, name TEXT NOT NULL, cu INTEGER NOT NULL)")
        db.execute("CREATE TABLE vod (id INTEGER PRIMARY KEY, kind TEXT NOT NULL, key TEXT NOT NULL, name TEXT NOT NULL, year TEXT)")
        db.execute("CREATE TABLE title (id INTEGER PRIMARY KEY, title TEXT NOT NULL, sub TEXT NOT NULL)")
        db.execute("CREATE TABLE pr (tid INTEGER NOT NULL, ch INTEGER NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, ep TEXT)")
        db.execute("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)")
        # channels, and which channel each guide key belongs to (as the guide resolves it)
        emap: dict[str, int] = {}
        rows = []
        for i, c in enumerate(channels, 1):
            rows.append((i, c.key, c.name, c.catchup_days if c.catchup and c.catchup_days > 0 else 0))
            mk = epg_key_of(c) if epg_key_of else None
            if mk is not None:
                if mk:
                    emap[mk] = i                    # picked by hand: wins over an automatic match
                continue
            if c.id_key:
                emap.setdefault(c.id_key, i)
            emap.setdefault(c.name_key, i)
        db.executemany("INSERT INTO ch VALUES (?,?,?,?)", rows)
        vrows = [(it.kind, it.key, it.name, it.year or "") for it in movies]
        vrows += [(it.kind, it.key, it.name, it.year or "") for it in series]
        db.executemany("INSERT INTO vod (kind, key, name, year) VALUES (?,?,?,?)", vrows)
        n_prog = 0
        if epg_path and os.path.exists(epg_path):
            db.execute("CREATE TEMP TABLE emap (ekey TEXT PRIMARY KEY, ch INTEGER NOT NULL)")
            db.executemany("INSERT INTO emap VALUES (?,?)", emap.items())
            db.execute("COMMIT")                    # ATTACH is not allowed inside a transaction
            db.execute("ATTACH DATABASE ? AS e", (epg_path,))
            db.execute("BEGIN")
            db.execute("INSERT INTO title (title, sub) SELECT DISTINCT p.title, coalesce(p.sub, '') "
                       "FROM e.prog p JOIN emap m ON m.ekey = p.key")
            db.execute("CREATE UNIQUE INDEX title_ts ON title(title, sub)")
            db.execute("INSERT INTO pr SELECT t.id, m.ch, p.start, p.stop, p.ep FROM e.prog p "
                       "JOIN emap m ON m.ekey = p.key JOIN title t ON t.title = p.title AND t.sub = coalesce(p.sub, '')")
            n_prog = db.execute("SELECT count(*) FROM pr").fetchone()[0]
            db.execute("COMMIT")
            db.execute("DETACH DATABASE e")
            db.execute("BEGIN")
            db.execute("DROP INDEX title_ts")
            db.execute("DROP TABLE emap")
        db.execute("CREATE INDEX pr_tid ON pr(tid, stop)")
        db.execute(f"CREATE VIRTUAL TABLE f_ch USING fts5(name, content='ch', content_rowid='id', {TOK})")
        db.execute(f"CREATE VIRTUAL TABLE f_vod USING fts5(name, year, content='vod', content_rowid='id', {TOK})")
        db.execute(f"CREATE VIRTUAL TABLE f_title USING fts5(title, sub, content='title', content_rowid='id', {TOK})")
        for t in ("f_ch", "f_vod", "f_title"):
            db.execute(f"INSERT INTO {t}({t}) VALUES('rebuild')")
            db.execute(f"INSERT INTO {t}({t}) VALUES('optimize')")
        counts = {"channels": len(rows), "vod": len(vrows), "titles": db.execute("SELECT count(*) FROM title").fetchone()[0],
                  "programmes": n_prog}
        db.executemany("INSERT INTO meta VALUES (?,?)", [("builtAt", str(int(time.time())))] +
                       [(k, str(v)) for k, v in counts.items()])
        db.execute("COMMIT")
    except BaseException:
        db.close()
        try:
            os.remove(path)
        except OSError:
            pass
        raise
    db.close()
    return counts


class SearchIndex(Readers):
    """Read side. One read-only connection per thread, reopened after a swap."""

    def __init__(self, path: str):
        super().__init__(path)
        self.counts: dict = {}

    def available(self) -> bool:
        return os.path.exists(self.path)

    def swap_in(self, tmp_path: str, counts: Optional[dict] = None):
        self.replace_file(tmp_path)
        self.counts = counts or {}
        self.gen += 1

    def load_counts(self):
        try:
            self.counts = {k: int(v) for k, v in self._conn().execute("SELECT k, v FROM meta")}
        except (sqlite3.Error, ValueError):
            self.counts = {}

    # ---- queries (fq = fts_query(q); w0 = first word, for the starts-with boost) ----
    def channels(self, fq: str, w0: str, limit: int) -> list[str]:
        """Channel keys, best first."""
        return [r[0] for r in self._conn().execute(
            "SELECT ch.key FROM f_ch JOIN ch ON ch.id = f_ch.rowid WHERE f_ch MATCH ? "
            "ORDER BY instr(lower(ch.name), ?) = 1 DESC, bm25(f_ch), length(ch.name) LIMIT ?", (fq, w0, limit))]

    def vod(self, kind: str, fq: str, w0: str, limit: int) -> list[str]:
        """VOD keys of one kind ("movie" / "series"), best first."""
        return [r[0] for r in self._conn().execute(
            "SELECT vod.key FROM f_vod JOIN vod ON vod.id = f_vod.rowid WHERE f_vod MATCH ? AND vod.kind = ? "
            "ORDER BY instr(lower(vod.name), ?) = 1 DESC, bm25(f_vod, 4.0, 1.0), length(vod.name), vod.year DESC LIMIT ?",
            (fq, kind, w0, limit))]

    def programmes(self, fq: str, now: int, row_limit: int = 2000) -> list[tuple]:
        """(state, channel key, title, sub, ep, start, stop) for programmes still watchable or
        upcoming, ordered by state (now, upcoming, catch-up), relevance, then time (upcoming
        soonest first, catch-up newest first). state: 1 now, 2 upcoming, 3 catch-up."""
        sql = (
            "WITH t AS (SELECT rowid AS id, bm25(f_title, 4.0, 1.0) AS r FROM f_title WHERE f_title MATCH :q "
            "           ORDER BY r LIMIT 400) "
            "SELECT st, key, title, sub, ep, start, stop FROM ("
            "  SELECT CASE WHEN pr.start > :now THEN 2 WHEN pr.stop > :now THEN 1 "
            "              WHEN ch.cu > 0 AND pr.start >= :now - ch.cu * 86400 THEN 3 ELSE 0 END AS st, "
            "         t.r AS r, ch.key AS key, ti.title AS title, ti.sub AS sub, pr.ep AS ep, pr.start AS start, pr.stop AS stop "
            "  FROM t JOIN pr ON pr.tid = t.id AND pr.stop > :back JOIN title ti ON ti.id = t.id JOIN ch ON ch.id = pr.ch) "
            "WHERE st > 0 ORDER BY st, r, CASE WHEN st = 3 THEN -start ELSE start END LIMIT :lim")
        return self._conn().execute(sql, {"q": fq, "now": now, "back": now - BACK, "lim": row_limit}).fetchall()


def group_programmes(rows: Iterable[tuple], keep=lambda key: True, max_channels: int = LIMITS["progChannels"],
                     per_channel: int = LIMITS["progPerChannel"]) -> list[tuple[str, list[dict]]]:
    """Rows from SearchIndex.programmes -> [(channel key, [programme json])], channels in the
    order of their best programme, at most max_channels x per_channel. A channel's list is
    what is on now, then upcoming, then catch-up; up to 2 places are kept for catch-up so
    yesterday's airing is not pushed out by a week of repeats."""
    groups: dict[str, dict[int, list[dict]]] = {}
    skip: set[str] = set()
    for st, key, title, sub, ep, start, stop in rows:
        if key in skip:
            continue
        g = groups.get(key)
        if g is None:
            if len(groups) >= max_channels or not keep(key):
                skip.add(key)
                continue
            g = groups[key] = {1: [], 2: [], 3: []}
        lst = g[st]
        if len(lst) >= per_channel or any(x["start"] == start for x in lst):
            continue
        p = {"start": start, "stop": stop, "title": title, "st": ST_NAMES[st]}
        if sub:
            p["sub"] = sub
        if ep:
            p["ep"] = ep
        lst.append(p)
    out = []
    for key, g in groups.items():
        cu = g[3][:min(2, per_channel)]
        head = (g[1] + g[2])[:per_channel - len(cu)]
        cu = g[3][:per_channel - len(head)]          # fewer upcoming: more catch-up
        out.append((key, head + cu))
    return out
