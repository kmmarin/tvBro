#!/bin/bash
# Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
# Rotate the encryption key inside the container (as root):
#   bash /opt/tvbro/deploy/rotate_key.sh [--end-sessions]
# Stops the backend, re-encrypts every sealed file under a new key (backup in
# /root/tvbro-key-backup/rotate-<time>/, the old key retired there, never deleted), starts the
# backend again and checks it answers. Safe to run again after a crash: it finishes the same
# rotation. Prints no key material.
set -uo pipefail
[ "$(id -u)" = 0 ] || { echo "run as root" >&2; exit 1; }
systemctl stop tvbro-server
rc=0
( cd /opt/tvbro/server && TVBRO_DATA=/var/lib/tvbro python3 -B -m tvbro_server.rotate_key \
    --data /var/lib/tvbro --key /etc/credstore/tvbro.key --backup-dir /root/tvbro-key-backup --port 8750 "$@" ) || rc=$?
systemctl start tvbro-server
ok=0
for _ in 1 2 3 4 5 6 7 8 9 10; do
  sleep 1
  if curl -fsS http://127.0.0.1:8750/api/health >/dev/null 2>&1; then ok=1; break; fi
done
if [ "$ok" = 1 ]; then echo "backend is up"; else
  echo "backend did not come up: journalctl -u tvbro-server -n 30 (an unfinished rotation: run this again)" >&2
  [ "$rc" = 0 ] && rc=1
fi
exit $rc
