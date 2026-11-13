#!/bin/bash
# Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
# Provisions the tvBro web app inside a fresh Debian 12 LXC. Idempotent-ish.
# Expects /tmp/tvbro-web.tar (the web/ tree) to already be pushed into the container.
# Optional: TVBRO_SERVER_NAME=<the site's DNS name> (written to /etc/default/tvbro).
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq --no-install-recommends python3 python3-cryptography nginx curl ca-certificates ffmpeg >/dev/null

id tvbro >/dev/null 2>&1 || useradd --system --home /var/lib/tvbro --shell /usr/sbin/nologin tvbro
install -d -o tvbro -g tvbro -m 0750 /var/lib/tvbro

# unpack app
rm -rf /opt/tvbro && mkdir -p /opt/tvbro
tar -xf /tmp/tvbro-web.tar -C /opt/tvbro
# tarball contains server/, frontend/ and deploy/ at its root
# shellcheck source=lxc_common.sh
. /opt/tvbro/deploy/lxc_common.sh

# vendor the players locally (LAN-only: no CDN at runtime), checked against pinned SHA-256
vendor_players

# site settings and the encryption key (a systemd credential, outside the data folder)
ensure_env
ensure_key

# systemd service
cp /opt/tvbro/deploy/tvbro-server.service /etc/systemd/system/tvbro-server.service
systemctl daemon-reload
systemctl enable --now tvbro-server

# nginx
install -d /etc/nginx/certs/tvbro
rm -f /etc/nginx/sites-enabled/default
install_nginx_site || true

echo "setup done; nginx is reloaded once the cert is in /etc/nginx/certs/tvbro"
systemctl is-active tvbro-server
sleep 1
curl -fsS http://127.0.0.1:8750/api/health && echo
echo "open the site; Settings > Security > Require a password is recommended beyond a trusted home network"
