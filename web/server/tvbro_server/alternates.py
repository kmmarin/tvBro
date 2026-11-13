# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Alternate sources of a live channel: the same channel listed again by the playlist (or by
another playlist), e.g. "BBC One HD" / "BBC One FHD" / "BBC One (backup)", or another entry
with the same tvg-id. The player tries them when a channel stays down after its reconnects.

Matching is deliberately conservative: the same tvg-id, or the same name once quality / backup
words and punctuation are dropped. Country prefixes ("UK:") and timeshift marks ("+1") are
kept, so "UK: Fox" never stands in for "US: Fox" and "Film4 +1" never for "Film4".
Pure logic (unit-tested); the HTTP layer passes in the channel list.
"""
from __future__ import annotations

import re
import unicodedata
from typing import Iterable

# words that tell copies of one channel apart without changing what it shows
QUALITY = {
    "hd", "fhd", "uhd", "sd", "hq", "lq", "4k", "8k", "hevc", "h265", "h264", "avc", "1080", "1080p", "1080i",
    "720", "720p", "576p", "2160p", "50fps", "60fps", "25fps", "fps", "backup", "bkp", "alt", "alternative", "raw",
    "source", "server", "feed", "link", "mirror", "low", "high", "fullhd", "ultra",
}
_TOKEN = re.compile(r"\+?[a-z0-9]+")
_BRACKET = re.compile(r"[\(\[\{]([^\)\]\}]*)[\)\]\}]")
MAX_ALTS = 6


def _fold(s: str) -> str:
    s = unicodedata.normalize("NFKD", s or "")
    return "".join(c for c in s if not unicodedata.combining(c)).lower()


def norm_name(name: str) -> str:
    """The comparable form of a channel name ("" when nothing is left to compare)."""
    s = _fold(name)

    def bracket(m):            # "(backup)", "[FHD]", "(1080p 50fps)" go; "(Wales)" stays
        inner = _TOKEN.findall(m.group(1))
        return " " if inner and all(t in QUALITY or t.isdigit() and len(t) == 1 for t in inner) else " " + m.group(1) + " "
    s = _BRACKET.sub(bracket, s)
    toks = [t for t in _TOKEN.findall(s) if t not in QUALITY]
    # a trailing lone digit after a quality word ("HD 2", "Backup 1") numbers copies of the same channel
    while len(toks) > 1 and toks[-1].isdigit() and len(toks[-1]) == 1 and _ends_with_quality(s):
        toks.pop()
    return " ".join(toks)


def _ends_with_quality(s: str) -> bool:
    raw = _TOKEN.findall(s)
    return len(raw) >= 2 and raw[-2] in QUALITY


def norm_id(tvg_id) -> str:
    return (tvg_id or "").strip().lower()


class AltIndex:
    """name / tvg-id -> channel keys, over every enabled playlist (rebuilt when the list changes)."""

    def __init__(self, channels: Iterable):
        self.by_id: dict[str, list[str]] = {}
        self.by_name: dict[str, list[str]] = {}
        self.chans: dict[str, object] = {}
        for c in channels:
            self.chans[c.key] = c
            i = norm_id(c.tvg_id)
            if i:
                self.by_id.setdefault(i, []).append(c.key)
            n = norm_name(c.name)
            if n:
                self.by_name.setdefault(n, []).append(c.key)

    def alternates(self, key: str, usable=lambda k: True, limit: int = MAX_ALTS) -> list[str]:
        """Other channels showing the same thing: same tvg-id first, then the same name; the
        channel's own playlist before the others (same login, same server, usually)."""
        c = self.chans.get(key)
        if c is None:
            return []
        out: list[str] = []
        for bucket in (self.by_id.get(norm_id(c.tvg_id), []) if norm_id(c.tvg_id) else [],
                       self.by_name.get(norm_name(c.name), []) if norm_name(c.name) else []):
            same = [k for k in bucket if self.chans[k].source_id == c.source_id]
            other = [k for k in bucket if self.chans[k].source_id != c.source_id]
            for k in same + other:
                if k != key and k not in out and self.chans[k].url != c.url and usable(k):
                    out.append(k)
        return out[:limit]
