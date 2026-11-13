# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Rotates the server's encryption key (the Fernet key in /etc/credstore/tvbro.key).

    systemctl stop tvbro-server
    cd /opt/tvbro/server && python3 -m tvbro_server.rotate_key      # or: python3 run.py rotate-key
    systemctl start tvbro-server
(deploy/rotate_key.sh does exactly that and checks the site afterwards.)

What is sealed with the key: every ``*.enc`` file in the data folder: the provider logins
(provider.enc, provider-pN.enc), the stored channel lists and VOD catalogues (channels*.enc,
vod*.enc: their stream URLs carry the login) and the network-share login (share.enc).
state.db holds no sealed values; it is backed up with the rest all the same.

Steps, each one safe to crash in (run the command again: it finishes the same rotation, it never
leaves some files under one key and some under the other):
1. every sealed file must open with the current key (a wrong key changes nothing);
2. backup folder (0700, files 0600): the old key, state.db, every sealed file as it was;
3. ``keyrot.json`` in the data folder (the backup path; no key material): the server refuses
   to start while it exists; the new key (Fernet: 256 bits) goes to ``<key>.new`` (0600);
4. every sealed file is re-encrypted into a staged copy, checked, and only when all are staged
   are they swapped in (os.replace, fsync);
5. every file is checked to open with the new key; then the key is switched: the old one is
   kept as ``<key>.old``, ``<key>.new`` replaces ``<key>`` atomically;
6. the old key is retired: moved into the backup folder (never deleted); keyrot.json removed.
A crash before 4 swapped anything rolls back by itself (staged copies and <key>.new removed).
A stored channel list / catalogue that opens with neither key (a damaged cache) is moved to the
backup folder instead of stopping the rotation: the server fetches it again. A login that opens
with neither key stops it.
``--end-sessions`` also replaces sign.key (CSRF tokens, signed links, parental unlocks) and
moves sessions.json to the backup: every device starts a new session (and signs in again when a
password is required).

Never prints or logs key material or anything decrypted.
"""
from __future__ import annotations

import argparse
import json
import os
import secrets
import shutil
import socket
import sys
import time
from typing import Callable, Optional

from cryptography.fernet import Fernet, InvalidToken

from .secfile import read_key_file
from .secretstore import MARKER

STAGE = ".keyrot"                 # suffix of a staged re-encrypted file (never ends in .enc)
CACHE_PREFIXES = ("channels", "vod")
DEFAULT_CRED = "/etc/credstore/tvbro.key"
DEFAULT_BACKUP = "/root/tvbro-key-backup"


class RotateError(Exception):
    pass


# --------------------------------------------------------------------------- files

def sealed_files(data_dir: str) -> list[str]:
    return sorted(n for n in os.listdir(data_dir)
                  if n.endswith(".enc") and os.path.isfile(os.path.join(data_dir, n)))


def _is_cache(name: str) -> bool:
    return name.startswith(CACHE_PREFIXES)


def _fsync_dir(path: str) -> None:
    if os.name != "posix":
        return
    try:
        fd = os.open(path, os.O_RDONLY)
    except OSError:
        return
    try:
        os.fsync(fd)
    except OSError:
        pass
    finally:
        os.close(fd)


def _write_new(path: str, data: bytes, like: Optional[str] = None) -> None:
    """Creates path (must not exist) 0600 from the first byte, owned like `like`, fsynced."""
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0), 0o600)
    try:
        if like and hasattr(os, "fchown"):
            st = os.stat(like)
            try:
                os.fchown(fd, st.st_uid, st.st_gid)
            except PermissionError:
                pass                                # not root: the file stays ours
        with os.fdopen(fd, "wb") as f:
            fd = -1
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
    except BaseException:
        if fd >= 0:
            os.close(fd)
        try:
            os.remove(path)
        except OSError:
            pass
        raise


def _replace_secret(path: str, data: bytes, like: Optional[str] = None) -> None:
    """Writes path atomically (temp file 0600 + os.replace + directory fsync)."""
    tmp = f"{path}.{secrets.token_hex(6)}.tmp"
    _write_new(tmp, data, like if like and os.path.exists(like) else None)
    try:
        os.replace(tmp, path)
    except BaseException:
        os.remove(tmp)
        raise
    _fsync_dir(os.path.dirname(os.path.abspath(path)))


def _copy_private(src: str, dst: str) -> None:
    fd = os.open(dst, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0), 0o600)
    with os.fdopen(fd, "wb") as out, open(src, "rb") as f:
        shutil.copyfileobj(f, out, 1 << 20)
        out.flush()
        os.fsync(out.fileno())


def _move_private(src: str, dst: str) -> None:
    """Moves src into the backup folder (0600 there); never deletes anything."""
    try:
        os.replace(src, dst)
    except OSError:                                 # another file system: copy, then remove
        _copy_private(src, dst)
        os.remove(src)
    try:
        os.chmod(dst, 0o600)
    except OSError:
        pass


def _private_dir(path: str) -> None:
    os.makedirs(path, mode=0o700, exist_ok=True)
    try:
        os.chmod(path, 0o700)
    except OSError:
        pass


def _read_key(path: str) -> Optional[bytes]:
    return read_key_file(path) if os.path.exists(path) else None


def _fernet(key: Optional[bytes], what: str) -> Fernet:
    if not key:
        raise RotateError(f"{what} is missing or empty")
    try:
        return Fernet(key)
    except (ValueError, TypeError):
        raise RotateError(f"{what} is not a valid encryption key") from None


def _load_marker(path: str) -> Optional[dict]:
    if not os.path.exists(path):
        return None
    try:
        with open(path, encoding="utf-8") as f:
            d = json.load(f)
        if isinstance(d, dict) and isinstance(d.get("backup"), str):
            return d
    except (OSError, ValueError):
        pass
    raise RotateError(f"{path} is unreadable: check the rotation by hand (see SECURITY.md)")


def server_running(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), 0.5):
            return True
    except OSError:
        return False


# --------------------------------------------------------------------------- the rotation

def _backup(data_dir: str, key_path: str, backup_root: str, names: list[str]) -> str:
    _private_dir(backup_root)
    base = os.path.join(backup_root, "rotate-" + time.strftime("%Y%m%d-%H%M%S"))
    path, i = base, 1
    while os.path.exists(path):
        i += 1
        path = f"{base}-{i}"
    _private_dir(path)
    _private_dir(os.path.join(path, "data"))
    _copy_private(key_path, os.path.join(path, "tvbro.key.old"))
    for n in names + ["state.db", "state.db-wal", "state.db-shm"]:
        src = os.path.join(data_dir, n)
        if os.path.isfile(src):
            _copy_private(src, os.path.join(path, "data", n))
    with open(os.path.join(path, "README.txt"), "w", encoding="utf-8") as f:
        f.write("tvBro key rotation backup (keep it private; it can decrypt the old files).\n"
                f"Key file rotated: {key_path}\nData folder: {data_dir}\n"
                "tvbro.key.old      the key before the rotation\n"
                "data/              state.db and every sealed *.enc file as they were (under the old key)\n"
                "tvbro.key.retired  the old key, moved here once the rotation was verified\n"
                "unreadable/        stored lists that opened with neither key (fetched again)\n"
                "To go back: stop the server, copy tvbro.key.old over the key file and data/* into the\n"
                "data folder (owner tvbro, 0600), remove keyrot.json from the data folder, start it.\n")
    os.chmod(os.path.join(path, "README.txt"), 0o600)
    return path


def _stage_all(data_dir: str, old: Optional[Fernet], new: Fernet, backup: str, log) -> tuple[list[str], int]:
    """Re-encrypts every sealed file not yet under the new key into <name>.keyrot.
    -> (staged names, files already under the new key)."""
    staged, already = [], 0
    for name in sealed_files(data_dir):
        path = os.path.join(data_dir, name)
        with open(path, "rb") as f:
            raw = f.read()
        try:
            new.decrypt(raw)
            already += 1
            continue
        except InvalidToken:
            pass
        plain = None
        if old is not None:
            try:
                plain = old.decrypt(raw)
            except InvalidToken:
                pass
        if plain is None:
            if _is_cache(name):
                _private_dir(os.path.join(backup, "unreadable"))
                _move_private(path, os.path.join(backup, "unreadable", name))
                log(f"  {name}: opens with neither key (damaged stored list): moved to the backup, fetched again")
                continue
            raise RotateError(f"{name} opens with neither the old nor the new key: restore it from a backup")
        tok = new.encrypt(plain)
        if new.decrypt(tok) != plain:
            raise RotateError(f"{name}: re-encrypted copy does not check out")
        _write_new(path + STAGE, tok, like=path)
        staged.append(name)
    return staged, already


def _verify(data_dir: str, new: Fernet) -> int:
    n = 0
    for name in sealed_files(data_dir):
        with open(os.path.join(data_dir, name), "rb") as f:
            try:
                new.decrypt(f.read())
            except InvalidToken:
                raise RotateError(f"{name} does not open with the new key") from None
        n += 1
    return n


def _end_sessions(data_dir: str, backup: str, log) -> None:
    _private_dir(os.path.join(backup, "data"))
    sk = os.path.join(data_dir, "sign.key")
    if os.path.exists(sk):
        keep = os.path.join(backup, "data", "sign.key")
        if not os.path.exists(keep):
            _copy_private(sk, keep)
        k = secrets.token_bytes(32)
        while k != k.strip():                       # the server reads it back with .strip()
            k = secrets.token_bytes(32)
        _replace_secret(sk, k, like=sk)
        log("  sign.key replaced (CSRF tokens, signed links and parental unlocks end)")
    ss = os.path.join(data_dir, "sessions.json")
    if os.path.exists(ss):
        _move_private(ss, os.path.join(backup, "data", f"sessions.json.{int(time.time())}"))
        log("  sessions.json moved to the backup (every device starts a new session)")


def rotate(data_dir: str, key_path: str, backup_root: str = DEFAULT_BACKUP, end_sessions: bool = False,
           port: Optional[int] = None, log: Callable[[str], None] = print,
           _crash: Optional[Callable[[str], None]] = None) -> dict:
    """Rotates (or finishes an interrupted rotation). -> {"backup", "files", "resumed"}.
    `_crash(step)` is a test hook called between steps."""
    crash = _crash or (lambda step: None)
    data_dir, key_path = os.path.abspath(data_dir), os.path.abspath(key_path)
    if not os.path.isdir(data_dir):
        raise RotateError(f"data folder {data_dir} does not exist")
    if port and server_running(port):
        raise RotateError(f"the server is running (127.0.0.1:{port}): stop it first (systemctl stop tvbro-server)")
    new_path, old_path = key_path + ".new", key_path + ".old"
    marker_path = os.path.join(data_dir, MARKER)
    marker = _load_marker(marker_path)
    for n in os.listdir(data_dir):                  # staged copies of a run that crashed
        if n.endswith(".enc" + STAGE):
            os.remove(os.path.join(data_dir, n))

    fresh, switched = False, False
    if os.path.exists(new_path):                    # crashed before the key switch: finish it
        if marker is None:
            raise RotateError(f"{new_path} exists but no rotation is unfinished: check it by hand")
        old_bytes = _read_key(key_path)
        old, new = _fernet(old_bytes, f"the key {key_path}"), _fernet(_read_key(new_path), f"the new key {new_path}")
        backup = marker["backup"]
        log(f"finishing the interrupted rotation (backup {backup})")
    elif os.path.exists(old_path):                  # crashed after the key switch
        if marker is None:
            raise RotateError(f"{old_path} exists but no rotation is unfinished: check it by hand")
        old_bytes = _read_key(old_path)
        old, new = _fernet(old_bytes, f"the old key {old_path}"), _fernet(_read_key(key_path), f"the key {key_path}")
        backup, switched = marker["backup"], True
        log(f"finishing the interrupted rotation (backup {backup})")
    else:
        cur_bytes = _read_key(key_path)
        if cur_bytes is None:
            raise RotateError(f"the encryption key {key_path} is missing: nothing can be decrypted; not rotating")
        cur = _fernet(cur_bytes, f"the key {key_path}")
        names = sealed_files(data_dir)
        opened = 0
        for name in names:
            with open(os.path.join(data_dir, name), "rb") as f:
                try:
                    cur.decrypt(f.read())
                    opened += 1
                except InvalidToken:
                    if not _is_cache(name):
                        raise RotateError(f"{name} does not open with the key {key_path} (wrong key?): "
                                          "nothing changed") from None
        if names and not opened:
            raise RotateError(f"no sealed file opens with the key {key_path} (wrong key?): nothing changed")
        if marker is not None and os.path.exists(os.path.join(marker["backup"], "tvbro.key.retired")):
            # crashed after retiring the old key: only the marker was left
            os.remove(marker_path)
            log(f"the interrupted rotation had finished (backup {marker['backup']}); {opened} files open "
                "with the current key")
            return {"backup": marker["backup"], "files": opened, "resumed": True}
        if marker is not None:
            os.remove(marker_path)                  # crashed before anything changed: start again
        backup = _backup(data_dir, key_path, backup_root, names)
        log(f"backup: {backup}")
        crash("backup")
        _replace_secret(marker_path, json.dumps({"v": 1, "backup": backup, "key": key_path,
                                                 "started": int(time.time())}).encode())
        crash("marker")
        old_bytes, old = cur_bytes, cur
        new_key = Fernet.generate_key()             # 32 random bytes: 128-bit AES + 128-bit HMAC key
        _write_new(new_path, new_key, like=key_path)
        _fsync_dir(os.path.dirname(key_path))
        new = Fernet(new_key)
        del new_key
        fresh = True
        crash("newkey")

    if not switched:
        swapped = False
        try:
            staged, already = _stage_all(data_dir, old, new, backup, log)
            crash("staged")
            for name in staged:                     # the swap: every copy is ready and checked
                os.replace(os.path.join(data_dir, name + STAGE), os.path.join(data_dir, name))
                swapped = True
                crash("swap")
            _fsync_dir(data_dir)
        except BaseException:
            for n in os.listdir(data_dir):
                if n.endswith(".enc" + STAGE):
                    os.remove(os.path.join(data_dir, n))
            if fresh and not swapped:               # nothing changed: roll back
                os.remove(new_path)
                os.remove(marker_path)
                log(f"rotation stopped before any file changed: rolled back (backup kept: {backup})")
            raise
        log(f"  {len(staged)} sealed files re-encrypted" + (f", {already} already under the new key" if already else ""))
        _verify(data_dir, new)
        crash("verified")
        # the key switch: the old key stays next to it until the end
        if not os.path.exists(old_path):
            _write_new(old_path, old_bytes, like=key_path)
        crash("oldkept")
        os.replace(new_path, key_path)
        _fsync_dir(os.path.dirname(key_path))
        try:
            os.chmod(key_path, 0o600)
        except OSError:
            pass
        crash("switched")
    else:                                           # already switched: anything left under the old key?
        staged, _ = _stage_all(data_dir, old, new, backup, log)
        for name in staged:
            os.replace(os.path.join(data_dir, name + STAGE), os.path.join(data_dir, name))
        _fsync_dir(data_dir)

    files = _verify(data_dir, _fernet(_read_key(key_path), f"the key {key_path}"))
    if end_sessions:
        _end_sessions(data_dir, backup, log)
    crash("before-retire")
    _private_dir(backup)
    _move_private(old_path, os.path.join(backup, "tvbro.key.retired"))
    _fsync_dir(os.path.dirname(key_path))
    crash("retired")
    os.remove(marker_path)
    _fsync_dir(data_dir)
    log(f"new key in {key_path} (0600); {files} sealed files verified with it; "
        f"old key retired to {os.path.join(backup, 'tvbro.key.retired')}")
    return {"backup": backup, "files": files, "resumed": not fresh}


# --------------------------------------------------------------------------- command line

def default_key_path(data_dir: str) -> str:
    if os.path.exists(DEFAULT_CRED):
        return DEFAULT_CRED
    kf = os.environ.get("TVBRO_KEY_FILE")
    if kf:
        return kf
    kb = os.path.join(data_dir, "key.bin")
    if os.path.exists(kb):
        return kb
    if os.environ.get("TVBRO_KEY"):
        raise RotateError("the key comes from the TVBRO_KEY variable: move it to a key file first "
                          "(TVBRO_KEY_FILE or the systemd credential), then rotate")
    return DEFAULT_CRED


def main(argv: Optional[list[str]] = None) -> int:
    ap = argparse.ArgumentParser(prog="python3 -m tvbro_server.rotate_key",
                                 description="Rotate the tvBro encryption key (stop the server first).")
    ap.add_argument("--data", default=os.environ.get("TVBRO_DATA", "/var/lib/tvbro"), help="data folder")
    ap.add_argument("--key", help=f"key file (default {DEFAULT_CRED}, else TVBRO_KEY_FILE, else <data>/key.bin)")
    ap.add_argument("--backup-dir", default=DEFAULT_BACKUP, help="where backups go (default %(default)s)")
    ap.add_argument("--end-sessions", action="store_true",
                    help="also replace sign.key and end every session (after a compromise)")
    ap.add_argument("--port", type=int, default=int(os.environ.get("TVBRO_PORT", "8750")),
                    help="refuse while something answers on 127.0.0.1:PORT (0 = do not check)")
    a = ap.parse_args(argv)
    try:
        key = a.key or default_key_path(a.data)
        rotate(a.data, key, a.backup_dir, a.end_sessions, a.port or None)
    except RotateError as e:
        print(f"rotate-key: {e}", file=sys.stderr)
        return 1
    print("Done. Save a copy of the new key somewhere safe (password manager): without it the stored "
          "logins cannot be decrypted.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
