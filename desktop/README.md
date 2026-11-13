# tvBro desktop

tvBro for Windows (x64) and Raspberry Pi 4 (Debian 12 bookworm arm64, Wayland/labwc). Kotlin + Compose
Multiplatform for Desktop (JVM), video by libmpv through JNA. Dark theme only. Apache-2.0, clean-room like the
Android TV app.

## Layout

- `../shared`: the data layer shared with the Android app (moved out of `app/`), plus the guide window (`GuideWindow`) and TV-screen focus rules (`TvNav`): models, M3U / Xtream /
  XMLTV parsers, catch-up URL builder, VOD parsing, episode title cleaning. A plain Kotlin/JVM module; the
  Android app depends on it and its unit tests still pass. `Diag.sink` is the per-platform log hook.
- `src/main/kotlin/io/github/kmmarin/tvbro/desktop/`
  - `Main.kt`: window, theme, side menu, keys (arrows move focus, Enter selects, hold Enter / right click / Menu key: options, Esc closes the topmost layer: menu, question, full screen, page, else to the side menu; F11 full screen, Y / F favourite); each screen restores its last focused item; full-screen player keys come straight from AWT
  - `App.kt`: app state, cache-first loading, channel organisation (shared `ChannelView`), guide position, one player for full screen and preview, series and watch actions, context menus, reminders, last-UI restore, parental PIN (shared `Pin` / `PinGate`: locked groups, channels and film / show categories, 4 h unlock, waits after 5 wrong PINs), alternate sources of a channel (shared `AltIndex`), the guide-channel picker (shared `GuideMap`)
  - `Guide.kt`: TV screen: preview + programme header + guide grid on one time scale (shared `GuideWindow`). The grid starts at the current half hour and never shows the past (catch-up: channel / programme menu > Catch-up). Zones: side menu < groups < channel name < programmes; Left / Right walk them, Up / Down go to the nearest item. The preview plays the current channel; focus never changes it; Enter on another channel switches it, Enter on the playing one goes full screen
  - `Player.kt`: `VideoHost` (the one mpv surface, animated between the full window and the preview spot) and `PlayerCtl` (bottom bar and quick settings hiding after 5 s, Up next, per-series tracks, position saving, live reconnect: shared `LiveRecovery` + `StallWatch`, 1 / 3 / 6 s reconnects, then alternate sources, 30 s rest, a corner notice drawn by mpv)
  - `Screens.kt`: Movies / Shows with Continue watching, details with seasons, episode marks and resume, My list rows, Recordings; `Search.kt`: shared FTS4 index through sqlite-jdbc; `Settings.kt`: settings pages; `Backup.kt`: backup file (no logins or PIN), checked as a whole and summarised before a restore
  - `Widgets.kt`: focusable items with long-press / right-click menus; menus, questions, the searchable pick list (guide channel) and reminders as small windows above the video
  - `Recorder.kt` / `Storage.kt`: scheduled and series recordings (each in a headless mpv, `stream-record`), storage on this computer, a USB drive or an SMB share (jcifs-ng), loopback HTTP bridge to play share recordings
  - `Store.kt` / `WatchStore.kt`: settings JSON, encrypted secrets (sources, share password), watch state (`watch.json`)
  - `Mpv.kt`: JNA binding of libmpv (`MpvHardening` options; `MpvLocate` / `MpvLoader`: where libmpv is looked for, file checks, the user's chosen file); `MissingLib.kt`: the "Video library missing" screen; `PlayUrl.kt`: which addresses mpv and the external player may get; `SecureFiles.kt`: owner-only files (`Perms`), the encrypted cache format (`Sealed`), key-file and keyring rules; `SelfTest.kt`: headless check (`--selftest`)

Player keys: Up/Down or PgUp/PgDn channel (VOD: PgUp/PgDn ±1 min), digits pick a channel, Left/Right seek 10 s,
Enter or M quick settings (audio, subtitles, video quality, audio delay, aspect, sleep timer, record, stream info),
I stream info, Space pause, A / S audio / subtitles, [ ] audio delay, R record, N / B next / previous episode,
Y favourite, Esc back (live TV shrinks into the TV screen's preview, keeps playing with its sound, and the guide focuses it).

Data: Windows `%APPDATA%\tvBro` (settings, sources, watch state, recordings list) and `%LOCALAPPDATA%\tvBro\cache`;
Linux `~/.config/tvbro`, `~/.cache/tvbro`. Tests and the self-test can redirect them with `-Dtvbro.config=<dir>`
and `-Dtvbro.cache=<dir>`.

## Security

- Logins: `sources.bin` and `smb.bin` are encrypted with Windows DPAPI, else the libsecret keyring
  (`secret-tool`), else AES-GCM with `key.bin` (created once, 0600, never replaced). A locked or unreachable keyring
  is an error, not a reason for a new key; a secret that can no longer be decrypted is kept as `<name>.bin.bak`
  before anything overwrites it.
- Stream addresses (they carry the provider login) are not stored in plain text: the playlist / catalogue / guide
  cache is AES-256-GCM (`*.sgz`, key `cachekey.bin` kept like the logins); `recordings.json`, `watch.json`, backups
  and the search index hold no addresses, which are taken from the decrypted source at play / record time.
- Linux / Pi: config and cache folders 0700, files 0600 (also tightened for files of older versions).
- Players: only network addresses (http(s), rtmp(s), rtsp, rtp, udp, srt) from playlists; local files only for
  tvBro's own recordings. The external player gets an argument list with `--` before the address (mpv, VLC).
  libmpv runs without ytdl, scripts, config files or IPC, and FFmpeg may open network protocols only.
- Guides with `<!ENTITY` declarations are refused (shared `XmlGuard`). A server typed without `http://` is tried
  with https first; sources on plain http are marked "unencrypted connection".

## Build (Windows host)

```
gradlew :desktop:createDistributable          (Windows app image with bundled JRE)
gradlew :desktop:piLibs -Ptarget=linux-arm64  (Pi classpath with linux-arm64 Skia)
```

Windows zip: `gradlew :desktop:windowsZip` writes `out/tvbro-windows.zip` (app image + `NOTICE`, `LICENSE`,
`INSTALL.md`, `THIRD_PARTY.md`). **libmpv is not bundled** (the usual Windows builds are GPL, tvBro is
Apache-2.0): the task leaves out any `*mpv*.dll` / `libmpv*` and fails if one ends up in the zip.

Pi tarball: `python desktop/packaging/pack_pi.py desktop/build/pi/lib <OpenJDK17U-jre_aarch64_linux_hotspot.tar.gz> NOTICE LICENSE out/tvbro-pi-arm64.tar.gz docs/INSTALL.md docs/THIRD_PARTY.md`
(keeps Unix modes; JRE from adoptium.net, Temurin 17 aarch64; refuses any libmpv: the Pi uses the system
`libmpv2` package).

libmpv (`Mpv.kt`: `MpvLocate`, `MpvLoader`) is looked for in this order: the file the user chose
(`libmpv-path.txt` in the config folder), `libmpv-2.dll` next to `tvBro.exe`, then the OS search (`PATH`;
Linux: `libmpv.so.2`). Files are checked before loading (PE / ELF header, processor) and must export
`mpv_client_api_version`. Without one, `MissingLib.kt` shows the "Video library missing" screen (steps, Choose
file…, Check again, INSTALL.md, Continue without video). `--render-missing <png>` renders it off-screen.

## Run

- Windows: unzip `tvbro-windows.zip`, add your own `libmpv-2.dll` (see [docs/INSTALL.md](../docs/INSTALL.md);
  prefer an LGPL build), run `tvBro\tvBro.exe`. Add an Xtream or M3U source in Settings.
- Pi: needs libmpv first (`sudo apt install libmpv2`; not installed on the test Pi yet). Then
  `tar xzf tvbro-pi-arm64.tar.gz && tvbro/tvbro.sh` from the desktop session. The launcher uses XWayland
  (`DISPLAY=:0`) because AWT is X11; mpv uses `gpu-context=x11egl` and `hwdec=v4l2m2m-copy,auto-safe`.

Test options: `--m3u <file|url>` (temporary source, not saved), `--play <n>`, `--exit-after <s> --screenshot <png>`,
`--selftest <url> [--render <png>]` (plays headless through libmpv and prints resolution/fps/codecs; with
`--render`, loads the URL as M3U and renders the TV screen off-screen). `TVBRO_MPV_LOG=<file>` writes an mpv log
(it can contain stream URLs). `testdata/` has a synthetic playlist of public test streams plus a generated guide.

## Status (2026-11-12)

Android parity work (see docs/PARITY.md): series (continue watching, per-episode resume, Up next, per-show tracks,
watched marks), guide (one time scale, opens at now, keeps its place, Right lands on now), context menus with
Record series, recording storage (local / USB / SMB), 12-hour clock setting, 5 s auto-hiding player bar, full
screen shrinking into the preview, UI restore, plus search, editor, PIN, reminders, backup, external player.
Then the TV-app fixes: the guide never shows the past, focus zones and restoring, the preview never follows focus,
and the animated, still-sounding shrink from full screen into the preview. On 2026-11-12 the web parity items:
the last channel plays in the preview and is focused at start; live reconnect and alternate-source fallback;
parental PIN for single channels too (salted PBKDF2, 5 wrong tries then waits, 4 h unlock, Settings lists the
locks); backup with watch state, checked whole and confirmed; a searchable guide-channel picker with Automatic and
No guide. The external player already took live channels (channel menu).

Verified on the Windows server: shared unit tests (guide window, focus zones) and desktop unit tests pass,
including headless Compose UI tests (`TvScreenUiTest`) that drive the TV screen with arrows and Enter (also: resume of
the last channel, a locked channel kept out of the preview until the PIN); `BackupFileTest`; the app
compiles and packages; the off-screen render (`--selftest <m3u> --render <png> --no-play`,
`-Dtvbro.tab=SETTINGS -Dtvbro.page=4` for other screens) draws the TV screen.

Security fixes (2026-11-12, audit H6 M6 M8 L2 L6 L9 L10): unit tests on Windows and, for the 0700 / 0600 rules, on
Linux (WSL, JDK 17); headless libmpv against a local server: a .ts and an HLS stream play with the hardening, an HLS
playlist with `file://` segments is refused by the protocol whitelist, an M3U loads through the encrypted cache and a
guide with entity declarations is refused.

libmpv no longer bundled (2026-11-12, user decision): the Windows zip has no libmpv (checked by `windowsZip`);
`MpvLoaderTest` (missing / wrong-processor / wrong-kind / not-libmpv files, fallback order, plus the real JNA loader
on a 32-bit SysWOW64 DLL, a non-mpv DLL and a real libmpv-2.dll) and `MissingLibUiTest` (headless) pass; the
screen was looked at only as an off-screen render (`--render-missing`). Not seen on a real screen: the missing-library
screen in a window, its file dialog, opening INSTALL.md with the system viewer, and loading a chosen DLL from it.

Not verified: anything interactive on a real screen (keyboard focus outside the guide test, the mouse, long-press,
menus as windows over the video, the shrink / grow animation of the video and its sound, the player bar and Up
next OSD, the reconnect notice, the guide-channel picker window, the PIN questions, the backup file dialogs and its
confirmation), playback after these changes, reconnect / alternates against a really failing stream, recording through the headless mpv, a real SMB share, any real Xtream
provider, the Pi build on the Pi. Of the security fixes: the refusal toasts and the "unencrypted connection" hint on
screen, the https probe against a real provider, secret-tool with a real (locked / unlocked) keyring, recordings
started from a channel address after the change, and the upgrade of an existing install (old cache, recordings.json
and watch.json with addresses).