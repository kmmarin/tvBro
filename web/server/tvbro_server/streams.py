# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Concurrent-stream limiter.

Each browser tab has a stream session id (sid). A tab's live preview, live TV, catch-up,
movies and episodes all use the same sid, so one tab = one provider stream. A session is
*active* while it fetched media (or sent a heartbeat) in the last ACTIVE_SEC seconds.
When a new stream starts and ``max_streams`` active sessions already exist, the OLDEST
active one is stopped: its open relay is closed and its further media requests get
409 {"stopped": "replaced"} until that tab starts a stream again ("Play here").
Pure logic with an injectable clock so it can be unit-tested.
"""
from __future__ import annotations

import threading
import time
from typing import Callable, Optional

ACTIVE_SEC = 20


class Session:
    __slots__ = ("sid", "label", "what", "started", "last_seen", "gen", "pinned")

    def __init__(self, sid: str, label: str, what: str, now: float):
        self.sid, self.label, self.what = sid, label, what
        self.started = now
        self.last_seen = now
        self.gen = 0          # bumped when the session is stopped; relays compare it
        self.pinned = False   # a server-side recording: never evicted by a viewer


class StreamRegistry:
    def __init__(self, max_streams: int = 1, clock: Callable[[], float] = time.time):
        self.max_streams = max(1, min(3, int(max_streams)))
        self.clock = clock
        self._lock = threading.Lock()
        self._s: dict[str, Session] = {}
        self._stopped: dict[str, float] = {}     # sid -> when it was replaced
        self._why: dict[str, str] = {}

    def set_max(self, n: int):
        with self._lock:
            self.max_streams = max(1, min(3, int(n)))

    def _active(self, now: float):
        return [s for s in self._s.values() if now - s.last_seen < ACTIVE_SEC]

    def start(self, sid: str, label: str = "", what: str = "", pinned: bool = False) -> Optional[list[str]]:
        """A tab starts (or takes back) a stream. Returns the sids that were stopped.

        ``pinned`` sessions are server-side recordings: a viewer never evicts them. When every
        slot is held by a recording, a viewer's start is refused: returns None, and that sid's
        media requests get 409 {"stopped": "recording"}."""
        now = self.clock()
        with self._lock:
            others = sorted((x for x in self._active(now) if x.sid != sid), key=lambda x: x.started)
            evictable = [x for x in others if not x.pinned]
            if len(others) + 1 - self.max_streams > len(evictable):
                old = self._s.pop(sid, None)
                if old is not None:
                    old.gen += 1
                self._stopped[sid] = now
                self._why[sid] = "recording"
                return None
            self._stopped.pop(sid, None)
            self._why.pop(sid, None)
            s = self._s.get(sid)
            if s is None:
                s = self._s[sid] = Session(sid, label, what, now)
            else:
                s.label = label or s.label
                s.what = what or s.what
                if now - s.last_seen >= ACTIVE_SEC:
                    s.started = now       # was idle: counts as a new stream
                s.last_seen = now
            s.pinned = pinned
            stopped = []
            while len(others) + 1 > self.max_streams and evictable:
                old = evictable.pop(0)
                others.remove(old)
                old.gen += 1
                self._stopped[old.sid] = now
                self._why[old.sid] = "recording" if pinned else "replaced"
                del self._s[old.sid]
                stopped.append(old.sid)
            self._gc(now)
            return stopped

    def why(self, sid: str) -> str:
        """Why sid was stopped: 'replaced' (a newer window) or 'recording' (a recording needs the slot)."""
        with self._lock:
            return self._why.get(sid, "replaced")

    def touch(self, sid: Optional[str]) -> Optional[int]:
        """A media request / heartbeat from sid. Returns the session generation to watch,
        or None if this sid was replaced (caller answers 409). Unknown sids start implicitly."""
        if not sid:
            return 0
        now = self.clock()
        with self._lock:
            if sid in self._stopped:
                return None
            s = self._s.get(sid)
        if s is None:
            if self.start(sid) is None:
                return None
            with self._lock:
                s = self._s.get(sid)
                return s.gen if s else None
        with self._lock:
            s.last_seen = now
            return s.gen

    def alive(self, sid: Optional[str], gen: int) -> bool:
        """For long relays: still allowed to stream?"""
        if not sid:
            return True
        with self._lock:
            s = self._s.get(sid)
            if s is None or s.gen != gen:
                return False
            s.last_seen = self.clock()
            return True

    def stop(self, sid: str):
        """The tab paused / closed: release its slot."""
        with self._lock:
            self._s.pop(sid, None)

    def is_stopped(self, sid: str) -> bool:
        with self._lock:
            return sid in self._stopped

    def _gc(self, now: float):
        for k in [k for k, t in self._stopped.items() if now - t > 6 * 3600]:
            del self._stopped[k]
            self._why.pop(k, None)
        for k in [k for k, s in self._s.items() if now - s.last_seen > 3600]:
            del self._s[k]

    def status(self) -> dict:
        now = self.clock()
        with self._lock:
            act = sorted(self._active(now), key=lambda x: x.started)
            return {"max": self.max_streams, "count": len(act),
                    "active": [{"sid": s.sid[:6], "label": s.label, "what": s.what,
                                "since": int(s.started), "idleSec": int(now - s.last_seen),
                                "recording": s.pinned} for s in act]}
