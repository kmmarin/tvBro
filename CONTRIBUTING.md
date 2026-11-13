# Contributing to tvBro

Thanks for helping. Bug reports, ideas, translations and pull requests are welcome.

## Ground rules

- **Clean room.** tvBro is built only from public specifications and documentation (M3U, Xtream
  `player_api.php` conventions, XMLTV, Android / Media3, mpv). Never copy code, layouts, UI text,
  icons, images or other assets from proprietary IPTV players or their decompiled apps, and do not
  describe a change as "make it like app X". Describe the behaviour you want instead.
- **No content.** Do not add channel lists, playlists, provider URLs, logos of broadcasters or
  anything else that is content rather than player. Tests use synthetic fixtures and public test
  streams only.
- **No personal data.** No real logins, server addresses, private IPs, home paths or device serials
  in code, tests, logs or screenshots. Use `example.com` and `192.0.2.x`. CI runs
  `tools/check_private.py` and fails when it finds such things; run it locally too (see below).
- **Dark theme only**, visible focus on every focusable item, everything reachable with a TV remote
  (D-pad + OK + Back).
- **License.** By contributing you agree that your contribution is licensed under the
  [Apache License 2.0](LICENSE), like the rest of tvBro (Apache-2.0 section 5). New source files start with:
  ```
  // Copyright 2026 The tvBro authors
  // SPDX-License-Identifier: Apache-2.0
  ```
  New dependencies must be Apache-2.0-compatible and free software (no Play Services, Firebase,
  analytics or ads); add them to `docs/THIRD_PARTY.md` and, if they need a notice, to `NOTICE`.

## Security rules

Nothing is released with known critical or high security problems. tvBro runs inside people's home
networks, so a careless change can put other devices at risk. Every change keeps these defaults:

- **Anything that listens on the network** (web server, phone-link page) keeps per-client sessions,
  CSRF and Host-header checks on at all times; where a password is optional (web server, off by default
  by the project's decision) the UI must say plainly what that means, and no endpoint may expose secrets,
  files or internal services without it.
- **Secrets encrypted at rest** (provider logins, share passwords, keys) and never written to logs,
  backups, URLs shown in the UI, or crash reports.
- **TLS verification is never disabled** (no trust-all certificates or hostname verifiers).
- **No unrestricted outbound fetch**: only `http(s)` URLs, never `file://` or other schemes; servers
  never fetch loopback, link-local or internal addresses on behalf of a client; size and time limits on
  every download.
- Untrusted input (playlists, guides, provider JSON, stream URLs) is parsed defensively: no XML
  entities, no shell or argument injection into external programs.
- Report vulnerabilities privately, see [SECURITY.md](SECURITY.md).

Pull requests that touch networking, storage of secrets, the web server or the updater get a security
review before merging.

## Branches

- `main`: released code only. Every release is a tag `vX.Y.Z` on `main`.
- `dev`: where work happens. Open pull requests against `dev` (or work on a feature branch off `dev`
  and open the PR from there).
- Releases: `dev` is merged into `main`, the merge is tagged, and the release workflow builds and
  publishes the signed APK and desktop packages. Details in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Running the tests

```sh
./gradlew :shared:test :app:testDebugUnitTest      # data layer + Android unit tests (JVM)
./gradlew :desktop:test                             # desktop, including headless Compose UI tests
python -m unittest discover -s web/tests          # web server (needs: pip install cryptography)
node --test web/tests/guide.test.mjs web/tests/vod.test.mjs web/tests/reminders.test.mjs \
  web/tests/playfmt.test.mjs web/tests/resume.test.mjs web/tests/livefix.test.mjs   # web frontend logic
python tools/check_private.py                       # personal-data scan of tracked files
```

Enable the pre-commit scan once per clone: `git config core.hooksPath .githooks`.

## Reporting bugs

Use the issue templates. Include the platform and device (e.g. "Fire TV Stick 4K, Fire OS 7"), the
tvBro version (Settings > About), what you pressed on the remote and what happened. Diagnostics from
Settings > About help, but remove anything that looks like a server address or login first.
