# Architecture

Single-activity Compose app, one `AppModel` (AndroidViewModel) holding all UI state; `Screen` enum
(LIST, PLAYER, GUIDE, SETTINGS, ADD_SOURCE) selects the screen.

## data/
- `Model.kt`: `Source`, `Channel`, `Programme`, `EpgIndex` (map of EPG key to sorted programmes, binary search for now/next).
- `M3uParser.kt`: line-based #EXTM3U/#EXTINF parser (tvg-*, group-title, catchup*). Pure Kotlin.
- `Xtream.kt`: kotlinx.serialization models for player_api.php (leniently decoded, ids may be numbers or strings), URL builders, mapping to `Channel`.
- `XmltvParser.kt`: XmlPullParser streaming parse; programmes outside the window or not matching a wanted channel are dropped immediately.
- `Catchup.kt`: archive URL builder (Xtream timeshift, M3U append/default/shift).
- `Repository.kt`: OkHttp downloads to `filesDir/cache`, parses from disk, writes a compact gzip TSV EPG cache (fast start, low memory).
- `Store.kt`: settings in SharedPreferences; sources (with credentials) in EncryptedSharedPreferences.

## ui/
Compose screens. `tvFocus` modifier gives every focusable a filled background plus thick accent border.
Player uses Media3 ExoPlayer (OkHttp data source, tuned `DefaultLoadControl`, 32 MB target buffer), created per player screen and released on leave.
Guide: one shared `ScrollState` drives horizontal scrolling for all rows; vertical is a LazyColumn, so only visible rows exist.
Logos: tiny LRU bitmap cache (6 MB), downsampled to ~128 px, 4 concurrent loads.

## Memory notes (32-bit, 2.4 GB TV)
Streaming parsers, window-limited EPG (3 days back, 2 ahead, only channels in the playlist), no image library, lazy lists.
