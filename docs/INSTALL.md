# Installing tvBro

tvBro is not in the Google Play Store or the Amazon Appstore. You install it directly from this
project's [GitHub Releases](https://github.com/kmmarin/tvBro/releases) ("sideloading"). That is
safe as long as you take the file from that page: every release APK is signed with the same tvBro
key, and Android refuses an update that is signed with a different one. The certificate fingerprint
is listed in [SECURITY.md](../SECURITY.md).

Download links that always point to the newest version:

- Android APK: https://github.com/kmmarin/tvBro/releases/latest/download/tvbro.apk
- Windows: https://github.com/kmmarin/tvBro/releases/latest/download/tvbro-windows-x64.zip
- Raspberry Pi / Linux arm64: https://github.com/kmmarin/tvBro/releases/latest/download/tvbro-linux-arm64.tar.gz

After installing, open tvBro and add your source: **Settings > Playlists > Add** (Xtream login or
M3U URL plus an optional XMLTV guide URL). On a TV, choose **Use your phone** to type it on your phone
instead (scan the QR code; the phone must be on the same Wi-Fi).

tvBro is a player only. It contains no channels; you need your own IPTV subscription or playlist and
the rights to watch it.

---

## Android phone or tablet

1. On the phone, open https://github.com/kmmarin/tvBro/releases/latest/download/tvbro.apk in the browser.
2. When the download finishes, tap it. Android asks to allow your browser (or Files app) to
   **install unknown apps**: tap **Settings**, switch it on, go back.
3. Tap **Install**, then **Open**.

Android 7 (no per-app switch): Settings > Security > **Unknown sources** on, install, then switch it off again.

## Android TV and Google TV (Chromecast with Google TV, Sony, TCL, Philips, Nvidia Shield ...)

The TV has no normal browser, so use a helper app:

**With the Downloader app (easiest)**
1. On the TV, install **Downloader by AFTVnews** from the Play Store.
2. Allow it to install apps: Settings > Apps > Security & restrictions > **Unknown sources** >
   Downloader **on** (Google TV: Settings > System > About > tap **Android TV OS build** 7 times
   to enable developer options first if the switch is hidden).
3. Open Downloader, type the URL `https://github.com/kmmarin/tvBro/releases/latest/download/tvbro.apk`
   and press **Go**. When it finishes, choose **Install**.
4. After installing, Downloader offers to delete the APK file: you can delete it.

**With "Send files to TV"**: install it on both phone and TV, download the APK on the phone, send it
to the TV, open it there with a file manager and install.

tvBro appears in the apps row (on Google TV under **Apps > Your apps**; you can move it to Favourites).

## Amazon Fire TV (Fire TV Stick, Stick 4K / 4K Max, Cube, Fire TV televisions)

1. Home > **Find** > search **Downloader** > install it (free, by AFTVnews).
2. Turn on developer options: Settings > **My Fire TV** > **About** > select your device name
   **7 times** until it says you are a developer.
3. Settings > My Fire TV > **Developer options** > **Install unknown apps** > **Downloader: ON**
   (older Fire OS: "Apps from Unknown Sources: ON").
4. Open Downloader, allow access to files if asked, and in the URL box type
   `https://github.com/kmmarin/tvBro/releases/latest/download/tvbro.apk` > **Go**.
5. Choose **Install**, then **Open**. Delete the downloaded file when Downloader asks.
6. To find tvBro later: Home > **Apps** (the three-squares icon). Long-press tvBro > Move to put it on the home row.

Every Fire TV with Fire OS 6 or newer works (Android 7.1 and later, including 32-bit sticks).

## With ADB (any Android device, for advanced users)

1. On the device enable developer options and **ADB debugging** (Fire TV: Developer options >
   ADB debugging; Android TV: Settings > Device preferences > Developer options > USB / network debugging).
2. Find the device's IP address (Settings > Network).
3. On your computer with [Android platform-tools](https://developer.android.com/tools/releases/platform-tools):
   ```sh
   adb connect <device-ip>:5555        # accept the prompt on the TV
   adb install -r tvbro.apk
   ```

## Updates

- **Inside tvBro (Android)**: when tvBro starts it checks this project's latest release (at most once
  a day). If there is a newer version it shows **Update available** with the release notes and three
  choices: **Update** (download with progress, then **Install**), **Later**, or **Skip this version**.
  The download is checked (published SHA-256 checksum, same app, newer, same signing key) before the
  system installer opens; there you press Install. The first time, Android asks you to allow tvBro to
  install apps (Android 8 and later: "Install unknown apps" for tvBro; Android 7 / Fire OS 6: the
  "Unknown sources" / "Apps from Unknown Sources" switch); tvBro opens that setting for you. You can
  also check by hand in **Settings > About > Check for updates**, and turn the automatic check off there.
- **Obtainium (automatic updates without the store)**: install [Obtainium](https://github.com/ImranR98/Obtainium)
  (from its GitHub releases or F-Droid), tap **Add App**, paste `https://github.com/kmmarin/tvBro`
  and **Add**. Obtainium then notifies you of new tvBro releases and installs them. Leave
  "include pre-releases" off.
- **Manually**: install the newer APK over the old one, as above. Your settings and logins stay.

If an update fails with "App not installed" / "package conflicts": the installed copy was signed with
a different key (for example a self-built or test version). Back up in Settings, uninstall tvBro,
install the release APK, restore the backup, then add your login again.

## F-Droid

tvBro is not on F-Droid yet. The project is prepared for it (no non-free libraries, no tracking,
builds from source, store texts in `fastlane/metadata/android`); this section will be updated if it
is accepted. F-Droid builds would be signed by F-Droid and updated by the F-Droid app.

## Windows 10 / 11 (x64)

1. Download `tvbro-windows-x64.zip` from the latest release and unzip it, for example to
   `Documents\tvBro`.
2. tvBro plays video with **libmpv**, which is not included (it is licensed differently, see
   [THIRD_PARTY.md](THIRD_PARTY.md)). Download `mpv-dev-x86_64-<date>-git-<hash>.7z` from
   https://github.com/shinchiro/mpv-winbuild-cmake/releases, open it with 7-Zip and copy
   **`libmpv-2.dll`** into the unzipped `tvBro` folder, next to `tvBro.exe`.
3. Run `tvBro.exe`. Windows SmartScreen may warn about an unknown publisher: More info > Run anyway.
4. Keys: arrows move, Enter selects, Esc goes back, F11 full screen.

## Raspberry Pi 4 / 5 and other Linux arm64

Needs a 64-bit desktop OS (Raspberry Pi OS / Debian 12 bookworm or newer).

```sh
sudo apt install libmpv2
wget https://github.com/kmmarin/tvBro/releases/latest/download/tvbro-linux-arm64.tar.gz
tar xzf tvbro-linux-arm64.tar.gz
tvbro/tvbro.sh
```

## Self-hosted web app

For a household server (Debian / Ubuntu, a container or a small PC), see [web/README.md](../web/README.md).
Any browser on your network, including phones and tablets, then uses tvBro without installing anything.
