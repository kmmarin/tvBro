# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Where recordings are written: a folder on the server, or a network share (SMB / CIFS or
NFS). The server runs in an unprivileged LXC container, which cannot mount network shares
itself, so a share is mounted on the Proxmox host and bind-mounted into the container; this
module lists folders for the picker, tests a folder (exists, writable, free space, mounted)
and writes the host-side steps for a share. The share's login is stored encrypted (Fernet,
the same key as the provider login) and is never logged or sent back to the browser."""
from __future__ import annotations

import json
import os
import re
import secrets
import shlex
import shutil
from typing import Optional

from .secfile import write_secret

ROOTS = ("/mnt", "/media", "/srv")
MAX_LIST = 300


def roots(data_dir: str) -> list[str]:
    return [r for r in ROOTS if os.path.isdir(r)] + [os.path.realpath(data_dir)]


def allowed(path: str, data_dir: str) -> Optional[str]:
    """The real path when it is under one of the allowed roots, else None."""
    if not path or not os.path.isabs(path) or "\x00" in path:
        return None
    real = os.path.realpath(path)
    for r in roots(data_dir):
        rr = os.path.realpath(r)
        try:
            if os.path.commonpath([real, rr]) == rr:
                return real
        except ValueError:                  # different drives (Windows dev machines)
            continue
    return None


def list_dirs(path: str, data_dir: str) -> dict:
    """Sub-folders of `path` (or the roots when empty) for the folder picker."""
    if not path:
        return {"path": "", "parent": None, "dirs": [{"name": r, "path": r} for r in roots(data_dir)]}
    real = allowed(path, data_dir)
    if not real or not os.path.isdir(real):
        raise ValueError("not an allowed folder")
    out = []
    try:
        with os.scandir(real) as it:
            for e in it:
                if e.is_dir(follow_symlinks=True) and not e.name.startswith("."):
                    out.append({"name": e.name, "path": os.path.join(real, e.name)})
                if len(out) >= MAX_LIST:
                    break
    except OSError as e:
        raise ValueError("cannot read this folder: " + (e.strerror or "error"))
    out.sort(key=lambda d: d["name"].lower())
    parent = os.path.dirname(real)
    return {"path": real, "parent": parent if allowed(parent, data_dir) and parent != real else "", "dirs": out}


def _mount_of(path: str) -> str:
    p = path
    while not os.path.ismount(p) and p != "/":
        p = os.path.dirname(p)
    return p


def test_dir(path: str, data_dir: str) -> dict:
    """Exists, writable by the server, free space, and whether it is its own mount."""
    real = allowed(path, data_dir)
    if not real:
        return {"ok": False, "why": "Choose a folder under " + ", ".join(roots(data_dir)) + "."}
    if not os.path.isdir(real):
        return {"ok": False, "path": real, "why": "The folder does not exist (for a share: do the host step first)."}
    probe = os.path.join(real, ".tvbro-write-test-" + secrets.token_hex(4))
    try:
        with open(probe, "wb") as f:
            f.write(b"\0" * 65536)
            f.flush()
            os.fsync(f.fileno())
        os.remove(probe)
        writable, why = True, ""
    except OSError as e:
        writable, why = False, "The server cannot write here (" + (e.strerror or "error") + "): check the owner / mapping."
        try:
            os.remove(probe)
        except OSError:
            pass
    try:
        du = shutil.disk_usage(real)
        free, total = du.free, du.total
    except OSError:
        free = total = 0
    mnt = _mount_of(real)
    return {"ok": writable, "path": real, "writable": writable, "free": free, "total": total,
            "mount": mnt, "separateMount": mnt not in ("/",), "why": why}


# ---- network share settings (login encrypted) ----
# Strict shapes: these values end up in commands a person pastes into a root shell on the host.
_RULES = {
    "server": re.compile(r"^(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,62}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,62}[A-Za-z0-9])?)*"
                         r"|\[[0-9A-Fa-f:.]{2,45}\]|[0-9A-Fa-f:.]{2,45})$"),
    "share": re.compile(r"^[A-Za-z0-9._/-]{1,200}$"),
    "user": re.compile(r"^[A-Za-z0-9._@\\-]{0,64}$"),
    "options": re.compile(r"^[A-Za-z0-9=,._-]{0,200}$"),
    "mountpoint": re.compile(r"^(?:/mnt/[A-Za-z0-9._-]{1,64})?$"),
}


class ShareStore:
    def __init__(self, data_dir: str, secrets_store):
        self.path = os.path.join(data_dir, "share.enc")
        self.sec = secrets_store

    def get(self) -> Optional[dict]:
        try:
            with open(self.path, "rb") as f:
                raw = self.sec.open_bytes(f.read())
            return json.loads(raw.decode()) if raw else None
        except (OSError, ValueError):
            return None

    def public(self) -> Optional[dict]:
        s = self.get()
        if not s:
            return None
        return {k: s.get(k) for k in ("type", "server", "share", "user", "options", "mountpoint")} | {"hasPassword": bool(s.get("pass"))}

    def save(self, d: dict) -> dict:
        typ = d.get("type")
        if typ not in ("smb", "nfs"):
            raise ValueError("type must be smb or nfs")
        old = self.get() or {}
        s = {"type": typ}
        if not str(d.get("server") or "").strip() or not str(d.get("share") or "").strip():
            raise ValueError("server and share / export are needed")
        for k in ("server", "share", "user", "options", "mountpoint"):
            v = str(d.get(k) or "").strip()
            if not _RULES[k].match(v) or ".." in v.split("/"):
                raise ValueError({"server": "server: a host name or IP address",
                                  "share": "share / export: letters, digits, . _ - / only",
                                  "user": "user: letters, digits, . _ @ - only",
                                  "options": "options: name=value pairs separated by commas, no spaces",
                                  "mountpoint": "the folder in the container must be /mnt/<name> (letters, digits, . _ -)"}[k])
            s[k] = v
        s["mountpoint"] = s["mountpoint"] or "/mnt/recordings"
        pw = d.get("pass")
        s["pass"] = old.get("pass", "") if pw is None or pw == "" and d.get("keepPass", True) else str(pw)
        if any(c in s["pass"] for c in "\r\n\x00"):
            raise ValueError("the password cannot contain line breaks")
        write_secret(self.path, self.sec.seal_bytes(json.dumps(s).encode()))
        return self.public()

    def clear(self):
        try:
            os.remove(self.path)
        except OSError:
            pass


def _valid_share(share: dict) -> bool:
    try:
        return share.get("type") in ("smb", "nfs") and bool(share.get("server")) and bool(share.get("share")) and all(
            _RULES[k].match(str(share.get(k) or "")) for k in _RULES)
    except (TypeError, AttributeError):
        return False


def host_steps(share: dict, ctid: Optional[int] = None, uid: Optional[int] = None, gid: Optional[int] = None) -> list[str]:
    """Commands for the Proxmox host (an unprivileged container cannot mount a share itself),
    to be reviewed and pasted by the admin. Every value is validated (ShareStore.save) and
    shell-quoted here. ``ctid``: the container id (Settings); "$CTID" (set it first) when unknown.
    The password is never included: the user writes it into the credentials file."""
    if not _valid_share(share):
        return ["# the stored share settings are not valid any more: save them again"]
    q = shlex.quote
    uid = os.getuid() if uid is None and hasattr(os, "getuid") else (uid or 0)
    gid = os.getgid() if gid is None and hasattr(os, "getgid") else (gid or 0)
    huid, hgid = 100000 + int(uid), 100000 + int(gid)    # default unprivileged id mapping
    ct = str(int(ctid)) if isinstance(ctid, int) or (isinstance(ctid, str) and ctid.isdigit()) else None
    lines = [] if ct else ["CTID=   # the container id of tvBro (pct list); set it before the lines below"]
    ctv = ct or '"$CTID"'
    tag = ct or "ct"
    host = f"/mnt/tvbro-share-{tag}"
    mp = share.get("mountpoint") or "/mnt/recordings"
    opts = (share.get("options") + ",") if share.get("options") else ""
    lines.append(f"mkdir -p {q(host)}")
    if share.get("type") == "smb":
        src = "//" + share["server"].strip("/") + "/" + share["share"].strip("/")
        cred = f"/root/.tvbro-{tag}-smb.cred"
        lines += [f"printf 'username=%s\\npassword=YOUR_PASSWORD\\n' {q(share.get('user') or 'guest')} > {q(cred)} && chmod 600 {q(cred)}   # put the real password in this file",
                  "echo " + q(f"{src} {host} cifs credentials={cred},uid={huid},gid={hgid},file_mode=0660,dir_mode=0770,"
                               f"{opts}_netdev,nofail,x-systemd.automount 0 0") + " >> /etc/fstab"]
    else:
        src = share["server"].strip("/") + ":" + "/" + share["share"].strip("/")
        lines += ["echo " + q(f"{src} {host} nfs {opts}_netdev,nofail,x-systemd.automount 0 0") + " >> /etc/fstab",
                  f"# on the NFS server: let host uid {huid} (or all_squash,anonuid={huid},anongid={hgid}) write the export"]
    lines += ["systemctl daemon-reload && mount " + q(host),
              f"pct set {ctv} -mp0 {q(host + ',mp=' + mp + ',backup=0')}   # pick a free mpN if mp0 is used",
              f"pct reboot {ctv}"]
    return lines
