# tvBro Rust core

> **Status (2026-11-13): phase 2, step 3 done.** The search index (row 7) is on the core; every row has a shadow
> mode; a shadow run on the Fire TV Stick 4K (real Xtream provider, 15,800 channels, 375k programmes, plus an M3U
> source) found no difference, so **all six rows now run on the Rust core by default** (`-Ptvbro.core=kotlin` or a
> per-row override brings Kotlin back). See [Phase 2, step 3](#phase-2-step-3-done-2026-11-13).
>
> Step 2 (2026-11-13): the guide (XMLTV + gzip, writing `epg.db`) is
> a fifth switch row; debug builds have a shadow mode (Kotlin in charge, the core compared afterwards, summary only);
> the release build makes per-ABI APKs next to the universal one and the updater picks the device's; a truncated
> Xtream download is refused with the core on, like Kotlin. See [Phase 2, step 2](#phase-2-step-2-done-2026-11-13).
> Step 1: `libtvbro_ffi.so` (arm64-v8a / armeabi-v7a / x86_64) and the generated UniFFI Kotlin in the app; URL guard,
> PIN hashing, M3U and Xtream live parsing behind the rollout switch (default Kotlin, no behaviour change). Phase 1
> (2026-11-12): the `tvbro-core` crate (now 99 unit + 11 property + 3 integration tests, clippy clean), UniFFI JVM
> smoke test, cross builds, the PyO3 module, `cargo deny` / `cargo audit`.

One Rust library for every tvBro platform. The platform UIs stay native and thin (Android: Kotlin/Compose,
desktop: Compose Desktop, web: JS in the browser plus a server); the logic that must behave the same everywhere
moves into `core/`.

## Layout

```
core/
  Cargo.toml          workspace (members: tvbro-core, tvbro-ffi, tvbro-py)
  tvbro-core/         the library, #![forbid(unsafe_code)]
    src/
      m3u.rs          M3U / M3U8 playlists, streamed line by line
      xtream.rs       Xtream player_api.php JSON: live, VOD, series, info; URL builders; HLS variants
      xmltv.rs        streaming XMLTV, episode numbers
      gz.rs           gzip with decompression-bomb limits, byte caps
      lines.rs        bounded line reader (huge files, no newline bombs)
      catchup.rs      catch-up URLs, programme state (past / catch-up / now / future)
      channels.rs     group order (PPV last), user prefs view, web layout helpers, alternate sources
      epgmap.rs       automatic guide matching + manual mapping, guide-channel picker search
      guide.rs        guide window math (desktop GuideWindow + Android GuideLayout variant)
      search.rs       SQLite FTS4 search index, ranking, result grouping, search history
      epgstore.rs     on-disk guide store with generations (Android EpgDb schema, chunked deletes, log checkpoints)
      epgload.rs      guide refresh: XMLTV (plain / gzip) into the store, Android's resolver and cuts; row digests
      security/       urlguard, password (PBKDF2), ratelimit, seal (AES-256-GCM, HMAC tokens)
      update.rs       version compare, GitHub releases JSON, skip-version
    tests/props.rs    property / fuzz-style tests (proptest)
    tests/urlguard.rs URL guard cases with private / LAN addresses (under tests/, where the publish check allows them)
  tvbro-ffi/          UniFFI cdylib for Kotlin (Android .so, JVM desktop) + uniffi-bindgen binary (feature bindgen)
    jvm-smoke/        standalone Gradle project: JVM smoke test of the generated Kotlin (run.ps1)
  cross.toml          cross-build linkers (cargo --config cross.toml)
  deny.toml           cargo-deny policy
  tvbro-py/           PyO3 abi3 extension module `tvbro_core` for the web server
```

Crates used (all widely used and maintained): quick-xml (with `encoding`), serde / serde_json, rusqlite
(`bundled` SQLite), RustCrypto aes-gcm / pbkdf2 / hmac / sha2, subtle, getrandom, flate2, url, regex,
unicode-normalization, indexmap, crc32fast, thiserror; proptest for tests; uniffi and pyo3 for the bindings.
No networking crate: the URL guard uses the platform resolver through `std::net`.

## What is ported, and from where

| Area | Source of truth | Notes |
|---|---|---|
| M3U parsing | shared `M3uParser.kt` | Text that feeds keys follows the JVM exactly: Kotlin's `trim` / `isBlank` whitespace set (U+001C-U+001F yes, U+0085 no), `toIntOrNull` (sign, non-ASCII decimal digits, overflow), and the JVM UTF-8 decoder's replacement of bad bytes (one U+FFFD per encoded surrogate). Kotlin rules for names (text after the first comma following the last attribute, so "News, Weather" stays whole) and fallbacks (`group-title=""` gives "Ungrouped"). Channel keys: `KeyStyle::JavaHash` reproduces Kotlin's `line.hashCode().toString(16)` exactly (stored favourites / order keep working); `KeyStyle::Crc32` reproduces the web server's keys. Streams from any `BufRead`; lines over 1 MB are cut. |
| Xtream JSON | `Xtream.kt`, `Vod.kt`, web `parsers.py` | Lenient values (string / number / null / ""), list or map-of-objects roots, BOM, truncated lists keep what came before; items without an id are skipped (web rule). Big lists are streamed item by item. Web additions kept: year / genre, detail name / cover / runtime, `hls_variant`, `choose_format`. |
| XMLTV | `XmltvParser.kt`, `EpisodeNum` | Same resolve / time window / first-title / desc 400 / sub-title 120 / xmltv_ns-before-onscreen rules. Times: invalid calendar fields give -1 (web rule; Kotlin accepted e.g. month 13). One guide channel can feed several keys (web). |
| Gzip | Android `Repository.refreshEpg` | Magic-byte sniffing, multi-member gzip, total cap (2 GiB) and ratio cap (200x after 16 MB). |
| Catch-up | `Catchup.kt`, `ProgTime` | xc / append / shift / template kinds, all placeholders. |
| Groups / channels | Android `AppModel` + `GroupOrder.kt`, desktop `ChannelView.kt`, web `layout.py` | Favourites and All channels fixed on top, own groups next, then provider groups in the user's order with the rest in provider order and PPV last; channel order with PPV channels last unless the user ordered them; hide / rename / lock; per-group numbering; move up / down / to top / to bottom; `Prefs` reads the Kotlin JSON. Favourites label is the Android one ("Favourites"; desktop shows "My list"). |
| Alternates | `Alternates.kt` / `alternates.py` | Same quality-word list, bracket rule, numbered copies, same-playlist-first order. |
| EPG mapping | `Repository.refreshEpg`, `GuideMap.kt`, `epgmap.py` | Automatic (tvg-id or name, then display names) plus manual ids and `__none`. |
| Guide window | desktop `GuideWindow.kt`; Android `GuideLayout.kt` as `guide::android` | Never in the past, half-hour floor, 30 h ahead, reveal / left / right / after-end / catch-up list. |
| Search | `SearchIndex.kt`, `SearchLogic.kt` | Same SQLite schema (FTS4, contentless, prefix 2,3), same folding and ranking, same programme grouping and history rules. |
| EPG store | Android `EpgDb` | Same tables, indexes and `user_version` 2, so the existing `epg.db` opens unchanged. |
| URL guard | web `net.py` | http/https only, ports 80 / 443 / 1024+, every resolved address checked (loopback, link-local + metadata, multicast, reserved never; private / CGNAT / ULA only with allow-LAN), IPv4-mapped / NAT64 / 6to4 unwrapped, WHATWG IP forms (`0x7f.1`), redirect hops re-checked, at most 6. The caller connects only to the returned addresses (no DNS rebinding). |
| Passwords / PINs | web `auth.py`, `parental.py`; Android `Pin` | `pbkdf2_sha256$it$salt$hash`, 600k iterations, 16-byte salt, constant-time compare. Web hashes (600k and the 120k PIN hashes) verify unchanged; the Android legacy PIN hash (unsalted SHA-256 of "tvbro:" + PIN) verifies and reports `needs_rehash`. |
| Rate limiting | web `auth.Throttle`, parental counter | Clock passed in. |
| Sealing | new | AES-256-GCM, `0x01 | nonce | ct+tag`, purpose bound as AAD; key from the platform. Also the web server's CSRF and parental unlock tokens (HMAC-SHA256). The server's existing Fernet files are read by Python during migration and re-sealed. |
| Updater | Android `UpdateLogic.kt` | Same version rules and asset-URL allow-list, generalised to Android / Windows / Linux assets; `decide()` adds "skip this version" (a later release is offered again). |

Not ported in phase 1 (platform or UI code, or server-only): HTTP fetching, recording / remux, the phone-link
server, backup files, byte-range HLS playlists, sessions storage.

## Tests

`cargo test` in `core/`: 99 unit tests and 3 URL guard integration tests (the Kotlin `ParsersTest`, `GroupOrderTest`, `GuideSearchLogicTest`,
`SearchIndexTest`, `GuideWindowTest`, `UpdateLogicTest` cases and the web `test_alternates`, `test_security`
(URL / address / password / throttle) cases, plus new ones for limits and hostile input: billion laughs, XXE,
gzip bombs, huge tokens, truncated JSON, 200k-channel and 224k-programme streaming runs) and 11 property tests that
throw arbitrary bytes at every parser. `cargo clippy --all-targets -- -D warnings` is clean.

### Supply chain (2026-11-12)

- `cargo deny check` (`core/deny.toml`: RustSec advisories, licenses, bans, crates.io only): **advisories ok, bans
  ok, licenses ok, sources ok.** Allowed licenses: Apache-2.0 (incl. LLVM exception), MIT, BSD-3-Clause, Zlib,
  Unicode-3.0, MPL-2.0 (UniFFI, used unmodified).
- Fixed: RUSTSEC-2026-0385 (`ctutils` < 0.4.4: non-constant-time masks and `u128` compares; under `aes-gcm`, `hmac`
  and `sha2`). Updated to 0.4.8, which needs Rust 1.87, so the workspace `rust-version` went from 1.85 to 1.87.
- Accepted: RUSTSEC-2026-0374 (`basic-toml` unmaintained, no fixed version; reached through `askama` in
  `uniffi_bindgen`). It is only in the code generator behind the `bindgen` feature, which is used at build time and
  never shipped, so `cargo deny` (default features) no longer sees it. `cargo audit` reads the whole `Cargo.lock`
  and still lists it as an allowed warning: **0 vulnerabilities, 1 warning** (187 crates, 1490 advisories). Revisit
  when UniFFI drops it.
- Tools: cargo-deny 0.20.2, cargo-audit 0.23.0, installed into `C:\tools\native\rust\cargo\bin`.

Dev profile note: dependencies are built with `opt-level = 3` even in debug, otherwise 600k-iteration PBKDF2 makes
the test run take minutes.

## Bindings

### Kotlin (Android and desktop): UniFFI

`tvbro-ffi` is a `cdylib` with UniFFI proc-macro exports: `core_version`, `parse_m3u` / `parse_m3u_bytes` (Kotlin
channel keys), `parse_xtream_live`, `check_url` / `check_url_static` / `check_addrs` (URL guard; refusals are
`CoreException.Blocked`), `pin_hash` / `pin_hash_with` / `pin_verify` / `pin_needs_rehash`, `is_newer`, `fold_text`.
Large inputs cross as byte arrays: the app's cache files are sealed (AES-GCM, Keystore key), so Kotlin decrypts and
passes the plaintext; nothing is written unsealed for the core to read. Kotlin package
`io.github.kmmarin.tvbro.core` (`tvbro-ffi/uniffi.toml`). The Kotlin sources are generated from the built library; the
generator sits behind the `bindgen` feature, so `uniffi_bindgen` and its build-time dependencies stay out of the
shipped library:

```
cargo build -p tvbro-ffi
cargo run -p tvbro-ffi --features bindgen --bin uniffi-bindgen -- generate --library target/debug/tvbro_ffi.dll --language kotlin --out-dir bindings/kotlin
```

`bindings/` is generated and git-ignored. The generated code needs only JNA (no coroutines), which loads
`libtvbro_ffi.so` / `tvbro_ffi.dll` from `jniLibs/<abi>` on Android and from `jna.library.path` on the JVM.

**JVM smoke test (Windows x64):** `powershell -File core\tvbro-ffi\jvm-smoke\run.ps1` builds the DLL, generates the
bindings and runs `jvm-smoke` (standalone Gradle project, Kotlin 2.0.21, JNA 5.15.0, offline from the build machine's
Gradle cache). It loads the library, parses a small M3U (comma name, "Ungrouped" fallback, keys equal to Kotlin's
`"$src|$tvgId|${url.hashCode().toString(16)}"`, `url-tvg`), and refuses `127.0.0.1`, `[::1]`, `0x7f.1`,
`[::ffff:127.0.0.1]`, `169.254.169.254`, `file://`, port 22 and a TEST-NET address without allow-LAN (allowed with
it). Result: `JVM SMOKE OK` (19 checks).

### Cross builds

Machine-specific linker settings live in `core/cross.toml` and are passed per command (`cargo build --config
cross.toml ...`), so plain host builds never depend on the build machine's paths:

```
cargo build --config cross.toml -p tvbro-ffi --release --target aarch64-linux-android      # arm64-v8a
cargo build --config cross.toml -p tvbro-ffi --release --target armv7-linux-androideabi    # armeabi-v7a
cargo build --config cross.toml -p tvbro-ffi --release --target x86_64-linux-android       # x86_64
cargo build --config cross.toml -p tvbro-ffi --release --target aarch64-unknown-linux-gnu  # linux-aarch64
```

- **Android:** NDK 27.3.13750724 `<triple>24-clang.cmd` wrappers (API 24 = the app's minSdk) as both the linker and
  the C compiler for the bundled SQLite; `llvm-ar` from the NDK. arm64 and x86_64 link with
  `-z max-page-size=16384` (16 KB pages, needed from Android 15 on).
- **linux-aarch64:** LLVM clang 23 + lld (`clang.exe` / `llvm-ar.exe` from PATH, e.g. scoop) with `--target=aarch64-linux-gnu`, sysroot
  `C:\tools\native\pi-sysroot` (Debian 12 bookworm arm64: glibc 2.36, gcc 12 crt / libgcc from
  `usr/lib/gcc/aarch64-linux-gnu/12` via `--gcc-install-dir`); the same flags as the C compiler for SQLite.

llvm-readelf on the release builds (2026-11-12):

| Target | ELF | Size | LOAD align | NEEDED | Exports |
|---|---|---|---|---|---|
| arm64-v8a | ELF64 AArch64 | 2.67 MB | 0x4000 | libc.so, libdl.so | 5 UniFFI functions, 72 uniffi/ffi symbols |
| armeabi-v7a | ELF32 ARM, EABI5 | 1.84 MB | 0x1000 | libc.so, libdl.so | same |
| x86_64 | ELF64 x86-64 | 2.68 MB | 0x4000 | libc.so, libdl.so | same |
| linux-aarch64 | ELF64 AArch64 | 2.64 MB | 0x10000 | libc.so.6, libgcc_s.so.1 (highest symbol version GLIBC_2.34) | same |

No on-device run: the only local emulator image is `android-34;android-tv;x86` (32-bit x86 plus ARM translation;
`abilist64` is empty), which cannot run x86_64 code. It is also the image the navigation test agent uses. So
Android is build-only for now. With an x86_64 image, the smoke would be a test binary (`cargo test --no-run
--target x86_64-linux-android`) pushed with `adb push` to `/data/local/tmp` and run with `adb shell`.

Why UniFFI: one Rust definition gives idiomatic Kotlin (records as data classes, errors as exceptions) for both
Android and the JVM desktop app, so the two Kotlin apps share the same generated API.

### Web server: PyO3 (chosen over wasm-bindgen)

The web app's logic lives in the Python server (stdlib HTTP server, parsing, XMLTV, SQLite search, the URL guard,
passwords, the encrypted login store); the browser only renders. A PyO3 extension drops into that server as one
import, replaces the Python functions one by one, and shares the server's threads, files and SQLite. wasm in the
browser would move logic to the client, which the server design deliberately avoids (provider logins and URLs
never reach the browser). `abi3-py311` makes one build serve Debian 12's Python 3.11 and newer.

Build: `cargo build -p tvbro-py --release` (Windows gives `tvbro_core.dll`, rename to `tvbro_core.pyd`; Linux gives
`libtvbro_core.so`, rename to `tvbro_core.abi3.so`), or `maturin build` in the server's container.

### Status

| Binding | Target | Build | Loads + calls |
|---|---|---|---|
| UniFFI | Windows x64 JVM | `tvbro_ffi.dll` builds | **works**: `jvm-smoke/run.ps1`, JVM SMOKE OK (load, M3U, URL guard) |
| UniFFI | Android arm64-v8a / armeabi-v7a / x86_64 | **builds** in the app (Gradle runs cargo) | **works**: armeabi-v7a in the app on the Android 14 TV emulator (x86 image, through its ARM translation, `primaryCpuAbi=armeabi-v7a`); arm64 / x86_64 not run yet (no such image / device used) |
| UniFFI | linux-aarch64 JVM | **builds** (release, readelf checked) | not run: needs an arm64 Linux box (not touched in phase 1) |
| PyO3 | Windows x64, CPython 3.14 (abi3) | builds | **works**: `python -I tvbro-py/smoke_test.py <dir with tvbro_core.pyd>` imports the module, parses an M3U (CRC-32 key, comma name), refuses a loopback URL |

Note: the first build of the binding crates takes ~14 minutes on the build machine (dependencies are optimised
even in debug, see the dev profile).

## Migration plan

1. **Phase 1 (this branch):** the core and its tests; binding scaffolding with smoke tests. No app changes.
2. **Phase 2, Android:** ship `libtvbro_ffi.so` for the three ABIs in the app; switch M3U / Xtream / XMLTV
   parsing, the search index and the EPG store, the PIN hash (re-hash on next right PIN) and the URL guard (OkHttp
   `Dns` + network interceptor calling the core) to the core. Keep the Kotlin code behind a flag for one release and
   compare results on real playlists (same channel keys, same programme counts) before removing it.
3. **Phase 3, desktop:** the same `tvbro-ffi` API on the JVM (Windows x64, linux-aarch64); delete the desktop
   copies of shared logic (`ChannelView`, `GuideWindow`, `Alternates`, `GuideMap`).
4. **Phase 4, web server:** first through the PyO3 module (parsers, search, URL guard, passwords, sealing), then,
   if it pays off, a Rust server (axum) serving the same API, with the frontend unchanged.

## Phase 2 plan (Android)

Phase 2 changes no behaviour on its own: every switched call site keeps its Kotlin implementation, and a rollout
switch picks which one runs. Step 1 (rows 1-4 below) is done; see the section after this plan.

### Packaging

- `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/libtvbro_ffi.so` from the release cross-builds (a Gradle
  task runs `cargo build --config cross.toml --release --target ...` and copies the files; CI later).
- The generated `io.github.kmmarin.tvbro.core` Kotlin file goes into `app/src/main/java` (generated at build time, not
  committed), plus `net.java.dev.jna:jna:5.15.0@aar`. R8 keep rules for `com.sun.jna.**` and
  `io.github.kmmarin.tvbro.core.**` (JNA uses reflection on the generated structures).
- The FFI surface grows from the phase-1 smoke API to exactly what the call sites below need. Large inputs go in
  as file paths (the core streams them) rather than big strings or byte arrays across JNA.

### Call sites switched first, in this order

| # | Area | Kotlin call site today | Core replacement | Why this order |
|---|---|---|---|---|
| 1 | URL guard | `Repository.client` (`OkHttpClient.Builder()`, `Repository.kt:19`) has no guard at all | an OkHttp `Dns` that resolves with the platform resolver and passes the addresses to a new `check_addrs(host, addrs, allow_lan)` export, plus a network interceptor that runs `check_url` on every request and redirect hop (`followRedirects` stays on; each hop is a new request through the interceptor) | adds protection without changing parsing; failures are visible and easy to compare |
| 2 | PIN hashing | `Pin.hash` / `Pin.check` (`shared/.../Prefs.kt:35`), used by `AppModel.askPin` / `setPin` (`AppModel.kt:474-492`) | `pin_hash(pin)` (PBKDF2, `pbkdf2_sha256$...`) and `pin_verify(pin, stored) -> {ok, needs_rehash}`; the old unsalted SHA-256 hash verifies and is replaced by a PBKDF2 hash on the next right PIN | tiny surface, one-way migration that the Kotlin path can still read (it treats a `pbkdf2_` hash as "verify in core") |
| 3 | M3U parsing | `Repository.load` M3U branch (`M3uParser.parse(..., s.id)`, `Repository.kt:114`) and the sync probe `m3uHasChannels` (`Repository.kt:80`) | `parse_m3u_file(path, source_id)` with `KeyStyle::JavaHash`; returns the same `Channel` fields | channel keys must be bit-identical (favourites, order, renames, locks); the comparison harness proves it first |
| 4 | Xtream JSON | `Xtream.categories` / `Xtream.liveStreams` / `Xtream.toChannels` (`Repository.kt:110-112`), `xtreamHasStreams` (`Repository.kt:81`); VOD / series pages and details afterwards | `parse_xtream_live(cats_path, streams_path, source, server, user, pass)` streaming item by item | big lists (100k+) move off the Kotlin heap |
| 5 | XMLTV + gzip | `Repository.refreshEpg` (`Repository.kt:185-230`: gzip sniff, `XmltvParser.parse` with the `wanted` resolver, batches into `EpgDb`) | `refresh_epg(path, source_id, wanted, from, to, epg_db_path)`: gzip limits, streaming XMLTV and the generation-based write in one call | the heaviest Kotlin code path (memory, time); the core writes the same `epg.db` schema |
| 6 | Guide store | `EpgDb` reads: `load`, `desc`, `meta`, `count`, `forEachCurrent` (`EpgDb.kt:33-110`, used from `AppModel.kt:232/688/1102/1150`) | `EpgStore` object over the same `epg.db` (`user_version` 2) | after #5 the core owns writes; reads follow so one SQLite build owns the file |
| 7 | Search | `SearchStore` rebuild (`SearchIndex.create` / `SearchIndex.Writer`, `SearchStore.kt:95-106`) and queries `SearchIndex.search` / `page` / `categories` / `count` (`AppModel.kt:916/1032/1088`) | `SearchIndex` object over `search.db` (same FTS4 schema, prefix 2,3, same folding and ranking) | depends on #6 (the rebuild reads current programmes from the guide store) |

Left in Kotlin in phase 2: `GuideLayout` (pure UI math, cheap to keep), `GroupOrder` / `AppModel` channel views,
`EpisodeNum.line` formatting, catch-up URL building. They move in phase 3 together with the desktop app, which has
copies of the same logic.

### Comparison harness

Two layers, both run before a call site's switch default flips:

1. **Host (JVM, every build).** A Gradle test in `shared` runs the Kotlin implementation and the core (through
   `tvbro_ffi.dll` / `libtvbro_ffi.so` on `jna.library.path`, as in `core/tvbro-ffi/jvm-smoke`) on the same inputs and
   diffs the outputs field by field:
   - inputs: the `testenv` mock provider files (PPV / 4K / no-guide groups), the core's hostile-input corpus
     (truncated JSON, BOMs, comma names, huge lines, gzip bombs, non-UTF-8 XMLTV), and any real playlists placed in a
     git-ignored `testdata/private/` folder (never committed);
   - M3U / Xtream: channel count, then per channel key, name, group, url, tvg-id, logo, catch-up kind / days, in
     order; EPG URL list;
   - XMLTV: programme count per resolved key, then (key, start, stop, title, sub, ep, desc) sorted;
   - search: the result groups for a fixed query list (exact, prefix, folded accents, episode titles) on an index
     built by each side;
   - PIN: Kotlin legacy hash verifies in the core with `needs_rehash`;
   - known, accepted differences (the rule table above, e.g. invalid calendar fields give -1) are listed in one
     allow-list file with a reason each; anything else fails the test.
2. **On device (debug builds, opt-in).** A "shadow" mode for #3-#5: the Kotlin path stays authoritative, the core runs
   on the same downloaded file afterwards on a background thread, and only a summary is kept (counts, first
   differing key, timings, peak memory); no URLs, names or credentials are logged. Shown in the debug info screen so
   it can be checked on the TV and Fire TV test devices before flipping a default.

### Rollout switch

- One `CoreSwitch` object read at start-up, one entry per row above: `KOTLIN`, `SHADOW` (debug only) or `CORE`.
  Defaults are compiled in per build type (`BuildConfig`), and a hidden developer setting can override them per
  entry for testing.
- If `libtvbro_ffi.so` fails to load (`UnsatisfiedLinkError`, unsupported ABI) every entry falls back to `KOTLIN`
  for the process and the failure is shown once in the debug info screen.
- Order: release N ships the library with everything on `KOTLIN` (size and load checked in the field), then each row
  flips to `CORE` in order once its comparison is clean, at most two rows per release; the Kotlin code is deleted one
  release after its row has been on `CORE` without reports. The PIN row is the only one that rewrites stored data
  (legacy hash to PBKDF2); since the Kotlin `Pin.check` learns to delegate `pbkdf2_` hashes to the core before the
  flip, switching back never locks anyone out.

## Phase 2, step 1 (done 2026-11-13)

### What switched

| Row | Kotlin (default) | Core (switch on) |
|---|---|---|
| URL guard | no guard (Add source checks only `http(s)://`) | `Net.client` (repository, logos, recordings, ExoPlayer live / VOD streams) gets an OkHttp `Dns` that resolves with the system resolver and passes every address to `check_addrs`, plus an application and a network interceptor running `check_url_static` on the request and every redirect hop. Allow-LAN is on for Android (home servers are common): loopback, link-local (incl. 169.254.169.254), multicast, reserved, `localhost` names, ports below 1024 other than 80 / 443 and non-http(s) schemes are refused. Add source (form and phone link) checks the playlist / guide / server URL first and refuses with "Address not allowed (...)". Refusals are `SafeIOException("blocked: ...")`, shown without the URL. The updater's own GitHub client is not changed. |
| PIN hashing | `Pin.hash` / `Pin.verify` (PBKDF2 120k, legacy SHA-256 read) | `pin_hash` (PBKDF2-HMAC-SHA256, 120 000 iterations, 16-byte salt: `password::PIN_ITERATIONS`, the web PIN count; a 4-8 digit PIN is protected by the wrong-PIN limiter, and the hash runs on armv7 sticks at every unlock) and `pin_verify`; after a right PIN a legacy or weaker hash is replaced. Kotlin reads every hash the core writes, so switching back never locks anyone out. |
| M3U | `M3uParser.parse` | `parse_m3u_bytes` in `Repository.channels` and the download check `m3uHasChannels` |
| Xtream live | `Xtream.categories` / `liveStreams` / `toChannels` | `parse_xtream_live` (new `parse_live_categories`: a blank category name gives "Other", like Kotlin) in `Repository.channels` and `xtreamHasStreams` |

Search was not switched: it is not small (row 7 needs the guide store, row 6, first). Its text folding is compared
already (identical on the test words).

### Rollout switch

`CoreSwitch` (`app/.../data/Core.kt`), read once in `TvBroApp.onCreate`:

- default per build: `-Ptvbro.core=kotlin` (default) or `-Ptvbro.core=rust` (`BuildConfig.CORE_DEFAULT`), all rows
  (step 3 added `default`, per-row defaults, and made it the default value);
- debug builds: Settings > About > "Developer: Rust core" stores an override per row (applies at the next start), and
  `adb shell setprop debug.tvbro.core rust` (or `m3u=rust,pin=kotlin`) overrides both for the next start;
- the library is loaded only when a row wants the core; if it fails (`UnsatisfiedLinkError`, missing ABI) every row
  runs Kotlin and the reason shows in the Developer section and the diagnostics.
- `SHADOW` mode: added in step 2 (below).

### Packaging

`app/build.gradle.kts`: `coreLib-<abi>` tasks run `cargo build --locked -p tvbro-ffi --release --target ...` with the
SDK's NDK (`ndkVersion` 27.3.13750724) as linker and C compiler set through `CARGO_TARGET_*` / `CC_*` / `AR_*`
environment variables (no machine paths in the repository; `core/cross.toml` stays for manual builds), `coreLibs`
copies them to `build/generated/core/jniLibs`, `coreHost` builds the host library, `coreBindings` generates the Kotlin
into `build/generated/core/kotlin` (not committed). `preBuild` depends on both. `ndk.abiFilters` = the three ABIs
(this also drops JNA's x86 / other dispatch libraries). JNA 5.15.0 (`@aar`), R8 keep rules for `com.sun.jna.**` and
`io.github.kmmarin.tvbro.core.**`. CI: the Android job installs Rust with the three targets and the NDK; a new `core` job
runs `cargo test`, clippy and `cargo deny`.

### Comparison harness (`app/src/test/.../CoreParityTest.kt`)

Runs in `:app:testDebugUnitTest` (so in `testenv\build.ps1 -Test`) against the host build loaded through JNA
(`jna.library.path` = `core/target/debug`). Inputs: `testenv/www/playlist.m3u` when generated (`make_mock.py`),
`desktop/testdata/test.m3u`, a generated 5000-channel playlist (PPV / 4K / no-guide groups, catch-up, comma names,
Greek / Japanese / accented groups), a generated 3000-stream Xtream list, a hostile corpus (BOM, CR / CRLF, comma
names, attribute case, bare URLs, catch-up variants, numbers, Unicode digits / case / whitespace, 13 bad-UTF-8
sequences inside lines and at end of file, a 1.1 MB line; Xtream strings / numbers / nulls / "null" / missing ids /
unknown categories / BOM / map root / truncated / HTML), PIN vectors (6 PINs x 5 salts x 2 iteration counts both
ways, today's 120k default both ways, legacy hashes, 16 stored values incl. malformed ones), 52 URL / address cases
and the guard on a real OkHttp client, and any real playlists in the git-ignored `testdata/private/`. Every field of
every channel is compared in order (key, source, name, logo, group, url, tvg-id, number, catch-up kind / template /
days, guide keys) plus the guide URL list.

Result (2026-11-13): **62 cases identical, 7 accepted differences** (5,276 M3U channels, 3,015 Xtream channels, 82 PIN
vectors, 58 URL guard checks, 27 folding words). The accepted ones are listed with a reason each in
`app/src/test/resources/core-parity-allow.txt` (an entry that stops differing fails the test):

- `m3u:huge-line`: a line over 1 MB is cut by the core (memory bound); no real playlist has one.
- `xtream:null-strings`: the text "null" as logo / guide id is treated as missing by the core (intended).
- `xtream:missing-id`: a stream without `stream_id` is skipped by the core; Kotlin keeps an unplayable channel.
- `xtream:bom`, `xtream:map-root`, `xtream:truncated`: the core reads these lists (BOM, object of id -> stream, list
  cut mid-item: the items before the damage); Kotlin's decoder fails and gives no channels.
- `pin:long-hash`: a stored hash over 64 bytes (never written by tvBro) is refused by the core.

Found and fixed in the core by the harness: Kotlin's whitespace set for `trim` (channel keys of lines with U+001C-1F
or U+0085), the JVM's UTF-8 replacement of encoded surrogates (keys of lines with such bytes), `toIntOrNull` for
non-ASCII digits, live categories with a blank name ("Other" instead of the id), upper-case legacy PIN hashes and
empty salts (refused like Kotlin).

Not covered by a device yet: the URL guard on redirects (unit tests cover each hop's rules; OkHttp sends every hop
through the network interceptor and the `Dns`), real Xtream providers, arm64 and x86_64 at run time.

### Emulator check (2026-11-13, Android 14 TV x86 image, debug build, `debug.tvbro.core=rust`)

- `primaryCpuAbi=armeabi-v7a` (the APK has no x86 libraries, so the image's ARM translation runs the Fire TV build);
  log: `rust core: urlguard, pin, m3u, xtream on, loaded, core 0.1.0`.
- Add source, M3U, `http://127.0.0.1:8765/...`: not saved, stays on Add source; log `add source refused by the URL
  guard: address not allowed (local network, loopback or link-local)`.
- Add source `http://10.0.2.2:8765/playlist.m3u` (download through the guard, parsed by the core): 210 channels,
  guide 6,432 programmes stored, search index 210 channels + 6,432 programmes; the TV screen shows the guide (catch-up
  marks from the core-parsed attributes); Search "matchday" gives 45 programme results.
- Switch off (default): the library is not loaded; the same 210 channels from the cache. A per-row override from the
  Developer section (`m3u` only) gives `rust core: m3u on`.

### APK size (release, R8, unsigned; `.so` stored uncompressed and page-aligned, AGP strips them)

| | before (main 237b6a8 merged) | after | growth |
|---|---|---|---|
| whole APK | 4,599,763 B | 11,634,739 B | +7,034,976 B (+6.7 MiB) |
| arm64-v8a: `libtvbro_ffi.so` + `libjnidispatch.so` | - | 2,336,840 + 157,800 | +2,494,640 B (2.38 MiB) |
| armeabi-v7a | - | 1,627,928 + 112,248 | +1,740,176 B (1.66 MiB) |
| x86_64 | - | 2,572,112 + 108,712 | +2,680,824 B (2.56 MiB) |
| `classes.dex` (compressed) | 2,658,601 | 2,748,375 | +89,774 B (JNA, bindings, switch) |
| x86 | 9,284 (androidx graphics) | - | dropped by the ABI filter |

A device downloads all three ABIs with the universal APK; per-ABI splits (or an app bundle) would cut that to one.
32-bit x86-only devices without ARM translation can no longer install the app (none known among TV targets).

### Next in phase 2 (after step 1)

Items 1, 3 and the splits of 5 were done in step 2 (next section); the rest is listed there.

## Phase 2, step 2 (done 2026-11-13)

### Shadow mode (debug builds, opt-in)

`CoreSwitch` rows now have three modes: `KOTLIN`, `CORE` and, for the parsing rows (M3U, Xtream, guide) in debug
builds only, `SHADOW`. Opt in per row in Settings > About > Developer (OK cycles Kotlin / Rust / Shadow, next start)
or with `adb shell setprop debug.tvbro.core shadow` (all three rows) or `m3u=shadow,epg=shadow`. Kotlin stays in
charge; the core runs afterwards on the same downloaded file:

- M3U / Xtream (`Repository.channels`): the core parses the cached file on a low-priority thread; the summary has both
  channel counts, how many positions differ, the first one and the names of its differing fields (`name,group`, ...),
  and both timings.
- Xtream download check: whether Kotlin and the core accept or refuse the download (keep-old-data rule).
- Guide (`Repository.refreshEpg`): after Kotlin parsed and stored a file, the core parses the same file
  (`guide_digest`, nothing written). Both sides compute an order-independent digest of the rows (per field and per
  row, wrapping sums of FNV-1a 64 hashes; `epgload::Digest` = `CoreShadow.Digest`), so the summary names the fields
  that differ (`desc`, `ep`, ...) without either side keeping the rows.

Only counts, positions, field names and timings are kept: never names, URLs or credentials. The summary goes to
`files/core-shadow.log` (kept under 256 KB), to the diagnostics, and to the last lines of the Developer section:
`adb shell run-as io.github.kmmarin.tvbro cat files/core-shadow.log`. Emulator run (mock provider):

```
m3u: kotlin 210 / core 210 channels, identical; kotlin 424 ms, core 589 ms
guide: kotlin 6432 / core 6432 programmes, identical; kotlin 8760 ms (parse + store), core 528 ms (parse)
```

### Row 5: XMLTV + gzip into `epg.db` (switch row `epg`)

- Core: `epgload::load_into` streams a guide (gzip sniffed, `gz::Limits`: 2 GiB inflated, ratio 200 after 16 MB)
  through the XMLTV parser with Android's resolver (channel attribute, then the display names, lower case, among the
  source's tvg-ids and names; key `src|id`), the same time window, descriptions cut to 160 and sub-titles to 120
  UTF-16 units, into a new generation in 500-row transactions. `EpgStore` now has EpgDb.kt's log discipline:
  `begin_refresh` / `commit` / `discard` / `delete_source` delete 5000 rows per transaction with a checkpoint every
  10 chunks and `wal_checkpoint(TRUNCATE)` at the end; inserts checkpoint every 40 batches once the log passes 32 MB;
  open folds back a leftover log. WAL with `synchronous=NORMAL` (Android's WAL default).
- FFI: `CoreEpgDb` (one writer and one reader connection on the same file, so the UI's queries run while a refresh
  writes), `load_guide` with a `GuideFeed` callback (Kotlin decrypts the sealed download and hands it over in 256 KB
  chunks; progress after every batch), `current_page` (the search index rebuild pages by row id instead of holding
  the guide), `digest`.
- App: `GuideDb` interface with Android's `EpgDb` and the core's `CoreGuideDb`. **With row `epg` on, every access to
  `epg.db` goes through the core**, refreshes and reads alike: two SQLite builds in one process do not see each
  other's POSIX locks, so they must never share a database file (sqlite.org/howtocorrupt.html, 2.2.1). The choice is
  made once per process. The 256 MB leftover-log rule stays in Kotlin (it only deletes files before either opens).
  Both directions work on the emulator: the core opened the `epg.db` Android had written, and after a core refresh
  Android's EpgDb read the core-written file (same guide on screen, no SQLite errors).

**Parity (host, `CoreParityTest`)**: Kotlin's path (the pull parser as Android sets it up, the resolver, the cuts and
the store's first-wins rule on (key, start)) against the core's (`CoreEpgDb` in a temp file, then read back), on the
mock guide (every row compared field by field, plain and gzip), 14 hostile guides (entities / CDATA, display-name
fallback, id case, duplicate starts, cuts with accents and emoji, episode systems, first title wins, bad times,
nested markup, whitespace, Latin-1, two gzip members, the time window) and a generated 400,000-programme guide
(2000 channels x 200, gzip, digest compared). Device benchmark: `EpgBench` (debug builds,
`setprop debug.tvbro.epgbench kotlin|core`, one fresh process per run, separate database files, never `epg.db`).

| 400,000 programmes (gzip 6.8 MB, XML 155 MB) | Kotlin (EpgDb) | core |
|---|---|---|
| emulator (Android 14 TV x86; the core runs as armeabi-v7a through ARM translation, Kotlin natively), parse + store + commit | 465 s | **97 s** |
| emulator peak RSS (VmHWM; RSS at start 157-158 MB) | 213 MB | 230 MB |
| emulator log peak / after the commit | 1 MB / 0 B | 5 MB / 0 B |
| emulator `epg.db` | 118 MB | 118 MB |
| rows | 400,000 | 400,000, **digest identical** (every field) |
| host JVM test, Windows x64: Kotlin parse only (no store on the JVM) / core parse + store | 7.2 s, Java heap +198 MB | 26.4 s, Java heap +14 MB |
| host, core second refresh of the same source (old generation deleted in 5000-row chunks) | | 73 s, log peak 22.8 MB, 0 B after |

Mock guide (6,362 programmes in the window, host): Kotlin parse 289 ms, core parse + store 643 ms. Emulator, mock
provider: Kotlin refresh (with the shadow digest) 10.2 s, core refresh 2.4 s, both including the 0.5-0.6 s download.
The release core alone on the host: parse 6.3-8.3 s, parse + store 30.7 s. On the build machine, SQLite on the
build tree's drive was 6x slower than on the system temp drive, so the tests keep their databases in the temp
directory. The second refresh roughly doubles the file (freed pages are reused by the next refresh, not returned;
EpgDb behaves the same).

Accepted differences (allow-list, with reasons): a description cut in the middle of an emoji (Kotlin keeps a lone
surrogate, the core leaves the character out); a time with month 13 (Kotlin rolls it over, the core drops the
programme, the documented calendar rule); markup inside `<title>` (Kotlin's pull parser fails the whole guide, the
core takes the text).

### Truncated Xtream download (row `xtream`)

With the core on, the download check used `parse_xtream_live`, which keeps the items before damage, so a list cut off
mid-way was accepted and replaced the good one. Now `xtream_live_count_strict` (`xtream::is_complete`: a whole JSON
list or object from start to end, nothing after it) refuses it, like Kotlin's strict decoder, and the previous list
stays. Reading the cache stays lenient. Host comparison of the check on 17 inputs: identical, apart from two the core
accepts and Kotlin refuses (a whole list behind a BOM, an object of id -> stream).

### Per-ABI APKs

Release builds make `app-{arm64-v8a,armeabi-v7a,x86_64}-release.apk` and `app-universal-release.apk`
(`splits.abi`, only when a release task runs, so debug / test builds keep the single `app-debug.apk` the test tools
install; `-Ptvbro.abiSplits=false` turns them off). The release workflow publishes the universal APK as before
(`tvbro-X.Y.Z.apk`, `tvbro.apk`) plus `abi-<abi>-X.Y.Z.apk`. It also gained the Rust and NDK setup the CI Android job
got in step 1; without it a release build could not compile the core.

**Updater choice: the device's APK, universal as the fallback.** `UpdateLogic.parseLatest` takes
`Build.SUPPORTED_ABIS` and picks the per-ABI APK of the first ABI the release has (an x86 TV that translates ARM gets
armeabi-v7a, which it already runs), else the universal APK. The per-ABI names do not contain "tvbro" on purpose:
updaters released before the splits pick the first non-debug APK containing "tvbro", so they keep getting the
universal APK and never an APK for another ABI (unit-tested). Hashes come from GitHub's digest or `SHA256SUMS.txt` as
before.

Sizes (release, R8, unsigned):

| APK | bytes | `libtvbro_ffi.so` |
|---|---|---|
| universal | 18,653,075 | all three |
| arm64-v8a | 9,541,710 | 4,706,632 |
| armeabi-v7a | 8,620,564 | 3,834,632 |
| x86_64 | 9,772,461 | 4,986,544 |

Step 1's universal APK was 11,634,739 B: the library roughly doubled (arm64 2,336,840 -> 4,706,632 B) because the
guide store is now reachable from the FFI, so link-time optimisation keeps the bundled SQLite, quick-xml, flate2 and
encoding_rs it dropped before. A device downloading its per-ABI APK gets 8.6-9.8 MB instead of 18.7 MB.

### Publishing check

`tools/check_private.py` found 18 lines. The ones in files this branch adds are fixed: the linux-aarch64 linker in
`core/cross.toml` is now taken from PATH (no home-directory path), and the URL guard tests with private / LAN
addresses moved from `security/urlguard.rs` to `tests/urlguard.rs`, where the check treats them as test inputs. The
other findings are in files main already has and this branch does not change (release / resume notes, a desktop test
playlist, test-environment scripts).

### Next

1. Run shadow mode on the TV and Fire TV test devices with real playlists and guides (and real playlists in
   `testdata/private/` for the host comparison); an arm64 / x86_64 run-time check.
2. Flip rows 1-2 (URL guard, PIN) to `CORE` in a release; then 3-4; then 5 (guide) once shadow runs are clean.
3. Row 7: the search index (`search.db`, same FTS4 schema) on the core; then VOD / series parsing.
4. Size: try `opt-level = "s"` for the Android library, and check whether encoding_rs's tables can be trimmed.

## Phase 2, step 3 (done 2026-11-13)

### Row 7: the search index (switch row `search`)

- Core (`search.rs`): the writer owns the index (`into_writer` / `finish` gives it back) and does **not store stream
  addresses** (Kotlin's `storeUrls = false`; `into_writer_with(true)` keeps them for a platform that needs them).
  Folding now follows Kotlin's `Fold.text` exactly: Kotlin tests every UTF-16 unit, so a character outside the BMP
  is a separator; letters and digits are the JVM's categories (L* and Nd: every BMP run of decimal digits, not Rust's
  wider Alphabetic property, which adds letter numbers such as Roman numerals and circled letters). Blank titles use
  Kotlin's `isBlank`, the minimum query length and ranking tie-breaks use UTF-16 length and order.
  `text::kotlin_to_int` shares the digit table (M3U channel numbers with any script's digits, like the JVM).
- FFI: `CoreSearchBuilder` (`create(path)` with Kotlin's build pragmas, rows in batches of 2000, one kind at a time
  so item ids keep the call order; `programmes_from_guide(CoreEpgDb)` reads the current programmes from the core's
  guide store without crossing to Kotlin; `finish` returns the counts) and `CoreSearchIndex` (read-only: `search`,
  `page`, `categories`, `count`, `meta`).
- App: `SearchReader` (Kotlin `SqlSearch` / core `CoreSearch`) and `IndexSink` (`KotlinIndexSink` /
  `CoreIndexSink`). With the row on, the rebuild and every query of `search.db` go through the core's SQLite only
  (same rule as `epg.db`). Shadow: Kotlin's index is used; the core builds `search.shadow.db` from the same rows in
  the same order (a core failure never breaks Kotlin's build), the rebuild compares the stored counts, categories
  and first 500 catalogue rows of each kind, and every query is answered again by the core and compared hit by hit
  (all fields, in order). The query text is not logged.

### Shadow mode for every row

URL guard (Kotlin has none): the core's checks run on every request, redirect hop, DNS answer and Add source, and
only what it would refuse is counted and logged (with the reason, never the URL); nothing is blocked. PIN: Kotlin
decides; the core verifies the same PIN against the same stored hash, and after a new PIN the core verifies Kotlin's
hash while Kotlin verifies a core hash of the same PIN.

### Host parity (`CoreParityTest`, 16 tests)

579 identical cases, 15 accepted differences (the 14 of steps 1-2 and `search:fold-bmp-unassigned`, below).
Search: a generated corpus (5,060 channels, 2,000 movies, 1,000 series, 10,500 programmes with accents, Greek,
Japanese, emoji, duplicate titles differing in case, blank titles, channels interleaved with VOD) and the mock
provider, 65 + 105 queries at two section limits: **420 queries, 23,935 hits identical** field by field, plus the
counts, categories and catalogue pages. Build: core 1.2-1.6 s vs Kotlin 4.6-4.7 s (generated corpus, JDBC SQLite).
Folding of every BMP character between two letters: **all 61,903 characters the JVM assigns fold the same**; the
1,585 the JDK 17 tables (Unicode 13) leave unassigned are the accepted case: the core's newer tables treat some as
letters or marks. Kotlin's own answer for them depends on the device's runtime, and each side folds index and query
alike.

### Fire TV Stick 4K shadow run (AFTMM, Fire OS / Android 7.1.2, armeabi-v7a; debug build, all rows `shadow`)

Real Xtream provider plus the mock M3U served on the LAN; a guide refresh, two index rebuilds, 185 searches as
typed (prefixes of every query), a PIN set / wrong / right / removed. `files/core-shadow.log`: 213 lines, **0
differences**.

| Row | Device result | Timings (Kotlin / core) |
|---|---|---|
| URL guard | 527 requests, 526 network hops, 46 DNS answers, 1 Add source checked; core would refuse 0 | |
| PIN | hash cross-read both ways; verify right / wrong / right / right identical | verify 4.6-4.7 s / 0.27-0.29 s |
| M3U | 210 / 210 channels identical (twice) | 116-126 ms / 34-136 ms |
| Xtream | 15,801 and 15,800 channels identical (4 runs); download check: both accept | 2.0-2.6 s / 2.3-3.2 s |
| Guide | 374,889 and 374,542 programmes, digest identical; mock 6,432 | 201 s parse + store / 18 s parse (197 s / 17 s; 2.4 s / 0.28 s) |
| Search index | 15,801 ch, 32,469 movies, 3,798 series, 147,659 titles, 374,321 programmes identical; with the M3U source 16,010 / 32,472 / 3,799 / 148,246 / 380,399 identical | build 150 s (whole rebuild) / 127 s (core share) |
| Search queries | 185 queries identical (up to 30/30/30/300 hits) | typically 450-750 ms / 25-200 ms for broad queries |

The core's share of the index build is dominated by handing 410,000 rows across JNA from the Kotlin guide store;
with the guide row on the core too, `programmes_from_guide` reads them inside the core.

### Defaults

Every row was identical on the device and in the host tests (apart from the listed, intended hostile-input
differences), so `CoreSwitch.CORE_BY_DEFAULT` is now all six rows. `-Ptvbro.core=default` (the default) uses it,
`-Ptvbro.core=kotlin` forces every row back to Kotlin (the fallback), `-Ptvbro.core=rust` forces the core; debug
builds keep the per-row overrides and `debug.tvbro.core`. If the library fails to load, every row runs Kotlin.
Checked on the Fire TV with the new defaults and no overrides: `rust core: urlguard, pin, m3u, xtream, epg, search
on, loaded`; the core opened the `epg.db` and `search.db` Android had written (guide on screen, search results).

### Build and checks

Release, R8, signed with the release key (apksigner: verified, `CN=tvBro`):

| APK | bytes |
|---|---|
| universal | 18,955,283 |
| arm64-v8a | 9,662,158 |
| armeabi-v7a | 8,714,644 |
| x86_64 | 9,910,829 |

About +0.1 MB per ABI over step 2 (the search FFI). Unit tests: 93 (app + shared), 0 failures. `cargo test`:
101 unit + 11 property + 3 integration, clippy `-D warnings` clean, `cargo deny`: advisories / bans / licenses /
sources ok, `cargo audit`: 0 vulnerabilities, the 1 allowed warning (RUSTSEC-2026-0374, build-time only).

### Next

1. Remove the Kotlin implementations one release after the defaults have shipped without reports.
2. VOD / series parsing and details on the core; phase 3 (desktop).
3. Size: `opt-level = "s"` for the Android library; the index rebuild's row hand-over (batch records over JNA)
   is the core's main cost on armv7 when the guide row is off.

## Risks

- **Behaviour drift during the switch.** Kotlin and Python differ in small ways (names with commas, keys, calendar
  validation, lenient numbers); the core picks one rule per case (table above). Mitigation: phase-2 comparison runs.
- **Channel keys.** Stored favourites / order / names / recordings refer to keys; the core reproduces both key
  styles bit for bit (tests), and each platform keeps its own style.
- **Binary size and startup.** Bundled SQLite + crypto add roughly 2-3 MB per ABI; Android already ships SQLite,
  but the bundled one guarantees FTS4 and the same tokenizer everywhere.
- **JNA on Android.** UniFFI's Kotlin uses JNA (an extra ~1 MB AAR and reflection); acceptable, or switch to a JNI
  layer later without touching the core.
- **Encoding.** Non-UTF-8 XMLTV is decoded from its declaration (quick-xml `DecodingReader`); a declaration longer
  than 64 bytes cannot switch the encoding and such a file is read as UTF-8.
- **Toolchain.** Cross builds use `core/cross.toml` with the build machine's NDK and Pi sysroot paths (see
  `C:\tools\native\README.md`); CI for all targets is still to be set up, and a CI runner gets its own config file.
