# Screenshots

All screenshots use the fictional demo provider (`python tools/demo/demo_server.py`, Xtream user
`demo` / password `demo`): made-up channels, programmes, films and generated artwork. Never use a real
provider, real channel names or logos, and make sure no host names, IP addresses or account names are
visible. Dark theme only, PNG.

| File | Shows | Status |
|---|---|---|
| `web-tv.png` | Web app: TV screen (preview + guide) | done |
| `web-movies.png` | Web app: films | done |
| `web-shows.png` | Web app: shows | done |
| `web-series.png` | Web app: a series with seasons | done |
| `web-settings.png` | Web app: settings / sync | done |
| `tv-guide.png` | Android TV: TV screen, 1920x1080 | to do |
| `tv-movies.png` | Android TV: films | to do |
| `tv-series.png` | Android TV: series details | to do |
| `tv-search.png` | Android TV: search results | to do |
| `tv-settings.png` | Android TV: settings | to do |
| `phone-1.png` | Android phone | optional |
| `desktop-tv.png` | Desktop app TV screen (off-screen render: `--selftest <m3u> --render <png> --no-play`) | done |

Android TV: on an Android TV emulator (1920x1080), point tvBro at the demo provider
(`http://10.0.2.2:8790`, user `demo`, password `demo`) and capture with
`adb exec-out screencap -p > docs/screenshots/tv-guide.png`. Copy the TV shots to
`fastlane/metadata/android/en-US/images/tvScreenshots/1.png`, `2.png`, ... as well.
