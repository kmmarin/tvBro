# Security policy

## Reporting a vulnerability

Please report security problems **privately** through GitHub:
**[Report a vulnerability](https://github.com/kmmarin/tvBro/security/advisories/new)**
(repository > Security > Advisories > Report a vulnerability). Do not open a public issue for it.

Include what is affected (Android, desktop, web server or frontend), the version (Settings > About),
steps to reproduce and the impact you expect. Never include real provider logins or server
addresses; use made-up ones.

tvBro is maintained by volunteers, so there are no guaranteed response times. We aim to acknowledge
a report within about a week and to fix critical and high issues before anything else is released.
When a fix is released, it is announced in the release notes and in a published GitHub security
advisory, with credit to the reporter unless they prefer otherwise.

## Supported versions

Only the latest release gets security fixes. Android users learn about new releases through the
in-app update check (unless turned off) or Obtainium; desktop and web users should watch the
repository's releases.

## How the code base is kept secure

- **Release gate.** Work happens on `dev`; a release is a merge into `main` plus a version tag. Before
  every release the whole diff since the last release is reviewed for security, and no release is made
  while a known critical or high issue is open. The release workflow runs the full CI first and builds
  nothing if any job fails.
- **CI on every push and pull request** (`.github/workflows/ci.yml`): unit and UI tests for shared code,
  Android, desktop and the web server / frontend; gitleaks (secrets in code and history);
  osv-scanner (known-vulnerable dependencies); Gradle wrapper validation; `tools/check_private.py`
  (private addresses, home paths, keys, keystores). Pushes also submit the Gradle dependency graph so
  GitHub raises Dependabot alerts.
- **Dependencies.** Gradle versions are pinned; known-vulnerable transitive versions are overridden
  (Bouncy Castle >= 1.80 in the Android app). The web app's hls.js and mpegts.js are fetched at pinned
  versions and checked against SHA-256 hashes. No proprietary SDKs, analytics or ads.
- **Signed releases.** Android release APKs are signed with one tvBro key kept offline; Android itself
  rejects updates signed with another key. The certificate fingerprint is below.
- **Clean room.** No code, assets or text from other players.

## How user data is protected

| | Android | Desktop (Windows, Linux) | Web server |
|---|---|---|---|
| Provider login at rest | EncryptedSharedPreferences (Android Keystore) | Windows DPAPI; Linux keyring, or a private key file as fallback | encrypted with a key kept outside the data folder (root-only systemd credential) |
| Login in logs / backup files | no | no (except the opt-in mpv debug log `TVBRO_MPV_LOG`, which can contain stream addresses) | no |
| OS cloud backup / device transfer | switched off for tvBro's data | n/a | n/a |
| Parental PIN | salted PBKDF2-SHA256 hash | salted PBKDF2-SHA256, waits after wrong tries | salted hash, waits after wrong tries; the optional admin password is throttled too |
| Telemetry, analytics, crash reports | none | none | none |

- The only request tvBro makes that does not go to your own sources is the optional Android update
  check: one HTTPS request a day to the GitHub Releases API of this repository. A downloaded update must
  come from this repository's release download URL, stay within the announced size, match the SHA-256
  published for the release (GitHub's asset digest or the release's SHA256SUMS.txt; refused without one), be the same app, be newer and be signed with the same key, and
  it is installed only after you confirm in the system installer. The check can be turned off in
  Settings > About.
- TLS certificate verification is never disabled in any tvBro component.

## Network safety

- **Provider connections.** Most IPTV services use plain HTTP and put the login in the stream address.
  tvBro cannot change that; on an untrusted network the login can be observed. Android allows
  cleartext only for that reason and never for GitHub hosts.
- **Web server.** Every browser gets its own session (HttpOnly, SameSite=Strict cookie) that is checked on
  every API call; writes need the session's CSRF token and must come from the server's own pages (Origin /
  Sec-Fetch-Site checks); a password is optional and **off by default** (Settings > Security > Require a
  password; PBKDF2, throttled after wrong tries): without it anyone on your network can use and change
  the app, so turn it on whenever the server can be reached from beyond a trusted home network; the Host header must be one of the configured names (DNS-rebinding protection); responses
  carry security headers (CSP, frame denial, nosniff, referrer policy); the server fetches only
  `http(s)` URLs and refuses loopback, link-local and internal addresses unless you explicitly allow
  sources on your own network. It is meant for a home network: never expose it to the internet, and
  put it behind TLS (the provided nginx configuration does).

## If your device or server was compromised

1. **Change your provider password** (and the provider PIN if it has one) at your provider. This makes
   a stolen login useless, whatever was on the device.
2. **Android / desktop**: back up your settings in tvBro if you want them (backups contain no login),
   uninstall tvBro or delete its data folder, reinstall from this repository's releases, restore the
   backup and add the new login.
3. **Web server**: turn on or change the password in Settings > Security, then sign in to the provider again with the
   new login. There is no automated rotation of the server's encryption key yet; until there is,
   re-provision the container (fresh install with a new key) if the server itself was compromised.
4. If you think tvBro itself was the cause, report it privately as described above.

## Release signing certificate

Official Android releases are signed with this certificate (SHA-256):

```
BB:CA:8F:40:89:56:A5:DB:5A:CB:C1:E0:E1:AE:E4:12:89:FE:5E:7D:82:61:3D:F8:78:A5:9C:3A:51:AD:D2:DC
```

Check a downloaded APK with `apksigner verify --print-certs tvbro.apk`. Obtainium and AppVerifier
can pin this fingerprint too.
