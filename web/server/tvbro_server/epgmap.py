# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Manual channel-to-guide mapping (epgmap.json): playlist channel key -> XMLTV channel id of the
same playlist's guide, or "__none" (no guide: a wrong automatic match). Laid over the automatic
match (tvg-id, then name); used by the guide, now / next, search and the recorder's series rules.
Provider data is never changed, so a sync keeps every mapping."""
from __future__ import annotations

import json
import os
import threading
from typing import Optional

from .epgdb import NO_GUIDE

MAX = 50000


class EpgMap:
    def __init__(self, path: str):
        self.path = path
        self._lock = threading.Lock()
        self.data: dict[str, str] = {}
        try:
            with open(path, encoding="utf-8") as f:
                d = json.load(f)
            if isinstance(d, dict):
                self.data = {str(k): str(v) for k, v in d.items() if k and v}
        except (OSError, ValueError):
            pass

    def _save(self):
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.data, f)
        os.replace(tmp, self.path)

    def get(self, key: str) -> Optional[str]:
        return self.data.get(key)

    def set(self, key: str, guide_id: Optional[str]):
        """guide_id: an XMLTV channel id, NO_GUIDE, or None / "" = automatic again."""
        if not key or len(key) > 300 or (guide_id and len(guide_id) > 300):
            raise ValueError("channel / guide channel")
        with self._lock:
            if guide_id:
                if key not in self.data and len(self.data) >= MAX:
                    raise ValueError("too many mappings")
                self.data[key] = guide_id
            else:
                self.data.pop(key, None)
            self._save()

    def replace(self, d: dict):
        with self._lock:
            self.data = {str(k): str(v) for k, v in d.items() if k and v}
            self._save()

    def for_source(self, sid: str) -> dict[str, str]:
        p = sid + "|"
        return {k: v for k, v in self.data.items() if k.startswith(p)}


__all__ = ["EpgMap", "NO_GUIDE"]
