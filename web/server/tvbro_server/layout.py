# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""The household's own channel layout, kept on the server (layout.json) and laid over what
the providers send: custom groups, renamed / hidden / reordered groups, renamed / hidden /
reordered channels. Provider data is never changed, so a sync keeps every edit, and a
channel that disappears from a provider simply drops out of its custom groups.

Pure logic plus an atomic JSON file; the HTTP layer passes in the provider's lists.
"""
from __future__ import annotations

import json
import os
import secrets
import threading
from typing import Optional

MAX_CUSTOM = 100
MAX_CUSTOM_CHANNELS = 2000
MAX_NAME = 60


def _empty() -> dict:
    return {"groups": {}, "order": [], "custom": {}, "channels": {}, "chorder": {}}


def ordered(ids: list[str], order: list[str]) -> list[str]:
    """ids in the saved order; ids the order does not know keep their own order after them
    (a new provider group shows up at the end of the part it was in)."""
    pos = {k: i for i, k in enumerate(order)}
    known = sorted((k for k in ids if k in pos), key=lambda k: pos[k])
    rest = [k for k in ids if k not in pos]
    return known + rest


def moved(ids: list[str], item: str, step: int) -> Optional[list[str]]:
    """ids with item moved by step places (clamped), or None when it is not there."""
    if item not in ids:
        return None
    out = list(ids)
    i = out.index(item)
    j = max(0, min(len(out) - 1, i + step))
    out.insert(j, out.pop(i))
    return out


def clean_name(v) -> str:
    return " ".join(str(v or "").split())[:MAX_NAME]


class Layout:
    def __init__(self, path: str):
        self.path = path
        self._lock = threading.RLock()
        self.data = _empty()
        try:
            with open(path, encoding="utf-8") as f:
                d = json.load(f)
            for k, v in _empty().items():
                if isinstance(d.get(k), type(v)):
                    self.data[k] = d[k]
        except (OSError, ValueError):
            pass

    def _save(self):
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.data, f)
        os.replace(tmp, self.path)

    # ---- reading ----
    def group_hidden(self, gid: str) -> bool:
        return bool(self.data["groups"].get(gid, {}).get("hidden"))

    def channel_hidden(self, key: str) -> bool:
        return bool(self.data["channels"].get(key, {}).get("hidden"))

    def custom_ids(self) -> list[str]:
        return list(self.data["custom"].keys())

    def custom_keys(self, gid: str) -> list[str]:
        return list(self.data["custom"].get(gid, {}).get("channels", []))

    def is_custom(self, gid: str) -> bool:
        return gid in self.data["custom"]

    def groups(self, provider_groups: list[dict], with_hidden: bool = False) -> list[dict]:
        """provider_groups: [{"id", "name", "count", ...}] in provider order. Custom groups
        come first until the household orders them otherwise."""
        cust = [{"id": gid, "name": g.get("name") or "My group", "count": len(g.get("channels", [])), "custom": True}
                for gid, g in self.data["custom"].items()]
        allg = {g["id"]: dict(g) for g in cust + provider_groups}
        out = []
        for gid in ordered([g["id"] for g in cust + provider_groups], self.data["order"]):
            g = allg[gid]
            meta = self.data["groups"].get(gid, {})
            if meta.get("name") and not g.get("custom"):
                g["origName"], g["name"] = g["name"], meta["name"]
            if meta.get("hidden"):
                if not with_hidden:
                    continue
                g["hidden"] = True
            out.append(g)
        return out

    def channel(self, js: dict, with_hidden: bool = False) -> dict:
        meta = self.data["channels"].get(js.get("key"), {})
        if meta.get("name"):
            js["origName"], js["name"] = js.get("name"), meta["name"]
        if with_hidden and meta.get("hidden"):
            js["hidden"] = True
        return js

    def order_channels(self, gid: str, keys: list[str]) -> list[str]:
        return ordered(keys, self.data["chorder"].get(gid, []))

    # ---- editing ----
    def edit(self, op: str, d: dict, group_ids: Optional[list[str]] = None,
             channel_keys: Optional[list[str]] = None) -> dict:
        """One edit. group_ids: the current group order (for group.move); channel_keys: the
        group's current channel order (for channel.move). Raises ValueError."""
        with self._lock:
            out = self._edit(op, d, group_ids, channel_keys)
            self._save()
            return out

    def _edit(self, op, d, group_ids, channel_keys) -> dict:
        D = self.data
        gid = str(d.get("id") or d.get("group") or "")
        key = str(d.get("key") or "")
        if op == "group.create":
            name = clean_name(d.get("name"))
            if not name:
                raise ValueError("name the group")
            if len(D["custom"]) >= MAX_CUSTOM:
                raise ValueError("too many groups")
            new = "c:" + secrets.token_hex(4)
            D["custom"][new] = {"name": name, "channels": []}
            if D["order"]:
                D["order"].insert(0, new)
            return {"id": new}
        if op == "group.rename":
            name = clean_name(d.get("name"))
            if gid in D["custom"]:
                if not name:
                    raise ValueError("name the group")
                D["custom"][gid]["name"] = name
            else:
                self._meta("groups", gid, "name", name or None)
            return {}
        if op == "group.hide":
            self._meta("groups", gid, "hidden", bool(d.get("hidden")) or None)
            return {}
        if op == "group.delete":
            if gid not in D["custom"]:
                raise ValueError("only your own groups can be deleted; hide a provider group instead")
            del D["custom"][gid]
            D["groups"].pop(gid, None)
            D["chorder"].pop(gid, None)
            D["order"] = [x for x in D["order"] if x != gid]
            return {}
        if op == "group.move":
            new = moved(group_ids or [], gid, int(d.get("dir") or 0))
            if new is None:
                raise ValueError("group not found")
            D["order"] = new
            return {}
        if op == "channel.rename":
            self._meta("channels", key, "name", clean_name(d.get("name")) or None)
            return {}
        if op == "channel.hide":
            self._meta("channels", key, "hidden", bool(d.get("hidden")) or None)
            return {}
        if op == "channel.move":
            new = moved(channel_keys or [], key, int(d.get("dir") or 0))
            if new is None:
                raise ValueError("channel not in this group")
            if gid in D["custom"]:
                D["custom"][gid]["channels"] = new
            else:
                D["chorder"][gid] = new
            return {}
        if op in ("channel.add", "channel.remove"):
            if gid not in D["custom"]:
                raise ValueError("choose one of your own groups")
            chans = D["custom"][gid]["channels"]
            if op == "channel.add":
                if not key:
                    raise ValueError("channel")
                if key not in chans:
                    if len(chans) >= MAX_CUSTOM_CHANNELS:
                        raise ValueError("this group is full")
                    chans.append(key)
            elif key in chans:
                chans.remove(key)
            return {}
        if op == "reset":
            self.data = _empty()
            return {}
        raise ValueError("unknown edit")

    def _meta(self, kind: str, ident: str, field: str, value):
        if not ident:
            raise ValueError("which one?")
        m = self.data[kind].setdefault(ident, {})
        if value is None:
            m.pop(field, None)
        else:
            m[field] = value
        if not m:
            del self.data[kind][ident]
