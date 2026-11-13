<!-- Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0 -->
# tvBro Web

A browser version of tvBro: the same IPTV experience as the Android/desktop apps
(live TV with groups, EPG and catch-up; movies and shows; search; my list), served
**LAN-only** behind a small backend proxy that keeps the provider login server-side.

Clean-room: built from the public M3U, Xtream `player_api.php` and XMLTV specs and
web-platform docs only. No other application's code, UI or branding was used. Dark theme only.

## Why a backend proxy
Browsers cannot talk to an Xtream/IPTV provider directly — there is no CORS, and raw
MPEG-TS will not play in a `<video>` element. So a backend:
- stores the provider login **encrypted on the server** and talks to the provider for the browser;
- returns compact JSON (groups in provider order, channels, now/next, paged guide, movies/series + details, search);
- relays streams: MPEG-TS is streamed through for `mpegts.js` (MSE); HLS playlists are
  rewritten so every segment is fetched back through the proxy (`/seg`, HMAC-signed);
- proxies and disk-caches logos and posters.

The provider password is **never** sent to the browser, written to a log, or put in any URL.
No sign-in is needed by default; an optional password can be required (see **Security** below). Everything the server fetches for a playlist
is limited to http / https and public addresses.

## Layout
```
web/
  server/                 backend (Python 3 stdlib + cryptography)
    tvbro_server/
      parsers.py          clean-room port of the shared Kotlin data layer
      secretstore.py      Fernet-encrypted provider-login store (key in env or 0600 file)
      rotate_key.py       encryption-key rotation (python3 -m tvbro_server.rotate_key)
      app.py              ThreadingHTTPServer proxy + JSON API + media relay; Sources (several playlists)
      searchidx.py        FTS5 search index (built after each sync, swapped in atomically)
      catcache.py         last good channel list / VOD catalogue on disk (encrypted)
      epgdb.py            the guide on disk (SQLite)
      remux.py            on-the-fly ffmpeg remux of MKV/AVI/… to HLS fMP4 (no video re-encoding)
      recorder.py         server-side recording (record now / scheduled from the EPG)
      tsindex.py          keyframe index of a recorded .ts -> HLS playlist of byte ranges
      layout.py           the household's channel layout (own groups, rename / hide / reorder)
    run.py                entry point
    requirements.txt      cryptography
  frontend/               static SPA (vanilla JS, dark theme)
    index.html  css/  js/ (api.js, player.js, app.js)
    vendor/               hls.js + mpegts.js (fetched at deploy, not committed)
  deploy/
    setup_lxc.sh          provisions a Debian 12 LXC
    rotate_key.sh         rotates the encryption key in the container (stop, rotate, start, check)
    tvbro-server.service  systemd unit (unprivileged, localhost-only, capped)
    nginx.conf            serves static + reverse-proxies the backend
```

## Build / run locally
```powershell
cd web/server
python -m pip install -r requirements.txt
$env:TVBRO_STATIC = "..\frontend"   # dev convenience: backend also serves the static files
python run.py                        # http://127.0.0.1:8750
```
Environment: `TVBRO_BIND` (default 127.0.0.1), `TVBRO_PORT` (8750), `TVBRO_DATA`
(data/state dir), `TVBRO_ALLOWED_HOSTS` (comma-separated host names the server answers to, besides
localhost: the site's DNS name), the encryption key from the systemd credential `tvbro.key`, else
`TVBRO_KEY_FILE`, else `TVBRO_KEY`, else a 0600 `key.bin` in the data dir (a warning is logged),
`TVBRO_ALLOW_PRIVATE_SOURCES=1` (same as Settings ▸ Security ▸ local network sources),
`TVBRO_STATIC` (optional static dir for dev — production uses nginx). `TVBRO_ALLOW_LOOPBACK_SOURCES=1`
is for the test suite's mock provider only.

In production nginx serves `frontend/` and reverse-proxies `/api`, `/stream`, `/seg`,
`/vodstream`, `/img` to the backend; the frontend `vendor/` holds hls.js + mpegts.js.

## How to set up (once, for the whole house)
Open the site, go to **Settings**, choose the provider type and save:
- **Xtream**: server (`http://host:port`), username, password.
- **M3U**: a playlist URL (+ optional XMLTV EPG URL).
- **Demo / single stream**: one public HLS/TS URL — used only for testing.

The server owns everything. The login is stored encrypted on the server. By default no device
signs in: anyone on your network can watch **and change** the app. **Settings ▸ Security ▸ Require a
password** (off by default; recommended whenever the server can be reached from beyond a trusted home
network) makes every device sign in once (admin password, or an optional viewer password for watching
only). All user state (My list, resume points / watched marks,
reminders, search history, preferences) lives in the server's SQLite `state.db`, so every
device shows the same lists; browsers keep only an in-memory view.

## Security (threat model: a LAN site; never expose it to the internet)
- **Sessions and the optional password**: every browser gets its own session from `/api/session` (a
  random 256-bit id in an `HttpOnly; SameSite=Strict` cookie, `Secure` behind HTTPS, 180 days, only its
  SHA-256 stored in `sessions.json`); CSRF tokens and signed media links are bound to it. **No password is
  needed by default**: without one, anyone on your network can use and change the app. **Settings ▸
  Security ▸ Require a password** (off by default) sets the admin password (PBKDF2-SHA256, 600 000
  iterations, `auth.json` 0600), ends every password-less session and makes each device sign in once; an
  optional **viewer password** then signs devices in for watching only. Turn it on whenever the server can
  be reached from anywhere but a trusted home network (and never expose it to the internet). With it on,
  admin only: playlists, settings, restore, recordings folder / share, deleting recordings, folder listing,
  passwords. Wrong passwords: after 5 per address (30 overall) each try waits 30 s, doubling up to 15 min
  (429); the parental PIN has its own limit (5 tries, then 30 s and longer).
- **Writes** need `Content-Type: application/json`, a same-origin `Origin` / `Sec-Fetch-Site` when the
  browser sends them, and the session's CSRF token (`X-CSRF-Token`; sign-in itself `X-TvBro: 1`), with
  or without the password option. Host
  names other than localhost and `TVBRO_ALLOWED_HOSTS` get 421 (DNS rebinding).
- **Outbound fetches** (`net.py`: playlist, guide, provider API, images, streams, segments, recordings):
  http / https only (no `file:`, `ftp:`, `data:`…), ports 80 / 443 / 1024+, and the resolved address is
  checked and used for the connection, on every redirect too: loopback, link-local (169.254.169.254),
  multicast and reserved addresses are always refused; 10/8, 172.16/12, 192.168/16, 100.64/10 and fc00::/7
  only when Settings ▸ Security allows sources on the local network (default off). ffmpeg / ffprobe get
  `-protocol_whitelist http,https,tcp,tls,crypto` (a recording on disk: `file` only) and the address check
  before they start (ffmpeg follows HTTP redirects itself: a remux of a provider stream is the one path where
  a redirect hop is not re-checked; its output is only ever video).
- **Images**: `/img` serves only URLs the server signed in its own answers, only sniffed JPEG / PNG / GIF /
  WebP / AVIF / ICO / BMP (never the upstream type, never SVG / HTML), max 5 MB, with `nosniff` and
  `Content-Security-Policy: sandbox`; the disk cache is capped at 300 MB (oldest out).
- **Media URLs** for players without cookies: HMAC-SHA256 (128 bits), live / catch-up 6 h, films 24 h,
  bound to the session that asked (it ends with that session's sign-out).
- **Secrets**: the provider / share logins are Fernet-encrypted; the key is a systemd credential outside the
  data folder (`LoadCredential=tvbro.key:/etc/credstore/tvbro.key`), else `TVBRO_KEY_FILE` / `TVBRO_KEY`,
  else `key.bin` in the data folder (logged as a warning). A missing key with encrypted data present stops
  the server instead of making a new key; so does a key that opens none of the stored encrypted files
  (wrong key). Secret files are created 0600 with `O_EXCL` and swapped in.
- **Key rotation** (`server/tvbro_server/rotate_key.py`; in the container, as root):
  `bash /opt/tvbro/deploy/rotate_key.sh` (stops the backend, rotates, starts it, checks `/api/health`;
  add `--end-sessions` after a compromise). By hand: `systemctl stop tvbro-server`, then
  `cd /opt/tvbro/server && python3 -m tvbro_server.rotate_key` (same as `python3 run.py rotate-key`;
  `--data`, `--key`, `--backup-dir`), then `systemctl start tvbro-server`. It refuses while the server
  answers on 127.0.0.1:8750, and refuses without changing anything when the current key is missing,
  invalid or does not open the stored logins. It backs up the old key, `state.db` and every sealed file to
  `/root/tvbro-key-backup/rotate-<time>/` (0700, files 0600), writes a new 256-bit Fernet key to
  `tvbro.key.new` (0600), re-encrypts **every** sealed file (`provider*.enc`, `channels*.enc`, `vod*.enc`,
  `share.enc`; `state.db` holds none) into staged copies and swaps them in only once all are ready, checks
  every file opens with the new key, replaces `/etc/credstore/tvbro.key` atomically and only then moves the
  old key into the backup (`tvbro.key.retired`, never deleted). While it runs, `keyrot.json` in the data
  folder stops the server from starting on half-rotated data; after a crash run it again: it finishes the
  same rotation (a crash before any file was swapped rolls back by itself). A damaged stored channel list /
  catalogue is moved to the backup and fetched again. `--end-sessions` also replaces `sign.key` and ends
  every session. Nothing it prints contains key material. Save a copy of the new key afterwards. See
  [SECURITY.md](SECURITY.md) for what to do if the server is compromised.
- **Headers**: CSP (`script-src 'self'`, no inline handlers, `frame-ancestors 'none'`), `nosniff`,
  `Referrer-Policy: no-referrer`, HSTS (nginx), `X-Frame-Options: DENY`. Request bodies max 1 MB (backup
  restore 16 MB; nginx allows 4 MB), 120 s idle timeout, 256 connections; logs carry no query strings.

## Server-side data and sync
- Channels, EPG and VOD are loaded by **one background job per kind** (low priority, off
  the request threads). Results are swapped in atomically; a failed sync keeps the old data.
  A scheduler refreshes each kind on its interval (6/12/24/48 h, default 12, `settings.json`).
- **Never emptied by a bad answer**: a sync that gets 0 channels (or 0 movies and 0 series, or
  a guide with 0 programmes where there were some) keeps the previous data, records
  `lastError` and counts as failed. Failed syncs are retried after 1, 5, then every 15 min
  (`failures`, `nextAt` in the status).
- The last good **channel list and VOD catalogue are stored** (`channels.enc`, `vod.enc`:
  gzip'd JSON encrypted with the login's key, since stream URLs carry the login) and served
  at once after a restart; the channel list is refreshed in the background on start.
- **Search index** (`search.db`, `searchidx.py`): SQLite FTS5 (`unicode61 remove_diacritics 2`,
  prefix 2/3/4) over channel names, movie / series titles + year and programme titles +
  episode titles (one row per distinct title; programmes point at it). Rebuilt in the background
  after every channel / VOD / guide sync and swapped in with `os.replace` (~10 s at nice 10
  under the 60 % CPU cap for 15.8k channels, 34k titles, 386k programmes; 42 MB on disk).
  A query: every word is a prefix ("barc" finds Barcelona), accents ignored, all words must
  match, best first (bm25, names starting with the first word first). Programmes on now /
  upcoming / past with catch-up are decided in SQL (past without catch-up is dropped), grouped by
  channel (10 channels x 5, two places kept for catch-up). 2–30 ms in the server.
- The **EPG is stored on disk** (`epg.db`, SQLite, ~100 MB for ~390k programmes / 15.8k
  channels): a sync writes a new file and `os.replace()`s it in; clients request only the
  visible rows and time window. The stored guide is served right after a restart.
- **Stream limit**: one stream session per browser tab (preview, live, catch-up and VOD
  share it); max 1–3 (default 1, capped at the Xtream account's `max_connections`). A new
  stream stops the oldest active one (its relay closes, further requests get
  `409 {"stopped":"replaced"}`); that tab shows "Playback moved to another window" with
  **Play here**.

## Films in containers the browser cannot play (MKV on iPhone)
iPhone/iPad browsers play MP4/M4V/MOV files; desktop browsers also try MKV/WebM themselves.
Anything else (and anything the browser refuses: the player then asks with `remux=1`) is
remuxed on the server by **ffmpeg** (Debian package in the CT) into HLS with fMP4 segments:
video is copied (HEVC tagged `hvc1`; video that would need re-encoding is refused with a
reason), audio copied when the client plays it (AAC; AC-3/E-AC-3 on Apple devices or where
`MediaSource.isTypeSupported` says so) else converted to AAC stereo/5.1, text subtitles
written as WebVTT and added to the player as text tracks (picture subtitles are skipped with
a note). One remux per stream session (it counts against the stream limit), `nice 10`,
paused (SIGSTOP) two minutes ahead of the viewer, killed after 60 s without requests or when
the session is replaced; segments more than five minutes behind the viewer are deleted.
Seeking outside what is produced starts ffmpeg again at that time (`-ss` with `-copyts`; the
real start keyframe is read from the first segment and served as `info.json`, so the
player's offset, subtitles and resume points stay exact). ffmpeg runs with `-loglevel error`
and its output is scrubbed of URLs (the input URL carries the login). "Open in VLC / Infuse"
stays in the player menu for every film.

## HEVC live TV, catch-up and recordings on iPhone / iPad
Clients without MediaSource (every iPhone / iPad browser) ask for HLS (`fmt=hls`). Apple
players take HEVC only from fMP4 segments, never from MPEG-TS, so for those clients an HEVC
channel is **remuxed live** by ffmpeg: the provider's live TS is copied (`-c:v copy -tag:v
hvc1`; audio copied if AAC / AC-3 / E-AC-3, else AAC) into HLS fMP4 (`-hls_time 4
-hls_list_size 10`, `delete_segments+independent_segments`, `-fflags nobuffer`, 1 MB probe).
`/stream?…&fmt=hls` then answers with ffmpeg's playlist (URIs `/remux/<id>/…`, steady
`EXT-X-TARGETDURATION`, media sequence derived from the clock) as soon as the first segment
exists: ~2.5 s to the first segment with the codec known, ~4.5 s the first time. H.264
channels and every MSE browser keep the zero-cost relay. The codec comes from one quick
ffprobe per channel, cached in `codecs.json` (3 days; a failed probe is retried after 10 min
and the stream relayed meanwhile; a failed remux drops the entry so it is probed again).
Same session rules as film remuxes (one per stream session, counted against the stream
limit, nice 10, killed after 60 s without requests or when the session is replaced or moves
to another stream, temp dir removed). Catch-up of an HEVC channel: the same with an EVENT
playlist from the programme's start (throttled ahead of the viewer like a film; seeking
only within what has been produced). Cost: ffmpeg ~0.5 % of a core and ~65 MB RSS per stream
(BBC One HD, HEVC 1080p50 + E-AC-3).
**Recordings** stay a plain TS copy (no recording CPU). On playback the player asks
`/api/play?kind=rec&id=…`: the recording's own HLS of byte ranges, or for an HEVC recording on
iPhone / iPad (or any client after `remux=1`) a remux of the file from disk with the film
remux (seekable, resume points); a recording still being written is followed (`-follow 1`).
These local remuxes use no provider stream: they neither count against nor are stopped by
the stream limit (at most 2 at a time).

## Recordings
Record now (player menu ▸ Record: until the current programme ends) or from the guide
(programme menu ▸ Record: EPG start/end plus padding). The server copies the channel's
MPEG-TS (HLS sources: their TS segments) to `/var/lib/tvbro/recordings` without conversion,
keeps a keyframe index beside it and serves it as an HLS playlist of byte ranges, so it is
seekable in every browser, also while it is still recording. A recording holds a stream slot
and is never evicted by a viewer (a viewer that finds every slot held by recordings is told
so). It stops at the end time, at the size cap, or when free disk space falls under the floor
(Settings ▸ Recordings). Recordings screen: list, play, stop, cancel, delete.

## Shows: continue watching, resume, next episode, captions, watched marks
Server-side (`series.py`, tables `series` / `episodes` / `series_eps` in `state.db`, schema
version in `PRAGMA user_version`; version 1 moved the old per-episode resume points from `kv`
into `episodes` and kept everything else), so it follows the household across devices.
- An episode counts as **watched** at 90 % or when it played to the end (also when "Up next"
  skips the credits). The **next episode** is the last played one if unfinished (resume), else
  the first unwatched one after it, into the next season; none left = finished.
- **Continue watching** (first row on Shows, also in My list): a show appears as soon as an
  episode starts playing; newest first; the tile shows the next / in-progress episode with its
  progress; finished shows drop off; item menu: Play, Go to the show, Mark watched, Remove.
- Show page: "▶ Resume S2 E4 (12:31)" + "⟲ Play from start", or "▶ Play S2 E5", or "Watch
  again S1 E1" when finished; "Mark as watched / unwatched" for the show; season "⋯" menu for
  the season; episode tiles show progress / ✓ and have a menu (right-click, long press, ⋯, M).
- **Up next**: in the last 30 s (when the length is known, > 2 min) or at the end, a card
  "Up next S2 E5 – title" counts down 10 s (paused with the video), Cancel / Play now (Enter /
  Esc). The next episode plays in the **same `<video>`** (new source, no new player), so iPhone
  full screen and the browser's permission to play with sound carry over; this works for the
  native file, HLS and the ffmpeg remux path alike. Settings > Playback turns it off.
- **Captions per show**: turning subtitles on (language) or off in an episode is saved for the
  show and applied to its later episodes (same language, else the first text track; off stays
  off). The audio language is kept the same way (hls.js tracks, Safari's own audio tracks, and
  for a remux the server is asked once for the matching track).

## Recording folder and network shares
Settings > Recordings > "Folder / network share…": browse folders on the server (under `/mnt`,
`/media`, `/srv` and the data dir), **Test** (writable, free space, own mount or not) and use one
for new recordings (old ones stay where they were written). The container is an **unprivileged** LXC:
it cannot mount SMB / NFS itself, so a share is mounted on the Proxmox host and bind-mounted in.
The dialog stores the share (login encrypted, never returned or logged) and prints the host
commands to review and paste (every value validated and shell-quoted; the container id is set in
Settings ▸ Security, else the lines start with `CTID=`), e.g. for SMB (tvbro is uid 999 / gid 996
in the CT = 100999 / 100996 on the host; container 345 here):
```sh
mkdir -p /mnt/tvbro-share
printf 'username=%s\npassword=YOUR_PASSWORD\n' USER > /root/.tvbro-smb.cred && chmod 600 /root/.tvbro-smb.cred
echo '//nas.example.lan/share /mnt/tvbro-share cifs credentials=/root/.tvbro-smb.cred,uid=100999,gid=100996,file_mode=0660,dir_mode=0770,_netdev,nofail,x-systemd.automount 0 0' >> /etc/fstab
systemctl daemon-reload && mount /mnt/tvbro-share
pct set <ctid> -mp0 /mnt/tvbro-share,mp=/mnt/recordings,backup=0
pct reboot <ctid>
```
Then pick `/mnt/recordings` in the dialog, Test, "Use this folder". (NFS: an fstab `nfs` line and
the export must let host uid 100999 write, e.g. `all_squash,anonuid=100999,anongid=100996`.)
(The host step is the admin's.)

## Series recordings
Guide programme menu ▸ "⟳ Record series": a rule (channel + title, `series_rules.json`) that
schedules every airing of that title on that channel found in the guide (next 8 days), each
once; checked every 10 minutes. Recordings screen ▸ "Series recordings" lists rules; stopping one
cancels its scheduled recordings (finished ones are kept).

## Guide and player behaviour
- **The guide never shows the past**: the 150-min window starts at the current half hour and only goes
  forward (arrows, the ‹ button, the wheel). Left at the window start moves the focus to the channel
  name (never scrolls back, never traps); Right from the name lands on the programme on now (window back
  at now). A window that time has left behind snaps to now (30 s tick), and a focused programme that
  has ended moves to the one on now. Earlier programmes with catch-up: channel / programme menu ▸
  "⟲ Catch-up: earlier programmes…" (newest first).
- **Focus (keyboard / remote)**: zones side menu < groups < channel name < programmes; Left walks them
  one by one, Right comes back the same way; entering the groups column focuses the selected group;
  every zone has a visible mark. Other screens use spatial focus (nearest item in the arrow's
  direction); Right / Enter from the side menu returns to the item focused last in that screen (an empty
  screen keeps the focus in the menu); an arrow after the focus was lost (a click on empty space) goes
  back to it; back from a film / show page the opened tile is focused again. Escape / Backspace close the
  topmost layer: menu > dialog > player panels (quick settings, info) > full screen > screen (> side
  menu). A `<select>` opens with Enter (Up / Down move on, they no longer change its value), and text
  fields are left with Up / Down, Escape or Left at the start of the text.
- **The preview plays the current channel, never the focused one**: moving through the guide changes
  only the info panel. Enter on another channel (its name or its programme on now) makes it the current
  channel and the preview switches to it; Enter on the current channel goes full screen. With the
  preview off, Enter goes full screen at once. There is no "preview follows focus" setting. The first
  time (nothing played anywhere yet) the first channel shown becomes the current one.
- **Resume the last channel on open**: the current channel is kept on the device (`tvbro.ui`) and on the
  server (`prefs.lastChannel` in `state.db`, with its time). At start the newer of the two is the current
  channel, so a new or cleared browser, or another device, opens on the channel watched last (the preview
  plays it; a device that never showed the guide focuses it). One that no longer exists is forgotten.
  The web version has no user profiles: "per user" is the household.
- Up / Down keep the time column but never land on a programme that has already ended (the window starts
  at the half hour, so its first minutes can be over).
- The guide keeps its group, focused channel, scroll position and focus zone when the nav rail
  opens / closes or another screen is visited, and after a reload (per device, `localStorage`
  `tvbro.ui`: section, guide position, page scroll per section, full screen or guide, the playing
  channel; a reload in full screen starts that channel again, muted if the browser insists).
- Leaving full-screen live TV (Back / Esc / ‹ Back) shrinks the picture into the preview box
  (0.32 s transform) and the **same `<video>`** with the same engine and stream keeps playing
  there, with sound, until another channel is made current with Enter (then the preview is muted per
  the preview setting). Also with the preview off (the box keeps what was watched). Not used with audio
  delay (Web Audio).
- Whenever the guide comes back from the player it focuses and scrolls to the playing channel,
  in the group it was started from (else its own group).
- Every tile has a "⋯" menu button (also right-click, long press, M / ContextMenu key): guide rows
  (★ favourite toggle on the row; Watch / Catch-up / Remind / Record / Record series / Favourites
  / add to group / rename / hide / remove), shows (My list, mark watched / unwatched), episodes,
  Continue watching, My list tiles, reminders.
- The player's quick-settings bar hides after 5 s without input (mouse, key, touch).
- Settings > General > **Time format**: 12-hour (default, "4:30 PM"), 24-hour, or browser
  default; a server preference, used for every time shown.

## Live reconnect and alternate sources
A live channel that errors (stream refused, decoder error) or stalls (no progress for 15 s while
playing or starting) is reconnected after 1, 3 and 6 s with a freshly signed URL. It keeps its stream
slot, and never reconnects when another window took the stream (that stays "Playback moved"). Then
the channel's **alternate sources** are tried, one reconnect each: `GET /api/alternates` lists the other
entries of the same channel, same tvg-id or the same name without quality / backup words ("BBC One HD",
"BBC One FHD", "BBC One (backup)"; "+1" and country prefixes are kept), from any enabled playlist, own
playlist first, hidden and locked ones left out. When every source failed it rests 30 s and starts over.
A small corner label shows it ("Reconnecting… (2/3)", "Trying another source: …", "Playing from another
source: …"); there is no dialog and the keys keep working. The guide, the current channel and the info
panel keep showing the channel that was asked for (badge "Alternate source"). The TV preview and the
docked full-screen video recover the same way. Policy: `livefix.js` (unit-tested).

## Parental PIN
Settings ▸ Parental controls: set / change / remove the PIN (4–8 digits) and "Locked groups and
categories…" (every TV group, film and show category, and the channels locked one by one). The TV channel
menu (M) locks / unlocks the channel or its group. The PIN is stored only as a salted PBKDF2-SHA256 hash
(`parental.json`, 0600, never returned or logged); after five wrong PINs each try waits 30 s (longer
after more). A right PIN gives that browser tab an HMAC-signed unlock token (4 h, `sessionStorage`; a PIN
change ends every unlock). The server enforces it: without the unlock it answers `423` instead of signing
a live / catch-up stream or a film, and gives no film / show details (a show's episodes are reached only
through them). Lists mark locked items (🔒 on guide rows, groups and tiles); the PIN is asked before playing;
the preview does not play a locked current channel until it is unlocked. Locks only bite while a PIN is
set. Not covered: a recording of a locked channel, and a signed link handed out before the lock (24 h).

## Backup and restore
Settings ▸ Backup & restore: **Download a backup** (`tvbro-backup-YYYY-MM-DD.json`) and **Restore from a
file…** (the server checks the whole file first and says what is in it, you confirm, then it is
written; admin only). In it: My list / favourites, resume points and watched marks,
shows' progress, reminders, search history, preferences, the channels & groups layout, parental locks,
the guide mapping, refresh / recording settings and series-recording rules. Never in it: provider
logins, the admin / viewer password or parental PIN, keys, sessions and tokens, recordings. Each section in the file replaces the
server's; series-recording rules are added (none is cancelled). A bad file changes nothing.

## Manual channel-to-guide mapping
TV channel menu (M) ▸ "Guide channel…" (or 📺 in Channels & groups): a searchable list of the playlist
guide's own channels (XMLTV ids + names, stored with every guide sync), plus Automatic and "No guide" (a
wrong automatic match). Stored on the server (`epgmap.json`), laid over the automatic match (tvg-id,
then name) and used by the guide, now / next, search and series rules. A guide channel nothing matched
before has no stored programmes: picking it starts a guide sync and the row fills in when it is done.

## Live TV in a player app
Player quick settings ▸ "Open in app" and the TV channel menu ▸ "Open in a player app…": the channel's
server-signed link (absolute, 24 h) with Open in VLC / Infuse, Copy link and Download .m3u. It fits the
LAN-only design: the link names only the channel, an expiry and an HMAC signature (no provider address
or login), the server relays the stream and its HLS segments as for a browser, it is asked in the
channel's own format (no browser remux), it has its own stream session (counts against the limit) and a
locked channel needs the PIN first. It works only where the site's name resolves (the LAN), and stops when the session that made it signs out.

## Several playlists, and your own channel layout
Settings ▸ "+ Add a playlist" adds more sources next to the first (p0 = `provider.enc`,
`epg.db`; others pN = `provider-pN.enc`, `epg-pN.db`; order / names / enabled in
`sources.json`). Every channel / VOD key carries its source (`p1|…`, VOD ids `p1~123`), so
each request goes to its own provider; groups of the second and later playlists are labelled
with the playlist name. A disabled playlist is hidden and not synced.
"Channels & groups" (Settings, or the TV channel menu) edits `layout.json`: your own groups
(filled from any playlist), and rename / hide / move for groups and channels. Provider data
is never changed, so syncs keep every edit.

### API (JSON; every endpoint except `/api/health` and `/api/session` needs the browser's session cookie, writes also `X-CSRF-Token`; see Security)
| Endpoint | Shape |
|---|---|
| `GET /api/session` · `POST /api/auth/login {password}` · `POST /api/auth/logout` · `POST /api/auth/require {enabled, password?, current?}` · `POST /api/auth/password {current, password}` · `POST /api/auth/viewer {password}` | session: `{auth: ok\|login, role, csrf, required, signedIn, kind, sources, viewerPassword}` (no password required: a new browser gets its session cookie here); login needs `X-TvBro: 1`; wrong passwords 403, then 429 `{retryAfter}`; require / password / viewer: admin |
| `GET /api/sync/status` | `{playlists:[{id, name, kind, enabled, channels:{…job, channels, groups}, epg:{…job, channelsWithGuide, channels, programmes, from, to}, vod:{…job, movies, series, supported}, search:{…job, channels, vod, titles, programmes}}], settings:{epgHours, channelsHours, vodHours, maxStreams, allowLanSources, ctid, rec…}, account:{max_connections, active_cons, status, exp_date, is_trial}, streams:{max, count, active:[{sid, label, what, since, idleSec}]}, time}` where `job = {state: idle/starting/downloading/parsing/saving, running, progress:{bytes,total,programmes,…}, startedAt, lastOk, lastDurationSec, lastError:{at,msg}, failures, nextAt (the retry time while failing), intervalHours}` |
| `POST /api/sync/{epg,channels,vod,all}` | starts the job(s); returns the status above |
| `POST /api/settings` | `{epgHours?, channelsHours?, vodHours?, maxStreams?, allowLanSources?, ctid?, rec…}` → status (admin) |
| `GET /api/state` | `{mylist:[…], resume:{key:{secs,dur,at,done?,meta}}, reminders:[…], history:[{q,at}], prefs:{…}}` |
| `POST /api/state/<scope>` | `{op:"put"|"del"|"clear", key, value}` (scopes: mylist, resume, reminders, history, prefs) |
| `GET /api/play?kind=live&key=…` / `kind=movie|episode&id=…&ext=…` | `{url}` signed `/stream` / `/vodstream` URL (24 h); the client appends `&sid=` |
| `POST /api/stream/{start,heartbeat,stop}` | `{sid, label?, what?}` → `{ok, replaced}` / `{ok}` or `{stopped:"replaced"}` |
| `GET /api/groups`, `/api/channels`, `/api/nownext`, `/api/guide`, `/api/vod/*`, `/api/catchup` | catalogue / guide (guide paged by `keys` + `from`/`to`); `all=1` on groups/channels includes hidden ones (editor) |
| `GET /api/search?q=` | `{movies:[≤20 VOD items], series:[≤20], channels:[≤30 channel + now:{start,stop,title}], programs:[≤10 {ch, items:[≤5 {start, stop, title, sub?, ep?, st:"now"|"future"|"catchup"}]}], ms}`; `{…, indexing:true}` while the first index is built. No descriptions. |
| `GET /api/prog?key=&start=` | one programme in full (with `desc`) for the search card |
| `GET /api/play?kind=movie\|episode&id&ext&sid&client=ios\|mse&caps=hevc,ac3,eac3,av1[&remux=1&ss=&a=]` | `{fmt:"file", url, file}`, `{fmt:"hls", url:"/remux/<id>/index.m3u8", file, remux:{ss, duration, info, audio, audioPos, subs:[{label, lang, url}], notes}}` or `{fmt:"external", why, file}` |
| `GET /remux/<id>/{index.m3u8, init.mp4, sNNNNN.m4s, subN.vtt, info.json}?sid=` | the running remux (`info.json` = `{start, ss}`) |
| `GET /api/recordings` · `POST /api/recordings {key, title?, start?, stop?}` · `POST /api/recordings/{stop,delete} {id}` | `{items:[{id, key, channel, logo, title, start, stop, progStart, progStop, status, note, size, duration, rule?}], disk:{free, total, used, capBytes, floorBytes}, settings, rules:[{id, key, title, channel, logo}], storage:{dir, default, share:{type, server, share, user, mountpoint, hasPassword}\|null}}` |
| `POST /api/recordings/series {key, title}` · `POST /api/recordings/series/delete {id}` | series rule: every airing of that title on that channel in the guide is scheduled (checked every 10 min and after the rule is made; each airing once); deleting a rule cancels its scheduled recordings |
| `GET /api/recordings/dirs?path=` · `POST /api/recordings/storage/test {path}` · `POST /api/recordings/storage {path}` (admin) | folder picker (under /mnt, /media, /srv, the data dir), test → `{ok, path, writable, free, total, mount, separateMount, why}`, use it for new recordings (`settings.json` `recDir`; each recording remembers its folder) |
| `POST /api/recordings/share {type: smb\|nfs, server, share, user, pass, mountpoint}` / `{clear:true}` (admin) · `GET /api/recordings/share/steps` | network share settings, login Fernet-encrypted in `share.enc`, never returned → `{share, steps:[host commands]}` |
| `GET /api/series/continue` | `{items:[{id, name, poster, key, next:{id, season, num, title, ext, duration, image, secs, dur, resume}, at}]}` started, unfinished shows, newest first |
| `GET /api/series/state?id=` | `{id, name, poster, key, lastEp, subs, audio, next, finished, started, episodes:{epId:{secs, dur, done, at}}}` |
| `POST /api/series/progress {seriesId, epId, secs, dur, ended?, name?, poster?}` | records a position (also at the start, secs 0) → the summary without `episodes` |
| `POST /api/series/mark {seriesId, watched, season?, epId?}` · `POST /api/series/prefs {seriesId, subs?, audio?}` · `POST /api/series/hide {seriesId}` | watched / unwatched for an episode, a season or the whole show (→ state); caption / audio choice per show; drop from Continue watching until it plays again |
| `GET /rec/<id>.m3u8`, `/rec/<id>.ts` (Range) | a recording as HLS of byte ranges / the file |
| `GET /api/play?kind=rec&id&sid&client&caps[&remux=1&ss=&a=]` | a recording: `{fmt:"file", url:"/rec/<id>.m3u8", file}` or (HEVC on iPhone) the remux answer as for films |
| `POST /api/signin {kind, name?, server, user, pass, url, epgUrl}` | adds a playlist (admin; `url` / `epgUrl` / `server` http or https only) → `{id}` |
| `POST /api/sources/<id>/{update {name?, enabled?}, sync, delete}` | playlist management (admin) → status |
| `GET /api/alternates?key=` | `{items:[channel]}` other entries of the same channel (same tvg-id / name), tried when it stays down |
| `GET /api/parental` · `POST /api/parental/{unlock {pin}, pin {pin?, newPin}, lock {kind: group\|channel\|vodcat, id, locked}}` | `{pin, active, groups, channels, vodCats, unlocked}`; unlock → `{token, ttl}` (sent back as `X-Parental`); wrong PIN 403, waiting 429 `{retryAfter}`; locked content answers 423 `{locked:true}` |
| `GET /api/backup` · `POST /api/restore[?check=1]` (body: the file; admin) | the backup file; check → `{contains}`, restore → `{restored:{…counts}}`; 400 with the reason for a bad file |
| `GET /api/epg/channels?key=&q=` · `POST /api/epg/map {key, guideId}` | `{items:[{id, name, hasGuide}], needSync, current:{mode: auto\|manual\|none, id, name, hasGuide}}`; guideId an XMLTV id, `"__none"` or null (automatic) → `{ok, syncing, current}` |
| `GET /api/layout` · `POST /api/layout {op, …}` | `group.create {name}`, `group.rename {id, name}`, `group.hide {id, hidden}`, `group.move {id, dir}`, `group.delete {id}`, `channel.rename {key, name}`, `channel.hide {key, hidden}`, `channel.move {group, key, dir}`, `channel.add/remove {group, key}`, `reset` |

## Tests
`node --test web/tests/guide.test.mjs web/tests/vod.test.mjs web/tests/reminders.test.mjs web/tests/playfmt.test.mjs web/tests/resume.test.mjs web/tests/livefix.test.mjs`
and `python -m unittest discover -s web/tests` (security attack cases in `test_security.py`: `file:` / `gopher:` /
`ftp:` / `data:` / `concat:` sources and streams, loopback, 169.254.169.254, 10.x / 172.16.x / 192.168.x / 100.64.x,
redirects to them, DNS rebinding, ffmpeg protocol whitelist, CSRF without token / wrong origin / text/plain,
unknown Host, `/img` with HTML (XSS) or unsigned URLs, viewer vs admin, oversized bodies, password and
parental PIN brute force, strict share values; resume-channel choice, live recovery policy and stall
watch, alternate-source matching, parental PIN hash / wait / tokens / locks, backup round trip and
validation, guide mapping in the parser / guide / search; guide layout math, episode-title cleaning,
next-episode choice, search classification, reminder timing, XMLTV episode numbers,
stream replace-oldest logic, state store, stream-format choice, remux decisions / ffmpeg
command / start-time parsing, WebVTT cues, recording timing / space rules / a real copy of a
synthetic TS, channel layout edits, search index: prefix / accents / ranking / now-upcoming-catch-up
classification / grouping, a sync that gets nothing keeps the old data and backs off, stored
lists are encrypted and come back after a restart; shows: watched threshold, next episode into
the next season, Continue watching order, caption choice, marks, the state.db migration and the
`/api/series/*` endpoints; series recording rules, recordings folder picker / test, share login
encryption and host steps; key rotation in `test_rotate_key.py`: round trip, a crash at every step then a
re-run, roll back before anything changed, wrong / missing / invalid key and a running server refused, the
server refusing to start mid-rotation or with a wrong key, file modes (POSIX: run it in WSL too),
`--end-sessions`, no key material printed).

### Browser tests (focus / guide / preview / full screen)
`node --test web/tests/e2e/focus.e2e.mjs` starts a mock provider (M3U with 8 channels in 2 groups +
XMLTV now-12h..now+24h, a mock Xtream playlist with 14 films and 2 shows, an ffmpeg-made HLS clip), the
real backend on a free port with a temp data dir, and headless Chrome (or Edge) through Playwright, then
drives arrows / Enter / Escape through the guide, menus, the player and every screen: window at the half
hour and never earlier (also after time passes, with a fake clock), Left / Right zones and restoration,
focus always visible, the preview not following focus, Enter to switch / go full screen, Escape layer by
layer, the same `<video>` docked into the preview still playing with sound (no pause / emptied events),
focus on the playing channel, film / show page and back to the same tile, no page errors.
`node --test web/tests/e2e/parity.e2e.mjs` (same mock stack, plus a channel whose stream is down with
an "HD" entry of the same tvg-id, and a channel the guide does not match): resume on a new browser, the
down channel reconnecting and then playing from its alternate (notice, no dialog), parental PIN set in
Settings / lock from the channel menu / a new browser must give it (wrong PIN refused, wait after five)
and a locked film category, backup download / bad file / restore, guide mapping picked with the keyboard
and used by the guide and search, the live link and .m3u played outside the browser.
Node 25 needs the file names (`node --test web/tests/e2e/` alone fails to load the folder).
Needs Playwright (`PLAYWRIGHT_DIR`, or a normal `playwright` install), ffmpeg, python + cryptography and
`web/frontend/vendor/hls.min.js` (not committed: copy it from the server's `/opt/tvbro/frontend/vendor/`); missing pieces skip the test.

## Search speed (measured on a small home server, from the LAN)
| query | before: right after a restart (in this order) | before: warm | now: right after a restart | now: warm | size before → now (gzip) |
|---|---|---|---|---|---|
| barcelona | 11.6 s | 0.34–0.40 s | 0.04 s | 0.027 s | 32 KB → 1.8 KB (6.5 KB raw) |
| bbc | 0.61 s | 0.35–0.36 s | 0.09 s | 0.036 s | 80 KB → 2.0 KB (15 KB) |
| reacher | 0.42 s | 0.38–0.46 s | 0.03 s | 0.029 s | 11 KB → 1.4 KB |
| news | 0.36 s | 0.54–0.75 s | 0.07–0.16 s | 0.05–0.06 s | 86 KB → 3.3 KB (18 KB) |
| bb | 0.38 s | 0.38–0.75 s | 0.05–0.10 s | 0.033–0.045 s | 105 KB → 2.2 KB (14 KB) |

Before, the first search after a restart waited for the whole channel list and VOD catalogue
to download (11.6 s); every search then scanned 386k guide titles with `LIKE '%q%'` twice
(~160 ms), looked up now-playing for each matching channel, and sent up to 120 programmes with
descriptions uncompressed. Now the server time is 2–30 ms (`ms` in the answer); "cold" numbers
right after a restart include the restart's own background channel refresh and index rebuild.
The search box waits 200 ms after typing, a newer query cancels the older request, and the
previous results stay (dimmed) until the new ones arrive.

## Deploy (a Debian 12 LXC behind nginx)
First install: push the staged tar into the container and run `deploy/setup_lxc.sh` with
`TVBRO_SERVER_NAME=<the site's DNS name>`; updates: `powershell -File web\deploy\stage.ps1 -Out <dir>`,
push `tvbro-web.tar` + `deploy/update_lxc.sh`, run `update_lxc.sh restart` (back up `state.db` first).
The scripts keep the site's own settings in `/etc/default/tvbro` (`TVBRO_SERVER_NAME`,
`TVBRO_ALLOWED_HOSTS`), the encryption key as a systemd credential in `/etc/credstore/tvbro.key`
(an existing `key.bin` is copied there once and then moved out of the data folder to
`/root/tvbro-key-backup/`, never deleted), install the nginx site with the site's name and
check the vendored players against pinned SHA-256 sums. Keep a copy of the key: without it the
stored logins cannot be decrypted (a new one after every rotation: `deploy/rotate_key.sh`, see Security). Your own hosts, addresses and container id belong in
`web/deploy.local.md` (gitignored; template `deploy.local.md.example`).
## Parity with the Android app
Rule: keep all tvBro versions (Android, web, desktop) as close as possible. Compared with the
Android app (main branch README / docs/ROADMAP.md, 2026-11-11):
- **Done here too**: live TV with groups / now-next / favourites / number zapping, guide with
  catch-up / record / remind, programme search (now the server's index), VOD with resume and
  watched marks, My list, custom groups and hide / rename / reorder, several playlists,
  recordings (server-side), reminders, voice search, audio delay (positive only), aspect, sleep
  timer, caption size / background, external player for films (VLC / Infuse),
  **picture in picture** (player menu; added with the search work).
- **Matched with the Android app (2026-11-11, built in parallel; same rules)**: shows Continue
  watching (first row, appears on start, next / in-progress episode, finished drop off),
  per-episode resume with "Resume S2 E4 (12:31)" / "Play from start", Up next with a 10 s
  countdown into the next season (Settings > Playback switch), captions (and audio language)
  kept per show, watched / unwatched per episode / season / show with auto-watched at 90 %;
  guide position memory and focus on the playing channel; item menus everywhere (favourite,
  record, record series, remind, watch, catch-up, hide / rename / move, mark watched, My list);
  series recordings (server rule); recordings folder incl. network share (host bind mount in
  the web version); quick-settings bar auto-hide (5 s); shrink-to-preview when leaving full
  screen with the same video playing; 12-hour time by default with a time-format setting;
  UI restored on reload / relaunch (per device).
- **Matched with the TV app's fixes of 2026-11-11 (night)**: the guide never shows the past (window at the
  half hour, Left to the channel name, snap to now, catch-up from the menus); consistent remote focus
  (zones, last item restored, Back closes the topmost layer, no traps); the preview plays the current
  channel and does not follow focus (Enter switches, Enter again = full screen); back from full screen
  the same video keeps playing in the preview with focus on the playing channel. See docs/PARITY.md.
- **Closed on 2026-11-12 (tvbro-web6)**: resume the last channel on open (server state); live
  reconnect with back-off + alternate-stream fallback (player, preview, docked video); parental PIN for
  TV groups, channels and film / show categories (hashed server-side, enforced by the server); backup /
  restore to a JSON file (no credentials); manual channel-to-guide mapping; external player for live TV
  (signed link, .m3u). See the sections above and docs/PARITY.md.
- **Android has, web still lacks**:
  - **Downloads for offline** (movies / episodes / seasons): in the web version this would be
    server-side downloads to the CT's disk, played like recordings.
- **Known limits of the new items**: a show's episodes are locked through its details (an episode URL
  signed before the lock keeps working for 24 h, as every signed link); the parental unlock is per browser
  tab (Android: per app session); the guide mapping needs one guide sync for a guide channel nothing
  matched before.
- **Not applicable in a browser**: frame-rate / refresh-rate matching, launch on boot, Google TV
  global search.
- **Neither has yet**: pause / rewind live (timeshift), multi-view.

## Constraints honoured
- LAN only: no WAN exposure, no port-forward, no public DNS record.
- The provider login is never exposed to the internet, the browser, logs, or URLs.
- Clean-room; tested only against a public HLS test stream and local mocks, never a real provider.