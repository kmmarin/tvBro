# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""The last good channel list and VOD catalogue of a playlist, kept on disk so a restart
serves them at once (and a provider that answers with nothing never empties the app).
Channel stream URLs carry the login, so the file is gzip'd JSON encrypted with the same key
as the login (SecretStore), written to a temp file and swapped in with os.replace."""
from __future__ import annotations

import gzip
import json
import os
from dataclasses import astuple
from typing import Callable, Optional

from . import parsers as P

VERSION = 1


def _ch(c: P.Channel) -> list:
    return [c.key, c.source_id, c.name, c.logo, c.group, c.url, c.tvg_id, c.number,
            [c.catchup.type, c.catchup.template] if c.catchup else None, c.catchup_days]


def _ch_back(r: list) -> P.Channel:
    cu = r[8]
    return P.Channel(r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], P.Catchup(cu[0], cu[1]) if cu else None, r[9])


def encode(channels: Optional[tuple] = None, vod: Optional[dict] = None) -> bytes:
    """channels = (list[Channel], list[XtreamCategory]); vod = Provider.vod dict."""
    d: dict = {"v": VERSION}
    if channels is not None:
        chans, cats = channels
        d["channels"] = [_ch(c) for c in chans]
        d["cats"] = [astuple(c) for c in cats]
    if vod is not None:
        d["vod"] = {k: [astuple(x) for x in vod.get(k, [])] for k in ("movie_cats", "series_cats", "movies", "series")}
    return gzip.compress(json.dumps(d, separators=(",", ":")).encode(), 5)


def decode(raw: bytes) -> dict:
    """-> {"channels": (chans, cats)?, "vod": {...}?}; {} when unreadable."""
    try:
        d = json.loads(gzip.decompress(raw))
    except (OSError, ValueError, EOFError):
        return {}
    if not isinstance(d, dict) or d.get("v") != VERSION:
        return {}
    out: dict = {}
    try:
        if "channels" in d:
            out["channels"] = ([_ch_back(r) for r in d["channels"]], [P.XtreamCategory(*r) for r in d["cats"]])
        if "vod" in d:
            v = d["vod"]
            out["vod"] = {"movie_cats": [P.XtreamCategory(*r) for r in v["movie_cats"]],
                          "series_cats": [P.XtreamCategory(*r) for r in v["series_cats"]],
                          "movies": [P.VodItem(*r) for r in v["movies"]],
                          "series": [P.VodItem(*r) for r in v["series"]]}
    except (TypeError, KeyError, IndexError):
        return {}
    return out


def save(path: str, seal: Callable[[bytes], bytes], data: bytes):
    tmp = path + ".tmp"
    with open(tmp, "wb") as f:
        f.write(seal(data))
    try:
        os.chmod(tmp, 0o600)
    except OSError:
        pass
    os.replace(tmp, path)


def load(path: str, unseal: Callable[[bytes], Optional[bytes]]) -> dict:
    try:
        with open(path, "rb") as f:
            raw = unseal(f.read())
    except OSError:
        return {}
    return decode(raw) if raw else {}
