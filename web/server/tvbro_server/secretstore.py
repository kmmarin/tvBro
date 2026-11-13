# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Server-side encrypted store for the user's Xtream provider login.

The login never lives in the repo, in logs, or in any URL the frontend sees. It is written
to an encrypted file; the symmetric key comes from the env var TVBRO_KEY, or from a 0600
key file next to the data (generated on first run). Decryption happens only in memory when a
request needs to reach the provider.
"""
from __future__ import annotations

import json
import logging
import os
from typing import Optional

from cryptography.fernet import Fernet, InvalidToken

from .secfile import read_key_file, write_secret

LOG = logging.getLogger("tvbro")
_warned = []
# written by rotate_key while a key rotation runs: the server must not start on half-rotated data
MARKER = "keyrot.json"
ROTATION_UNFINISHED = ("a key rotation is unfinished (keyrot.json in the data folder): run "
                       "`python3 -m tvbro_server.rotate_key` again to finish it (see SECURITY.md)")


def startup_check(data_dir: str) -> None:
    """Refuses to start (RuntimeError) mid-rotation, without the key, or with a key that opens
    none of the stored encrypted files (a wrong key would look like a signed-out server)."""
    if os.path.exists(os.path.join(data_dir, MARKER)):
        raise RuntimeError(ROTATION_UNFINISHED)
    names = sorted(n for n in os.listdir(data_dir) if n.endswith(".enc"))
    if not names:
        return
    st = SecretStore(data_dir)                  # raises when the key is missing
    for n in names:
        try:
            with open(os.path.join(data_dir, n), "rb") as f:
                if st.open_bytes(f.read()) is not None:
                    return
        except OSError:
            pass
    raise RuntimeError("the encryption key opens none of the stored encrypted files (wrong key?): not starting. "
                       "Load the right key (credential tvbro.key / TVBRO_KEY_FILE) or restore it from "
                       "/root/tvbro-key-backup (see SECURITY.md)")


def key_source():
    """(where, key) from outside the data folder, else None. In order: the systemd credential
    ``tvbro.key`` ($CREDENTIALS_DIRECTORY, LoadCredential= in the unit), TVBRO_KEY_FILE, TVBRO_KEY."""
    cd = os.environ.get("CREDENTIALS_DIRECTORY")
    if cd and os.path.exists(os.path.join(cd, "tvbro.key")):
        return "credential", read_key_file(os.path.join(cd, "tvbro.key"))
    kf = os.environ.get("TVBRO_KEY_FILE")
    if kf and os.path.exists(kf):
        return "file", read_key_file(kf)
    env = os.environ.get("TVBRO_KEY")
    if env:
        return "env", env.encode()
    return None


def _warn_once():
    if not _warned:
        _warned.append(1)
        LOG.warning("the encryption key is kept in the data folder (key.bin): a copy of that folder can "
                    "decrypt the provider login. Load it as a systemd credential instead (see README).")


class SecretStore:
    def __init__(self, data_dir: str, name: str = "provider.enc"):
        """One encrypted login per playlist source: provider.enc (the first), provider-pN.enc."""
        self.data_dir = data_dir
        os.makedirs(data_dir, exist_ok=True)
        if os.path.exists(os.path.join(data_dir, MARKER)):
            raise RuntimeError(ROTATION_UNFINISHED)
        self.enc_path = os.path.join(data_dir, name)
        self.key_path = os.path.join(data_dir, "key.bin")
        self._fernet = Fernet(self._load_key())
        self._cache: Optional[dict] = None

    def _load_key(self) -> bytes:
        k = key_source()
        if k is not None:
            return k[1]
        _warn_once()
        if os.path.exists(self.key_path):
            return read_key_file(self.key_path)
        # never make a new key over data that an old key encrypted (e.g. the credential did not
        # load): that would lose the logins for good
        if any(n.endswith(".enc") for n in os.listdir(self.data_dir)):
            raise RuntimeError("the encryption key is missing (credential tvbro.key / TVBRO_KEY_FILE / key.bin) "
                               "but encrypted data exists; not making a new key")
        key = Fernet.generate_key()
        write_secret(self.key_path, key)
        return key

    def has_provider(self) -> bool:
        return os.path.exists(self.enc_path)

    def save_provider(self, kind: str, server: str, user: str, password: str,
                      epg_url: str = "", url: str = "") -> None:
        """kind is 'xtream' or 'm3u'. Stored encrypted at rest."""
        payload = {"kind": kind, "server": server, "user": user, "pass": password,
                   "epgUrl": epg_url, "url": url}
        token = self._fernet.encrypt(json.dumps(payload).encode())
        write_secret(self.enc_path, token)
        self._cache = payload

    def get_provider(self) -> Optional[dict]:
        if self._cache is not None:
            return self._cache
        if not os.path.exists(self.enc_path):
            return None
        try:
            with open(self.enc_path, "rb") as f:
                self._cache = json.loads(self._fernet.decrypt(f.read()).decode())
        except (InvalidToken, ValueError, OSError):
            return None
        return self._cache

    def seal_bytes(self, data: bytes) -> bytes:
        """Encrypts other server-side data that can carry the login (stored channel lists:
        their stream URLs embed it)."""
        return self._fernet.encrypt(data)

    def open_bytes(self, token: bytes) -> Optional[bytes]:
        try:
            return self._fernet.decrypt(token)
        except InvalidToken:
            return None

    def clear_provider(self) -> None:
        self._cache = None
        try:
            os.remove(self.enc_path)
        except OSError:
            pass

    def public_status(self) -> dict:
        """Safe-to-return description: never includes the password."""
        p = self.get_provider()
        if not p:
            return {"signedIn": False}
        return {"signedIn": True, "kind": p.get("kind"),
                "server": p.get("server"), "user": p.get("user")}
