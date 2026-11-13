#!/bin/bash
# Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
# Update an existing CT: unpack /tmp/tvbro-web.tar over /opt/tvbro (vendor/ kept, checked),
# install the nginx site / systemd unit when they changed, move the encryption key to a
# systemd credential once, restart the backend only when asked ($1 = restart).
set -euo pipefail
cd /opt/tvbro
tar -xf /tmp/tvbro-web.tar -C /opt/tvbro
find /opt/tvbro/server -name __pycache__ -type d -prune -exec rm -rf {} +
chown -R root:root /opt/tvbro/frontend /opt/tvbro/server /opt/tvbro/deploy
chmod -R a+rX,go-w /opt/tvbro/frontend /opt/tvbro/server /opt/tvbro/deploy
# shellcheck source=lxc_common.sh
. /opt/tvbro/deploy/lxc_common.sh
vendor_players
ensure_env
ensure_key
unit_changed=0
if ! cmp -s /opt/tvbro/deploy/tvbro-server.service /etc/systemd/system/tvbro-server.service; then
  cp /opt/tvbro/deploy/tvbro-server.service /etc/systemd/system/tvbro-server.service
  systemctl daemon-reload
  unit_changed=1
fi
install_nginx_site
if [ "${1:-}" = "restart" ] || [ "$unit_changed" = 1 ]; then
  systemctl restart tvbro-server; sleep 2
  curl -fsS http://127.0.0.1:8750/api/health >/dev/null && retire_key_bin || true
fi
systemctl is-active tvbro-server
rm -f /tmp/tvbro-web.tar
echo "deployed: $(grep -o 'v=[^"]*' /opt/tvbro/frontend/index.html | head -1)"
