# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Shows: per-episode resume points and watched marks, the last episode, the caption / audio
choice per series, the next episode to play and the Shows "Continue watching" row.

Lives in the same SQLite file as the rest of the user state (state.db), so it follows the
household across devices. Schema version in PRAGMA user_version:
  1: tables series / episodes / series_eps; episode resume points that older versions kept
     in kv (scope "resume", key "e|<id>" with meta.seriesId) are moved in.

Rules (the Android app follows the same ones):
- An episode counts as watched once 90 % of it has played, or it played to the end.
- The next episode: the most recently played one if unfinished (resume), else the first
  unwatched episode after it (seasons in order, then episode numbers); none = finished.
- Continue watching: series with any started episode that are not finished, newest first.
"""
from __future__ import annotations

import json
import re
import threading
import time
from typing import Callable, Optional

WATCHED_FRAC = 0.9
SCHEMA = 1
EPS_TTL = 6 * 3600          # refresh the stored episode list after this long
MAX_SERIES = 3000


def is_watched(secs: float, dur: float, ended: bool = False) -> bool:
    """>= 90 % played, or the player reached the end."""
    if ended:
        return True
    try:
        secs, dur = float(secs or 0), float(dur or 0)
    except (TypeError, ValueError):
        return False
    return dur > 0 and secs >= WATCHED_FRAC * dur


def _num(v, default=0) -> int:
    try:
        return int(v)
    except (TypeError, ValueError):
        return default


def ordered(episodes: list) -> list:
    """Episodes by season, then episode number (stable for equal numbers)."""
    return sorted(episodes or [], key=lambda e: (_num(e.get("season")), _num(e.get("num"))))


def next_episode(episodes: list, prog: dict) -> Optional[dict]:
    """episodes: [{id, season, num, ...}]; prog: {epId: {secs, dur, done, at}}.
    -> {"ep": episode, "resume": bool, "secs", "dur", "started": bool} or None when finished."""
    flat = ordered(episodes)
    if not flat:
        return None
    best = None
    for i, e in enumerate(flat):
        p = prog.get(str(e.get("id")))
        if p is not None:
            k = (float(p.get("at") or 0), i)
            if best is None or k > best:
                best = k
    if best is None:
        return {"ep": flat[0], "resume": False, "secs": 0, "dur": 0, "started": False}
    last = best[1]
    p = prog[str(flat[last]["id"])]
    if not p.get("done"):
        secs = int(p.get("secs") or 0)
        return {"ep": flat[last], "resume": secs > 0, "secs": secs, "dur": int(p.get("dur") or 0), "started": True}
    for e in flat[last + 1:]:
        q = prog.get(str(e.get("id")))
        if not q or not q.get("done"):
            secs = int((q or {}).get("secs") or 0)
            return {"ep": e, "resume": secs > 0, "secs": secs, "dur": int((q or {}).get("dur") or 0), "started": True}
    return None


def pick_track(tracks: list, want: Optional[str]):
    """Caption / audio choice for a new episode: tracks [{lang, label}]; want = saved language /
    label, "off" or None. -> index, -1 for off, None when nothing is saved."""
    if want is None or want == "":
        return None
    if want == "off":
        return -1
    if not tracks:
        return None
    for i, t in enumerate(tracks):
        if want in (t.get("lang"), t.get("label")):
            return i
    return 0


_SUB = re.compile(r"S(\d+)\s*E(\d+)", re.I)


def _ep_json(e: dict) -> dict:
    return {k: e.get(k) for k in ("id", "season", "num", "title", "ext", "duration", "image")}


class SeriesStore:
    """fetch_eps(series_id) -> (name, poster, [episode dicts]) from the provider (may raise)."""

    def __init__(self, state, fetch_eps: Optional[Callable] = None):
        self.db = state.db
        self._lock = state._lock
        self.fetch_eps = fetch_eps
        self._migrate()

    # ------------------------------------------------------------------ schema
    def _migrate(self):
        with self._lock:
            ver = self.db.execute("PRAGMA user_version").fetchone()[0]
            if ver >= SCHEMA:
                return
            self.db.execute("BEGIN")
            try:
                self.db.execute(
                    "CREATE TABLE IF NOT EXISTS series (id TEXT PRIMARY KEY, name TEXT, poster TEXT, skey TEXT,"
                    " last_ep TEXT, subs TEXT, audio TEXT, hidden INTEGER NOT NULL DEFAULT 0,"
                    " next TEXT, finished INTEGER NOT NULL DEFAULT 0, stale INTEGER NOT NULL DEFAULT 1,"
                    " watched_at REAL NOT NULL DEFAULT 0, updated REAL NOT NULL DEFAULT 0)")
                self.db.execute(
                    "CREATE TABLE IF NOT EXISTS episodes (id TEXT PRIMARY KEY, series_id TEXT NOT NULL,"
                    " season INTEGER, num INTEGER, secs REAL NOT NULL DEFAULT 0, dur REAL NOT NULL DEFAULT 0,"
                    " done INTEGER NOT NULL DEFAULT 0, at REAL NOT NULL)")
                self.db.execute("CREATE INDEX IF NOT EXISTS episodes_series ON episodes(series_id)")
                self.db.execute("CREATE TABLE IF NOT EXISTS series_eps (id TEXT PRIMARY KEY, eps TEXT NOT NULL, at REAL NOT NULL)")
                # move the per-episode resume points older versions kept in kv
                has_kv = self.db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='kv'").fetchone()
                rows = self.db.execute("SELECT key, value FROM kv WHERE scope='resume' AND key LIKE 'e|%'").fetchall() if has_kv else []
                moved = []
                for key, value in rows:
                    try:
                        r = json.loads(value)
                        meta = r.get("meta") or {}
                        sid = str(meta.get("seriesId") or "")
                    except (ValueError, AttributeError):
                        continue
                    if not sid:
                        continue
                    m = _SUB.search(str(meta.get("sub") or ""))
                    at = float(r.get("at") or 0)
                    at = at / 1000.0 if at > 1e11 else at          # the browser stored milliseconds
                    self.db.execute("INSERT OR REPLACE INTO episodes(id, series_id, season, num, secs, dur, done, at) VALUES (?,?,?,?,?,?,?,?)",
                                    (key[2:], sid, int(m.group(1)) if m else None, int(m.group(2)) if m else None,
                                     float(r.get("secs") or 0), float(r.get("dur") or 0), 1 if r.get("done") else 0, at))
                    self.db.execute("INSERT INTO series(id, name, poster, skey, watched_at, updated, stale) VALUES (?,?,?,?,?,?,1) "
                                    "ON CONFLICT(id) DO UPDATE SET watched_at=max(watched_at, excluded.watched_at), "
                                    "name=coalesce(series.name, excluded.name), poster=coalesce(series.poster, excluded.poster)",
                                    (sid, meta.get("title"), meta.get("poster"), _series_key(sid), at, at))
                    moved.append(key)
                for key in moved:
                    self.db.execute("DELETE FROM kv WHERE scope='resume' AND key=?", (key,))
                self.db.execute(f"PRAGMA user_version={SCHEMA}")
                self.db.execute("COMMIT")
            except Exception:
                self.db.execute("ROLLBACK")
                raise

    # ------------------------------------------------------------------ episode lists
    def _stored_eps(self, sid: str):
        with self._lock:
            r = self.db.execute("SELECT eps, at FROM series_eps WHERE id=?", (sid,)).fetchone()
        if not r:
            return None, 0
        try:
            return json.loads(r[0]), r[1]
        except ValueError:
            return None, 0

    def episodes(self, sid: str, refresh: bool = False, need: Optional[str] = None) -> list:
        """The series' episode list: stored copy, refreshed from the provider when old, missing,
        asked for, or when it lacks episode `need`. Falls back to what is known locally."""
        eps, at = self._stored_eps(sid)
        stale = eps is None or refresh or time.time() - at > EPS_TTL or (need and not any(str(e.get("id")) == need for e in eps))
        if stale and self.fetch_eps:
            try:
                name, poster, fresh = self.fetch_eps(sid)
                fresh = [_ep_json(e) for e in fresh or []]
                if fresh:
                    eps = fresh
                    with self._lock:
                        self.db.execute("INSERT OR REPLACE INTO series_eps(id, eps, at) VALUES (?,?,?)", (sid, json.dumps(eps), time.time()))
                        self.db.execute("INSERT INTO series(id, name, poster, skey) VALUES (?,?,?,?) ON CONFLICT(id) DO UPDATE SET "
                                        "name=coalesce(excluded.name, series.name), poster=coalesce(excluded.poster, series.poster)",
                                        (sid, name or None, poster or None, _series_key(sid)))
            except Exception:                       # noqa: BLE001 - provider down: keep what we have
                pass
        if eps:
            return eps
        with self._lock:          # nothing from the provider: the episodes played here
            rows = self.db.execute("SELECT id, season, num FROM episodes WHERE series_id=?", (sid,)).fetchall()
        return [{"id": i, "season": s or 0, "num": n or 0} for i, s, n in rows]

    # ------------------------------------------------------------------ reads
    def progress(self, sid: str) -> dict:
        with self._lock:
            rows = self.db.execute("SELECT id, secs, dur, done, at FROM episodes WHERE series_id=?", (sid,)).fetchall()
        return {i: {"secs": int(s), "dur": int(d), "done": bool(dn), "at": a} for i, s, d, dn, a in rows}

    def _row(self, sid: str):
        with self._lock:
            return self.db.execute("SELECT id, name, poster, skey, last_ep, subs, audio, hidden, next, finished, stale, watched_at "
                                   "FROM series WHERE id=?", (sid,)).fetchone()

    def summary(self, sid: str, with_eps: bool = True) -> dict:
        r = self._row(sid)
        if r and r[10]:
            self.recompute(sid)
            r = self._row(sid)
        prog = self.progress(sid) if with_eps else None
        if not r:
            out = {"id": sid, "next": None, "finished": False, "subs": None, "audio": None, "lastEp": None, "started": False}
        else:
            out = {"id": sid, "name": r[1], "poster": r[2], "key": r[3], "lastEp": r[4], "subs": r[5], "audio": r[6],
                   "next": json.loads(r[8]) if r[8] else None, "finished": bool(r[9]), "at": r[11]}
        if prog is not None:
            out["episodes"] = prog
            out["started"] = bool(prog)
        return out

    def continue_rows(self, limit: int = 40) -> list:
        with self._lock:
            stale = [x[0] for x in self.db.execute("SELECT id FROM series WHERE stale=1").fetchall()]
        for sid in stale[:20]:
            self.recompute(sid)
        with self._lock:
            rows = self.db.execute(
                "SELECT s.id, s.name, s.poster, s.skey, s.next, s.watched_at FROM series s "
                "WHERE s.finished=0 AND s.hidden=0 AND s.next IS NOT NULL "
                "AND EXISTS (SELECT 1 FROM episodes e WHERE e.series_id=s.id) "
                "ORDER BY s.watched_at DESC LIMIT ?", (limit,)).fetchall()
        return [{"id": i, "name": n, "poster": p, "key": k, "next": json.loads(nx), "at": a} for i, n, p, k, nx, a in rows]

    # ------------------------------------------------------------------ writes
    def recompute(self, sid: str, eps: Optional[list] = None):
        eps = eps or self.episodes(sid)
        prog = self.progress(sid)
        n = next_episode(eps, prog)
        nxt = None
        if n:
            nxt = dict(_ep_json(n["ep"]), secs=n["secs"], dur=n["dur"], resume=n["resume"])
        finished = 1 if (n is None and prog) else 0
        with self._lock:
            self.db.execute("INSERT INTO series(id, skey) VALUES (?,?) ON CONFLICT(id) DO NOTHING", (sid, _series_key(sid)))
            self.db.execute("UPDATE series SET next=?, finished=?, stale=0, updated=? WHERE id=?",
                            (json.dumps(nxt) if nxt else None, finished, time.time(), sid))
        return nxt, bool(finished)

    def _touch_series(self, sid: str, name=None, poster=None, last_ep=None, watched=True):
        now = time.time()
        with self._lock:
            self.db.execute("INSERT INTO series(id, name, poster, skey, last_ep, watched_at, updated) VALUES (?,?,?,?,?,?,?) "
                            "ON CONFLICT(id) DO UPDATE SET name=coalesce(excluded.name, series.name), "
                            "poster=coalesce(excluded.poster, series.poster), last_ep=coalesce(excluded.last_ep, series.last_ep), "
                            "watched_at=CASE WHEN ? THEN excluded.watched_at ELSE series.watched_at END, hidden=CASE WHEN ? THEN 0 ELSE series.hidden END",
                            (sid, name or None, poster or None, _series_key(sid), last_ep, now if watched else 0, now, 1 if watched else 0, 1 if watched else 0))
            # bound the table: forget the least recently watched series beyond MAX_SERIES
            self.db.execute("DELETE FROM series WHERE id IN (SELECT id FROM series ORDER BY watched_at DESC LIMIT -1 OFFSET ?)", (MAX_SERIES,))

    def record(self, sid: str, ep_id: str, secs: float, dur: float, ended: bool = False, name=None, poster=None) -> dict:
        """Progress from a player (also at start: secs 0). Returns the summary without episodes."""
        sid, ep_id = str(sid), str(ep_id)
        eps = self.episodes(sid, need=ep_id)
        e = next((x for x in eps if str(x.get("id")) == ep_id), {})
        secs = max(0.0, float(secs or 0))
        dur = max(0.0, float(dur or 0))
        done = is_watched(secs, dur, ended)
        with self._lock:
            old = self.db.execute("SELECT done, dur FROM episodes WHERE id=?", (ep_id,)).fetchone()
            if old and old[0] and not done and secs < 5:
                done = True       # reopening a watched episode does not unwatch it until it really plays
            self.db.execute("INSERT OR REPLACE INTO episodes(id, series_id, season, num, secs, dur, done, at) VALUES (?,?,?,?,?,?,?,?)",
                            (ep_id, sid, e.get("season"), e.get("num"), dur if done and dur else secs, dur or (old[1] if old else 0),
                             1 if done else 0, time.time()))
        self._touch_series(sid, name, poster, ep_id)
        self.recompute(sid, eps)
        return self.summary(sid, with_eps=False)

    def mark(self, sid: str, watched: bool, season=None, ep_id: Optional[str] = None) -> dict:
        """Watched / unwatched for one episode, one season or the whole series."""
        sid = str(sid)
        eps = self.episodes(sid, need=ep_id)
        if ep_id:
            sel = [e for e in eps if str(e.get("id")) == str(ep_id)] or [{"id": str(ep_id)}]
        elif season is not None:
            sel = [e for e in eps if _num(e.get("season")) == _num(season)]
        else:
            sel = list(eps)
        now = time.time()
        with self._lock:
            for i, e in enumerate(ordered(sel)):
                if watched:
                    dur = _dur_secs(e.get("duration"))
                    # later episodes a hair newer, so "the last watched" is the last one in order
                    self.db.execute("INSERT OR REPLACE INTO episodes(id, series_id, season, num, secs, dur, done, at) VALUES (?,?,?,?,?,?,1,?)",
                                    (str(e["id"]), sid, e.get("season"), e.get("num"), dur, dur, now + i * 1e-3))
                else:
                    self.db.execute("DELETE FROM episodes WHERE id=?", (str(e["id"]),))
        if watched:
            self._touch_series(sid, last_ep=str(ordered(sel)[-1]["id"]) if sel else None, watched=False)
        self.recompute(sid, eps)
        return self.summary(sid)

    def set_prefs(self, sid: str, subs=None, audio=None):
        sid = str(sid)
        with self._lock:
            self.db.execute("INSERT INTO series(id, skey) VALUES (?,?) ON CONFLICT(id) DO NOTHING", (sid, _series_key(sid)))
            if subs is not None:
                self.db.execute("UPDATE series SET subs=? WHERE id=?", (str(subs)[:40], sid))
            if audio is not None:
                self.db.execute("UPDATE series SET audio=? WHERE id=?", (str(audio)[:40], sid))

    def hide(self, sid: str):
        """Remove from Continue watching (until an episode plays again)."""
        with self._lock:
            self.db.execute("UPDATE series SET hidden=1 WHERE id=?", (str(sid),))

    def recompute_stale(self):
        with self._lock:
            ids = [x[0] for x in self.db.execute("SELECT id FROM series WHERE stale=1").fetchall()]
        for sid in ids:
            try:
                self.recompute(sid)
            except Exception:                       # noqa: BLE001
                pass


def _series_key(sid: str) -> str:
    src, raw = sid.split("~", 1) if "~" in sid else ("p0", sid)
    return f"s|{src}|{raw}"


def _dur_secs(v) -> float:
    """'01:19:13' / '45:10' / seconds -> seconds (0 when unknown)."""
    if not v:
        return 0.0
    if isinstance(v, (int, float)):
        return float(v)
    try:
        parts = [int(p) for p in str(v).split(":")]
    except ValueError:
        return 0.0
    t = 0
    for p in parts:
        t = t * 60 + p
    return float(t)


def start_background(store: SeriesStore):
    threading.Thread(target=store.recompute_stale, name="series-recompute", daemon=True).start()
