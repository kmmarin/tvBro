# Development and releases

## Layout

| Folder | What |
|---|---|
| `shared/` | Platform-neutral Kotlin data layer: models, M3U / Xtream / XMLTV parsers, catch-up URLs, search index logic, watch state, guide window, focus rules. Used by `app/` and `desktop/`. |
| `app/` | Android app (phones, Android TV, Google TV, Fire TV). Kotlin, Jetpack Compose, Media3 ExoPlayer. |
| `desktop/` | Windows x64 and Linux arm64 app. Compose Multiplatform for Desktop, libmpv through JNA. |
| `web/` | Self-hosted web app: Python server (`web/server`) and browser frontend (`web/frontend`). |
| `docs/` | Install guide, architecture, feature parity matrix, third-party licenses, screenshots. |
| `fastlane/metadata/android/` | Store texts used by F-Droid / Obtainium-style catalogues. |
| `tools/` | `check_private.py` (personal-data scan used by CI and the pre-commit hook); `demo/demo_server.py`, a fictional Xtream / M3U / XMLTV provider for demos, screenshots and manual tests (`python tools/demo/demo_server.py`, user `demo`, password `demo`; needs ffmpeg). |

Rule: keep the platforms as close as possible; [PARITY.md](PARITY.md) lists every feature per platform.

## Branch model

```
feature/xyz ──PR──> dev ──merge──> main ──tag vX.Y.Z──> release workflow ──> GitHub Release
```

1. Issues are worked on in `dev`, directly for small fixes or on a feature branch off `dev` with a PR.
2. CI (`.github/workflows/ci.yml`) runs on every push and pull request to `dev` and `main`:
   personal-data scan, shared + Android unit tests, an Android debug build, desktop tests, web tests.
3. Release:
   ```sh
   git checkout main && git merge --no-ff dev
   git tag -a v0.2.0 -m "tvBro 0.2.0"
   git push origin main v0.2.0
   ```
   The release workflow (`.github/workflows/release.yml`) runs only for `v*` tags that are on `main`.
   It builds the signed universal APK (`tvbro-0.2.0.apk` and `tvbro.apk`), the Windows zip and the
   Raspberry Pi tarball, writes `SHA256SUMS.txt` and attaches everything to a GitHub Release named
   after the tag. Add a changelog file `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`
   before tagging; its text becomes the release notes.
4. **Release checklist** (all must be true before tagging; the release workflow also refuses to build
   anything unless the full CI workflow passes for the tag):
   - [ ] Security review of the whole diff since the last release tag
     (`git diff vPREVIOUS..main`), with extra care for networking, secret storage, the web server,
     the updater and anything that parses provider data.
   - [ ] No open critical or high security issue (GitHub security advisories and issues labelled
     `security`); medium ones are fixed or have a documented reason.
   - [ ] CI green on `main`: tests (shared, Android, desktop, web), `check_private.py`, gitleaks,
     wrapper validation, osv-scanner; no new Dependabot alerts above low.
   - [ ] Secure defaults unchanged (see CONTRIBUTING.md: sessions + CSRF + Host checks on, secrets encrypted, TLS verified,
     no unrestricted outbound fetch).
   - [ ] Changelog file for the new versionCode written; docs/PARITY.md and docs/THIRD_PARTY.md current.
5. Versions: `vMAJOR.MINOR.PATCH`; the Android `versionCode` is `MAJOR*10000 + MINOR*100 + PATCH`
   (MINOR and PATCH below 100), computed in `app/build.gradle.kts` from `-Ptvbro.version`.

## Keeping private things out of the repository

Everything machine- or person-specific lives in gitignored files with a committed `*.example`
template:

| Gitignored file | Template | Used for |
|---|---|---|
| `keystore.properties` | `keystore.properties.example` | local release signing |
| `local.properties` | (Android Studio writes it) | SDK path |
| `web/deploy.local.md` | `web/deploy.local.md.example` | your own deployment notes: host, container id, addresses |
| `/etc/default/tvbro` (on the server) | see `web/README.md` | host name nginx answers to, allowed hosts |
| `tools/private-patterns.txt` | - | extra words `check_private.py` must refuse (your name, your domain) |

`tools/check_private.py` refuses private IP ranges, personal Windows / home paths, local host names,
private keys and keystores; with `tools/private-patterns.txt` present it also refuses your own words.
Run it before committing, or enable the hook: `git config core.hooksPath .githooks`.

## Release signing (maintainers)

The release key is never in the repository. Locally, `keystore.properties` points to it. In GitHub,
repository secrets hold it: `TVBRO_KEYSTORE_BASE64` (the keystore file, base64), `TVBRO_KEYSTORE_PASSWORD`,
`TVBRO_KEY_ALIAS`, `TVBRO_KEY_PASSWORD`. Losing the key means existing installs can no longer be
updated, so keep two backups of it.

## Reproducible builds

The release APK contains no VCS info and no dependency-metadata blob; Gradle and all dependencies are
pinned. Building the same tag with the same JDK (Temurin 17) and Android build-tools should give the
same unsigned APK; compare with `apksigner` / `diffoscope` ignoring the signature block.
