# Third-party software in tvBro

tvBro itself is licensed under the Apache License 2.0 (see `LICENSE`, `NOTICE`). It uses the
components below, each under its own license. The list was taken from the resolved Gradle runtime
classpaths (`:app:releaseRuntimeClasspath`, `:desktop:runtimeClasspath`), `web/server/requirements.txt`,
the web deploy script and the desktop packaging notes.

Legend: **OK** = permissive / Apache-compatible, nothing to do beyond keeping notices.
**Note** = fine, but read the remark. **Action** = needs a decision before binaries are published.

## Android app (`app/`, `shared/`)

| Component | Version | License | Status |
|---|---|---|---|
| Kotlin stdlib, kotlinx.coroutines, kotlinx.serialization, kotlinx-datetime, atomicfu, JetBrains annotations | 2.0.21 / 1.8.1 / 1.7.3 / 0.6.0 / 0.23.2 / 23.0.0 | Apache-2.0 | OK |
| AndroidX: Compose (ui, foundation, animation, runtime, material3, material-icons-core, ripple), Activity, Lifecycle, Core, Collection, Annotation, Arch core, Autofill, Customview, Emoji2, ExifInterface, Graphics-path, Interpolator, Media, RecyclerView, SavedState, Startup, Tracing, VersionedParcelable, Concurrent-futures, ProfileInstaller, Work, Room, SQLite | BOM 2024.10.01 etc. | Apache-2.0 | OK |
| AndroidX Media3 (ExoPlayer, HLS, UI, datasource-okhttp, extractor, decoder, container, database) | 1.4.1 | Apache-2.0 | OK |
| AndroidX Security-crypto (EncryptedSharedPreferences) | 1.1.0-alpha06 | Apache-2.0 | OK |
| Google Tink (via security-crypto) | 1.8.0 | Apache-2.0 | OK |
| Guava, failureaccess, listenablefuture (via Work / Media3) | 33.0.0-android | Apache-2.0 | OK |
| Gson (via Tink) | 2.8.9 | Apache-2.0 | OK |
| OkHttp, Okio (Square) | 4.12.0 / 3.6.0 | Apache-2.0 | OK |
| ZXing core (QR code for "Use your phone") | 3.5.3 | Apache-2.0 | OK |
| jcifs-ng (SMB network share for recordings) | 2.1.10 | LGPL-2.1 | Note 1 |
| Bouncy Castle bcprov (via jcifs-ng) | 1.76 | MIT-style (Bouncy Castle License) | OK |
| SLF4J API (via jcifs-ng) | 1.7.36 | MIT | OK |
| Test only: JUnit 4 | 4.13.2 | EPL-1.0 | OK (not shipped) |
| Test only: kXML2 | 2.3.0 | BSD-style | OK (not shipped) |
| Test only: sqlite-jdbc | 3.46.1.3 | Apache-2.0 (SQLite: public domain) | OK (not shipped) |
| Compile only: XmlPull API | 1.1.3.1 | Public domain | OK (not shipped) |
| Icons, launcher icon, banner | - | Original vector drawings in this repo (Apache-2.0) | OK |
| Fonts | - | Android system fonts, none bundled | OK |

Native code: only AndroidX `graphics-path` (`libandroidx.graphics.path.so`, about 10 KB per ABI,
Apache-2.0, pulled in by Compose). The release APK carries it for armeabi-v7a, arm64-v8a, x86 and
x86_64, so one universal APK runs on every device.

No proprietary SDKs: no Google Play Services, Firebase, analytics, crash reporting or ads.

**Note 1, jcifs-ng (LGPL-2.1).** Used unmodified as a separate library. LGPL-2.1 section 6 lets an
Apache-2.0 app ship it as long as users can replace the library with a modified version: tvBro's
complete source and build files are public, so anyone can rebuild the APK with a changed jcifs-ng.
The release APK keeps jcifs-ng classes un-obfuscated (`-keep class jcifs.** { *; }` in
`app/proguard-rules.pro`). Keep the jcifs-ng line in `NOTICE` and the link to its source
(https://github.com/AgNO3/jcifs-ng). If this is ever unwanted, the SMB feature can be dropped and the
dependency removed without touching anything else.

## Desktop app (`desktop/`, Windows x64 and Linux arm64)

| Component | Version | License | Status |
|---|---|---|---|
| JetBrains Compose Multiplatform for Desktop (runtime, ui, foundation, material, material3, animation) | 1.7.3 | Apache-2.0 | OK |
| Skiko (Skia bindings, native per OS) | 0.8.18 | Apache-2.0; Skia itself BSD-3-Clause | OK |
| JetBrains AndroidX lifecycle ports | 2.8.4 | Apache-2.0 | OK |
| kotlinx-coroutines-swing | 1.8.1 | Apache-2.0 | OK |
| JNA, JNA Platform | 5.15.0 | Apache-2.0 (dual-licensed with LGPL-2.1; used under Apache-2.0) | OK |
| OkHttp / Okio | 4.12.0 / 3.6.0 | Apache-2.0 | OK |
| kXML2 (XmlPullParser at run time) | 2.3.0 | BSD-style | OK |
| sqlite-jdbc (search index) | 3.46.1.3 | Apache-2.0; bundled SQLite natives public domain | OK |
| jcifs-ng + Bouncy Castle + SLF4J | as above | LGPL-2.1 / MIT / MIT | Note 1 (same as Android; plain jar on the classpath, replaceable) |
| Bundled Java runtime (Eclipse Temurin / OpenJDK 17, via jpackage or the Pi tarball) | 17 | GPL-2.0 with Classpath Exception | Note 2 |
| **libmpv** (video engine), Windows `libmpv-2.dll` | shinchiro mpv-winbuild-cmake build | **GPL-2.0-or-later as built** (mpv `gpl` feature on, FFmpeg with GPL parts) | **Action 1** |
| libmpv on Linux / Raspberry Pi | system package `libmpv2` | as packaged by the distribution | OK (not shipped; installed with `apt`) |

**Note 2, Java runtime.** OpenJDK's Classpath Exception allows shipping it next to an application
under any license. The runtime is unmodified; its license files stay inside the bundled `runtime/`
(Windows) or `jre/` (Pi) folder, and its source is at https://github.com/adoptium/jdk17u.

**Action 1, libmpv on Windows.** tvBro loads libmpv at run time through JNA; it is not linked in.
The ready-made Windows builds from shinchiro/mpv-winbuild-cmake enable mpv's GPL features and a GPL
FFmpeg, so the DLL is GPL-2.0-or-later. Shipping that DLL *inside* the tvBro Windows zip makes the
zip a GPL distribution: it would need the complete corresponding source of mpv, FFmpeg and every
library linked into that DLL to be offered with it, and the combined package could not be
redistributed under Apache-2.0 terms alone. Options:

1. **Do not bundle the DLL (recommended).** The Windows zip ships tvBro only; when `libmpv-2.dll`
   is missing, tvBro should explain where to get it (a link to the upstream mpv Windows builds) and
   which folder to put it in, or download it from the upstream release after the user agrees
   (desktop to-do, see docs/INSTALL.md for the manual step meanwhile). tvBro then distributes no GPL binary at all, the release stays purely Apache-2.0
   ("free to use for any purpose"), and there is no source-mirroring duty. Cost: one extra step for
   Windows users. The release workflow in `.github/workflows/release.yml` already builds the zip
   without the DLL.
2. Bundle an **LGPL build** of libmpv (mpv built with `-Dgpl=false`, FFmpeg without `--enable-gpl`
   / `--enable-nonfree`), built from pinned sources in CI. Still a binary distribution of
   LGPL code, so the exact source archives must be published with each release (attach them as
   release assets). More CI work; zero extra steps for users.
3. Bundle the GPL DLL and label the **Windows binary package** "GPL-3.0-or-later as a whole"
   (Apache-2.0 code is GPL-3.0-compatible; mpv is GPL-2.0-or-later). The tvBro source stays
   Apache-2.0. Needs the full corresponding source of the DLL published with each release.

Recommendation: option 1 now; option 2 later if one-click Windows install matters.

## Web app (`web/`, self-hosted server + browser frontend)

| Component | Version | License | Status |
|---|---|---|---|
| Python 3 standard library | 3.11+ | PSF-2.0 | OK |
| cryptography (pip) | >= 42.0 | Apache-2.0 OR BSD-3-Clause | OK |
| hls.js (fetched at deploy into `frontend/vendor/`, not committed) | 1.5.17 | Apache-2.0 | OK |
| mpegts.js (fetched at deploy into `frontend/vendor/`, not committed) | 1.7.3 | Apache-2.0 | OK |
| nginx (deployment example only) | distro | BSD-2-Clause | OK (not shipped) |
| FFmpeg (`ffmpeg` command run as a separate process for remux / recording) | distro | LGPL-2.1+ or GPL-2.0+ depending on the build | Note 3 |
| Test only: Node.js built-in test runner, Playwright | - | MIT / Apache-2.0 | OK (not shipped) |
| Fonts | - | `system-ui` stack, none bundled | OK |

**Note 3, FFmpeg.** The web server starts `ffmpeg` as an external program installed by the operator
(`apt install ffmpeg`). Nothing of FFmpeg is linked into or distributed with tvBro, so FFmpeg's
license does not reach tvBro's code. If a future Docker image bundles ffmpeg, that image becomes an
FFmpeg distribution and must carry its license and source offer.

## Build tooling (not shipped)

Gradle 8.12 and wrapper (Apache-2.0), Android Gradle Plugin 8.7.3 (Apache-2.0), Kotlin Gradle
plugins (Apache-2.0), JetBrains Compose Gradle plugin (Apache-2.0), R8 (BSD-3-Clause).

## Verdict

Apache-2.0 fits "free to use for any purpose" (commercial use, modification, redistribution,
explicit patent grant). No dependency forces a different license on tvBro's source. The only
blocker for binaries is the GPL libmpv DLL in the Windows zip (Action 1). jcifs-ng (LGPL) and the
OpenJDK runtime (GPL + Classpath Exception) are fine as used; keep their notices.
