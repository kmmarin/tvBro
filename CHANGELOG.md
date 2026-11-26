# Changelog

All notable changes to tvBro. Versions follow `MAJOR.MINOR.PATCH`; the Android app's version code is
`MAJOR * 10000 + MINOR * 100 + PATCH`.

## 0.3.4 (Android)

### Added
- **Encrypted backup** (Settings > Channels and groups > Backup): **Export backup** saves everything you set up in one
  file: sources with their logins, favourites, channel layout, locks and PIN, all settings, watch
  positions and shows' progress, reminders, series rules and the recording and download lists. The file is
  encrypted with a passphrase you choose (AES-256-GCM, key from PBKDF2-HMAC-SHA256, 200,000 rounds).
  **Import backup** opens one from the backup folder or with the system file picker, shows what it holds,
  adds it to what is already on the device (nothing is removed; a PIN set here stays) and seals the logins
  again with this device's own key. An imported file from the backup folder is deleted afterwards.
  Moving tvBro to a new TV or reinstalling it no longer means typing logins again.
- **Import a backup** straight from the first screen (Add source) of a new install.

### Changed
- The plain settings backup without logins is replaced by the encrypted backup; a settings file of an
  older version can still be restored from Import backup.

### Fixed
- **Password fields** (provider password, PIN, passphrase) now tell the on-screen keyboard they are
  passwords: keyboards no longer learn or suggest them, and the Fire TV keyboard shows them in its password
  font with its Hide Password key (it treated them as ordinary text before).

## 0.3.3 (Android)

### Changed
- **One search**: Movies and Shows no longer have their own search box (it could keep an old query and
  filter the list); the global Search is the only search.
- **Guide**: the full-height "now" line is gone. The programme on now shows how far along it is with a
  progress bar inside its own box, the same bar as the info panel.

### Fixed
- **Fire TV**: the home-screen tile showed a broken image; the launcher icon and banner now also come as
  PNG for devices that can't draw the vector versions (Android before 8.0).

## 0.3.2 (Android)

### Changed
- **Seeking like streaming apps** (movies, episodes, recordings, catch-up): tap Left / Right to skip 10 s;
  hold to scrub at 2x, which keeps going after you let go; press or hold the same way again for 4x, then 8x;
  the other way slows down and stops below 2x. The bar shows the speed and where it is. The remote's
  fast-forward / rewind keys step through the same speeds.
- **OK** shows the banner; OK again pauses / plays (while scrubbing: plays from the new place). The banner
  stays while paused. Hold OK, press Down or swipe up for the extra options.

## 0.3.1 (Android)

### Changed
- **Touch everywhere**: on touch screens, tap a multiview tile to move the sound to it and hold a tile
  for its options; hold anywhere in the player for the action icons (same as holding OK); swipe up or
  tap the chevron under the banner to open its rows, swipe down to close them; settings changed with
  left/right have on-screen â€¹ â€º buttons.
- The multiview sound marker is a speaker icon (the old note symbol was missing on some devices).

### Fixed
- Back on the small preview (after leaving full screen) now stops the video; the channel stays selected.

## 0.3.0 (Android)

### New
- **Multiview**: watch 2 to 4 live channels at once (side by side, 1+2, 3 in a row, 2x2 or 1+3). Focus and
  sound follow the remote; OK on a tile changes, adds or removes a channel; Back goes full screen.
- **Stream limit per provider**: tvBro reads each Xtream provider's connection limit and keeps every
  preview, recording and multiview tile within it, naming the provider when a limit is reached. A
  recording that starts closes multiview tiles past the limit.
- **Account info** (Settings > Playlists): status, expiry and created dates, trial, active connections
  and max streams for each Xtream source, with a warning when the account expires within 14 days. The
  password is never shown.
- Long-press Back opens the playing channel.

### Changed
- The global "Maximum simultaneous streams" setting is replaced by a number per source (taken from the
  provider when it reports one); the old value is kept as each source's fallback.

### Fixed
- A recording interrupted when the app was closed no longer stays "recording" and holds a stream.
- One OK press is one action: pop-ups no longer open and close at once.

## 0.2.0 (Android)

### New
- **One player menu** for live TV, catch-up, films, episodes and recordings: a one-line banner with an
  action row (audio, captions, picture size, sleep timer, favourite, guide, history and more).
- **Captions** for live TV: pick a language once, it stays on for every channel.
- **Instant start**: the TV screen you left is shown at once when the app opens, then quietly
  refreshed. The channel you were watching starts again.
- **Network share (SMB) setup on one screen**, with a Test button before saving. Fill it in from your
  phone by scanning a QR code; the password never goes back to the phone.
- **Settings > Appearance > Scrolling**: lists move under a centred selection (default), or the
  selection moves.
- **Settings > Appearance > Show channel numbers** (off by default).
- **Last channel and History** in the player; Rewind in full screen goes back to the last channel.
- **Catch-up from the guide in two presses.**
- **Undo** after hiding a group.
- Movies and Shows open on the category you looked at last.
- Settings > About > Advanced: restart the app in one press.

### Faster
- New parsing and guide engine (a small native library, the "core") for playlists, Xtream lists, the TV
  guide and search. Lower memory use and faster guide loading on Fire TV sticks.
- TV guide: focus moves in one frame, rows load in batches with their programmes, neighbouring groups
  are prepared in advance, 3-hour window, 24-hour guide redraws per row.
- Channel changes cancel the previous request at once; Up/Down zaps within the playing channel's group.
- Movies / Shows, My list, Recordings, Settings and the side menu open faster; the first 48 titles show
  at once.
- Search lists results like other TV apps: rows alphabetical, favourite channels first, programmes by
  channel; the keyboard opens with the screen.
- Smaller downloads: per-device APKs (the in-app updater picks the right one; `tvbro.apk` stays the
  universal download).

### Fixed
- Recording and downloads no longer crash on Android 7 (Fire TV Stick 4K).
- A stream that does not play now says so in the player's banner, shows each new try, and stops after
  five tries.
- The guide keeps focus in the grid (it no longer falls to the side menu); Back in the guide returns to
  now, then to the groups.
- Pop-ups take focus, also over Settings; moving or hiding groups keeps focus in the group column.
- No false "no picture" message when full screen takes over the preview's stream.
- A damaged details cache is fetched again instead of showing nothing.
- The captions chooser opens on the first track when captions are off; History leaves out the playing
  channel.

### Security
- The start-up snapshot (channel rows with their stream addresses) is encrypted on the device like the
  other cache files.
- Film and series lists nested thousands of levels deep (broken or hostile server answers) no longer
  crash the app.
- Debug switches are compiled out of release builds.
- Parsers for playlists, guides and Xtream answers are fuzz-tested (hundreds of thousands of damaged
  inputs, no crash).

## 0.1.0

First public release: live TV with guide and catch-up, films and series with resume, search,
recording, reminders; Xtream and M3U/XMLTV sources, added from your phone with a QR code; in-app update
check (Settings > About, can be turned off).
