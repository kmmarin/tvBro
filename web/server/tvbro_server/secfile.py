# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Secret files (keys, encrypted logins, password hashes, sessions): created 0600 from the
first byte (O_CREAT|O_EXCL with mode 0600, never chmodded afterwards) and swapped in
atomically, so no other account ever sees them, even for a moment."""
from __future__ import annotations

import os
import secrets


def write_secret(path: str, data: bytes) -> None:
    tmp = f"{path}.{secrets.token_hex(6)}.tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0), 0o600)
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    except BaseException:
        try:
            os.remove(tmp)
        except OSError:
            pass
        raise


def read_key_file(path: str) -> bytes:
    with open(path, "rb") as f:
        return f.read().strip()
