# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Backup / restore of the household's own data as one JSON file (no credentials).

In it: the shared state (My list = favourites, resume points / watched marks, reminders, search
history, preferences), the shows' progress, the channel layout (own groups, renames, hidden,
order), the parental locks (not the PIN), the manual guide mapping, refresh / recording settings
and the series-recording rules.

Never in it: provider logins (they live encrypted in provider*.enc), the admin / parental PIN,
the server's keys and tokens, recordings, caches.

A restore validates the whole file first and writes nothing when anything is wrong; then each
section in the file replaces what the server has (series-recording rules are added, existing ones
kept, so no scheduled recording is cancelled by a restore).
"""
from __future__ import annotations

import json
import time

from .layout import _empty as _layout_empty, MAX_CUSTOM, MAX_CUSTOM_CHANNELS
from .state import SCOPES, MAX_VALUE

FORMAT = "tvbro-web-backup"
VERSION = 1
MAX_BYTES = 16 * 1024 * 1024
SETTINGS_KEYS = ("epgHours", "channelsHours", "vodHours", "recMaxGB", "recMinFreeMB", "recPadBeforeMin", "recPadAfterMin")
SERIES_COLS = ("id", "name", "poster", "skey", "last_ep", "subs", "audio", "hidden", "next", "finished", "watched_at", "updated")
EP_COLS = ("id", "series_id", "season", "num", "secs", "dur", "done", "at")


class BadBackup(ValueError):
    pass


# --------------------------------------------------------------------------- export

def export(app) -> dict:
    with app.state._lock:
        kv = app.state.db.execute("SELECT scope, key, value, updated FROM kv ORDER BY scope, updated").fetchall()
        has_series = app.state.db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='series'").fetchone()
        series = app.state.db.execute(f"SELECT {', '.join(SERIES_COLS)} FROM series").fetchall() if has_series else []
        eps = app.state.db.execute(f"SELECT {', '.join(EP_COLS)} FROM episodes").fetchall() if has_series else []
    state: dict = {s: [] for s in SCOPES}
    for scope, key, value, updated in kv:
        if scope in state:
            try:
                state[scope].append({"key": key, "value": json.loads(value), "updated": updated})
            except ValueError:
                pass
    doc = {
        "format": FORMAT, "version": VERSION, "createdAt": int(time.time()),
        "note": "tvBro web backup: favourites, resume points, reminders, preferences, layout, locks, guide mapping. No logins or PINs.",
        "state": state,
        "series": {"series": [dict(zip(SERIES_COLS, r)) for r in series], "episodes": [dict(zip(EP_COLS, r)) for r in eps]},
        "layout": app.layout.data,
        "parental": app.parental.locks(),
        "settings": {k: app.settings[k] for k in SETTINGS_KEYS if k in app.settings},
        "seriesRules": [{"key": r.get("key"), "title": r.get("title")} for r in getattr(app.recorder, "rules", [])],
    }
    if getattr(app, "epgmap", None) is not None:
        doc["epgMap"] = dict(app.epgmap.data)
    return doc


# --------------------------------------------------------------------------- validation

def _need(cond, msg):
    if not cond:
        raise BadBackup(msg)


def _str_list(v, what, limit, maxlen=300):
    _need(isinstance(v, list) and len(v) <= limit, f"{what}: a list (at most {limit})")
    _need(all(isinstance(x, str) and 0 < len(x) <= maxlen for x in v), f"{what}: text entries")
    return list(v)


def _num(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def _clean_state(st) -> dict:
    _need(isinstance(st, dict), "state: an object")
    out = {}
    for scope, rows in st.items():
        _need(scope in SCOPES, f"state: unknown list {scope!r}")
        _need(isinstance(rows, list) and len(rows) <= SCOPES[scope], f"state.{scope}: at most {SCOPES[scope]} entries")
        clean = []
        for r in rows:
            _need(isinstance(r, dict) and isinstance(r.get("key"), str) and 0 < len(r["key"]) <= 300, f"state.{scope}: every entry needs a key")
            _need(len(json.dumps(r.get("value"))) <= MAX_VALUE, f"state.{scope}: an entry is too large")
            up = r.get("updated", 0)
            clean.append((r["key"], r.get("value"), float(up) if _num(up) else 0.0))
        out[scope] = clean
    return out


def _clean_series(se) -> dict:
    _need(isinstance(se, dict), "series: an object")
    rows = se.get("series") or []
    eps = se.get("episodes") or []
    _need(isinstance(rows, list) and len(rows) <= 20000 and isinstance(eps, list) and len(eps) <= 200000, "series: lists")
    for r in rows:
        _need(isinstance(r, dict) and isinstance(r.get("id"), str) and r["id"], "series: every show needs an id")
        for k in ("name", "poster", "skey", "last_ep", "subs", "audio", "next"):
            _need(r.get(k) is None or isinstance(r.get(k), str), f"series.{k}: text")
        for k in ("hidden", "finished", "watched_at", "updated"):
            _need(r.get(k) is None or _num(r.get(k)), f"series.{k}: a number")
    for e in eps:
        _need(isinstance(e, dict) and isinstance(e.get("id"), str) and e["id"] and isinstance(e.get("series_id"), str), "episodes: id and series_id")
        for k in ("season", "num", "secs", "dur", "done", "at"):
            _need(e.get(k) is None or _num(e.get(k)), f"episodes.{k}: a number")
    return {"series": rows, "episodes": eps}


def _clean_layout(lay) -> dict:
    _need(isinstance(lay, dict), "layout: an object")
    out = _layout_empty()
    for k, v in lay.items():
        if k not in out:
            continue
        _need(isinstance(v, type(out[k])), f"layout.{k}: wrong type")
        out[k] = v
    _need(len(out["custom"]) <= MAX_CUSTOM, "layout: too many own groups")
    for gid, g in out["custom"].items():
        _need(isinstance(g, dict) and isinstance(g.get("name", ""), str), "layout.custom: groups need a name")
        g["channels"] = _str_list(g.get("channels", []), "layout.custom.channels", MAX_CUSTOM_CHANNELS)
    _str_list(out["order"], "layout.order", 50000)
    for k in ("groups", "channels"):
        for ident, meta in out[k].items():
            _need(isinstance(meta, dict), f"layout.{k}: entries are objects")
            _need(meta.get("name") is None or isinstance(meta.get("name"), str), f"layout.{k}: names are text")
    for gid, keys in out["chorder"].items():
        _str_list(keys, "layout.chorder", 50000)
    return out


def validate(doc) -> dict:
    """The parts of a backup that will be restored, checked. Raises BadBackup (nothing written)."""
    _need(isinstance(doc, dict), "not a tvBro backup (not a JSON object)")
    _need(doc.get("format") == FORMAT, "not a tvBro web backup (format)")
    _need(isinstance(doc.get("version"), int) and 1 <= doc["version"] <= VERSION, "made by a newer tvBro: update this server first")
    out: dict = {}
    if "state" in doc:
        out["state"] = _clean_state(doc["state"])
    if "series" in doc:
        out["series"] = _clean_series(doc["series"])
    if "layout" in doc:
        out["layout"] = _clean_layout(doc["layout"])
    if "parental" in doc:
        p = doc["parental"]
        _need(isinstance(p, dict), "parental: an object")
        out["parental"] = {k: _str_list(p.get(k) or [], "parental." + k, 5000) for k in ("groups", "channels", "vodCats")}
    if "settings" in doc:
        s = doc["settings"]
        _need(isinstance(s, dict), "settings: an object")
        out["settings"] = {k: v for k, v in s.items() if k in SETTINGS_KEYS}
    if "seriesRules" in doc:
        rs = doc["seriesRules"]
        _need(isinstance(rs, list) and len(rs) <= 200, "seriesRules: at most 200")
        _need(all(isinstance(r, dict) and isinstance(r.get("key"), str) and isinstance(r.get("title"), str) for r in rs), "seriesRules: key and title")
        out["seriesRules"] = rs
    if "epgMap" in doc:
        m = doc["epgMap"]
        _need(isinstance(m, dict) and len(m) <= 50000, "epgMap: an object")
        _need(all(isinstance(k, str) and isinstance(v, str) and 0 < len(k) <= 300 and 0 < len(v) <= 300 for k, v in m.items()), "epgMap: channel -> guide channel")
        out["epgMap"] = m
    _need(out, "the backup holds nothing to restore")
    return out


# --------------------------------------------------------------------------- restore

def restore(app, clean: dict) -> dict:
    """Writes a validated backup. Returns what was restored (counts)."""
    counts: dict = {}
    db = app.state.db
    with app.state._lock:
        db.execute("BEGIN")
        try:
            if "state" in clean:
                for scope, rows in clean["state"].items():
                    db.execute("DELETE FROM kv WHERE scope=?", (scope,))
                    db.executemany("INSERT INTO kv(scope, key, value, updated) VALUES (?,?,?,?)",
                                   [(scope, k, json.dumps(v), up) for k, v, up in rows])
                    counts[scope] = len(rows)
            if "series" in clean:
                db.execute("DELETE FROM series")
                db.execute("DELETE FROM episodes")
                db.executemany(f"INSERT OR REPLACE INTO series({', '.join(SERIES_COLS)}, stale) VALUES ({', '.join('?' * len(SERIES_COLS))}, 1)",
                               [tuple(_series_val(r, c) for c in SERIES_COLS) for r in clean["series"]["series"]])
                db.executemany(f"INSERT OR REPLACE INTO episodes({', '.join(EP_COLS)}) VALUES ({', '.join('?' * len(EP_COLS))})",
                               [tuple(_ep_val(e, c) for c in EP_COLS) for e in clean["series"]["episodes"]])
                counts["shows"] = len(clean["series"]["series"])
                counts["episodes"] = len(clean["series"]["episodes"])
            db.execute("COMMIT")
        except Exception:
            db.execute("ROLLBACK")
            raise
    if "layout" in clean:
        with app.layout._lock:
            app.layout.data = clean["layout"]
            app.layout._save()
        counts["ownGroups"] = len(clean["layout"]["custom"])
    if "parental" in clean:
        app.parental.set_locks(clean["parental"])
        counts["locks"] = sum(len(v) for v in clean["parental"].values())
    if "settings" in clean:
        app.settings.save(clean["settings"])                 # it keeps only allowed values
        counts["settings"] = len(clean["settings"])
    if "epgMap" in clean and getattr(app, "epgmap", None) is not None:
        app.epgmap.replace(clean["epgMap"])
        counts["guideMapping"] = len(clean["epgMap"])
    if "seriesRules" in clean:
        n = 0
        for r in clean["seriesRules"]:
            try:
                app.recorder.add_rule(r["key"], r["title"])
                n += 1
            except (ValueError, KeyError):
                pass                                          # that channel is not in the playlists any more
        counts["seriesRules"] = n
    return counts


def _series_val(r: dict, col: str):
    v = r.get(col)
    if col in ("hidden", "finished"):
        return int(v or 0)
    if col in ("watched_at", "updated"):
        return float(v or 0)
    return v


def _ep_val(e: dict, col: str):
    v = e.get(col)
    if col in ("secs", "dur", "at"):
        return float(v or 0)
    if col == "done":
        return int(v or 0)
    return v
