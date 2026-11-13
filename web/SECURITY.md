# tvBro Web: security

The threat model, what is protected and how is in [README.md](README.md#security-threat-model-a-lan-site-never-expose-it-to-the-internet).
tvBro Web is a LAN site: never expose it to the internet.

## Where the secrets are (in the container)

| what | where | protects |
|---|---|---|
| encryption key (Fernet, 256 bits) | `/etc/credstore/tvbro.key` (root 0600, systemd credential) | every `*.enc` file below |
| provider logins | `/var/lib/tvbro/provider.enc`, `provider-pN.enc` | your IPTV account |
| stored channel lists / catalogues | `/var/lib/tvbro/channels*.enc`, `vod*.enc` | stream URLs carry the login |
| network-share login | `/var/lib/tvbro/share.enc` | the recordings share |
| signing key | `/var/lib/tvbro/sign.key` | CSRF tokens, signed media / image links, parental unlocks |
| sessions, passwords | `/var/lib/tvbro/sessions.json`, `auth.json` (PBKDF2 hashes) | sign-in |
| backups of old keys | `/root/tvbro-key-backup/` (0700) | can decrypt older copies of the files |

## Rotating the encryption key

Inside the container, as root:

```bash
bash /opt/tvbro/deploy/rotate_key.sh              # stop backend, rotate, start, check /api/health
bash /opt/tvbro/deploy/rotate_key.sh --end-sessions   # also new sign.key + every session ended
```

From the admin machine (the container id and host are in your `web/deploy.local.md`):

```powershell
ssh root@<proxmox-host> 'pct exec <ctid> -- bash /opt/tvbro/deploy/rotate_key.sh'
```

By hand, same thing:

```bash
systemctl stop tvbro-server
cd /opt/tvbro/server && python3 -m tvbro_server.rotate_key     # = python3 run.py rotate-key
systemctl start tvbro-server
```

It prints the backup folder (`/root/tvbro-key-backup/rotate-<time>/`: the old key, `state.db` and every
sealed file as they were, plus `tvbro.key.retired` once done). It never prints a key. If it stops part way
(power cut, full disk), run the same command again: it finishes that rotation; the server refuses to start
until then (`keyrot.json` in the data folder). A wrong or missing key is refused before anything changes.

Afterwards **save a copy of the new key** where you keep passwords (it is the only thing that opens the
stored logins), e.g. `pct exec <ctid> -- cat /etc/credstore/tvbro.key` from the Proxmox host, straight into
the password manager; do not paste it into chats, tickets or logs. Delete the copy of the *old* key from
the password manager only after you have checked the site works.

To go back to the old key: stop the server, copy `tvbro.key.old` over `/etc/credstore/tvbro.key` and
`data/*` into `/var/lib/tvbro/` (owner `tvbro`, mode 0600), start it (see `README.txt` in the backup).

## If your server is compromised

Someone had a shell in the container, a copy of `/var/lib/tvbro` or `/etc/credstore`, a backup of them,
or admin access to the site from an untrusted device. In this order:

1. **Cut access.** Take the device or account you suspect off the network; check the site is not
   reachable from the internet (no port forward, no public DNS record). If the container itself was
   broken into, rebuild it (`deploy/setup_lxc.sh` on a fresh Debian 12 CT) instead of cleaning it, and
   restore only your settings via Settings ▸ Backup & restore (the backup file holds no credentials).
2. **Change the provider password** at your IPTV provider (their website / portal), and the share
   password on the NAS if a network share is set up. The old ones must be assumed known: the key may
   have been taken with the encrypted files, and rotating the key does not make a stolen login secret again.
3. **Rotate the encryption key and end every session**:
   ```bash
   pct exec <ctid> -- bash /opt/tvbro/deploy/rotate_key.sh --end-sessions
   ```
   Save the new key (above).
4. **Enter the new logins** in the site: Settings ▸ the playlist (edit it: Xtream server / user / new password, or the
   new M3U URL) and Settings ▸ Recordings (network share). They are stored encrypted under the new key.
5. **Require a password**: Settings ▸ Security ▸ Require a password, set a new admin password (and a
   viewer password for watching only, if you want). This ends every password-less session; each device
   signs in once. If a password was already on, set a new one there.
6. **Check**: channels, guide and films load; `journalctl -u tvbro-server -n 50` shows no errors; remove
   key copies you no longer trust (the old key in `/root/tvbro-key-backup/` opens only the *old* files,
   which hold the old, now-changed logins: keep or delete those backups as you see fit).

## Reporting a problem

Open an issue in the project without secrets in it (no keys, logins, playlist URLs with credentials).
