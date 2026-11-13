# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Server-side recording: copy a channel's stream to disk, no transcoding.

- "Record now" from the player and scheduled recordings from the guide (programme start/end
  from the EPG plus padding). One thread per running recording, one small scheduler thread.
- The MPEG-TS bytes are written as they arrive (HLS sources: the segments are appended), and
  a keyframe index is kept beside the file (see tsindex) so the recording plays, seekable,
  as an HLS playlist of byte ranges — also while it is still being recorded.
- A recording holds a stream slot like a viewer does (it is a provider connection) and is
  never evicted by a viewer.
- It stops by itself at the end time, when free disk space falls under the floor, or when
  all recordings together reach the size cap. A dropped source is reconnected.
"""
from __future__ import annotations

import json
import logging
import os
import re
import secrets
import shutil
import threading
import time
import urllib.parse
from typing import Callable, Optional

from . import net as NET

from .tsindex import TsIndexer, index_line, read_index, recording_segments

LOG = logging.getLogger("tvbro")
UA = "tvBro-web/1.0"
CHUNK = 64 * 1024
MAX_HOURS = 8                   # one recording never runs longer than this
MAX_ITEMS = 500
REC_DEFAULTS = {"recMaxGB": 3, "recMinFreeMB": 1024, "recPadBeforeMin": 1, "recPadAfterMin": 5}
REC_ALLOWED = {"recMaxGB": (1, 2, 3, 5, 10, 20, 50, 100, 200), "recMinFreeMB": (512, 1024, 2048, 5120),
               "recPadBeforeMin": (0, 1, 2, 5), "recPadAfterMin": (0, 2, 5, 10, 15, 30)}
PUBLIC = ("id", "key", "channel", "logo", "title", "start", "stop", "progStart", "progStop", "status",
          "note", "size", "duration", "createdAt", "startedAt", "endedAt", "rule")


def plan_times(now: int, start: Optional[int], stop: Optional[int], pad_before_min: int, pad_after_min: int):
    """(record from, record until, starts now?) for a request. A programme that already began
    is recorded from now; padding goes around the programme's own start / end."""
    prog_start = int(start) if start else now
    prog_stop = int(stop) if stop else 0
    if prog_stop <= max(prog_start, now):
        prog_stop = max(prog_start, now) + 2 * 3600          # no end known: two hours
    rec_start = prog_start - pad_before_min * 60
    rec_stop = prog_stop + pad_after_min * 60
    if rec_start <= now:
        rec_start = now
    rec_stop = min(rec_stop, rec_start + MAX_HOURS * 3600)
    return rec_start, rec_stop, rec_start <= now


def space_problem(free: int, used: int, floor_bytes: int, cap_bytes: int) -> Optional[str]:
    """Why recording must not run / must stop, or None."""
    if free < floor_bytes:
        return "disk space floor reached"
    if used >= cap_bytes:
        return "recordings size cap reached"
    return None


class Recorder:
    def __init__(self, data_dir: str, source: Callable[[str], Optional[dict]], streams,
                 settings: Callable[[], dict], clock: Callable[[], float] = time.time):
        """source(key) -> {"name", "logo", "url"} (the upstream URL never leaves the server)."""
        self.default_dir = self.dir = os.path.join(data_dir, "recordings")
        os.makedirs(self.dir, exist_ok=True)
        self.path = os.path.join(data_dir, "recordings.json")
        self.rules_path = os.path.join(data_dir, "series_rules.json")
        self.source, self.streams, self.settings, self.clock = source, streams, settings, clock
        self._lock = threading.RLock()
        self._wake = threading.Event()
        self._stop_flags: dict[str, threading.Event] = {}
        self.items: list[dict] = []
        self.rules: list[dict] = []
        # find_progs(key, frm, to) -> [(start, stop, title)] from the guide; set by the app
        self.find_progs: Optional[Callable] = None
        self._rules_at = 0.0
        try:
            with open(self.path, encoding="utf-8") as f:
                self.items = [x for x in json.load(f) if isinstance(x, dict) and x.get("id")]
        except (OSError, ValueError):
            pass
        try:
            with open(self.rules_path, encoding="utf-8") as f:
                self.rules = [x for x in json.load(f) if isinstance(x, dict) and x.get("id")]
        except (OSError, ValueError):
            pass

    # ---- files / persistence ----
    def set_dir(self, path: Optional[str]):
        """Where new recordings go (existing ones stay where they were written)."""
        self.dir = path or self.default_dir

    def _dir_of(self, rid: str) -> str:
        x = self.get(rid)
        return (x.get("dir") if x else None) or self.default_dir

    def file(self, rid: str) -> str:
        return os.path.join(self._dir_of(rid), rid + ".ts")

    def idx(self, rid: str) -> str:
        return os.path.join(self._dir_of(rid), rid + ".idx")

    def _save(self):
        with self._lock:
            tmp = self.path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(self.items, f)
            os.replace(tmp, self.path)

    # ---- series rules: keep recording a programme title on a channel ----
    def _save_rules(self):
        with self._lock:
            tmp = self.rules_path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(self.rules, f)
            os.replace(tmp, self.rules_path)

    def add_rule(self, key: str, title: str) -> dict:
        title = (title or "").strip()[:200]
        src = self.source(key)
        if not src or not title:
            raise ValueError("channel and programme title needed")
        with self._lock:
            for r in self.rules:
                if r["key"] == key and r["title"].lower() == title.lower():
                    return r
            if len(self.rules) >= 200:
                raise ValueError("too many series rules")
            r = {"id": secrets.token_hex(6), "key": key, "title": title, "channel": src.get("name") or "",
                 "logo": src.get("logo"), "createdAt": int(self.clock())}
            self.rules.append(r)
            self._save_rules()
        self.apply_rules()
        return r

    def delete_rule(self, rule_id: str, cancel_scheduled: bool = True) -> bool:
        with self._lock:
            r = next((x for x in self.rules if x["id"] == rule_id), None)
            if not r:
                return False
            self.rules.remove(r)
            self._save_rules()
            if cancel_scheduled:
                self.items = [x for x in self.items if not (x.get("rule") == rule_id and x["status"] == "scheduled")]
                self._save()
        return True

    def apply_rules(self, horizon: int = 8 * 86400) -> int:
        """Schedule upcoming airings of every rule's title from the guide; returns how many."""
        if not self.find_progs or not self.rules:
            return 0
        now = int(self.clock())
        self._rules_at = now
        added = 0
        for r in list(self.rules):
            try:
                progs = self.find_progs(r["key"], now, now + horizon)
            except Exception:                           # noqa: BLE001
                continue
            for start, stop, title in progs:
                if stop <= now or title.strip().lower() != r["title"].lower():
                    continue
                with self._lock:
                    seen = any(x["key"] == r["key"] and x.get("progStart") == start for x in self.items)
                if seen:
                    continue
                try:
                    item = self.add(r["key"], title, start, stop)
                except ValueError:
                    continue
                with self._lock:
                    x = self.get(item["id"])
                    if x:
                        x["rule"] = r["id"]
                    self._save()
                added += 1
        return added

    def get(self, rid: str) -> Optional[dict]:
        with self._lock:
            return next((x for x in self.items if x["id"] == rid), None)

    def used_bytes(self) -> int:
        with self._lock:
            return sum(int(x.get("size") or 0) for x in self.items)

    def limits(self) -> tuple[int, int]:
        s = self.settings()
        return (int(s.get("recMinFreeMB", REC_DEFAULTS["recMinFreeMB"])) << 20,
                int(s.get("recMaxGB", REC_DEFAULTS["recMaxGB"])) << 30)

    def disk(self) -> dict:
        try:
            du = shutil.disk_usage(self.dir)
            free, total = du.free, du.total
        except OSError:
            free = total = 0
        floor, cap = self.limits()
        return {"free": free, "total": total, "used": self.used_bytes(), "capBytes": cap, "floorBytes": floor}

    def _space_problem(self) -> Optional[str]:
        d = self.disk()
        return space_problem(d["free"], d["used"], d["floorBytes"], d["capBytes"])

    def list(self) -> list[dict]:
        with self._lock:
            out = [{k: x.get(k) for k in PUBLIC} for x in self.items]
        out.sort(key=lambda x: (0 if x["status"] == "recording" else 1 if x["status"] == "scheduled" else 2,
                                x["start"] if x["status"] == "scheduled" else -(x["start"] or 0)))
        return out

    # ---- requests ----
    def add(self, key: str, title: str, start: Optional[int], stop: Optional[int]) -> dict:
        """Schedule (or start right away). Raises ValueError with a message for the viewer."""
        src = self.source(key)
        if not src:
            raise ValueError("channel not found")
        now = int(self.clock())
        s = self.settings()
        rec_start, rec_stop, _ = plan_times(now, start, stop, int(s.get("recPadBeforeMin", 1)), int(s.get("recPadAfterMin", 5)))
        if rec_stop <= now:
            raise ValueError("this programme is already over")
        prob = self._space_problem()
        if prob:
            raise ValueError(prob)
        with self._lock:
            for x in self.items:
                if x["key"] == key and x["status"] in ("scheduled", "recording") and x["start"] < rec_stop and x["stop"] > rec_start:
                    raise ValueError("already recording this" if x["status"] == "recording" else "already scheduled")
            if len(self.items) >= MAX_ITEMS:
                raise ValueError("too many recordings: delete some first")
            item = {"id": secrets.token_hex(6), "key": key, "channel": src.get("name") or "", "logo": src.get("logo"),
                    "title": (title or src.get("name") or "Recording")[:200], "start": rec_start, "stop": rec_stop,
                    "progStart": int(start) if start else now, "progStop": int(stop) if stop else rec_stop,
                    "status": "scheduled", "note": "", "size": 0, "duration": 0,
                    "createdAt": now, "startedAt": None, "endedAt": None}
            if self.dir != self.default_dir:
                item["dir"] = self.dir
            self.items.append(item)
            self._save()
        self._wake.set()
        return {k: item.get(k) for k in PUBLIC}

    def stop(self, rid: str) -> bool:
        """Stop a running recording (it is kept) or cancel a scheduled one (it is removed)."""
        with self._lock:
            x = self.get(rid)
            if not x:
                return False
            if x["status"] == "scheduled":
                self.items.remove(x)
                self._save()
                return True
            ev = self._stop_flags.get(rid)
        if ev:
            ev.set()
        return True

    def delete(self, rid: str) -> bool:
        with self._lock:
            x = self.get(rid)
            if not x:
                return False
            ev = self._stop_flags.get(rid)
            x["deleted"] = True
            paths = (self.file(rid), self.idx(rid))
        if ev:
            ev.set()
            for _ in range(50):                      # let the writer close the file
                if rid not in self._stop_flags:
                    break
                time.sleep(0.1)
        with self._lock:
            if x in self.items:
                self.items.remove(x)
            self._save()
        for p in paths:
            try:
                os.remove(p)
            except OSError:
                pass
        return True

    # ---- scheduler ----
    def run_forever(self):
        self._recover()
        while True:
            try:
                wait = self.tick()
            except Exception:                           # noqa: BLE001
                LOG.exception("recorder tick")
                wait = 30
            self._wake.wait(wait)
            self._wake.clear()

    def _recover(self):
        """After a restart: continue recordings that should still be running, close the rest."""
        now = int(self.clock())
        with self._lock:
            for x in self.items:
                if x["status"] == "recording":
                    if x["stop"] > now + 20:
                        x["status"], x["note"] = "scheduled", "continued after a server restart"
                        x["resume"] = True
                    else:
                        self._finish(x, "done", "ended by a server restart")
            self._save()

    def tick(self) -> float:
        """Start what is due. Returns seconds until the next look."""
        now = int(self.clock())
        nxt = 60.0
        if self.rules and now - self._rules_at >= 600:      # series rules: every 10 minutes
            try:
                self.apply_rules()
            except Exception:                           # noqa: BLE001
                LOG.exception("series rules")
        with self._lock:
            due = []
            for x in self.items:
                if x["status"] != "scheduled":
                    continue
                if x["stop"] <= now:
                    x["status"], x["note"], x["endedAt"] = "failed", "missed: the server was not running", now
                elif x["start"] <= now:
                    due.append(x)
                else:
                    nxt = min(nxt, x["start"] - now)
            for x in due:
                x["status"], x["startedAt"] = "recording", x.get("startedAt") or now
                ev = self._stop_flags[x["id"]] = threading.Event()
                threading.Thread(target=self._run, args=(x, ev), daemon=True, name="rec-" + x["id"]).start()
            if due or nxt < 60:
                self._save()
        return max(1.0, min(60.0, nxt))

    # ---- one recording ----
    def _finish(self, x: dict, status: str, note: str = ""):
        cuts, end = read_index(self.idx(x["id"]))
        try:
            x["size"] = os.path.getsize(self.file(x["id"]))
        except OSError:
            x["size"] = 0
        x["duration"] = round(sum(s[2] for s in recording_segments(cuts, end, x["size"], True)))
        if not x["size"] and status == "done":
            status, note = "failed", note or "nothing was received"
        x["status"], x["note"], x["endedAt"] = status, note, int(self.clock())
        x.pop("resume", None)

    def _run(self, x: dict, stop_ev: threading.Event):
        rid = x["id"]
        sid = "rec:" + rid
        try:
            os.setpriority(os.PRIO_PROCESS, threading.get_native_id(), 5)
        except (AttributeError, OSError):
            pass
        status, note = "done", ""
        try:
            if self.streams.start(sid, "Recording", x["title"], pinned=True) is None:
                raise RuntimeError("stream limit: other recordings use every stream")
            src = self.source(x["key"])
            if not src or not src.get("url"):
                raise RuntimeError("channel not found")
            note = self._copy(x, src["url"], sid, stop_ev) or ""
        except Exception as e:                          # noqa: BLE001
            status, note = "failed", re.sub(r"\w+://\S+", "<url>", f"{e}")[:160] or type(e).__name__
            LOG.warning("recording %s failed: %s", rid, note)
        finally:
            self.streams.stop(sid)
            with self._lock:
                if os.path.exists(self.file(rid)) and os.path.getsize(self.file(rid)) > 0 and status == "failed":
                    status = "done"                      # keep what was captured, with the reason
                self._finish(x, status, note)
                self._stop_flags.pop(rid, None)
                if not x.get("deleted"):
                    self._save()
            self._wake.set()

    def _copy(self, x: dict, url: str, sid: str, stop_ev: threading.Event) -> Optional[str]:
        """Copy until the end time. Returns a note when it ended for another reason."""
        rid = x["id"]
        path = self.file(rid)
        pos = os.path.getsize(path) if x.get("resume") and os.path.exists(path) else 0
        ix = TsIndexer(pos=pos)
        if pos:
            ix.discontinuity()
        gen = self.streams.touch(sid)
        state = {"last_check": 0.0, "written": 0, "fails": 0}
        hls = url.split("?", 1)[0].lower().endswith(".m3u8")
        with open(path, "ab" if pos else "wb") as out, open(self.idx(rid), "a" if pos else "w", encoding="ascii") as idx:

            def write(data: bytes) -> Optional[str]:
                """Returns a reason to stop, or None."""
                out.write(data)
                n = len(ix.cuts)
                if ix.feed(data):
                    idx.write("".join(index_line(c) for c in ix.cuts[n:]))
                state["written"] += len(data)
                state["fails"] = 0
                now = self.clock()
                if now - state["last_check"] < 2:
                    return None
                state["last_check"] = now
                out.flush()
                idx.flush()
                x["size"] = ix.pos
                if stop_ev.is_set():
                    return "stopped"
                if now >= x["stop"]:
                    return "end"
                if not self.streams.alive(sid, gen):
                    return "the stream slot was taken"
                return self._space_problem()

            why = None
            while why is None:
                if stop_ev.is_set():
                    why = "stopped"
                    break
                if self.clock() >= x["stop"]:
                    why = "end"
                    break
                try:
                    why = self._copy_hls(url, write, stop_ev, x) if hls else self._copy_ts(url, write)
                except Exception as e:                  # noqa: BLE001
                    LOG.info("recording %s: source dropped (%s)", rid, type(e).__name__)
                if why is None:                          # source ended / dropped: reconnect
                    state["fails"] += 1
                    if state["fails"] > 15:
                        why = "the source stopped sending"
                        break
                    ix.discontinuity()
                    self.streams.alive(sid, gen)
                    stop_ev.wait(min(20, 2 * state["fails"]))
            out.flush()
            if ix.last_vpts is not None and ix.cuts:
                idx.write(f"E {ix.pos} {ix.last_vpts + 0.04:.4f}\n")
            x["size"] = ix.pos
        return None if why == "end" else ("stopped by you" if why == "stopped" else why)

    def _copy_ts(self, url: str, write) -> Optional[str]:
        with NET.open_url(url, timeout=30) as up:
            ctype = (up.headers.get("Content-Type") or "").lower()
            if "mpegurl" in ctype:
                raise RuntimeError("unexpected playlist")
            while True:
                data = up.read(CHUNK)
                if not data:
                    return None
                why = write(data)
                if why:
                    return why

    def _copy_hls(self, url: str, write, stop_ev: threading.Event, x: dict) -> Optional[str]:
        """An HLS source: append each new media segment (MPEG-TS segments only)."""
        seen: list[str] = []
        idle = 0
        while True:
            text, base = _fetch_text(url)
            variant = _best_variant(text, base)
            if variant:
                url = variant
                continue
            if "#EXT-X-MAP" in text:
                raise RuntimeError("fMP4 HLS cannot be recorded")
            target = 6.0
            m = re.search(r"#EXT-X-TARGETDURATION:(\d+)", text)
            if m:
                target = float(m.group(1))
            segs = [urllib.parse.urljoin(base, ln.strip()) for ln in text.splitlines() if ln.strip() and not ln.startswith("#")]
            new = [s for s in segs if s not in seen]
            if not seen and len(new) > 3:
                new = new[-3:]                           # join a live playlist near its edge
                seen.extend(s for s in segs if s not in new)
            for s in new:
                seen.append(s)
                with NET.open_url(s, timeout=30) as up:
                    while True:
                        data = up.read(CHUNK)
                        if not data:
                            break
                        why = write(data)
                        if why and why != "end":
                            return why
                if self.clock() >= x["stop"]:
                    return "end"
            del seen[:-400]
            if "#EXT-X-ENDLIST" in text:
                return "end"
            idle = 0 if new else idle + 1
            if idle > 20:
                return None
            if stop_ev.wait(max(1.0, target / 2)):
                return "stopped"
            if self.clock() >= x["stop"]:
                return "end"


def _fetch_text(url: str) -> tuple[str, str]:
    with NET.open_url(url, timeout=20) as up:
        return up.read(2 * 1024 * 1024).decode("utf-8", "replace"), (up.geturl() or url)


def _best_variant(text: str, base: str) -> Optional[str]:
    """Master playlist -> the highest-bandwidth variant URL; None for a media playlist."""
    best, best_bw, lines = None, -1, text.splitlines()
    for i, ln in enumerate(lines):
        if ln.startswith("#EXT-X-STREAM-INF"):
            m = re.search(r"BANDWIDTH=(\d+)", ln)
            bw = int(m.group(1)) if m else 0
            nxt = next((x.strip() for x in lines[i + 1:] if x.strip() and not x.startswith("#")), None)
            if nxt and bw > best_bw:
                best, best_bw = urllib.parse.urljoin(base, nxt), bw
    return best
