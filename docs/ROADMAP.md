# Roadmap (phased)

Done in 0.1.0: sources (M3U/Xtream), channel list, favourites, search, player, guide grid, catch-up, EPG streaming/cache/shift, settings.

## Phase 1: next (requested for first test build)
1. VOD: Xtream get_vod_categories/get_vod_streams/get_vod_info, get_series_categories/get_series/get_series_info; stream URLs
   (`/movie/u/p/id.ext`, `/series/u/p/id.ext`); M3U VOD heuristics; poster grid (paged), details screen, continue-watching
   position, watched flag, audio/subtitle track selection (Media3 TrackSelectionParameters). Use streaming JSON for large catalogs.
2. Full EPG: multiple EPG URLs, manual channel-to-EPG mapping, programme details, guide search, reminders (AlarmManager + overlay/notification).
3. Recording: foreground service (`mediaPlayback`/`dataSync`), record now with end time/stop, schedule from guide with padding
   (AlarmManager/WorkManager), app external files dir or USB, free-space check, TS copy / HLS segment concatenation,
   reconnect into the same file, recordings list (play/delete/size).

## Phase 2
Multiple playlists with merged favourites and custom groups; hide/rename/reorder channels and groups; parental PIN (groups/VOD);
backup/restore settings+favourites to a file.

## Phase 3
Pause/rewind live (timeshift buffer); alternate stream fallback; auto frame-rate / refresh-rate matching; external player option.

## Phase 4
Multi-view (2-4 streams, memory permitting), picture-in-picture, launch on boot.

## Status (end of first session)
Done: Phase 1 (VOD, EPG extras, recording), Phase 2 (custom groups, hide/rename/reorder, PIN, backup/restore), alternate stream fallback,
frame-rate matching, external player option, PiP on Home.
Remaining: pause/rewind live (needs a local buffer, ExoPlayer cannot seek raw live TS), multi-view, launch on boot (blocked on Android 10+
without overlay permission), HLS recording for encrypted or fMP4 streams, per-source EPG-time offsets, Xtream short-EPG fallback.
## Voice search and searchable app (planned)
- Voice search: a microphone button next to the Search field using the system `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` (no RECORD_AUDIO permission needed), plus the remote's search key (`KEYCODE_SEARCH` / `onSearchRequested`), filling the query and showing partial results where the recognizer provides them.
- Search scope: channels, guide programmes, movies, series and episodes, grouped by type, from the local cache.
- Searchable app for Google TV global search: needs a `SearchableInfo` + content provider that returns cached titles (`SUGGEST_COLUMN_*` for Android TV search) and an `ACTION_VIEW`/`MEDIA_PLAY_FROM_SEARCH` intent filter on MainActivity; a small amount of work but needs on-device testing, so it follows the layout rework.
- Remaining after weekly reset: Room paging, My list sections and favourite-anything, downloads, per-type refresh schedules, gamepad Y shortcut, TV preview + guide screen, settings side panel.
## Player (status)
Done: quick-settings bar (Down or Menu): channels, video track, audio track, subtitles incl. embedded tracks, aspect (Normal/Fill/Zoom), sleep timer, settings, clock; caption size and background in Settings > Appearance. Not done: audio delay (Media3 1.4 has no simple audio offset; needs a custom AudioSink or an audio processor delay buffer), 16:9 and 4:3 forced aspect, external subtitle files, VOD transport bar with prev/next episode and restart buttons and info chips (seek is Left/Right 10 s today).

## Player (update)
Done: audio delay -1000..+1000 ms in 50 ms steps (player bar > Audio delay, remembered per channel or title, default in Settings > Playback; implemented as an AudioProcessor in the ExoPlayer audio sink that inserts silence or drops samples, so it only affects 16-bit PCM output, not passthrough); VOD seek bar with position/duration, Left/Right 10 s with hold-to-accelerate, restart, rewind, forward, previous/next episode and stream chips; forced 16:9 and 4:3 aspect (stretches the video into a box of that ratio); closed captions (CEA-608/708) are labelled in the subtitle picker when the stream carries them. Not verified on a device.

## Session status (late)
Done: My list with sections and favourite-anything (menu, yellow/bookmark key, gamepad Y, star badges, included in backup); downloads for movies, episodes and whole seasons (queue in the recording service, pause/resume/cancel, HTTP Range resume, unmetered networks only, free-space check, offline badge, local-first playback; HLS and encrypted streams are refused with a message); multiple playlists with enable/disable, reorder, per-source counts, merged channels with a source prefix on groups; guide window = longest catch-up period of the source (min 1 day) back to 7 days ahead, stored range and size shown in Settings > EPG; guide refresh default 12 h.
Deferred: Room/SQLite storage with paging for catalogues and EPG (current storage is files plus in-memory per-channel arrays, which works but loads the stored window into memory), extra global EPG sources, per-type refresh schedules for movie/series catalogues, searchable-app registration for Google TV global search.

## Status (2026-11-11)
Done on Android: items 1-5 of the TV test feedback (My list grid, guide hides the past, search redesign, in-app reminders, long-press Back), catalogues in SQLite with paging, episode-code line, per-type refresh schedules, FTS4 search index built during sync, keep-old-data with retry backoff, Settings > Sync, channels and groups editor, stream limit.
Remaining: Google TV global search registration (content provider + searchable), stream limit capped by the Xtream account's `max_connections`, glass look (n/a on Android, see docs/PARITY.md), parity gaps listed in docs/PARITY.md for Web and Desktop (FTS search, episode codes, sync cards on desktop).
