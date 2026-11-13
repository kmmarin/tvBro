#!/bin/bash
# Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
# Shared by setup_lxc.sh and update_lxc.sh (sourced, runs as root inside the container).

# the vendored players, pinned by SHA-256 (fetched at deploy time, never from a CDN at runtime)
HLS_URL=https://cdn.jsdelivr.net/npm/hls.js@1.5.17/dist/hls.min.js
HLS_SHA256=484054e8cd03d3f6d1781fb7f402bdc318d8a4c527f933a95c624e27cc9a9470
MPEGTS_URL=https://cdn.jsdelivr.net/npm/mpegts.js@1.7.3/dist/mpegts.min.js
MPEGTS_SHA256=453d5591d09b281a7862e56659aca8e4d0e470d86fe0fcb85684d986475e632e

fetch_pinned() {   # url sha256 dest
  local tmp
  tmp=$(mktemp)
  curl -fsSL "$1" -o "$tmp"
  if ! echo "$2  $tmp" | sha256sum -c --quiet -; then
    rm -f "$tmp"; echo "checksum mismatch for $1" >&2; return 1
  fi
  install -m 0644 "$tmp" "$3"; rm -f "$tmp"
}

vendor_players() {
  install -d /opt/tvbro/frontend/vendor
  local f
  for f in "hls.min.js $HLS_URL $HLS_SHA256" "mpegts.min.js $MPEGTS_URL $MPEGTS_SHA256"; do
    set -- $f
    if ! echo "$3  /opt/tvbro/frontend/vendor/$1" | sha256sum -c --quiet - 2>/dev/null; then
      fetch_pinned "$2" "$3" "/opt/tvbro/frontend/vendor/$1"
    fi
  done
}

# /etc/default/tvbro: this site's name (not in the repo). TVBRO_SERVER_NAME can be passed in.
ensure_env() {
  if [ ! -f /etc/default/tvbro ]; then
    local name="${TVBRO_SERVER_NAME:-tvbro.example.lan}"
    printf 'TVBRO_SERVER_NAME=%s\nTVBRO_ALLOWED_HOSTS=%s\n' "$name" "$name" > /etc/default/tvbro
    chmod 0644 /etc/default/tvbro
  fi
  # shellcheck disable=SC1091
  . /etc/default/tvbro
}

# the encryption key as a systemd credential, outside the data folder
ensure_key() {
  install -d -m 0700 /etc/credstore
  if [ ! -s /etc/credstore/tvbro.key ]; then
    if [ -s /var/lib/tvbro/key.bin ]; then
      install -m 0600 -o root -g root /var/lib/tvbro/key.bin /etc/credstore/tvbro.key
      echo "encryption key copied to /etc/credstore/tvbro.key"
    else
      ( umask 077; python3 -c 'import base64,os;print(base64.urlsafe_b64encode(os.urandom(32)).decode())' > /etc/credstore/tvbro.key.new )
      mv /etc/credstore/tvbro.key.new /etc/credstore/tvbro.key
    fi
  fi
}

# after the service runs with the credential: move the old key.bin out of the data folder
# (kept under /root, never deleted); put back if the service does not come up without it
retire_key_bin() {
  [ -s /var/lib/tvbro/key.bin ] || return 0
  cmp -s <(tr -d '\n' < /var/lib/tvbro/key.bin) <(tr -d '\n' < /etc/credstore/tvbro.key) || { echo "key.bin differs from the credential: left in place" >&2; return 0; }
  install -d -m 0700 /root/tvbro-key-backup
  local keep
  keep=/root/tvbro-key-backup/key.bin.$(date +%Y%m%d%H%M%S)
  mv /var/lib/tvbro/key.bin "$keep"
  systemctl restart tvbro-server; sleep 2
  if ! curl -fsS http://127.0.0.1:8750/api/health >/dev/null; then
    echo "backend did not start without key.bin: restored it" >&2
    mv "$keep" /var/lib/tvbro/key.bin; chown tvbro:tvbro /var/lib/tvbro/key.bin; chmod 600 /var/lib/tvbro/key.bin
    systemctl restart tvbro-server; return 1
  fi
  echo "key.bin moved to $keep"
}

# nginx site with this site's name; reloaded only when it changed and passes nginx -t
install_nginx_site() {
  local new=/etc/nginx/sites-available/tvbro.new
  sed "s/tvbro\.example\.lan/${TVBRO_SERVER_NAME:-tvbro.example.lan}/g" /opt/tvbro/deploy/nginx.conf > "$new"
  if [ -f /etc/nginx/sites-available/tvbro ] && cmp -s "$new" /etc/nginx/sites-available/tvbro; then rm -f "$new"; return 0; fi
  [ -f /etc/nginx/sites-available/tvbro ] && cp /etc/nginx/sites-available/tvbro /etc/nginx/sites-available/tvbro.prev
  mv "$new" /etc/nginx/sites-available/tvbro
  ln -sf /etc/nginx/sites-available/tvbro /etc/nginx/sites-enabled/tvbro
  if [ -f /etc/nginx/certs/tvbro/fullchain.pem ]; then
    if nginx -t 2>/dev/null; then systemctl reload nginx && echo "nginx reloaded"
    else
      echo "nginx -t failed: previous site kept" >&2
      [ -f /etc/nginx/sites-available/tvbro.prev ] && mv /etc/nginx/sites-available/tvbro.prev /etc/nginx/sites-available/tvbro
      return 1
    fi
  fi
}
