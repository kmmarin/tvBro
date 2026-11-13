## What and why

<!-- Short description; link the issue: "Fixes #123". PRs go into `dev`. -->

## Checklist

- [ ] Targets `dev`
- [ ] Tests pass locally (`./gradlew :shared:test :app:testDebugUnitTest`, plus desktop / web if touched)
- [ ] `python tools/check_private.py` is clean (no addresses, logins, home paths, real channel data)
- [ ] Clean room: nothing copied from other players (code, UI text, icons, layouts)
- [ ] Works with a TV remote only (D-pad, OK, Back) and keeps the dark theme
- [ ] Same change needed on other platforms? Noted in docs/PARITY.md
- [ ] New dependency? License added to docs/THIRD_PARTY.md