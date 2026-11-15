# Changelog

All notable changes to tvBro. Versions follow `MAJOR.MINOR.PATCH`; the Android app's version code is
`MAJOR * 10000 + MINOR * 100 + PATCH`.

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
