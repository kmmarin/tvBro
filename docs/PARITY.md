# Feature parity: Android TV / Web / Desktop

Rule: keep the three tvBro versions as close as possible. This matrix lists every user-facing feature. It was derived
from the code on 2026-11-11: Android = branch `main` (`app/`), Web = branch `web` (`web/`), Desktop = `desktop/` on
the `desktop` branch (updated 2026-11-11 after the parity work there, and late that night after the TV-app fixes:
guide never in the past, focus rules, preview does not follow focus, back from full screen; and on 2026-11-12 after the catch-up with the web parity items: resume, live reconnect, parental PIN, backup, guide mapping, external player). Roku was dropped.

Status: **done**, **partial** (see the note), **missing**, **in progress** (being built on that branch), **pending** (planned next), or **n/a** (with the reason). Rows added on 2026-11-11 (series, guide, recording storage) were not checked against web unless stated. In the rows of the TV-app fixes the Web column is taken from branch `web` (110d9aa).

Desktop "done" means built, unit-tested where the logic is shared, and rendered off-screen; the interactive window
(keyboard focus, the video moving between full screen and the preview, menus as windows over the video) has not
been used on a real screen yet. See desktop/README.md.

## Sources and sync

| Feature | Android | Web | Desktop | Notes |
|---|---|---|---|---|
| Xtream login, M3U + XMLTV | done | done | done | Web also has a demo single-stream source. |
| Several playlists (enable, disable, order, remove) | done | done | done | Desktop: rename and order too (Settings > Sources). |
| Add or edit a source from a phone (QR, LAN page) | done | n/a | missing | Web is already a LAN page that any device can open. |
| Credentials kept off the UI and logs | done | done | done | Android: EncryptedSharedPreferences. Web: encrypted on the server. Desktop: DPAPI, keyring or a key file (also the share password). |
| Per-kind refresh schedules (channels, guide, movies and series) | done | done | partial | Desktop: guide, and channels + catalogues together. |
| Settings sync status with Sync now and live progress | done | done | partial | Desktop: "Sync everything now", age of the stored channels per playlist, a progress bar. |
| A failed or empty sync keeps the old data | done | done | partial | Desktop: cache kept, a failed or empty load keeps the channels and guide on screen; no retry schedule. |
| Stored data shown at start, before the network | done | done | done | |
| Diagnostics | done | partial | done | Web: per-job status, no log viewer. |

## Live TV and guide

| Feature | Android | Web | Desktop | Notes |
|---|---|---|---|---|
| Groups in provider order, last group remembered | done | done | done | Desktop: shared `ChannelView`. |
| Channel number modes (provider / per group) | done | done | done | |
| Channels and groups editor (rename, hide, reorder, own groups, reset) | done | done | done | Desktop: channel and group menus, Settings > Channels and groups (hidden lists, own groups, reset). |
| TV screen with live preview and guide grid on one time scale | done | done | done | Desktop: shared `GuideWindow`; the separate Guide tab was merged into TV. |
| Guide opens at the current half hour and never shows the past | done | done | done | Shared `GuideWindow` (clampWin, leftOf, rightOfLabel; unit-tested): the 150-min window starts at the current half hour and never goes before it; Left at the window start goes to the channel name; past programmes (catch-up) from the channel / programme menu ("Catch-up: earlier programmes…"). Desktop: a headless UI test walks Right / Left through the grid. |
| Episode code and sub-title in the guide header ("S2 E4 title") | done | missing | done | Parsed from XMLTV `episode-num` (xmltv_ns / onscreen) and `sub-title`. |
| Guide keeps its place (group, focused channel, scroll) after the side menu, full screen or another section | done | done | done | Desktop: window, group, scroll per group, focused channel and zone (groups / name / programme) kept in the app state; Right from the side menu returns to the item focused last. Not seen on a real screen. |
| Back from full screen: video shrinks into the preview, same player keeps playing, playing channel focused | done | done | done | Desktop: one mpv surface at a stable place in the window; its bounds animate (0.32 s) between the full window and the preview spot, also with the preview off; never reloaded, and it keeps its sound (the preview mute does not apply to it). The animation and the sound were not seen / heard on a real screen. |
| Right from a channel name lands on the programme on now; a window left in the past returns to now | done | done | done | Desktop: also when time passes (the clock tick snaps the window; an ended focused programme hands the focus to the one on now). |
| Preview plays the current channel; moving focus never changes it (Enter switches it, Enter on the playing channel goes full screen) | done | done | done | Desktop: shared `TvNav.enter`; no "preview follows focus" behaviour or setting is left; Enter and a mouse click follow the same rule; with the preview off Enter goes full screen at once. Headless UI test. |
| Remote focus rules: Left channel > groups > side menu and Right back, focus always visible, nearest item, last item restored, Back closes the topmost layer, no traps | done | done | done | Desktop: shared `TvNav` zones; Left from a name goes to the selected group, Left again to the current side menu item; each screen restores its last focused item; Esc closes a menu / question (their own windows), then full screen, then a page, else goes to the side menu; a lost focus comes back on the next arrow; back from a film / show page its tile is focused. Guide zones covered by the headless UI test; other screens and the mouse not seen on a real screen. |
| Time format: 12-hour (default), 24-hour or System | done | missing | done | Shared `TimeFmt`; desktop Settings > General. |
| EPG time shift | done | done | done | |
| Manual channel-to-guide mapping | done | missing | done | Desktop: channel menu > Guide channel…: a searchable list of the playlist's guide channels (XMLTV ids and names), Automatic, No guide; shared `GuideMap`; the guide, now / next, search and series recording use it. Not seen on a real screen. |
| Catch-up (Xtream timeshift, M3U catchup) | done | done | done | |
| Long-press Back in TV/guide plays the previewed channel full screen | done | n/a | n/a | Needs a remote Back key. Browser and desktop use Esc. |
| Number zapping, channel up/down | done | done | done | |
| Resume the last channel at start (plays in the preview, focused) | done | done | done | Desktop: the TV screen focuses the channel watched last and the preview plays it (a locked one waits for Enter and the PIN). Headless UI test. |
| Live reconnect, then alternate sources, small corner notice | done | done | done | Desktop: shared `LiveRecovery` / `StallWatch` / `AltIndex` (unit-tested): an error, end of stream or 15 s without progress reconnects after 1 / 3 / 6 s, then the same channel elsewhere (same tvg-id, or the same name without HD / FHD / backup words; hidden and locked ones skipped; own playlist first), then 30 s rest and again. mpv draws the notice in full screen and in the preview; no dialog. Never seen against a real failing stream. |
| Picture-in-picture | done | missing | n/a | Desktop: windowed app. |
| External player | done | partial | done | Web: VOD only (VLC / Infuse links). Desktop: a command (e.g. vlc, mpv), for everything (live, catch-up, films, episodes) or a live channel from its channel menu (PIN asked for a locked one). |

## Search

| Feature | Android | Web | Desktop | Notes |
|---|---|---|---|---|
| One search over channels, programmes, movies and shows | done | done | done | |
| Full-text index (prefix per word, diacritics folded, ranked) | done | missing | done | Android: SQLite FTS4 `search.db`, built during sync, swapped atomically. Desktop: the same shared `SearchIndex` through sqlite-jdbc. Web: SQL `LIKE` on titles. |
| Results as rows (Movies, Shows, Channels, Programs by channel + details) | done | done | done | |
| Past (catch-up) / now (progress, minutes left) / future (air time) markers | done | done | done | |
| Actions from a programme: Watch, Record, Remind, My list | done | done | done | |
| Search history (last 20 chips, clear) | done | done | done | Web: shared by every device through the server. |
| Voice search | done | partial | missing | Android: system speech intent. Web: Web Speech API where available. |
| Google TV global search | missing | n/a | n/a | Planned for Android. |

## Movies and shows

| Feature | Android | Web | Desktop | Notes |
|---|---|---|---|---|
| Poster grid, categories, details, seasons | done | done | done | |
| Catalogue stored on disk and paged | done | done | missing | Android: stored in `search.db` and read in pages of 240. Desktop: catalogue cached on disk, held in memory. |
| Resume, watched marks, restart | done | done | done | |
| Continue watching | done | done | done | Movies. |
| Shows: Continue watching row first (from the first second, S2 E4 + progress; finished series drop off) | done | in progress | done | Shared `Watch` logic (shared/WatchLogic.kt) with tests; Android state in SQLite `state.db`, desktop in `watch.json`. |
| Per-show resume: "Resume S2 E4 (12:31)" / Play from start, episode progress bars and check marks | done | in progress | done | |
| Up next: autoplay the next episode (card, 10 s countdown, Cancel / Play now, next season), setting | done | in progress | done | Desktop: card drawn by mpv's OSD; Settings > Playback > Autoplay next episode. Not seen on a real screen. |
| Captions and audio language remembered per series | done | in progress | done | Saved when picked during an episode; applied to later episodes (language match, else first track). |
| Mark episode / season / show watched or unwatched; watched at 90 % or at the end | done | in progress | done | |
| Previous / next episode | done | done | done | Desktop: N / B in the player. |
| Downloads for offline viewing | done | missing | missing | |
| Parental PIN lock for groups and categories | done | missing | done | Web: its admin PIN only guards settings. Desktop: live groups, single channels and film / show categories; salted PBKDF2 hash (shared `Pin`, old hashes still accepted); 5 wrong PINs -> each try waits 30 s; a right PIN opens the locks for 4 h (shared `PinGate`); Settings > Channels and groups lists every lock; channel menu locks the channel or its group. Headless UI test of the locked preview / PIN. |

## My list, reminders, recording

| Feature | Android | Web | Desktop | Notes |
|---|---|---|---|---|
| My list as rows of cards (continue, channels, movies, shows, programmes, recordings, groups) | done | done | done | Web has no recordings or groups rows. Desktop: programmes = reminders. |
| Favourite shortcut key | done | done | done | Android: yellow / bookmark key or gamepad Y. Web: F. Desktop: Y, F or the bookmark key. |
| Reminders with an in-app pop-up (Watch now / Dismiss) | done | done | done | Android shows a notification when tvBro is not in front. Web shows a browser notification when the tab is hidden. Desktop: pop-up window, or an OSD line (W to watch) in full screen. |
| Record now / scheduled from the guide, recordings list | done | done | done | Web records on the server (continues with every browser closed). Desktop: while tvBro runs, each recording in its own headless mpv; reconnects into a new part after a stream drop. |
| Record series (every airing of a title on a channel, scheduled from the guide) | done | missing | done | Airings 48 h ahead, checked at every guide load and every 30 min while the app runs. Shared `SeriesRec`. |
| Recording storage picker: internal, USB / SD, network share (SMB) with free space and Test | done | n/a | done | SMB via jcifs-ng; records locally first and copies at the end, falls back with a note. Desktop plays share recordings through a loopback HTTP bridge. Never tested against a real share. |
| Long-press menu on every item (channels, programmes, movies, shows, episodes) | done | missing | done | Desktop: right click, hold Enter, Menu key or Shift+F10. |
| Stream limit (max simultaneous streams) | partial | done | missing | Android: setting 1 to 3; a recording holds a stream, the preview pauses and playback warns. Not capped by the account's `max_connections` yet. Web: house-wide, the oldest stream is stopped. |

## Player

| Feature | Android | Web | Desktop | Notes |
|---|---|---|---|---|
| Audio tracks, subtitles, caption style | done | done | done | Desktop: caption size and background (mpv sub options). |
| Aspect (normal, fill, zoom, 16:9, 4:3) | done | done | done | |
| Audio delay | done | partial | done | Browser: positive delay only (Web Audio DelayNode); none on iPhone/iPad. |
| Sleep timer, clock | done | done | done | |
| Quick-settings bar hides after 5 s without input | done | missing | done | Desktop: bottom bar (programme, progress, clock, keys) and quick settings. |
| Stream info (resolution, codecs, bitrate) | done | missing | done | |
| Video quality picker | done | done | done | Android: video track picker. Web: hls.js levels. Desktop: mpv video tracks (HLS variants) + Auto. |
| HEVC | done | done | done | Android and desktop decode it natively. Web: MSE browsers play it directly; iPhone gets a server remux. |
| MKV / AVI | done | done | done | Android (ExoPlayer) and desktop (libmpv) play them natively. Web needs the server's ffmpeg remux. |
| Frame-rate matching | done | n/a | missing | Browsers cannot switch the display mode. |

## Look and state

| Feature | Android | Web | Desktop | Notes |
|---|---|---|---|---|
| Dark theme only | done | done | done | |
| Glass theme with Reduce transparency | n/a | done | missing | Android stays on solid panels: Compose has no backdrop blur before Android 12, and the 32-bit TV GPU budget is small. |
| State shared between devices | n/a | done | n/a | Web keeps all state in the server's `state.db`. Apps keep it per device; Android has backup/restore instead. |
| Backup / restore (no credentials) | done | n/a | done | Desktop: My list, resume points / watched marks / shows' progress, reminders, search history, settings, channel layout, locks, guide mapping, series rules; never logins, the PIN or the share login. The whole file is checked first, then its contents are shown for confirmation; series rules are added. Unit-tested; the file dialogs not used on a real screen. |
| Restore the last screen at start (full screen / guide / section / settings page, focused channel) | done | n/a | done | Desktop: last UI record in settings.json; full-screen live plays the last channel again. |
| Touch and mobile layout | n/a | partial | n/a | |
| Update available notice (GitHub Releases; checked download; Update / Later / Skip this version) | done | missing | missing | Android: daily check at start (opt-out) + Settings > About with current / latest version and notes; SHA-256 and signing-key checks; system installer. Desktop: same notice with a checked download of the right zip (planned). Web: notice in Settings with the upgrade command for self-hosters (planned). |
