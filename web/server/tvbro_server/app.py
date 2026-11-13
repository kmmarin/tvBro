# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""tvBro web backend: a LAN-only proxy that keeps the provider login server-side and
serves compact JSON + relayed media to the static frontend.

Design notes
- Browsers cannot reach an Xtream provider directly (no CORS, raw MPEG-TS is not playable),
  so every provider call and every stream is relayed through here.
- The provider login is stored encrypted (secretstore) and only decrypted in memory. It never
  appears in a response, a log line, or a URL handed to the browser.
- Every browser has a session (auth.py: HttpOnly cookie, CSRF token on writes, Host allowlist);
  a password is optional (Settings > Security > Require a password, off by default). Media URLs for
  players that cannot send cookies are HMAC-signed and bound to the session that asked.
- Everything fetched for a playlist goes through net.py (http/https only, no loopback /
  link-local / private addresses unless the admin allows the local network).
- Pure Python standard library + ``cryptography``; runs under a ThreadingHTTPServer so it idles
  at ~0% CPU and exits cleanly on SIGTERM (systemd).
"""
from __future__ import annotations

import base64
import gzip
import hmac
import io
import json
import logging
import os
import re
import secrets as pysecrets
import socketserver
import sqlite3
import threading
import time
import urllib.parse
import urllib.request
from hashlib import sha256, sha512
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import parsers as P
from . import net as NET
from .secretstore import SecretStore, startup_check
from .streams import StreamRegistry
from .state import StateStore
from .epgdb import EpgDb, EpgWriter, key_for, mapped_key, NO_GUIDE
from .epgmap import EpgMap
from . import catcache
from . import searchidx as SI
from . import remux as R
from .recorder import Recorder, REC_DEFAULTS, REC_ALLOWED
from .tsindex import read_index, recording_playlist
from .layout import Layout
from . import series as SER
from . import storage as STO
from . import alternates as ALT
from .parental import Parental, Locked as PinWait
from .secfile import write_secret
from . import auth as AUTH
from . import backup as BK

try:                                    # deterministic authenticated encryption for /seg URLs
    from cryptography.hazmat.primitives.ciphers.aead import AESSIV
except Exception:                       # noqa: BLE001  (very old cryptography)
    AESSIV = None

LOG = logging.getLogger("tvbro")

DATA_DIR = os.environ.get("TVBRO_DATA", os.path.join(os.path.dirname(__file__), "..", "data"))
DATA_DIR = os.path.abspath(DATA_DIR)
BIND = os.environ.get("TVBRO_BIND", "127.0.0.1")
PORT = int(os.environ.get("TVBRO_PORT", "8750"))
SOURCE_ID = "p0"
API_TTL = 12 * 3600          # provider catalogue refresh (brief: 12 h)
EPG_TTL = 12 * 3600
GUIDE_AHEAD = 7 * 86400      # guide window: now .. +7 days
GUIDE_BACK = 7 * 86400       # and back far enough for catch-up
UA = NET.UA
MAX_PLAYLIST_BYTES = 200 * 1024 * 1024     # an M3U playlist
MAX_API_BYTES = 300 * 1024 * 1024          # one Xtream API answer (VOD lists)
MAX_BODY = 1024 * 1024                     # a JSON request body (restore has its own cap)
LIVE_TTL = 6 * 3600                        # signed live / catch-up URLs (films: 24 h)
RETRY_BACKOFF = (60, 300, 900)   # a failed sync is retried after 1, 5, then every 15 min


# --------------------------------------------------------------------------- provider


class SyncJob:
    """One background sync per kind (channels / epg / vod), off the request threads, at low
    priority. Results are swapped in atomically by the producer; on failure the old data stays."""

    def __init__(self, kind: str):
        self.kind = kind
        self._lock = threading.Lock()
        self.running = False
        self.state = "idle"
        self.progress: dict = {}
        self.started = 0.0
        self.last_ok = 0.0
        self.last_dur = 0.0
        self.last_err: dict | None = None
        self.fails = 0                  # failures in a row; retried after RETRY_BACKOFF
        self.retry_at = 0.0
        self.done = threading.Event()
        self.done.set()

    def start(self, fn) -> bool:
        with self._lock:
            if self.running:
                return False
            self.running = True
            self.done.clear()
            self.state, self.progress, self.started = "starting", {}, time.time()
        threading.Thread(target=self._run, args=(fn,), daemon=True, name=f"sync-{self.kind}").start()
        return True

    def _run(self, fn):
        try:
            os.setpriority(os.PRIO_PROCESS, threading.get_native_id(), 10)   # Linux: per thread
        except (AttributeError, OSError):
            pass
        try:
            fn(self)
            self.last_ok = time.time()
            self.last_dur = round(self.last_ok - self.started, 1)
            self.fails, self.retry_at = 0, 0.0
        except Exception as e:                      # noqa: BLE001
            self.failed(_safe_err(e))
            LOG.warning("sync %s failed (%d in a row): %s", self.kind, self.fails, self.last_err["msg"])
        finally:
            self.state = "idle"
            self.running = False
            self.done.set()

    def failed(self, msg: str, now: float | None = None):
        """Records a failure; the scheduler retries after 1, 5, then every 15 minutes."""
        now = time.time() if now is None else now
        self.last_err = {"at": int(now), "msg": msg}
        self.fails += 1
        self.retry_at = now + RETRY_BACKOFF[min(self.fails, len(RETRY_BACKOFF)) - 1]

    def retry_due(self, now: float | None = None) -> bool:
        return bool(self.fails) and not self.running and (time.time() if now is None else now) >= self.retry_at

    def wait(self, timeout: float):
        self.done.wait(timeout)

    def to_json(self, interval_h: float) -> dict:
        nxt = int(self.last_ok + interval_h * 3600) if self.last_ok else None
        if self.fails:
            nxt = int(self.retry_at)
        return {"state": self.state if self.running else "idle", "running": self.running,
                "progress": self.progress, "startedAt": int(self.started) if self.running else None,
                "lastOk": int(self.last_ok) or None, "lastDurationSec": self.last_dur or None,
                "lastError": self.last_err, "failures": self.fails, "nextAt": nxt, "intervalHours": interval_h}


class KeptOldData(RuntimeError):
    """A sync that got nothing (0 channels / titles / programmes) where data was there before:
    the old data stays and the sync counts as failed (retried with backoff)."""


def _fold(s: str) -> list[str]:
    import unicodedata
    s = "".join(ch for ch in unicodedata.normalize("NFKD", s.lower()) if not unicodedata.combining(ch))
    return re.findall(r"\w+", s)


def _words_match(q: str, name: str) -> bool:
    """Search rule of the index for a single name: every query word is a prefix of a word."""
    qs, ws = _fold(q), _fold(name)
    return bool(qs) and all(any(w.startswith(x) for w in ws) for x in qs)


def _safe_err(e: Exception) -> str:
    """Short error text with any URL (which could carry the login) removed."""
    import re as _re
    msg = f"{type(e).__name__}: {e}"
    msg = _re.sub(r"\w+://\S+", "<url>", msg)
    return msg[:160]


class _CountingReader(io.RawIOBase):
    """Wraps an HTTP response to count downloaded bytes for progress."""

    def __init__(self, r, job: SyncJob):
        self.r, self.job, self.n = r, job, 0

    def readable(self):
        return True

    def readinto(self, b):
        data = self.r.read(len(b))
        n = len(data)
        b[:n] = data
        self.n += n
        self.job.progress["bytes"] = self.n
        return n


SETTINGS_DEFAULTS = {"epgHours": 12, "channelsHours": 12, "vodHours": 12, "maxStreams": 1, "recDir": "",
                     "allowLanSources": False,   # playlists / streams on the local network (net.py)
                     "ctid": None,               # Proxmox container id for the share's host steps
                     **REC_DEFAULTS}


class Provider:
    """Holds decrypted creds in memory; channels, EPG and VOD are fetched by background
    sync jobs and swapped in atomically."""

    def __init__(self, store: SecretStore, sid: str = SOURCE_ID, settings: dict | None = None, name: str = ""):
        self.store = store
        self.sid = sid                 # source id: prefixes every channel / VOD key ("p0|â€¦")
        self.name = name
        sfx = "" if sid == SOURCE_ID else "-" + sid
        self._lock = threading.Lock()
        self._cache: dict[str, tuple[float, object]] = {}
        self.channels: list[P.Channel] = []
        self.channels_by_key: dict[str, P.Channel] = {}
        self.live_cats: list[P.XtreamCategory] = []
        self.epgdb = EpgDb(os.path.join(store.data_dir, f"epg{sfx}.db"))
        self._stats_path = os.path.join(store.data_dir, f"epg_stats{sfx}.json")
        self.epg_stats: dict = {}
        self.vod: dict = {}
        self.vod_by_key: dict[str, P.VodItem] = {}
        self._epg_at = 0.0
        self._chan_at = 0.0
        self._vod_at = 0.0
        self._chan_cache = os.path.join(store.data_dir, f"channels{sfx}.enc")
        self._vod_cache = os.path.join(store.data_dir, f"vod{sfx}.enc")
        self.search = SI.SearchIndex(os.path.join(store.data_dir, f"search{sfx}.db"))
        self.epgmap = None             # EpgMap, set by Sources (shared by every playlist)
        self._reindex_pending = False
        self.jobs = {k: SyncJob(k) for k in ("channels", "epg", "vod", "index")}
        # a guide stored by an earlier run is served right away (no re-download on restart)
        try:
            if self.epgdb.available():
                self.epgdb.load_keys()
                with open(self._stats_path, encoding="utf-8") as f:
                    self.epg_stats = json.load(f)
                self._epg_at = os.path.getmtime(self.epgdb.path)
                self.jobs["epg"].last_ok = self._epg_at
        except (OSError, ValueError, sqlite3.Error):
            pass
        # so are the last good channel list and VOD catalogue (refreshed in the background)
        if store.has_provider():
            self._load_cached()
        if self.search.available():
            self.search.load_counts()
            self.jobs["index"].last_ok = os.path.getmtime(self.search.path)
        self.settings = settings if settings is not None else dict(SETTINGS_DEFAULTS)   # shared, server-wide

    def _load_cached(self):
        for path, kind in ((self._chan_cache, "channels"), (self._vod_cache, "vod")):
            try:
                d = catcache.load(path, self.store.open_bytes)
                at = os.path.getmtime(path) if d else 0.0
            except Exception as e:                  # noqa: BLE001
                LOG.warning("stored %s unreadable: %s", kind, _safe_err(e))
                continue
            if "channels" in d and d["channels"][0]:
                chans, cats = d["channels"]
                self.channels, self.channels_by_key, self.live_cats = chans, {c.key: c for c in chans}, cats
                self._chan_at = self.jobs["channels"].last_ok = at
            if "vod" in d:
                self._set_vod(d["vod"])
                self._vod_at = self.jobs["vod"].last_ok = at

    def _set_vod(self, vod: dict):
        by_key = {it.key: it for k in ("movies", "series") for it in vod.get(k, [])}
        with self._lock:
            self.vod, self.vod_by_key = vod, by_key

    def _store(self, path: str, **what):
        try:
            catcache.save(path, self.store.seal_bytes, catcache.encode(**what))
        except Exception as e:                      # noqa: BLE001
            LOG.warning("could not store %s: %s", os.path.basename(path), _safe_err(e))

    # ---- search index (rebuilt in the background after every sync) ----
    def reindex(self):
        self._reindex_pending = True
        self.jobs["index"].start(self._build_index)

    def _build_index(self, job: SyncJob):
        while self._reindex_pending:
            self._reindex_pending = False
            job.state = "saving"
            with self._lock:
                chans, vod = self.channels, self.vod
            tmp = self.search.path + ".new"
            counts = SI.build(tmp, chans, vod.get("movies", []), vod.get("series", []),
                              self.epgdb.path if self.epgdb.available() else None, self.epg_key_of)
            self.search.swap_in(tmp, counts)
            job.progress = dict(counts)

    def epg_key_of(self, ch: P.Channel):
        """The manual guide mapping of a channel for the search index: None = automatic,
        "" = no guide, else the guide key."""
        m = self.epgmap.get(ch.key) if self.epgmap else None
        if not m:
            return None
        return "" if m == NO_GUIDE else mapped_key(ch, m)

    def index_stale(self) -> bool:
        """No index yet, or older than the data it was built from (e.g. after an upgrade)."""
        if not self.search.available():
            return bool(self.channels)
        at = os.path.getmtime(self.search.path)
        return any(os.path.exists(p) and os.path.getmtime(p) > at + 1
                   for p in (self.epgdb.path, self._chan_cache, self._vod_cache))

    def vid(self, raw: str) -> str:
        """VOD id handed to browsers: the provider's own id for the first source, else
        '<source>~<id>' so a request can be routed back (see Sources.vod)."""
        return raw if self.sid == SOURCE_ID else f"{self.sid}~{raw}"

    @staticmethod
    def raw_vid(vid: str) -> str:
        return vid.split("~", 1)[1] if "~" in vid else vid

    def kind(self) -> str | None:
        p = self.store.get_provider()
        return p.get("kind") if p else None

    def creds(self):
        p = self.store.get_provider()
        if not p or p.get("kind") != "xtream":
            return None
        return p["server"], p["user"], p["pass"]

    def allowed_hosts(self) -> set[str]:
        hosts = set()
        c = self.creds()
        if c:
            h = urllib.parse.urlparse(P.normalize_server(c[0])).hostname
            if h:
                hosts.add(h)
        for ch in self.channels:
            h = urllib.parse.urlparse(ch.url).hostname
            if h:
                hosts.add(h)
        return hosts

    # ---- upstream fetch (creds never logged) ----
    def _open(self, url: str, timeout: int = 30):
        return NET.open_url(url, timeout=timeout)       # http(s) only, no local addresses (net.py)

    def _get(self, action: str, **extra) -> bytes:
        c = self.creds()
        if not c:
            raise RuntimeError("not signed in")
        url = P.api_url(c[0], c[1], c[2], action, **extra)
        LOG.info("fetch action=%s %s", action, ",".join(f"{k}={v}" for k, v in extra.items()))
        with self._open(url, timeout=120) as r:
            return NET.read_capped(r, MAX_API_BYTES)

    def account(self) -> dict:
        """Xtream user_info (max_connections, status, exp_date); cached 1 h. Never the password."""
        if self.kind() != "xtream":
            return {}
        hit = self._cache.get("account")
        if hit and time.time() - hit[0] < 3600:
            return hit[1]
        try:
            c = self.creds()
            with self._open(P.api_url(c[0], c[1], c[2], "").replace("&action=", ""), timeout=20) as r:
                ui = json.loads(r.read().decode("utf-8", "replace") or "{}")
            u = ui.get("user_info") if isinstance(ui, dict) else None
            out = {}
            if isinstance(u, dict):
                for k in ("max_connections", "active_cons", "status", "exp_date", "is_trial"):
                    if u.get(k) not in (None, ""):
                        out[k] = u.get(k)
                fm = u.get("allowed_output_formats")
                if isinstance(fm, list) and fm:
                    out["formats"] = [str(x) for x in fm][:6]
        except Exception:                           # noqa: BLE001
            out = {}
        self._cache["account"] = (time.time(), out)
        return out

    def invalidate(self):
        """Sign-in / sign-out: forget everything (next use loads fresh)."""
        with self._lock:
            self._cache.clear()
            self.channels, self.channels_by_key, self.live_cats = [], {}, []
            self.epg_stats, self.vod, self.vod_by_key = {}, {}, {}
            self.epgdb.keys = set()
            self._chan_at = self._epg_at = self._vod_at = 0.0
            for j in self.jobs.values():
                j.fails, j.retry_at = 0, 0.0
        for p in (self._chan_cache, self._vod_cache):    # another login's lists must not come back
            try:
                os.remove(p)
            except OSError:
                pass

    # ---- scheduling ----
    def _stale(self, at: float, hours_key: str) -> bool:
        return time.time() - at > self.settings[hours_key] * 3600

    def sync(self, kind: str) -> bool:
        if not self.store.has_provider():
            return False
        fn = {"channels": self._sync_channels, "epg": self._sync_epg, "vod": self._sync_vod}[kind]
        return self.jobs[kind].start(fn)

    def tick(self, now: float | None = None):
        """Scheduler: refresh what is due (only kinds that were loaded at least once), and
        retry a failed sync after its backoff (1, 5, then every 15 min)."""
        if not self.store.has_provider():
            return
        for kind, at, hours in (("channels", self._chan_at, "channelsHours"), ("epg", self._epg_at, "epgHours"),
                                ("vod", self._vod_at, "vodHours")):
            if self.jobs[kind].retry_due(now) or (at and self._stale(at, hours)):
                self.sync(kind)
        ij = self.jobs["index"]
        if not ij.running and (ij.retry_due(now) or (not ij.fails and self.index_stale())):
            self.reindex()

    def _ensure(self, kind: str, at: float, wait: float):
        """First use: run the sync and wait for it; later: data is served while a due
        refresh runs in the background."""
        if at:
            return
        self.sync(kind)
        self.jobs[kind].wait(wait)

    # ---- live ----
    def live_categories(self) -> list[P.XtreamCategory]:
        self.ensure_channels()
        return self.live_cats

    def ensure_channels(self):
        self._ensure("channels", self._chan_at, 180)
        if not self._chan_at:
            err = self.jobs["channels"].last_err
            raise RuntimeError(err["msg"] if err else "channels not loaded yet")

    def _sync_channels(self, job: SyncJob):
        p = self.store.get_provider()
        if not p:
            raise RuntimeError("not signed in")
        job.state = "downloading"
        if p.get("kind") == "xtream":
            c = self.creds()
            cats = P.parse_categories(self._get("get_live_categories"))
            raw = self._get("get_live_streams")
            job.state, job.progress["bytes"] = "parsing", len(raw)
            chans = P.to_channels(self.sid, c[0], c[1], c[2], cats, P._load_list(raw))
        else:
            chans = self._m3u_channels(p, job)
            seen, cats = set(), []
            for ch in chans:
                if ch.group not in seen:
                    seen.add(ch.group)
                    cats.append(P.XtreamCategory(ch.group, ch.group))
        if not chans:
            # e.g. right after a provider restart: never replace a good list with nothing
            raise KeptOldData("the provider returned 0 channels" +
                              ("; the previous list is kept" if self.channels else ""))
        job.state = "saving"
        by_key = {ch.key: ch for ch in chans}
        with self._lock:
            self.channels, self.channels_by_key, self.live_cats = chans, by_key, cats
            self._chan_at = time.time()
        job.progress = {"channels": len(chans), "groups": len(cats)}
        self._store(self._chan_cache, channels=(chans, cats))
        self.reindex()

    def _m3u_channels(self, p, job: SyncJob) -> list[P.Channel]:
        url = p.get("url", "")
        if not url:
            return []
        if p.get("kind") == "demo":
            return [P.Channel(f"{self.sid}|test", self.sid, p.get("name") or "Test Stream",
                              p.get("logo"), "Test", url, None, 1, None, 0)]
        LOG.info("fetch m3u playlist")
        with self._open(url, timeout=120) as r:
            text = NET.read_capped(r, MAX_PLAYLIST_BYTES).decode("utf-8", "replace")
        job.state, job.progress["bytes"] = "parsing", len(text)
        chans, epg = P.parse_m3u(text, self.sid)
        if epg and not p.get("epgUrl"):
            p["epgUrl"] = epg[0]
        return chans

    # ---- EPG ----
    def ensure_epg(self):
        self.ensure_channels()
        self._ensure("epg", self._epg_at, 240)

    def _sync_epg(self, job: SyncJob):
        if not self._chan_at:
            self._sync_channels(SyncJob("channels"))
        p = self.store.get_provider() or {}
        if p.get("kind") == "xtream":
            c = self.creds()
            epg_source = P.xmltv_url(c[0], c[1], c[2])
        else:
            epg_source = p.get("epgUrl") or ""
        chans = self.channels
        wanted: set[str] = set()
        for ch in chans:
            if ch.id_key:
                wanted.add(ch.id_key)
            wanted.add(ch.name_key)
        # guide channels picked by hand (Channel menu > Guide channelâ€¦) are kept too
        emap = self.epgmap.for_source(self.sid) if self.epgmap else {}
        mapped = {f"{self.sid}|{g.lower()}" for g in emap.values() if g != NO_GUIDE}
        now = int(time.time())
        min_stop, max_start = now - GUIDE_BACK, now + GUIDE_AHEAD
        tmp = self.epgdb.path + ".new"
        w = EpgWriter(tmp)

        def resolve(ch_attr, names):
            k = f"{self.sid}|{ch_attr.lower()}"
            auto = k if k in wanted else None
            if auto is None:
                for nm in names or ():
                    nk = f"{self.sid}|{nm.lower()}"
                    if nk in wanted:
                        auto = nk
                        break
            if k in mapped and auto != k:
                return [auto, k] if auto else k
            return auto

        def sink(pr: P.Programme):
            w.add(pr)
            if w.count % 2000 == 0:
                job.progress["programmes"] = w.count

        try:
            if epg_source:
                LOG.info("fetch epg")
                job.state = "downloading"
                with self._open(epg_source, timeout=180) as r:
                    total = r.headers.get("Content-Length")
                    if total and total.isdigit():
                        job.progress["total"] = int(total)
                    job.state = "parsing"     # streaming: download, parse and write overlap
                    gnames = P.parse_xmltv(io.BufferedReader(_CountingReader(r, job), 256 * 1024), resolve, min_stop, max_start, sink)
                w.guide_channels(gnames)
            job.state = "saving"
            info = w.finish()
        except BaseException:
            w.abort()
            raise
        if not info["programmes"] and (self.epg_stats or {}).get("programmes"):
            try:
                os.remove(tmp)
            except OSError:
                pass
            raise KeptOldData("the guide had 0 programmes for our channels; the previous guide is kept")
        keys = info["keys"]
        def has_guide(ch):
            m = emap.get(ch.key)
            if m:
                return m != NO_GUIDE and mapped_key(ch, m) in keys
            return (ch.id_key and ch.id_key in keys) or ch.name_key in keys
        with_data = sum(1 for ch in chans if has_guide(ch))
        stats = {"channelsWithGuide": with_data, "channels": len(chans), "programmes": info["programmes"],
                 "from": info["from"], "to": info["to"]}
        with self._lock:
            self.epgdb.swap_in(tmp, keys)
            self.epg_stats = stats
            self._epg_at = time.time()
        try:
            with open(self._stats_path, "w", encoding="utf-8") as f:
                json.dump(stats, f)
        except OSError:
            pass
        job.progress = {"programmes": info["programmes"], "channelsWithGuide": with_data}
        self.reindex()

    def programmes(self, ch: P.Channel, frm: int | None = None, to: int | None = None) -> list[P.Programme]:
        now = int(time.time())
        return self.epgdb.window(key_for(self.epgdb, ch, self.epgmap.data if self.epgmap else None), now - GUIDE_BACK if frm is None else frm,
                                 now + GUIDE_AHEAD if to is None else to)


    # ---- VOD (Xtream only) ----
    def ensure_vod(self):
        if self.kind() != "xtream":
            return
        self._ensure("vod", self._vod_at, 180)

    def _sync_vod(self, job: SyncJob):
        if self.kind() != "xtream":
            self._set_vod({})
            self._vod_at = time.time()
            return
        job.state = "downloading"
        mc = P.parse_categories(self._get("get_vod_categories"))
        sc = P.parse_categories(self._get("get_series_categories"))
        raw_m = self._get("get_vod_streams")
        job.progress["bytes"] = len(raw_m)
        raw_s = self._get("get_series")
        job.progress["bytes"] += len(raw_s)
        job.state = "parsing"
        movies = P.parse_movies(raw_m, self.sid, mc)
        del raw_m
        series = P.parse_series_list(raw_s, self.sid, sc)
        del raw_s
        if self.sid != SOURCE_ID:
            for it in movies + series:
                it.id = self.vid(it.id)
        if not movies and not series and (self.vod.get("movies") or self.vod.get("series")):
            raise KeptOldData("the provider returned 0 movies and 0 series; the previous catalogue is kept")
        job.state = "saving"
        vod = {"movie_cats": mc, "series_cats": sc, "movies": movies, "series": series}
        self._set_vod(vod)
        self._vod_at = time.time()
        job.progress = {"movies": len(movies), "series": len(series)}
        self._store(self._vod_cache, vod=vod)
        self.reindex()

    def movie_cats(self):
        self.ensure_vod()
        return self.vod.get("movie_cats", [])

    def series_cats(self):
        self.ensure_vod()
        return self.vod.get("series_cats", [])

    def movies(self) -> list[P.VodItem]:
        self.ensure_vod()
        return self.vod.get("movies", [])

    def series(self) -> list[P.VodItem]:
        self.ensure_vod()
        return self.vod.get("series", [])

    def movie_info(self, vid: str) -> P.VodDetail:
        return P.parse_vod_info(self._get("get_vod_info", vod_id=self.raw_vid(vid)))

    def series_info(self, sid: str) -> P.VodDetail:
        d = P.parse_series_info(self._get("get_series_info", series_id=self.raw_vid(sid)))
        for e in d.episodes:
            e.id = self.vid(e.id)
        return d

    def vod_url(self, kind: str, vid: str, ext: str) -> str | None:
        c = self.creds()
        if not c:
            return None
        raw = self.raw_vid(vid)
        return P.episode_url(c[0], c[1], c[2], raw, ext) if kind == "episode" else P.movie_url(c[0], c[1], c[2], raw, ext)

    # ---- status ----
    def status(self) -> dict:
        p = self.store.get_provider() or {}
        s = self.settings
        ch = self.jobs["channels"].to_json(s["channelsHours"])
        ch.update({"channels": len(self.channels), "groups": len(self.live_cats)})
        ep = self.jobs["epg"].to_json(s["epgHours"])
        ep.update(self.epg_stats or {"channelsWithGuide": 0, "channels": len(self.channels), "programmes": 0,
                                     "from": None, "to": None})
        vd = self.jobs["vod"].to_json(s["vodHours"])
        vd.update({"movies": len(self.vod.get("movies", [])), "series": len(self.vod.get("series", [])),
                   "supported": p.get("kind") == "xtream"})
        ix = self.jobs["index"].to_json(0)
        ix.pop("intervalHours", None)
        if not ix["failures"]:
            ix["nextAt"] = None                     # rebuilt after each sync, not on a timer
        ix.update(self.search.counts)
        return {"id": self.sid, "name": self.name or default_name(p.get("kind")), "kind": p.get("kind"),
                "channels": ch, "epg": ep, "vod": vd, "search": ix}


def default_name(kind) -> str:
    return {"xtream": "Xtream account", "m3u": "M3U playlist", "demo": "Demo stream"}.get(kind, "Provider")


class Settings(dict):
    """Server-wide settings (settings.json), shared by every playlist source."""

    def __init__(self, data_dir: str):
        super().__init__(SETTINGS_DEFAULTS)
        self.path = os.path.join(data_dir, "settings.json")
        try:
            with open(self.path, encoding="utf-8") as f:
                self.update({k: v for k, v in json.load(f).items() if k in SETTINGS_DEFAULTS})
        except (OSError, ValueError):
            pass

    def public(self) -> dict:
        return dict(self)

    def save(self, upd: dict):
        allowed = {"epgHours": (6, 12, 24, 48), "channelsHours": (6, 12, 24, 48), "vodHours": (6, 12, 24, 48),
                   "maxStreams": (1, 2, 3), **REC_ALLOWED}
        if isinstance(upd.get("allowLanSources"), bool):
            self["allowLanSources"] = upd["allowLanSources"]
        if "ctid" in upd:
            c = upd["ctid"]
            if c in (None, ""):
                self["ctid"] = None
            elif isinstance(c, (int, str)) and str(c).isdigit() and 100 <= int(c) <= 999999999:
                self["ctid"] = int(c)
        for k, v in upd.items():
            if k in ("allowLanSources", "ctid"):
                continue
            try:
                v = int(v)
            except (TypeError, ValueError):
                continue
            if k in allowed and v in allowed[k]:
                self[k] = v
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(dict(self), f)
        os.replace(tmp, self.path)


def group_id(sid: str, cid: str) -> str:
    """Category ids from several sources may collide: the first source keeps its own ids."""
    return cid if sid == SOURCE_ID else f"{sid}:{cid}"


def split_group_id(gid: str) -> tuple[str, str]:
    head, sep, rest = gid.partition(":")
    if sep and head.startswith("p") and head[1:].isdigit() and head != SOURCE_ID:
        return head, rest
    return SOURCE_ID, gid


class Sources:
    """Several playlists side by side. Source p0 is the original one (provider.enc, epg.db);
    more are added as p1, p2â€¦ (provider-pN.enc, epg-pN.db). sources.json keeps the order,
    names and the enabled flag. Channel and VOD keys carry the source id, so every request
    is routed to its own provider; the merged views below are what the API serves."""

    def __init__(self, data_dir: str, settings: Settings):
        self.data_dir, self.settings = data_dir, settings
        self.epgmap = EpgMap(os.path.join(data_dir, "epgmap.json"))     # manual channel -> guide mapping
        self.path = os.path.join(data_dir, "sources.json")
        self._lock = threading.RLock()
        self.meta: list[dict] = []
        try:
            with open(self.path, encoding="utf-8") as f:
                self.meta = [m for m in json.load(f) if isinstance(m, dict) and m.get("id")]
        except (OSError, ValueError):
            pass
        if not any(m["id"] == SOURCE_ID for m in self.meta):
            self.meta.insert(0, {"id": SOURCE_ID, "name": "", "enabled": True})
        self.providers: dict[str, Provider] = {}
        for m in self.meta:
            self.providers[m["id"]] = self._make(m)
        self._merged = None

    def _make(self, m: dict) -> Provider:
        store = SecretStore(self.data_dir, "provider.enc" if m["id"] == SOURCE_ID else f"provider-{m['id']}.enc")
        p = Provider(store, m["id"], self.settings, m.get("name") or "")
        p.epgmap = self.epgmap
        return p

    def _save(self):
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.meta, f)
        os.replace(tmp, self.path)

    # ---- the list ----
    def all(self) -> list[Provider]:
        return [self.providers[m["id"]] for m in self.meta if m["id"] in self.providers]

    def enabled(self) -> list[Provider]:
        on = {m["id"] for m in self.meta if m.get("enabled", True)}
        return [p for p in self.all() if p.sid in on and p.store.has_provider()]

    def is_enabled(self, sid: str) -> bool:
        return any(m["id"] == sid and m.get("enabled", True) for m in self.meta)

    def get(self, sid: str) -> Provider | None:
        return self.providers.get(sid)

    @property
    def primary(self) -> Provider:
        return self.providers[SOURCE_ID]

    def has_any(self) -> bool:
        return any(p.store.has_provider() for p in self.all())

    def add(self, kind: str, name: str, server: str, user: str, password: str, epg_url: str, url: str) -> str:
        with self._lock:
            used = {m["id"] for m in self.meta}
            if not self.primary.store.has_provider():
                sid = SOURCE_ID                      # the first playlist is p0
            else:
                n = 1
                while f"p{n}" in used:
                    n += 1
                sid = f"p{n}"
            m = next((x for x in self.meta if x["id"] == sid), None)
            if m is None:
                m = {"id": sid, "name": "", "enabled": True}
                self.meta.append(m)
            m["name"], m["enabled"] = (name or "").strip()[:60], True
            prov = self.providers.get(sid) or self._make(m)
            prov.store.save_provider(kind, server, user, password, epg_url, url)
            prov.name = m["name"]
            prov.invalidate()
            self.providers[sid] = prov
            self._save()
            self._merged = None
        prov.sync("channels")
        return sid

    def update(self, sid: str, name=None, enabled=None) -> bool:
        with self._lock:
            m = next((x for x in self.meta if x["id"] == sid), None)
            if m is None:
                return False
            if name is not None:
                m["name"] = str(name).strip()[:60]
                self.providers[sid].name = m["name"]
            if enabled is not None:
                m["enabled"] = bool(enabled)
            self._save()
            self._merged = None
        if enabled:
            self.providers[sid].sync("channels")
        return True

    def remove(self, sid: str) -> bool:
        with self._lock:
            prov = self.providers.get(sid)
            if prov is None:
                return False
            prov.store.clear_provider()
            prov.invalidate()
            prov.search.close_thread_conn()
            prov.search.counts = {}
            for p in (prov.epgdb.path, prov._stats_path, prov.search.path):
                try:
                    os.remove(p)
                except OSError:
                    pass
            if sid == SOURCE_ID:
                m = self.meta[[x["id"] for x in self.meta].index(sid)]
                m["name"], m["enabled"] = "", True
            else:
                self.meta = [x for x in self.meta if x["id"] != sid]
                del self.providers[sid]
            self._save()
            self._merged = None
        return True

    # ---- routing ----
    def for_key(self, key: str) -> Provider | None:
        """'p1|123' (channel) or 'm|p1|123' (VOD) -> its source."""
        parts = key.split("|")
        sid = parts[1] if len(parts) > 2 and parts[0] in ("m", "s") else parts[0]
        return self.providers.get(sid)

    def for_vid(self, vid: str) -> Provider:
        sid = vid.split("~", 1)[0] if "~" in vid else SOURCE_ID
        return self.providers.get(sid) or self.primary

    def channel(self, key: str) -> P.Channel | None:
        prov = self.for_key(key)
        if prov is None or not self.is_enabled(prov.sid):
            return None
        ch = prov.channels_by_key.get(key)
        if ch is None and prov.store.has_provider():
            try:
                prov.ensure_channels()               # right after a restart
            except Exception:                       # noqa: BLE001
                return None
            ch = prov.channels_by_key.get(key)
        return ch

    # ---- merged views of the enabled sources ----
    def _views(self):
        provs = self.enabled()
        sig = tuple((p.sid, id(p.channels), id(p.vod)) for p in provs)
        mg = self._merged
        if mg is None or mg[0] != sig:
            chans, by_key = [], {}
            for p in provs:
                chans.extend(p.channels)
                by_key.update(p.channels_by_key)
            mg = self._merged = (sig, chans, by_key)
        return mg

    @property
    def channels(self) -> list[P.Channel]:
        return self._views()[1]

    @property
    def channels_by_key(self) -> dict[str, P.Channel]:
        return self._views()[2]

    def alt_index(self) -> ALT.AltIndex:
        """Alternate sources of each channel (same tvg-id / same name), rebuilt when the list changes."""
        sig = self._views()[0]
        ai = getattr(self, "_alts", None)
        if ai is None or ai[0] != sig:
            ai = self._alts = (sig, ALT.AltIndex(self.channels))
        return ai[1]

    def ensure_channels(self):
        errs = []
        provs = self.enabled()
        for p in provs:
            try:
                p.ensure_channels()
            except Exception as e:                  # noqa: BLE001
                errs.append(str(e))
        if provs and len(errs) == len(provs):
            raise RuntimeError(errs[0])

    def ensure_epg(self):
        self.ensure_channels()
        for p in self.enabled():
            p._ensure("epg", p._epg_at, 240)

    def programmes(self, ch: P.Channel, frm=None, to=None):
        p = self.providers.get(ch.source_id)
        return p.programmes(ch, frm, to) if p else []

    def live_groups(self) -> list[tuple[str, str, str]]:
        """(group id, provider group name, source) for every enabled source."""
        out = []
        for p in self.enabled():
            try:
                cats = p.live_categories()
            except Exception:                       # noqa: BLE001
                continue
            out += [(group_id(p.sid, c.category_id), c.category_name, p.sid) for c in cats]
        return out

    def vod_cats(self, kind: str) -> list[tuple[str, str, str]]:
        out = []
        for p in self.enabled():
            cats = p.movie_cats() if kind == "movie" else p.series_cats()
            out += [(group_id(p.sid, c.category_id), c.category_name, p.sid) for c in cats]
        return out

    def vod_items(self, kind: str) -> list[P.VodItem]:
        out = []
        for p in self.enabled():
            out += p.movies() if kind == "movie" else p.series()
        return out

    def source_label(self, sid: str) -> str:
        p = self.providers.get(sid)
        if not p:
            return sid
        return p.name or default_name(p.kind())

    def tick(self):
        for p in self.enabled():
            p.tick()

    def sync(self, kind: str, sid: str | None = None):
        for p in self.enabled():
            if sid is None or p.sid == sid:
                p.sync(kind)

    def account(self) -> dict:
        for p in self.enabled():
            if p.kind() == "xtream":
                return p.account()
        return {}

    def status(self) -> dict:
        lists = []
        on = {m["id"]: m.get("enabled", True) for m in self.meta}
        for p in self.all():
            if not p.store.has_provider():
                continue
            st = p.status()
            st["enabled"] = on.get(p.sid, True)
            lists.append(st)
        return {"playlists": lists, "settings": self.settings.public(), "account": self.account()}


# --------------------------------------------------------------------------- server glue


class App:
    def __init__(self):
        self.settings = Settings(DATA_DIR)
        self.sources = Sources(DATA_DIR, self.settings)
        self.epgmap = self.sources.epgmap
        self.state = StateStore(os.path.join(DATA_DIR, "state.db"))
        self.series = SER.SeriesStore(self.state, self._series_eps)
        SER.start_background(self.series)
        self.streams = StreamRegistry(self.settings.get("maxStreams", 1))
        self.remux = R.RemuxManager(DATA_DIR, lambda: self.streams.max_streams, self.streams.is_stopped)
        self.codecs = R.CodecCache(os.path.join(DATA_DIR, "codecs.json"))    # channel key -> video/audio codec
        self.recorder = Recorder(DATA_DIR, self._rec_source, self.streams, lambda: self.settings)
        self.recorder.find_progs = self._rule_progs
        rd = STO.allowed(self.settings.get("recDir") or "", DATA_DIR)
        if rd and os.path.isdir(rd):
            self.recorder.set_dir(rd)
        self.shares = STO.ShareStore(DATA_DIR, SecretStore(DATA_DIR))
        self.layout = Layout(os.path.join(DATA_DIR, "layout.json"))
        self.images = ImageCache(os.path.join(DATA_DIR, "imgcache"))
        self.sign_key = self._load_sign_key()
        self.auth = AUTH.Auth(DATA_DIR, self.sign_key)
        NET.set_allow_private(lambda: bool(self.settings.get("allowLanSources")))
        self.parental = Parental(os.path.join(DATA_DIR, "parental.json"), self.sign_key)
        self._siv = None
        self.no_timeshift_hls: dict[str, float] = {}     # provider host -> when its timeshift .m3u8 failed
        self._segmap: dict[str, str] = {}
        self._seg_lock = threading.Lock()

    def _rule_progs(self, key: str, frm: int, to: int):
        """Guide entries of a channel for the series-recording rules: [(start, stop, title)]."""
        ch = self.sources.channels_by_key.get(key)
        if not ch:
            return []
        return [(p.start, p.stop, p.title) for p in self.sources.programmes(ch, frm, to)]

    def _series_eps(self, vid: str):
        """(name, cover, [episode dicts]) of a series for the Shows progress store."""
        js = series_info_json(vid, self.sources)
        return js.get("name"), js.get("cover"), js.get("episodes") or []

    def _rec_source(self, key: str):
        """A channel for the recorder: name, logo and the upstream URL (MPEG-TS where the
        provider offers it). The URL stays on the server."""
        ch = self.sources.channel(key)
        if not ch:
            return None
        return {"name": ch.name, "logo": ch.logo, "url": P.choose_format(ch.url, "ts")[0]}

    def _load_sign_key(self) -> bytes:
        path = os.path.join(DATA_DIR, "sign.key")
        if os.path.exists(path):
            with open(path, "rb") as f:
                return f.read().strip()             # as before (existing keys stay valid)
        k = pysecrets.token_bytes(32)
        write_secret(path, k)
        return k

    def sign(self, s: str) -> str:
        """HMAC-SHA256, 128 bits kept (media URLs, image URLs, CSRF tokens)."""
        return hmac.new(self.sign_key, s.encode(), sha256).hexdigest()[:32]

    # Upstream segment URLs can carry the provider login (/live/<user>/<pass>/..), so the
    # browser only ever gets them sealed: encrypted + authenticated, and deterministic so a
    # reloaded playlist names the same segment the same way.
    def seal(self, url: str) -> str:
        if AESSIV is not None:
            if self._siv is None:
                self._siv = AESSIV(sha512(b"tvbro-seg" + self.sign_key).digest())
            raw = self._siv.encrypt(url.encode(), None)
            return base64.urlsafe_b64encode(raw).decode().rstrip("=")
        tok = hmac.new(self.sign_key, url.encode(), sha256).hexdigest()[:40]
        with self._seg_lock:
            self._segmap[tok] = url
            while len(self._segmap) > 50000:
                self._segmap.pop(next(iter(self._segmap)))
        return tok

    def unseal(self, tok: str) -> str | None:
        if AESSIV is not None:
            try:
                if self._siv is None:
                    self._siv = AESSIV(sha512(b"tvbro-seg" + self.sign_key).digest())
                raw = base64.urlsafe_b64decode(tok + "=" * (-len(tok) % 4))
                return self._siv.decrypt(raw, None).decode()
            except Exception:                       # noqa: BLE001
                return None
        with self._seg_lock:
            return self._segmap.get(tok)


APP: App | None = None
_INFO_CACHE: dict[str, tuple[float, dict]] = {}


# the page's Content-Security-Policy (the same as deploy/nginx.conf; used for dev static serving)
APP_CSP = ("default-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; worker-src 'self' blob:; "
           "connect-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self'; object-src 'none'; "
           "frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
PUBLIC_GET = ("/api/health", "/api/session")
ADMIN_POST = {"/api/signin", "/api/signout", "/api/settings", "/api/restore", "/api/auth/password", "/api/auth/viewer", "/api/auth/require",
              "/api/recordings/storage", "/api/recordings/storage/test", "/api/recordings/share", "/api/recordings/delete"}
ADMIN_POST_PREFIX = "/api/sources/"


def allowed_hosts() -> set[str]:
    """Host names this server answers to (DNS rebinding guard): TVBRO_ALLOWED_HOSTS (comma
    separated, e.g. the site's name) plus localhost."""
    extra = {h.strip().lower().strip("[]") for h in os.environ.get("TVBRO_ALLOWED_HOSTS", "").split(",") if h.strip()}
    return {"localhost", "127.0.0.1", "::1"} | extra


def source_problem(kind: str, server: str, url: str, epg: str):
    """Why a new playlist's addresses are refused (only http / https), else None."""
    checks = []
    if kind == "xtream":
        if not server.strip():
            return "server address missing"
        if ":/" in server and not server.strip().lower().startswith(("http://", "https://")):
            return "server: only http and https are allowed"
        checks.append(("server", P.normalize_server(server)))
    else:
        if not url.strip():
            return "playlist / stream URL missing"
        checks.append(("playlist URL", url.strip()))
    if epg.strip():
        checks.append(("EPG URL", epg.strip()))
    for what, u in checks:
        try:
            NET.check_url(u)
        except NET.Blocked as e:
            return f"{what}: {e.why}"
    return None


def _media_payload(path: str, q: dict) -> str:
    """Canonical string signed for media URLs: path + sorted params except sig/sid/t."""
    items = sorted((k, v[0]) for k, v in q.items() if k not in ("sig", "sid", "t"))
    return path + "?" + "&".join(f"{k}={v}" for k, v in items)   # VOD details (header + details page)


def vod_info_json(kind: str, vid: str, sources=None) -> dict:
    """A movie's / series' details (6 h cache; series: + number of seasons)."""
    ck = f"info:{kind}:{vid}"
    hit = _INFO_CACHE.get(ck)
    if hit and time.time() - hit[0] < 6 * 3600:
        return hit[1]
    prov = (sources or APP.sources).for_vid(vid)
    detail = prov.movie_info(vid) if kind == "movie" else prov.series_info(vid)
    js = detail.to_json()
    if kind == "series":
        js["seasons"] = len({e.season for e in detail.episodes})
    _INFO_CACHE[ck] = (time.time(), js)
    while len(_INFO_CACHE) > 400:          # small LRU-ish bound (insertion order)
        _INFO_CACHE.pop(next(iter(_INFO_CACHE)))
    return js


def series_info_json(vid: str, sources=None) -> dict:
    return vod_info_json("series", vid, sources)


# --------------------------------------------------------------------------- handler


class Handler(BaseHTTPRequestHandler):
    server_version = "tvBro"
    protocol_version = "HTTP/1.1"

    timeout = 120                     # idle / stalled connections are dropped (M7)
    sess = None
    _csp = False

    def log_message(self, fmt, *args):
        LOG.info("%s", fmt % args)

    def log_request(self, code="-", size="-"):
        """Method, path without the query (it can name image URLs or search words) and status."""
        path = (getattr(self, "path", "") or "").split("?", 1)[0]
        LOG.info('"%s %s" %s', getattr(self, "command", "-"), path[:200], getattr(code, "value", code))

    def log_error(self, fmt, *args):
        LOG.info("http error: %s", (fmt % args).split("?", 1)[0][:200])

    def send_header(self, keyword, value):
        if keyword.lower() == "content-security-policy":
            self._csp = True
        super().send_header(keyword, value)

    def end_headers(self):
        """Security headers on every answer (nginx adds them too; these cover direct runs)."""
        if not self._csp:
            self.send_header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Frame-Options", "DENY")
        self._csp = False
        super().end_headers()

    # -- who is asking (auth.py) --
    def _client_ip(self) -> str:
        ip = self.client_address[0] if self.client_address else ""
        real = self.headers.get("X-Real-IP", "")
        if real and ip in ("127.0.0.1", "::1"):         # nginx in front
            return real.strip()[:64]
        return ip

    def _host_ok(self) -> bool:
        host = (self.headers.get("Host") or "").strip().lower()
        if host.startswith("["):
            host = host[1:].split("]", 1)[0]
        elif host.count(":") == 1:
            host = host.split(":", 1)[0]
        return host in allowed_hosts()

    def _session(self):
        c = self.headers.get("Cookie") or ""
        for part in c.split(";"):
            k, _, v = part.strip().partition("=")
            if k == AUTH.COOKIE:
                return APP.auth.session(v)
        return None

    def _https(self) -> bool:
        return self.headers.get("X-Forwarded-Proto", "").lower() == "https"

    def _cookie(self, sid: str, max_age: int) -> str:
        return (f"{AUTH.COOKIE}={sid}; Path=/; Max-Age={max_age}; HttpOnly; SameSite=Strict"
                + ("; Secure" if self._https() else ""))

    def _origin_ok(self) -> bool:
        """A write from this site's own pages: Origin (when sent) names this host, and
        Sec-Fetch-Site (when sent) is same-origin."""
        o = self.headers.get("Origin")
        if o:
            try:
                ou = urllib.parse.urlparse(o)
            except ValueError:
                return False
            if (ou.netloc or "").lower() != (self.headers.get("Host") or "").lower():
                return False
        sfs = self.headers.get("Sec-Fetch-Site")
        return not sfs or sfs in ("same-origin", "none")

    def _json_request(self) -> bool:
        return (self.headers.get("Content-Type") or "").split(";", 1)[0].strip().lower() == "application/json"

    def _need_login(self):
        """No session: with a password required, sign in; else get one from /api/session."""
        return self._json({"error": "sign in" if APP.auth.required() else "no session (open the page again)",
                           "auth": "login" if APP.auth.required() else "session"}, 401)

    def _is_admin(self) -> bool:
        """Admin role, or any browser while no password is required (the default)."""
        if not self.sess:
            return False
        return self.sess["role"] == "admin" or (self.sess["role"] == "open" and not APP.auth.required())

    # -- helpers --
    def _json(self, obj, code=200, headers=None):
        body = json.dumps(sign_images(obj), separators=(",", ":")).encode()
        gz = len(body) > 1400 and "gzip" in self.headers.get("Accept-Encoding", "")
        if gz:
            body = gzip.compress(body, 5)
        self.send_response(code)
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.send_header("Content-Type", "application/json")
        if gz:
            self.send_header("Content-Encoding", "gzip")
        self.send_header("Vary", "Accept-Encoding")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _err(self, code, msg):
        self._json({"error": msg}, code)

    def _authed(self) -> bool:
        return bool(self.sess)

    def _admin_ok(self) -> bool:
        """Provider, settings, storage, restore, deletes: the admin role (auth.py)."""
        return self._is_admin()

    # -- parental PIN (parental.py): locked groups / channels / VOD categories --
    def _unlocked(self) -> bool:
        """This browser gave the parental PIN (unlock token, 4 h)."""
        return APP.parental.valid(self.headers.get("X-Parental", ""))

    def _lock_pred(self):
        """Predicate: a channel that is locked (itself, its provider group or one of your own
        groups holding it). Always False while no PIN is set."""
        PA = APP.parental
        if not PA.active():
            return lambda c: False
        lg, lc = set(PA.data["groups"]), set(PA.data["channels"])
        pg = {(sid, name) for gid, name, sid in APP.sources.live_groups() if gid in lg}
        ck = set()
        for gid in lg:
            if APP.layout.is_custom(gid):
                ck.update(APP.layout.custom_keys(gid))
        return lambda c: c.key in lc or c.key in ck or (c.source_id, c.group) in pg

    def _vod_lock_pred(self, kind: str):
        PA = APP.parental
        if not PA.active() or not PA.data["vodCats"]:
            return lambda it: False
        lv = set(PA.data["vodCats"])
        pairs = {(sid, name) for gid, name, sid in APP.sources.vod_cats(kind) if gid in lv}
        return lambda it: (it.source_id, it.category) in pairs

    def _vod_item(self, kind: str, vid: str):
        prov = APP.sources.for_vid(vid)
        raw = prov.raw_vid(vid)
        it = prov.vod_by_key.get(f"{'m' if kind == 'movie' else 's'}|{prov.sid}|{raw}")
        if it is None:
            it = next((x for x in APP.sources.vod_items(kind) if x.id == vid), None)
        return it

    def _locked_answer(self, what: str = "locked"):
        return self._json({"error": what, "locked": True}, 423)

    def _media_sig_ok(self) -> bool:
        exp = self.q.get("exp", ["0"])[0]
        sig = self.q.get("sig", [""])[0]
        try:
            if int(exp) < time.time():
                return False
        except ValueError:
            return False
        if not (bool(sig) and hmac.compare_digest(sig, APP.sign(_media_payload(self.path_only, self.q)))):
            return False
        return APP.auth.tag_alive(self.q.get("a", [""])[0])     # the session that asked still exists

    def _signed(self, path: str, params: dict, ttl: int = 86400) -> str:
        """Server-signed media URL (24 h; live TV 6 h), bound to the asking session (a=); the
        browser adds only &sid=. Players that cannot send cookies (VLC, Infuse) use it as is."""
        q = {k: str(v) for k, v in params.items() if v is not None}
        # (an external player following a signed playlist passes on the tag it was given)
        q["a"] = self.sess["h"][:16] if self.sess else self.q.get("a", [""])[0][:16]
        q["exp"] = str(int(time.time()) + ttl)
        pq = {k: [v] for k, v in q.items()}
        q["sig"] = APP.sign(_media_payload(path, pq))
        return path + "?" + urllib.parse.urlencode(q)

    # -- routing --
    def _gate_get(self, path: str) -> bool:
        """True when the request may go on (else it has been answered)."""
        if path in PUBLIC_GET:
            return True
        if path in ("/stream", "/vodstream"):        # signed URLs (also for external players)
            if self._media_sig_ok():
                return True
            self._err(403, "bad signature")
            return False
        if path == "/seg" or path.startswith("/remux/"):
            return True                              # sealed URL / 96-bit job id, checked in the route
        if path.startswith("/rec/"):
            if self.sess or self._media_sig_ok():
                return True
            self._need_login()
            return False
        if path.startswith("/api/") or path == "/img":
            if self.sess:
                return True
            self._need_login()
            return False
        return True                                  # static files (dev only; nginx in production)

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        self.path_only = u.path
        self.q = urllib.parse.parse_qs(u.query)
        self.sess = None
        try:
            if not self._host_ok():
                return self._err(421, "unknown host name")
            self.sess = self._session()
            if not self._gate_get(u.path):
                return
            self.route_get(u.path)
        except BrokenPipeError:
            pass
        except Exception as e:                      # noqa: BLE001
            LOG.exception("GET %s failed", u.path)
            try:
                self._err(500, type(e).__name__)
            except Exception:
                pass

    def do_POST(self):
        u = urllib.parse.urlparse(self.path)
        self.path_only = u.path
        self.q = urllib.parse.parse_qs(u.query)
        self._body_cache = None
        self.sess = None
        try:
            if not self._host_ok():
                return self._err(421, "unknown host name")
            try:
                n = int(self.headers.get("Content-Length", "0") or "0")
            except ValueError:
                return self._err(400, "length")
            if n < 0 or n > (BK.MAX_BYTES if u.path == "/api/restore" else MAX_BODY):
                self.close_connection = True
                left = n if 0 < n <= 16 * 1024 * 1024 else 0      # read it away so the client sees the answer
                while left > 0:
                    chunk = self.rfile.read(min(left, 256 * 1024))
                    if not chunk:
                        break
                    left -= len(chunk)
                return self._err(413, "request too large")
            if u.path == "/api/restore":
                self.close_connection = True         # its body is read only by r_restore
            else:
                self._body()                         # read now (keep-alive stays in step)
            # CSRF: JSON only (a cross-site form or beacon cannot send it without a preflight),
            # from this site's own pages, and with the session's token (or X-TvBro on sign-in)
            if not self._json_request() or not self._origin_ok():
                return self._err(403, "cross-site request refused")
            self.sess = self._session()
            if u.path == "/api/auth/login":
                if self.headers.get("X-TvBro") != "1":
                    return self._err(403, "cross-site request refused")
            elif not self.sess:
                return self._need_login()
            elif not APP.auth.csrf_ok(self.sess["h"], self.headers.get("X-CSRF-Token", "")):
                return self._err(403, "csrf token")
            if u.path in ADMIN_POST or u.path.startswith(ADMIN_POST_PREFIX):
                if not self._is_admin():
                    return self._err(403, "admin only")
            self.route_post(u.path)
        except Exception as e:                      # noqa: BLE001
            LOG.exception("POST %s failed", u.path)
            self._err(500, type(e).__name__)

    def _body(self) -> dict:
        try:
            n = min(int(self.headers.get("Content-Length", "0") or "0"), MAX_BODY)
        except ValueError:
            n = 0
        if getattr(self, "_body_cache", None) is not None:
            return self._body_cache
        raw = self.rfile.read(n) if n > 0 else b""
        try:
            d = json.loads(raw.decode("utf-8")) if raw else {}
        except ValueError:
            d = {}
        self._body_cache = d if isinstance(d, dict) else {}
        return self._body_cache

    # -- POST routes --
    def r_auth_post(self, action: str):
        """login {password}, logout, require {enabled, password?, current?} (admin: the optional
        "Require a password"), password {current, password} (admin), viewer {password|""} (admin)."""
        d = self._body()
        A = APP.auth
        if action == "require":
            on = d.get("enabled") is True
            if on:
                pw = d.get("password") or None
                if A.data["admin"] and not pw:          # turning it on again with the old password
                    if not self._pw_ok(str(d.get("current") or "")):
                        return self._err(403, "wrong password")
                try:
                    A.set_required(True, pw, keep=self.sess["h"])
                except ValueError as e:
                    return self._err(400, str(e))
            else:
                if A.required() and not self._pw_ok(str(d.get("current") or "")):
                    return self._err(403, "wrong password")
                A.set_required(False)
            return self._json({"ok": True, "required": A.required()})
        if action == "login":
            if not A.data["admin"]:
                return self._err(409, "no password is set")
            try:
                sid, role = A.login(d.get("password"), self._client_ip())
            except AUTH.Wait as e:
                return self._wait_answer(e.retry_after, "too many wrong passwords")
            except PermissionError:
                LOG.warning("wrong password from %s", self._client_ip())
                return self._err(403, "wrong password")
            return self._login_answer(sid, role)
        if action == "logout":
            A.logout(self.sess["h"])
            return self._json({"ok": True}, headers={"Set-Cookie": self._cookie("", 0)})
        if action == "password":
            if not self._pw_ok(str(d.get("current") or "")):
                return self._err(403, "wrong password")
            try:
                A.set_password("admin", d.get("password"), keep=self.sess["h"])
            except ValueError as e:
                return self._err(400, str(e))
            return self._json({"ok": True})
        if action == "viewer":
            pw = d.get("password") or ""
            try:
                A.set_password("viewer", pw)
            except ValueError as e:
                return self._err(400, str(e))
            return self._json({"ok": True, "viewerPassword": A.has_viewer()})
        return self._err(404, "not found")

    def _pw_ok(self, pw: str) -> bool:
        """The current admin password, throttled like sign-in (raises AUTH.Wait)."""
        A = APP.auth
        A.throttle.check(self._client_ip())
        if A.data["admin"] and AUTH.verify_password(pw, A.data["admin"]):
            return True
        A.throttle.fail(self._client_ip())
        return False

    def _login_answer(self, sid: str, role: str):
        h = AUTH.Auth._h(sid)
        return self._json({"ok": True, "role": role, "csrf": APP.auth.csrf(h)},
                          headers={"Set-Cookie": self._cookie(sid, AUTH.SESSION_TTL)})

    def _wait_answer(self, secs: int, msg: str):
        return self._json({"error": f"{msg}: try again in {secs} s", "retryAfter": secs}, 429,
                          headers={"Retry-After": str(secs)})

    def route_post(self, path):
        if path.startswith("/api/auth/"):
            try:
                return self.r_auth_post(path.rsplit("/", 1)[1])
            except AUTH.Wait as e:
                return self._wait_answer(e.retry_after, "too many wrong passwords")
        if path == "/api/signin":
            # adding / replacing a playlist (admin; the admin password is made on the first visit)
            d = self._body()
            kind = d.get("kind", "xtream")
            if kind not in ("xtream", "m3u", "demo"):
                return self._err(400, "kind")
            why = source_problem(kind, str(d.get("server") or ""), str(d.get("url") or ""), str(d.get("epgUrl") or ""))
            if why:
                return self._err(400, why)
            sid = APP.sources.add(kind, str(d.get("name", "")), str(d.get("server") or "").strip(), str(d.get("user") or ""),
                                  str(d.get("pass") or ""), str(d.get("epgUrl") or "").strip(), str(d.get("url") or "").strip())
            _INFO_CACHE.clear()
            return self._json({"token": "", "id": sid, "status": {"signedIn": True}})
        if path.startswith("/api/sources/"):
            if not self._admin_ok():
                return self._err(403, "admin only")
            parts = path.split("/")                 # /api/sources/<id>/<action>
            if len(parts) != 5:
                return self._err(404, "not found")
            sid, action = parts[3], parts[4]
            d = self._body()
            if action == "update":
                ok = APP.sources.update(sid, d.get("name"), d.get("enabled"))
            elif action == "delete":
                ok = APP.sources.remove(sid)
            elif action == "sync":
                APP.sources.sync("channels", sid)
                APP.sources.sync("epg", sid)
                APP.sources.sync("vod", sid)
                ok = True
            else:
                return self._err(404, "not found")
            _INFO_CACHE.clear()
            return self._json(self._sync_status()) if ok else self._err(404, "source")
        if path == "/api/signout":
            if not self._admin_ok():
                return self._err(403, "admin only")
            for p in list(APP.sources.all()):
                APP.sources.remove(p.sid)
            return self._json({"ok": True})
        if path == "/api/refresh":
            if not self._authed():
                return self._err(401, "unauthorized")
            _INFO_CACHE.clear()
            for k in ("channels", "epg", "vod"):
                APP.sources.sync(k)
            return self._json({"ok": True})
        if path.startswith("/api/sync/"):
            if not self._authed():
                return self._err(401, "unauthorized")
            kind = path.rsplit("/", 1)[1]
            if kind == "all":
                for k in ("channels", "epg", "vod"):
                    APP.sources.sync(k)
            elif kind in ("channels", "epg", "vod"):
                if kind == "vod":
                    _INFO_CACHE.clear()
                APP.sources.sync(kind)
            else:
                return self._err(404, "not found")
            return self._json(self._sync_status())
        if path == "/api/settings":
            d = self._body()
            APP.settings.save(d)
            APP.streams.set_max(self._max_streams())
            return self._json(self._sync_status())
        if path.startswith("/api/state/"):
            scope = path.rsplit("/", 1)[1]
            d = self._body()
            op = d.get("op", "put")
            if op == "clear":
                APP.state.clear(scope)
            elif op == "del":
                APP.state.delete(scope, str(d.get("key", "")))
            elif not APP.state.put(scope, str(d.get("key", "")), d.get("value")):
                return self._err(400, "bad state")
            return self._json({"ok": True})
        if path.startswith("/api/parental/"):
            return self.r_parental_post(path.rsplit("/", 1)[1], self._body())
        if path == "/api/restore":
            return self.r_restore()
        if path == "/api/epg/map":
            return self.r_epg_map(self._body())
        if path.startswith("/api/series/"):
            return self.r_series_post(path.rsplit("/", 1)[1], self._body())
        if path == "/api/layout":
            return self.r_layout_edit()
        if path == "/api/recordings":
            d = self._body()
            try:
                start = int(d["start"]) if d.get("start") else None
                stop = int(d["stop"]) if d.get("stop") else None
                item = APP.recorder.add(str(d.get("key", "")), str(d.get("title") or ""), start, stop)
            except (ValueError, TypeError) as e:
                return self._err(400, str(e) or "bad request")
            return self._json({"ok": True, "item": item})
        if path == "/api/recordings/series":
            d = self._body()
            try:
                return self._json({"ok": True, "rule": APP.recorder.add_rule(str(d.get("key", "")), str(d.get("title") or ""))})
            except ValueError as e:
                return self._err(400, str(e))
        if path == "/api/recordings/series/delete":
            ok = APP.recorder.delete_rule(str(self._body().get("id", "")))
            return self._json({"ok": ok}) if ok else self._err(404, "rule")
        if path == "/api/recordings/storage/test":
            return self._json(STO.test_dir(str(self._body().get("path") or ""), DATA_DIR))
        if path == "/api/recordings/storage":
            if not self._admin_ok():
                return self._err(403, "admin only")
            want = str(self._body().get("path") or "")
            if want and want != APP.recorder.default_dir:
                t = STO.test_dir(want, DATA_DIR)
                if not t["ok"]:
                    return self._err(400, t.get("why") or "folder not usable")
                want = t["path"]
            else:
                want = ""
            APP.settings["recDir"] = want
            APP.settings.save({})
            APP.recorder.set_dir(want or None)
            return self._json({"ok": True, "dir": APP.recorder.dir})
        if path == "/api/recordings/share":
            if not self._admin_ok():
                return self._err(403, "admin only")
            d = self._body()
            if d.get("clear"):
                APP.shares.clear()
                return self._json({"ok": True, "share": None, "steps": []})
            try:
                pub = APP.shares.save(d)
            except ValueError as e:
                return self._err(400, str(e))
            return self._json({"ok": True, "share": pub, "steps": STO.host_steps(APP.shares.get(), APP.settings.get("ctid"))})
        if path in ("/api/recordings/stop", "/api/recordings/delete"):
            rid = str(self._body().get("id", ""))
            ok = APP.recorder.stop(rid) if path.endswith("/stop") else APP.recorder.delete(rid)
            return self._json({"ok": ok}) if ok else self._err(404, "recording")
        if path in ("/api/stream/start", "/api/stream/heartbeat", "/api/stream/stop"):
            if not self._authed():
                return self._err(401, "unauthorized")
            d = self._body()
            sid = str(d.get("sid") or self.q.get("sid", [""])[0])[:64]
            if not sid:
                return self._err(400, "sid")
            if path.endswith("/start"):
                stopped = APP.streams.start(sid, str(d.get("label", ""))[:60], str(d.get("what", ""))[:120])
                if stopped is None:
                    return self._json({"ok": False, "stopped": "recording"})
                return self._json({"ok": True, "replaced": len(stopped)})
            if path.endswith("/heartbeat"):
                if APP.streams.is_stopped(sid):
                    return self._json({"stopped": APP.streams.why(sid)})
                APP.streams.touch(sid)
                return self._json({"ok": True})
            APP.streams.stop(sid)
            return self._json({"ok": True})
        self._err(404, "not found")

    # -- GET routes --
    def route_get(self, path):
        if path == "/api/health":
            return self._json({"status": "ok", "time": int(time.time())})
        if path == "/api/session":
            hdr = None
            if not self.sess:
                if APP.auth.required():
                    return self._json({"auth": "login", "signedIn": False, "required": True})
                sid = APP.auth.open_session()            # no password needed: this browser's session
                self.sess = APP.auth.session(sid)
                hdr = {"Set-Cookie": self._cookie(sid, AUTH.SESSION_TTL)}
            elif self.sess.get("renew"):
                hdr = {"Set-Cookie": self._cookie(self.headers.get("Cookie", "").split(AUTH.COOKIE + "=", 1)[1].split(";", 1)[0],
                                                  AUTH.SESSION_TTL)}
            on = APP.sources.enabled()
            return self._json({"auth": "ok", "role": "admin" if self._is_admin() else self.sess["role"],
                               "csrf": APP.auth.csrf(self.sess["h"]), "required": APP.auth.required(),
                               "signedIn": APP.sources.has_any(), "kind": on[0].kind() if on else None,
                               "sources": len(on), "viewerPassword": APP.auth.has_viewer()}, headers=hdr)
        if path in ("/api/recordings/dirs", "/api/recordings/share/steps") and not self._is_admin():
            return self._err(403, "admin only")
        if path == "/api/state":
            return self._json(APP.state.all())
        if path == "/api/parental":
            return self._json({**APP.parental.public(), "unlocked": self._unlocked()})
        if path == "/api/backup":
            return self.r_backup()
        if path == "/api/epg/channels":
            return self.r_epg_channels()
        if path == "/api/play":
            return self.r_play()
        if path == "/api/series/continue":
            return self._json({"items": APP.series.continue_rows()})
        if path == "/api/series/state":
            sid = self.q.get("id", [""])[0][:100]
            return self._json(APP.series.summary(sid)) if sid else self._err(400, "id required")

        if path == "/api/sync/status":
            return self._json(self._sync_status())
        if path == "/api/groups":
            return self.r_groups()
        if path == "/api/layout":
            return self._json(APP.layout.data)
        if path == "/api/channels":
            return self.r_channels()
        if path == "/api/nownext":
            return self.r_nownext()
        if path == "/api/guide":
            return self.r_guide()
        if path == "/api/vod/categories":
            return self.r_vod_categories()
        if path == "/api/vod/list":
            return self.r_vod_list()
        if path == "/api/vod/info":
            return self.r_vod_info()
        if path == "/api/search":
            return self.r_search()
        if path == "/api/prog":
            return self.r_prog()
        if path == "/api/catchup":
            return self.r_catchup_url()
        if path == "/api/alternates":
            return self.r_alternates()
        if path == "/stream":
            return self.r_stream()
        if path == "/seg":
            return self.r_seg()
        if path == "/vodstream":
            return self.r_vodstream()
        if path == "/img":
            return self.r_img()
        if path.startswith("/remux/"):
            return self.r_remux(path)
        if path == "/api/recordings":
            s = APP.settings
            return self._json({"items": APP.recorder.list(), "disk": APP.recorder.disk(),
                               "settings": {k: s.get(k, v) for k, v in REC_DEFAULTS.items()},
                               "rules": APP.recorder.rules,
                               "storage": {"dir": APP.recorder.dir, "default": APP.recorder.default_dir,
                                           "share": APP.shares.public()}})
        if path == "/api/recordings/dirs":
            try:
                return self._json(STO.list_dirs(self.q.get("path", [""])[0], DATA_DIR))
            except ValueError as e:
                return self._err(400, str(e))
        if path == "/api/recordings/share/steps":
            sh = APP.shares.get()
            return self._json({"steps": STO.host_steps(sh, APP.settings.get("ctid")) if sh else []})
        if path.startswith("/rec/"):
            return self.r_rec(path)
        # Optional static serving for local dev (production uses nginx). TVBRO_STATIC=<dir>.
        static = os.environ.get("TVBRO_STATIC")
        if static:
            return self._serve_static(static, path)
        self._err(404, "not found")

    def _serve_static(self, root, path):
        rel = path.lstrip("/") or "index.html"
        full = os.path.normpath(os.path.join(root, rel))
        if not full.startswith(os.path.abspath(root)):
            return self._err(403, "path")
        if os.path.isdir(full):
            full = os.path.join(full, "index.html")
        if not os.path.exists(full):
            full = os.path.join(root, "index.html")  # SPA fallback
            if not os.path.exists(full):
                return self._err(404, "not found")
        ctypes = {".html": "text/html", ".js": "text/javascript", ".css": "text/css",
                  ".json": "application/json", ".png": "image/png", ".svg": "image/svg+xml",
                  ".ico": "image/x-icon", ".woff2": "font/woff2"}
        ext = os.path.splitext(full)[1].lower()
        with open(full, "rb") as f:
            data = f.read()
        self.send_response(200)
        self.send_header("Content-Type", ctypes.get(ext, "application/octet-stream"))
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Content-Security-Policy", APP_CSP)
        self.end_headers()
        self.wfile.write(data)

    def _max_streams(self) -> int:
        n = int(APP.settings.get("maxStreams", 1))
        acc = APP.sources.account()
        try:
            cap = int(acc.get("max_connections") or 0)
        except (TypeError, ValueError):
            cap = 0
        return max(1, min(n, cap)) if cap > 0 else n

    def _sync_status(self) -> dict:
        st = APP.sources.status()
        st["streams"] = APP.streams.status()
        st["remux"] = APP.remux.status()
        st["time"] = int(time.time())
        return st

    def _stream_gate(self):
        """Concurrent-stream limit: returns (sid, gen) or None after answering 409."""
        sid = self.q.get("sid", [""])[0][:64] or None
        gen = APP.streams.touch(sid)
        if gen is None:
            self._json({"stopped": APP.streams.why(sid)}, 409)
            return None
        return sid, gen

    def _page(self, items, default=200):
        try:
            page = max(1, int(self.q.get("page", ["1"])[0]))
            size = min(500, max(1, int(self.q.get("size", [str(default)])[0])))
        except ValueError:
            page, size = 1, default
        total = len(items)
        start = (page - 1) * size
        return {"page": page, "size": size, "total": total,
                "items": items[start:start + size]}

    # -- live data --
    def _provider_groups(self) -> list[dict]:
        """Every enabled source's live groups, labelled with the playlist name after the first."""
        counts: dict[tuple, int] = {}
        for ch in APP.sources.channels:
            k = (ch.source_id, ch.group)
            counts[k] = counts.get(k, 0) + 1
        on = APP.sources.enabled()
        first = on[0].sid if on else SOURCE_ID
        out = []
        for gid, name, sid in APP.sources.live_groups():
            label = name if sid == first else f"{APP.sources.source_label(sid)} Â· {name}"
            out.append({"id": gid, "name": label, "count": counts.get((sid, name), 0), "source": sid, "_name": name})
        return out

    @staticmethod
    def _hidden_groups() -> set[tuple[str, str]]:
        """(source, provider group name) of every provider group the household hid."""
        L = APP.layout
        if not any(m.get("hidden") for m in L.data["groups"].values()):
            return set()
        return {(sid, name) for gid, name, sid in APP.sources.live_groups() if L.group_hidden(gid)}

    def _visible(self):
        """Predicate: a channel the household has not hidden (itself or its whole group)."""
        L = APP.layout
        hidden_groups = self._hidden_groups()
        return lambda c: not L.channel_hidden(c.key) and (c.source_id, c.group) not in hidden_groups

    def _with_hidden(self) -> bool:
        return self.q.get("all", [""])[0] == "1"

    def r_groups(self):
        APP.sources.ensure_channels()
        vis = self._visible()
        pg = self._provider_groups()
        groups = [{"id": "__fav", "name": "Favorites"},
                  {"id": "__all", "name": "All", "count": sum(1 for c in APP.sources.channels if vis(c))}]
        locked = set(APP.parental.data["groups"]) if APP.parental.has_pin() else set()
        for g in APP.layout.groups(pg, self._with_hidden()):
            g.pop("_name", None)
            if g["id"] in locked:
                g["locked"] = True
            groups.append(g)
        return self._json({"groups": groups})

    def r_layout_edit(self):
        d = self._body()
        op = str(d.get("op", ""))
        APP.sources.ensure_channels()
        gids = keys = None
        if op == "group.move":
            gids = [g["id"] for g in APP.layout.groups(self._provider_groups(), True)]
        elif op == "channel.move":
            keys = [c.key for c in self._channels_for(str(d.get("group", "")), "", True)]
        try:
            out = APP.layout.edit(op, d, gids, keys)
        except ValueError as e:
            return self._err(400, str(e))
        return self._json({"ok": True, **out})

    @staticmethod
    def _group_filter(gid: str, cats_of) -> tuple[str, str]:
        """Group id -> (source id, provider group name)."""
        sid, cid = split_group_id(gid)
        prov = APP.sources.get(sid)
        try:
            cats = cats_of(prov) if prov else []
        except Exception:                           # noqa: BLE001
            cats = []
        return sid, {c.category_id: c.category_name for c in cats}.get(cid, cid)

    def _channels_for(self, group, q, with_hidden=False):
        L = APP.layout
        chans = APP.sources.channels
        if group and L.is_custom(group):
            by = APP.sources.channels_by_key
            chans = [by[k] for k in L.custom_keys(group) if k in by]
        elif group and group not in ("__all", "__fav"):
            sid, name = self._group_filter(group, lambda p: p.live_categories())
            chans = [c for c in chans if c.source_id == sid and c.group == name]
            by = {c.key: c for c in chans}
            chans = [by[k] for k in L.order_channels(group, [c.key for c in chans])]
        if not with_hidden:
            if group and group not in ("__all", "__fav"):
                # a group someone opens shows its channels, minus the ones hidden one by one
                # (your own group may hold channels of a provider group you hid)
                chans = [c for c in chans if not L.channel_hidden(c.key)]
            else:
                vis = self._visible()
                chans = [c for c in chans if vis(c)]
        if q:
            ql = q.lower()
            chans = [c for c in chans if ql in L.channel({"key": c.key, "name": c.name})["name"].lower()]
        return chans

    def r_channels(self):
        APP.sources.ensure_channels()
        group = self.q.get("group", [""])[0]
        q = self.q.get("q", [""])[0]
        keys = [k for k in self.q.get("keys", [""])[0].split(",") if k]
        if keys:   # explicit list (favourites), returned in the order asked
            by = APP.sources.channels_by_key
            chans = [by[k] for k in keys if k in by]
        else:
            chans = self._channels_for(group, q, self._with_hidden())
        wh = self._with_hidden()
        out = self._page(chans)
        lp = self._lock_pred()
        out["items"] = [self._mark(APP.layout.channel(c.to_json(), wh), lp(c)) for c in out["items"]]
        return self._json(out)

    @staticmethod
    def _mark(js: dict, locked: bool) -> dict:
        if locked:
            js["locked"] = True
        return js

    def r_nownext(self):
        APP.sources.ensure_epg()
        keys = [k for k in self.q.get("keys", [""])[0].split(",") if k]
        now = int(time.time())
        res = {}
        for k in keys:
            ch = APP.sources.channels_by_key.get(k)
            if not ch:
                continue
            cur, nxt = P.now_next(APP.sources.programmes(ch, now - 43200, now + 86400), now)
            res[k] = {"now": cur.to_json() if cur else None,
                      "next": nxt.to_json() if nxt else None}
        return self._json({"nownext": res})

    def r_guide(self):
        APP.sources.ensure_epg()
        keys = [k for k in self.q.get("keys", [""])[0].split(",") if k]
        now = int(time.time())
        try:
            frm = int(self.q.get("from", [str(now)])[0])
            to = int(self.q.get("to", [str(now + 10800)])[0])
        except ValueError:
            frm, to = now, now + 10800
        res = {}
        for k in keys:
            ch = APP.sources.channels_by_key.get(k)
            if not ch:
                continue
            win = APP.sources.programmes(ch, frm, to)
            res[k] = [{**p.to_json(),
                       "state": P.classify(ch, p.start, p.stop, now),
                       "catchup": P.catchup_supports(ch, p.start, now)} for p in win]
        return self._json({"from": frm, "to": to, "guide": res})

    # -- vod --
    def r_vod_categories(self):
        kind = self.q.get("kind", ["movie"])[0]
        items = APP.sources.vod_items(kind)
        counts: dict[tuple, int] = {}
        for it in items:
            k = (it.source_id, it.category)
            counts[k] = counts.get(k, 0) + 1
        on = APP.sources.enabled()
        first = on[0].sid if on else SOURCE_ID
        out = [{"id": "__all", "name": "All", "count": len(items)}]
        locked = set(APP.parental.data["vodCats"]) if APP.parental.has_pin() else set()
        out += [self._mark({"id": gid, "name": name if sid == first else f"{APP.sources.source_label(sid)} Â· {name}",
                            "count": counts.get((sid, name), 0)}, gid in locked) for gid, name, sid in APP.sources.vod_cats(kind)]
        return self._json({"categories": out})

    def r_vod_list(self):
        kind = self.q.get("kind", ["movie"])[0]
        cat = self.q.get("cat", [""])[0]
        q = self.q.get("q", [""])[0].lower()
        items = APP.sources.vod_items(kind)
        if cat and cat != "__all":
            sid, name = self._group_filter(cat, lambda p: p.movie_cats() if kind == "movie" else p.series_cats())
            items = [it for it in items if it.source_id == sid and it.category == name]
        if q:
            items = [it for it in items if q in it.name.lower()]
        out = self._page(items, 60)
        vlp = self._vod_lock_pred(kind)
        out["items"] = [self._mark(it.to_json(), vlp(it)) for it in out["items"]]
        return self._json(out)

    # -- manual channel-to-guide mapping (epgmap.py) --
    @staticmethod
    def _epg_current(prov, ch) -> dict:
        """How a channel gets its guide now: picked by hand, none, or the automatic match."""
        m = APP.sources.epgmap.get(ch.key)
        if m == NO_GUIDE:
            return {"mode": "none"}
        if m:
            return {"mode": "manual", "id": m, "name": prov.epgdb.guide_name(m) or m,
                    "hasGuide": mapped_key(ch, m) in prov.epgdb.keys}
        k = key_for(prov.epgdb, ch)
        if not k:
            return {"mode": "auto", "id": None}
        gid = k.split("|", 1)[1]
        return {"mode": "auto", "id": gid, "name": prov.epgdb.guide_name(gid) or gid, "hasGuide": True}

    def r_epg_channels(self):
        """GET /api/epg/channels?key=<channel>&q=: the channels of that playlist's guide to pick from."""
        key = self.q.get("key", [""])[0]
        ch = APP.sources.channel(key)
        prov = APP.sources.for_key(key)
        if not ch or not prov:
            return self._err(404, "channel")
        q = self.q.get("q", [""])[0][:80]
        items = prov.epgdb.guide_channels(prov.sid, q, 80)
        return self._json({"items": items or [], "needSync": items is None, "current": self._epg_current(prov, ch),
                           "channel": APP.layout.channel(ch.to_json())})

    def r_epg_map(self, d: dict):
        """POST {key, guideId}: guideId an XMLTV channel id of that playlist's guide, "__none" (no
        guide) or null / "" (automatic again). A guide channel whose programmes are not stored
        yet (nothing matched it before) is fetched by a guide sync, started here."""
        key = str(d.get("key") or "")
        ch = APP.sources.channel(key)
        prov = APP.sources.for_key(key)
        if not ch or not prov:
            return self._err(404, "channel")
        gid = str(d.get("guideId") or "").strip()[:300]
        if gid and gid != NO_GUIDE:
            known = prov.epgdb.guide_name(gid)
            if known is None and prov.epgdb.guide_channels(prov.sid, "", 1) is not None:
                return self._err(400, "that channel is not in this playlist's guide")
            # the guide's own spelling of the id (ids are matched without case)
            for g in prov.epgdb.guide_channels(prov.sid, gid, 80) or []:
                if g["id"].lower() == gid.lower():
                    gid = g["id"]
                    break
        try:
            APP.sources.epgmap.set(key, gid or None)
        except ValueError as e:
            return self._err(400, str(e))
        syncing = False
        if gid and gid != NO_GUIDE and mapped_key(ch, gid) not in prov.epgdb.keys:
            prov.sync("epg")                           # its programmes were not kept: fetch the guide
            syncing = True
        else:
            prov.reindex()                             # search follows the new mapping
        return self._json({"ok": True, "syncing": syncing, "current": self._epg_current(prov, ch)})

    def r_backup(self):
        """The household's data as one JSON file (no logins, no PINs): see backup.py."""
        body = json.dumps(BK.export(APP), separators=(",", ":"), ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Disposition", f'attachment; filename="tvbro-backup-{time.strftime("%Y-%m-%d")}.json"')
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def r_restore(self):
        """POST the backup file as the body. ?check=1 only validates and says what is in it.
        Needs the admin PIN when one is set. Nothing is written unless the whole file is valid."""
        if not self._admin_ok():
            return self._err(403, "admin only")
        n = int(self.headers.get("Content-Length", "0") or "0")
        if n <= 0 or n > BK.MAX_BYTES:
            return self._err(413 if n else 400, "the backup file is empty or too large")
        try:
            doc = json.loads(self.rfile.read(n).decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return self._err(400, "not a tvBro backup (not JSON)")
        try:
            clean = BK.validate(doc)
        except BK.BadBackup as e:
            return self._err(400, str(e))
        summary = {k: (len(v) if isinstance(v, (list, dict)) else 1) for k, v in clean.items()}
        if "state" in clean:
            summary["state"] = {s: len(rows) for s, rows in clean["state"].items()}
        if self.q.get("check", [""])[0] == "1":
            return self._json({"ok": True, "check": True, "createdAt": doc.get("createdAt"), "contains": summary})
        restored = BK.restore(APP, clean)
        _INFO_CACHE.clear()
        if "epgMap" in clean:
            APP.sources.sync("epg")                  # guide channels picked by hand are fetched again
        return self._json({"ok": True, "restored": restored})

    def r_parental_post(self, action: str, d: dict):
        """Parental PIN: unlock {pin} -> token; pin {pin?, newPin} set / change / remove;
        lock {kind: group|channel|vodcat, id, locked} (needs the PIN or an unlock)."""
        PA = APP.parental
        try:
            if action == "unlock":
                if not PA.has_pin():
                    return self._err(400, "no parental PIN is set")
                if not PA.check(str(d.get("pin") or "")):
                    return self._err(403, "wrong PIN")
                return self._json({"ok": True, "token": PA.token(), "ttl": 4 * 3600})
            if action == "pin":
                cur = True if self._unlocked() and not d.get("pin") else str(d.get("pin") or "")
                PA.set_pin(str(d.get("newPin") or "").strip(), cur)
                out = {"ok": True, **PA.public()}
                if PA.has_pin():
                    out["token"] = PA.token()        # whoever just set it stays unlocked
                return self._json(out)
            if action == "lock":
                if not PA.has_pin():
                    return self._err(400, "set a parental PIN first (Settings > Parental controls)")
                if not (self._unlocked() or PA.check(str(d.get("pin") or ""))):
                    return self._err(403, "wrong PIN")
                PA.lock(str(d.get("kind") or ""), str(d.get("id") or "")[:300], bool(d.get("locked")))
                return self._json({"ok": True, **PA.public()})
        except PinWait as e:
            return self._json({"error": f"too many wrong PINs: try again in {e.retry_after} s", "retryAfter": e.retry_after}, 429)
        except PermissionError as e:
            return self._err(403, str(e))
        except ValueError as e:
            return self._err(400, str(e))
        return self._err(404, "not found")

    def r_series_post(self, action: str, d: dict):
        """Shows progress (series.py): progress / mark / prefs / hide."""
        sid = str(d.get("seriesId") or "")[:100]
        if not sid:
            return self._err(400, "seriesId required")
        st = APP.series
        if action == "progress":
            ep = str(d.get("epId") or "")[:100]
            if not ep:
                return self._err(400, "epId required")
            try:
                secs, dur = float(d.get("secs") or 0), float(d.get("dur") or 0)
            except (TypeError, ValueError):
                return self._err(400, "secs / dur")
            return self._json(st.record(sid, ep, secs, dur, bool(d.get("ended")),
                                        str(d.get("name") or "")[:200] or None, str(d.get("poster") or "")[:500] or None))
        if action == "mark":
            ep = d.get("epId")
            return self._json(st.mark(sid, bool(d.get("watched")), d.get("season"), str(ep)[:100] if ep else None))
        if action == "prefs":
            st.set_prefs(sid, d.get("subs"), d.get("audio"))
            return self._json({"ok": True})
        if action == "hide":
            st.hide(sid)
            return self._json({"ok": True})
        return self._err(404, "not found")

    def r_vod_info(self):
        kind = self.q.get("kind", ["movie"])[0]
        vid = self.q.get("id", [""])[0]
        if not vid:
            return self._err(400, "id required")
        # a locked film / show: no details (a show's episodes are reached only through them)
        if APP.parental.active() and not self._unlocked():
            it = self._vod_item("movie" if kind == "movie" else "series", vid)
            if it is not None and self._vod_lock_pred("movie" if kind == "movie" else "series")(it):
                return self._locked_answer()
        return self._json(vod_info_json(kind, vid))

    def r_search(self):
        """Search through each playlist's FTS5 index (searchidx.py): every word a prefix,
        accents ignored, best first; at most 20 movies, 20 shows, 30 channels and 10 channels
        x 5 programmes (now / upcoming / catch-up, decided here). Only what the result list
        shows: no descriptions (the programme card asks /api/prog)."""
        t0 = time.perf_counter()
        q = self.q.get("q", [""])[0].strip()[:100]
        fq = SI.fts_query(q)
        out = {"channels": [], "movies": [], "series": [], "programs": []}
        if len(q) < 2 or not fq:
            return self._json(out)
        provs = APP.sources.enabled()
        for p in provs:                     # never block on a download; refresh in the background
            if p.kind() == "xtream" and not p._vod_at:
                p.sync("vod")
            if not p._epg_at and p._chan_at:
                p.sync("epg")
            if not p.search.available() and p.channels:
                p.reindex()
        ready = [p for p in provs if p.search.available()]
        if provs and not ready:
            out["indexing"] = True
            return self._json(out)
        w0 = SI._first_word(q)
        now = int(time.time())
        L = APP.layout
        lp = self._lock_pred()
        by_key = APP.sources.channels_by_key
        hidden = self._hidden_groups()

        def visible(key):
            c = by_key.get(key)
            return c is not None and not L.channel_hidden(key) and (c.source_id, c.group) not in hidden

        # channels (plus the household's own names for channels, which the index does not know)
        keys = [k for k, m in L.data["channels"].items() if m.get("name") and _words_match(q, m["name"])]
        for p in ready:
            keys += p.search.channels(fq, w0, 2 * SI.LIMITS["channels"])
        seen = set()
        for k in keys:
            if k in seen or not visible(k):
                continue
            seen.add(k)
            c = by_key[k]
            j = self._mark(L.channel(c.to_json()), lp(c))
            cur, _ = P.now_next(APP.sources.programmes(c, now - 43200, now + 3600), now)
            if cur:
                j["now"] = {"start": cur.start, "stop": cur.stop, "title": cur.title}
            out["channels"].append(j)
            if len(out["channels"]) >= SI.LIMITS["channels"]:
                break
        # movies and shows
        for kind, sect in (("movie", "movies"), ("series", "series")):
            lim = SI.LIMITS[sect]
            vlp = self._vod_lock_pred(kind)
            for p in ready:
                out[sect] += [self._mark(p.vod_by_key[k].to_json(), vlp(p.vod_by_key[k])) for k in p.search.vod(kind, fq, w0, lim) if k in p.vod_by_key]
            out[sect] = out[sect][:lim]
        # programmes, grouped by channel
        rows = [r for p in ready for r in p.search.programmes(fq, now)]
        if len(ready) > 1:
            rows.sort(key=lambda r: r[0])       # keep now / upcoming / catch-up order across playlists
        for key, items in SI.group_programmes(rows, visible):
            out["programs"].append({"ch": self._mark(L.channel(by_key[key].to_json()), lp(by_key[key])), "items": items})
        out["ms"] = round((time.perf_counter() - t0) * 1000, 1)
        return self._json(out)

    def r_prog(self):
        """One programme in full (the search result card): /api/prog?key=<channel>&start=<epoch>."""
        ch = APP.sources.channel(self.q.get("key", [""])[0])
        try:
            start = int(self.q.get("start", [""])[0])
        except ValueError:
            return self._err(400, "start")
        if not ch:
            return self._err(404, "channel")
        for p in APP.sources.programmes(ch, start, start + 1):
            if p.start == start:
                return self._json(p.to_json())
        return self._err(404, "programme")

    # -- media --
    def _signed_seg(self, url: str) -> str:
        sid = self.q.get("sid", [""])[0][:64]
        return f"/seg?e={APP.seal(url)}" + (f"&sid={urllib.parse.quote(sid)}" if sid else "")

    def _want_fmt(self) -> str:
        f = self.q.get("fmt", [""])[0]
        return f if f in ("hls", "ts") else ""

    def r_catchup_url(self):
        """Returns the stream URL (through the proxy) for a catch-up programme."""
        key = self.q.get("key", [""])[0]
        ch = APP.sources.channel(key)
        if not ch:
            return self._err(404, "channel")
        try:
            start = int(self.q.get("start", ["0"])[0])
            end = int(self.q.get("end", ["0"])[0])
        except ValueError:
            return self._err(400, "bad time")
        now = int(time.time())
        if not P.catchup_supports(ch, start, now):
            return self._err(400, "no catchup")
        if self._lock_pred()(ch) and not self._unlocked():
            return self._locked_answer()
        _, fmt = P.choose_format(P.catchup_url(ch, start, end, now), self._want_fmt())
        return self._json({"url": self._signed("/stream", {"key": key, "start": start, "end": end,
                                                           "fmt": fmt if self._want_fmt() else None}, LIVE_TTL),
                           "fmt": fmt})

    def r_alternates(self):
        """Other entries of the same channel (same tvg-id or name, any enabled playlist, not
        hidden): the player tries them when a live channel stays down after its reconnects."""
        key = self.q.get("key", [""])[0]
        if not APP.sources.channel(key):
            return self._err(404, "channel")
        L = APP.layout
        hidden = self._hidden_groups()
        by = APP.sources.channels_by_key
        lp = (lambda c: False) if self._unlocked() else self._lock_pred()

        def usable(k):   # a locked channel is no stand-in for an open one
            return k in by and not L.channel_hidden(k) and (by[k].source_id, by[k].group) not in hidden and not lp(by[k])
        keys = APP.sources.alt_index().alternates(key, usable)
        return self._json({"items": [L.channel(by[k].to_json()) for k in keys]})

    def r_play(self):
        """Signed stream URL for live TV or VOD:
        /api/play?kind=live&key=..[&fmt=hls|ts] | kind=movie|episode&id=..&ext=..
        fmt=hls asks for an HLS playlist (browsers without Media Source Extensions, i.e. every
        iPhone browser); the answer names the format actually served."""
        kind = self.q.get("kind", ["live"])[0]
        if kind == "live":
            key = self.q.get("key", [""])[0]
            if not key:
                return self._err(400, "key")
            ch = APP.sources.channel(key)
            if not ch:
                return self._err(404, "channel")
            if self._lock_pred()(ch) and not self._unlocked():
                return self._locked_answer()
            want = self._want_fmt()
            _, fmt = P.choose_format(ch.url, want)
            return self._json({"url": self._signed("/stream", {"key": key, "fmt": fmt if want else None}, LIVE_TTL),
                               "fmt": fmt})
        if kind in ("movie", "episode"):
            vid = self.q.get("id", [""])[0]
            ext = self.q.get("ext", ["mp4"])[0]
            if not vid or not ext.isalnum():
                return self._err(400, "id")
            if kind == "movie" and APP.parental.active() and not self._unlocked():
                it = self._vod_item("movie", vid)
                if it is not None and self._vod_lock_pred("movie")(it):
                    return self._locked_answer()
            if kind == "episode" and APP.parental.active() and not self._unlocked() and self._episode_locked(vid):
                return self._locked_answer()
            return self._vod_play(kind, vid, ext)
        if kind == "rec":
            return self._rec_play(self.q.get("id", [""])[0])
        return self._err(400, "kind")

    def _episode_locked(self, vid: str) -> bool:
        """An episode of a show in a locked category (L8). The player names the show (series=);
        the episode must be in that show's episode list. A show that cannot be checked counts as
        locked while film / show categories are locked."""
        if not APP.parental.data["vodCats"]:
            return False
        sid = self.q.get("series", [""])[0][:100]
        it = self._vod_item("series", sid) if sid else None
        if it is None:
            return True
        try:
            eps = series_info_json(sid).get("episodes") or []
        except Exception:                           # noqa: BLE001
            eps = []
        if not any(str(e.get("id")) == vid for e in eps):
            return True
        return self._vod_lock_pred("series")(it)

    def _rec_play(self, rid: str):
        """A recording: the MPEG-TS as recorded (HLS of byte ranges) for every client that
        plays it; an HEVC recording on iPhone / iPad (or any client after ``remux=1``) is
        remuxed from disk to fMP4 HLS on playback, so recording itself stays a plain copy."""
        x = APP.recorder.get(rid) if __import__("re").match(r"^[0-9a-f]{12}$", rid or "") else None
        if not x:
            return self._err(404, "recording")
        f = APP.recorder.file(rid)
        if not os.path.exists(f):
            return self._err(404, "recording file")
        as_file = {"url": self._signed(f"/rec/{rid}.m3u8", {}), "fmt": "file", "file": self._signed(f"/rec/{rid}.ts", {})}
        client = self._client()
        force = self.q.get("remux", ["0"])[0] == "1"
        if not APP.remux.available() or not (client["ios"] or force):
            return self._json(as_file)
        growing = x["status"] == "recording"
        try:                                         # a growing file is looked at again every 20 s
            probe = APP.remux.probe(f"rec:{rid}" + (f":{int(time.time() // 20)}" if growing else ""), f, local=True)
        except Exception:                           # noqa: BLE001
            return self._json(as_file)
        info = R.codec_summary(probe) or {}
        if R.rec_mode(client, info.get("v", ""), force) == "file":
            return self._json(as_file)
        sid = self.q.get("sid", [""])[0][:64]
        if not sid:
            return self._err(400, "sid")
        try:
            a = int(self.q.get("a", [""])[0])
        except ValueError:
            a = None
        p = R.plan(probe, client, a)
        if not p["ok"]:
            return self._json({"fmt": "external", "why": "This recording cannot be played in the browser: " + p["why"] + ".",
                               "file": as_file["file"]})
        p["follow"] = growing
        ss = self._start_secs(p)
        job = APP.remux.start(sid, f"rec:{rid}", f, p, ss, local=True)
        return self._remux_answer(job, p, ss, as_file["file"])

    def _start_secs(self, p: dict) -> float:
        try:
            ss = max(0.0, float(self.q.get("ss", ["0"])[0]))
        except ValueError:
            ss = 0.0
        if p["duration"] and ss > p["duration"] - 10:
            ss = max(0.0, p["duration"] - 10)
        return ss

    def _remux_answer(self, job, p: dict, ss: float, file_url: str):
        base = f"/remux/{job.rid}/"
        return self._json({"url": base + "index.m3u8", "fmt": "hls", "file": file_url, "remux": {
            "ss": ss, "duration": p["duration"], "info": base + "info.json",
            "audio": [{"label": x["label"], "codec": x["codec"], "channels": x["channels"], "lang": x["lang"]} for x in p["audio"]],
            "audioPos": p["a"]["pos"] if p["a"] else None,
            "subs": [{"label": s["label"], "lang": s["lang"], "url": base + f"sub{n}.vtt"} for n, s in enumerate(p["subs"])],
            "notes": p["notes"]}})

    def _client(self) -> dict:
        caps = set(self.q.get("caps", [""])[0].split(","))
        return {"ios": self.q.get("client", [""])[0] == "ios", "hevc": "hevc" in caps, "ac3": "ac3" in caps,
                "eac3": "eac3" in caps, "av1": "av1" in caps}

    def _vod_play(self, kind: str, vid: str, ext: str):
        """A movie / episode: the provider's file as it is when this browser plays its container,
        else an on-the-fly remux to HLS fMP4 (``remux=1`` forces it, e.g. after the browser
        refused the file; ``ss`` = start time, ``a`` = audio track position)."""
        file_url = self._signed("/vodstream", {"kind": kind, "id": vid, "ext": ext})
        client = self._client()
        force = self.q.get("remux", ["0"])[0] == "1"
        if R.vod_mode(ext, client, force) == "file" or not APP.remux.available():
            return self._json({"url": file_url, "fmt": "file", "file": file_url})
        sid = self.q.get("sid", [""])[0][:64]
        if not sid:
            return self._err(400, "sid")
        if APP.streams.touch(sid) is None:
            return self._json({"stopped": APP.streams.why(sid)}, 409)
        url = APP.sources.for_vid(vid).vod_url(kind, vid, ext)
        if not url:
            return self._err(401, "not signed in")
        key = f"{kind}:{vid}"
        try:
            probe = APP.remux.probe(key, url)
        except Exception as e:                      # noqa: BLE001
            return self._json({"fmt": "external", "why": _safe_err(e).split(": ", 1)[-1], "file": file_url})
        try:
            a = int(self.q.get("a", [""])[0])
        except ValueError:
            a = None
        p = R.plan(probe, client, a)
        if not p["ok"]:
            return self._json({"fmt": "external", "why": "This file cannot be played in the browser: " + p["why"] + ".",
                               "file": file_url})
        ss = self._start_secs(p)
        try:
            job = APP.remux.start(sid, key, url, p, ss)
        except Exception as e:                      # noqa: BLE001
            return self._json({"fmt": "external", "why": _safe_err(e).split(": ", 1)[-1], "file": file_url})
        return self._remux_answer(job, p, ss, file_url)

    def r_remux(self, path: str):
        parts = path.split("/")
        if len(parts) != 4 or not R.NAME_RE.match(parts[3]):
            return self._err(404, "not found")
        job = APP.remux.get(parts[2])
        if job is None:
            return self._err(410, "remux ended")
        if not job.local and self._stream_gate() is None:       # a recording uses no provider stream
            return
        name = parts[3]
        f = os.path.join(job.dir, "s00000.m4s" if name == "info.json" else name)
        seg = R.seg_number(name)
        APP.remux.seen(job, seg)
        gone = job.mode == "live" and seg is not None and seg < job.produced()   # left the live window
        if not name.endswith(".vtt") and not gone:
            deadline = time.time() + (25 if name == "index.m3u8" else 30)
            while not os.path.exists(f) and job.running() and time.time() < deadline:
                time.sleep(0.2)
                if seg is not None:
                    APP.remux.seen(job, seg)
        if name == "info.json":
            return self._json({"start": job.start_time(), "ss": job.ss})
        if name == "index.m3u8":
            try:
                with open(f, encoding="utf-8") as fh:
                    text = fh.read()
            except OSError:
                text = ""
            if not text or (job.failed() and "#EXT-X-ENDLIST" not in text):
                return self._err(502, "remux failed: " + (job.err or "no output")[:120])
            sid = self.q.get("sid", [""])[0][:64]
            return self._send_bytes(R.serve_playlist(text, urllib.parse.quote(sid)).encode(), "application/vnd.apple.mpegurl")
        if name.endswith(".vtt"):
            try:
                with open(f, "rb") as fh:
                    data = fh.read()
            except OSError:
                data = b"WEBVTT\n\n"
            return self._send_bytes(data, "text/vtt; charset=utf-8")
        try:
            fh = open(f, "rb")
        except OSError:
            return self._err(404 if job.running() or seg is not None else 502, "segment")
        with fh:
            size = os.fstat(fh.fileno()).st_size
            self.send_response(200)
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Content-Length", str(size))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            try:
                while True:
                    chunk = fh.read(256 * 1024)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass

    def r_rec(self, path: str):
        """A recording: /rec/<id>.m3u8 (HLS of byte ranges, growing while it records) and
        /rec/<id>.ts (the file, with Range). Served from disk: no provider stream is used."""
        m = __import__("re").match(r"^/rec/([0-9a-f]{12})\.(m3u8|ts)$", path)
        x = APP.recorder.get(m.group(1)) if m else None
        if not x:
            return self._err(404, "recording")
        rid = x["id"]
        f = APP.recorder.file(rid)
        try:
            size = os.path.getsize(f)
        except OSError:
            return self._err(404, "recording file")
        if m.group(2) == "m3u8":
            cuts, end = read_index(APP.recorder.idx(rid))
            body = recording_playlist(self._signed(f"/rec/{rid}.ts", {}), cuts, end, size, x["status"] != "recording")
            return self._send_bytes(body.encode(), "application/vnd.apple.mpegurl")
        start, stop = 0, size - 1
        rng = self.headers.get("Range", "")
        mr = __import__("re").match(r"bytes=(\d*)-(\d*)$", rng.strip())
        if mr and (mr.group(1) or mr.group(2)):
            if mr.group(1):
                start = int(mr.group(1))
                stop = min(size - 1, int(mr.group(2))) if mr.group(2) else size - 1
            else:
                start = max(0, size - int(mr.group(2)))
            if start > stop or start >= size:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{stop}/{size}")
        else:
            self.send_response(200)
        n = stop - start + 1
        self.send_header("Content-Type", "video/mp2t")
        self.send_header("Content-Length", str(n))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            with open(f, "rb") as fh:
                fh.seek(start)
                while n > 0:
                    chunk = fh.read(min(256 * 1024, n))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    n -= len(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _send_bytes(self, body: bytes, ctype: str):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _upstream_url_for_channel(self):
        key = self.q.get("key", [""])[0]
        ch = APP.sources.channel(key)
        if not ch:
            return None, None
        start = self.q.get("start", [None])[0]
        if start is not None:
            now = int(time.time())
            end = int(self.q.get("end", [str(now)])[0])
            return ch, P.choose_format(P.catchup_url(ch, int(start), int(end), now), self._want_fmt())[0]
        return ch, P.choose_format(ch.url, self._want_fmt())[0]

    def r_stream(self):
        g = self._stream_gate()
        if g is None:
            return
        ch, url = self._upstream_url_for_channel()
        if not url:
            return self._err(404, "stream")
        sid = self.q.get("sid", [""])[0][:64]
        if self._want_fmt() == "hls" and sid and APP.remux.available():
            if self._hevc_hls(ch, sid):
                return
        elif sid:
            APP.remux.stop_sid(sid)                  # this tab moved on from a remux
        if self._want_fmt() == "hls" and self.q.get("start", [None])[0] is not None:
            return self._catchup_hls(ch, url)
        self._relay_media(url, gate=g)

    def _channel_codecs(self, ch) -> dict | None:
        """The channel's codecs (cached; one quick ffprobe of the live stream when unknown)."""
        known, info = APP.codecs.get(ch.key)
        if not known:
            info = APP.remux.probe_live(P.choose_format(ch.url, "ts")[0])
            APP.codecs.put(ch.key, info)
            LOG.info("codecs of %s: %s", ch.key, info)
        return info

    def _hevc_hls(self, ch, sid: str) -> bool:
        """A client without MSE (iPhone / iPad) asking for HLS: an HEVC channel (or catch-up of
        one) is remuxed live to fMP4 HLS, since Apple players take HEVC only from fMP4. Returns
        False for every other channel (relayed as before, at no cost)."""
        start = self.q.get("start", [None])[0]
        if start is not None:
            now = int(time.time())
            try:
                start_i, end_i = int(start), int(self.q.get("end", [str(now)])[0])
            except ValueError:
                return False
            jkey, mode = f"cu:{ch.key}:{start_i}:{end_i}", "event"
        else:
            jkey, mode = f"live:{ch.key}", "live"
        job = APP.remux.find(sid, jkey)
        if job is not None and not job.failed():
            return self._serve_stream_playlist(job, ch)           # the player reloads the live playlist
        if job is not None:                                         # it failed: look at the channel again
            APP.remux.stop_job(job)
            APP.codecs.drop(ch.key)
        APP.remux.stop_sid(sid)                  # frees the provider connection before the probe
        info = self._channel_codecs(ch)
        if R.live_mode("hls", info) != "remux":
            return False
        if mode == "event":
            src = P.catchup_url(ch, start_i, end_i, int(time.time()))
            if not src:
                return False
        else:
            src = P.choose_format(ch.url, "ts")[0]
        try:
            job = APP.remux.start_stream(sid, jkey, src, info, mode)
        except Exception:                           # noqa: BLE001  (e.g. a refused address)
            return False
        return self._serve_stream_playlist(job, ch)

    def _serve_stream_playlist(self, job, ch) -> bool:
        """The remux's playlist, as soon as its first segment exists (ffmpeg writes the
        playlist then), with absolute /remux/<id>/ URIs (it is served from /stream)."""
        f = os.path.join(job.dir, "index.m3u8")
        deadline = time.time() + 20
        while not os.path.exists(f) and job.running() and time.time() < deadline:
            time.sleep(0.1)
        APP.remux.seen(job)
        try:
            with open(f, encoding="utf-8") as fh:
                text = fh.read()
        except OSError:
            text = ""
        if "#EXTINF" not in text:
            err = job.err or ("no output" if job.running() else "ended")
            APP.remux.stop_job(job)
            APP.codecs.drop(ch.key)                 # look at the channel again next time
            self._err(502, "remux failed: " + err[:120])
            return True
        sid = urllib.parse.quote(self.q.get("sid", [""])[0][:64])
        text, job.target = R.steady_target(text, job.target)
        body = R.serve_playlist(text, sid, prefix=f"/remux/{job.rid}/", live=job.mode == "live").encode()
        self._send_bytes(body, "application/vnd.apple.mpegurl")
        return True

    def _catchup_hls(self, ch, url):
        """Catch-up for a client that needs HLS. The provider's own timeshift playlist is used
        where it exists; many panels serve timeshift only as one seekable .ts file, which is
        then offered as byte ranges of the same (signed, relayed) URL: no transcoding, no copy."""
        now = int(time.time())
        start = int(self.q.get("start", ["0"])[0])
        end = int(self.q.get("end", [str(now)])[0])
        ts_url = P.catchup_url(ch, start, end, now)
        host = urllib.parse.urlparse(url).netloc
        if url != ts_url and time.time() - APP.no_timeshift_hls.get(host, 0) > 3600:
            try:
                with NET.open_url(url, timeout=15) as up:
                    data = up.read(4 * 1024 * 1024)
                    if b"#EXTM3U" in data[:64]:
                        return self._rewrite_hls(data, up.geturl() or url)
            except Exception:                       # noqa: BLE001
                pass
            APP.no_timeshift_hls[host] = time.time()     # do not ask again for an hour
        if url == ts_url and url.split("?", 1)[0].lower().endswith(".m3u8"):
            return self._relay_media(url)                # the source itself is a playlist
        total = phase = 0
        try:
            with NET.open_url(ts_url, timeout=20, headers={"Range": "bytes=0-1503"}) as up:
                cr = up.headers.get("Content-Range", "")
                if up.getcode() == 206 and "/" in cr and cr.rsplit("/", 1)[1].isdigit():
                    total = int(cr.rsplit("/", 1)[1])
                    phase = P.ts_phase(up.read(1504))
        except Exception:                           # noqa: BLE001
            pass
        if total < 188 * 1000:
            return self._err(502, "catch-up has no HLS form")
        seg = self._signed("/stream", {"key": ch.key, "start": start, "end": end, "fmt": "ts"}, LIVE_TTL)
        sid = self.q.get("sid", [""])[0][:64]
        if sid:
            seg += "&sid=" + urllib.parse.quote(sid)
        body = P.byterange_playlist(seg, total, max(end - start, 60), first=phase).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.apple.mpegurl")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def r_vodstream(self):
        kind = self.q.get("kind", ["movie"])[0]
        vid = self.q.get("id", [""])[0]
        ext = self.q.get("ext", ["mp4"])[0]
        url = APP.sources.for_vid(vid).vod_url(kind, vid, ext)
        if not url:
            return self._err(401, "not signed in")
        g = self._stream_gate()
        if g is None:
            return
        if g[0]:
            APP.remux.stop_sid(g[0])                 # this tab moved on from a remux
        self._relay_media(url, gate=g)

    def r_seg(self):
        url = APP.unseal(self.q.get("e", [""])[0])
        if not url:
            return self._err(403, "bad signature")
        # Only the server can seal a URL (authenticated encryption), which it does while
        # rewriting a playlist, so the segment host is already trusted (no open relay).
        if not url.startswith(("http://", "https://")):
            return self._err(400, "scheme")
        g = self._stream_gate()
        if g is None:
            return
        self._relay_media(url, allow_hls=True, gate=g)

    def _relay_media(self, url: str, allow_hls=True, gate=None):
        rng = self.headers.get("Range")
        try:
            up = NET.open_url(url, timeout=30, headers={"Range": rng} if rng else None)
        except urllib.error.HTTPError as e:
            return self._err(e.code if 400 <= e.code < 600 else 502, "upstream")
        except Exception as e:                      # noqa: BLE001
            if NET.is_blocked(e):
                return self._err(403, "source address not allowed")
            return self._err(502, "upstream")
        ctype = _media_ctype(up.headers.get("Content-Type"))
        is_hls = allow_hls and ("mpegurl" in ctype.lower() or url.lower().split("?")[0].endswith(".m3u8"))
        if is_hls:
            data = up.read(8 * 1024 * 1024)
            if data[:7] == b"#EXTM3U" or b"#EXTM3U" in data[:64]:
                return self._rewrite_hls(data, up.geturl() or url)   # after redirects
            # not actually a playlist; fall through as bytes
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        code = up.getcode() or 200
        self.send_response(code)
        self.send_header("Content-Type", ctype if up.headers.get("Content-Type") else "video/mp2t")
        for h in ("Content-Length", "Content-Range", "Accept-Ranges"):
            v = up.headers.get(h)
            if v:
                self.send_header(h, v)
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            n = 0
            while True:
                chunk = up.read(64 * 1024)
                if not chunk:
                    break
                n += 1
                if gate and n % 8 == 0 and not APP.streams.alive(*gate):
                    break      # this tab's stream was replaced by a newer one
                self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            up.close()

    def _rewrite_hls(self, data: bytes, base_url: str):
        base = base_url.rsplit("/", 1)[0] + "/"
        out_lines = []
        for line in data.decode("utf-8", "replace").splitlines():
            s = line.strip()
            if not s:
                out_lines.append(line)
                continue
            if s.startswith("#"):
                # proxy URI="..." inside tags (keys, maps, media playlists)
                if 'URI="' in s:
                    def repl(m):
                        abs_u = urllib.parse.urljoin(base, m.group(1))
                        return 'URI="' + self._signed_seg(abs_u) + '"'
                    s = __import__("re").sub(r'URI="([^"]+)"', repl, s)
                out_lines.append(s)
            else:
                abs_u = urllib.parse.urljoin(base, s)
                out_lines.append(self._signed_seg(abs_u))
        body = ("\n".join(out_lines) + "\n").encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.apple.mpegurl")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def r_img(self):
        """A logo / poster the server named in a catalogue answer (signed: see img_url()), fetched
        through net.py, served only as a sniffed JPEG / PNG / GIF / WebP (never the upstream
        Content-Type), cached on disk with a size cap."""
        u = self.q.get("u", [""])[0]
        sig = self.q.get("s", [""])[0]
        if not u or not sig or not hmac.compare_digest(sig, APP.sign("img:" + u)):
            return self._err(403, "bad signature")
        data = APP.images.get(u)
        if data is None:
            try:
                with NET.open_url(u, timeout=20, headers={"Accept": "image/*"}) as r:
                    data = r.read(MAX_IMG_BYTES + 1)
            except Exception as e:                  # noqa: BLE001
                return self._err(403 if NET.is_blocked(e) else 404, "image")
            if len(data) > MAX_IMG_BYTES:
                return self._err(413, "image too large")
            if _img_ctype(data) is None:
                return self._err(415, "not an image")
            APP.images.put(u, data)
        ctype = _img_ctype(data)
        if ctype is None:
            return self._err(415, "not an image")
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Content-Disposition", "inline")
        self.send_header("Content-Security-Policy", "sandbox; default-src 'none'")
        self.send_header("Cache-Control", "private, max-age=604800")
        self.end_headers()
        self.wfile.write(data)


MAX_IMG_BYTES = 5 * 1024 * 1024
IMG_KEYS = ("logo", "poster", "image", "backdrop", "cover")
MEDIA_CTYPES = ("video/", "audio/", "application/vnd.apple.mpegurl", "application/x-mpegurl",
                "application/octet-stream", "binary/octet-stream", "application/mp4")


def img_url(u: str) -> str:
    return "/img?" + urllib.parse.urlencode({"u": u, "s": APP.sign("img:" + u)})


def sign_images(obj):
    """Catalogue answers name images by their provider URL; the browser gets them as signed
    /img URLs instead (so /img fetches only what the server itself handed out)."""
    # a copy: the objects passed in are often cached or stored server-side
    if isinstance(obj, dict):
        return {k: (img_url(v) if isinstance(v, str) and v.startswith(("http://", "https://")) else v)
                if k in IMG_KEYS else (sign_images(v) if isinstance(v, (dict, list)) else v)
                for k, v in obj.items()}
    if isinstance(obj, list):
        return [sign_images(v) if isinstance(v, (dict, list)) else v for v in obj]
    return obj


def _media_ctype(ct) -> str:
    """The upstream Content-Type of a relayed stream when it is a media type, else
    application/octet-stream (a provider must not get HTML served from this origin)."""
    c = (ct or "").split(";", 1)[0].strip().lower()
    return c if c and c.startswith(MEDIA_CTYPES) else "application/octet-stream"


class ImageCache:
    """Logos / posters on disk (imgcache/), oldest dropped when the folder is over its cap."""

    def __init__(self, path: str, cap_bytes: int = 300 * 1024 * 1024):
        self.path, self.cap = path, cap_bytes
        os.makedirs(path, exist_ok=True)
        self._lock = threading.Lock()
        self._writes = 0

    def _file(self, u: str) -> str:
        return os.path.join(self.path, sha256(u.encode()).hexdigest())

    def get(self, u: str):
        f = self._file(u)
        try:
            with open(f, "rb") as fh:
                data = fh.read(MAX_IMG_BYTES + 1)
            os.utime(f)                                 # LRU by mtime
        except OSError:
            return None
        return data if len(data) <= MAX_IMG_BYTES else None

    def put(self, u: str, data: bytes):
        f = self._file(u)
        tmp = f + ".tmp"
        try:
            with open(tmp, "wb") as fh:
                fh.write(data)
            os.replace(tmp, f)
        except OSError:
            return
        with self._lock:
            self._writes += 1
            if self._writes % 25 == 1:
                self.prune()

    def prune(self):
        try:
            files = [(e.stat().st_mtime, e.stat().st_size, e.path) for e in os.scandir(self.path) if e.is_file()]
        except OSError:
            return
        total = sum(s for _, s, _ in files)
        for _, size, p in sorted(files):
            if total <= self.cap:
                break
            try:
                os.remove(p)
                total -= size
            except OSError:
                pass


def _img_ctype(data: bytes):
    if data[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if data[:6] in (b"GIF87a", b"GIF89a"):
        return "image/gif"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    if data[4:12] in (b"ftypavif", b"ftypavis"):
        return "image/avif"
    if data[:4] == b"\x00\x00\x01\x00":
        return "image/x-icon"
    if data[:2] == b"BM" and len(data) > 26:
        return "image/bmp"
    return None                      # SVG, HTML and anything else: refused


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True
    max_threads = 256                    # connections handled at once; more are refused (M7)

    def __init__(self, *a, **k):
        super().__init__(*a, **k)
        self._slots = threading.BoundedSemaphore(self.max_threads)

    def process_request(self, request, client_address):
        if not self._slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self._slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._slots.release()


def main():
    global APP
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    os.makedirs(DATA_DIR, exist_ok=True)
    try:
        startup_check(DATA_DIR)
    except RuntimeError as e:
        LOG.error("%s", e)
        raise SystemExit(2)
    APP = App()
    srv = Server((BIND, PORT), Handler)

    def scheduler():
        while True:
            time.sleep(60)
            try:
                APP.sources.tick()
            except Exception:                       # noqa: BLE001
                LOG.exception("scheduler")
    threading.Thread(target=scheduler, daemon=True, name="scheduler").start()
    threading.Thread(target=APP.recorder.run_forever, daemon=True, name="recorder").start()
    # stored channels / VOD / guide are served at once; refresh the channel list in the
    # background, and build the search index now if it is missing or older than the data
    APP.sources.sync("channels")
    for p in APP.sources.enabled():
        if p.kind() == "xtream" and not p._vod_at:
            p.sync("vod")                   # first run: search needs the catalogue (stored from then on)
        if p.index_stale():
            p.reindex()
    LOG.info("tvBro web backend on %s:%d (data=%s)", BIND, PORT, DATA_DIR)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        srv.shutdown()


if __name__ == "__main__":
    main()
