#!/usr/bin/env python3
# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Entry point: python run.py  (honours TVBRO_BIND/TVBRO_PORT/TVBRO_DATA/TVBRO_KEY).
python run.py rotate-key [...]  rotates the encryption key (see tvbro_server/rotate_key.py)."""
import sys

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "rotate-key":
        from tvbro_server.rotate_key import main as rotate_main
        sys.exit(rotate_main(sys.argv[2:]))
    from tvbro_server.app import main
    main()
