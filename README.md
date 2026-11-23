# Soulection Radio for Android (Unofficial)

An unofficial Android app for [Soulection Radio](https://radio.soulection.com/). It's the same site you'd open in Chrome, as a real app: it keeps playing with the screen off and adds likes, listening history, playlists, a sleep timer, a random show button and an endless Mixtape.

The Android version of [soulection-app](https://github.com/hotwheelz3000/soulection-app) (iPhone).

> **Unofficial fan project.** Not affiliated with, endorsed by or connected to Soulection. The app shows the official site; all shows, music, names and artwork belong to Soulection and the artists. No ads, accounts or tracking.

## Download

**[Download Soulection Radio](https://github.com/hotwheelz3000/soulection-android/releases/latest)**: open the link on your phone, then tap the `.apk` file under **Assets** (named with the version and build, e.g. `SoulectionRadio-1.0-12.apk`; the first build of a version has no build number).

## How to install

1. **Download the `.apk`** from the link above.
2. **Open it.** If Android asks, allow installs from your browser (or Files app), then tap **Install**.
3. Android 8 or later.

New builds install over the old one and keep your likes and history.

**Updates:** the app tells you when a new build is out. Or, with [Obtainium](https://github.com/ImranR98/Obtainium) installed, open this link on the phone:

```
obtainium://add/https://github.com/hotwheelz3000/soulection-android
```

## What it does

**Listening**
- **Plays with the screen off**, with the song, artist and cover in the notification and on the lock screen. Previous / next jump between songs in the show; headphone and Bluetooth buttons work too.
- **Pauses for phone calls** and carries on after. Unplugging headphones pauses it.
- **Big player, Up Next, swipe the mini player to skip, picks up where you left off**: the same as on iPhone.
- **Speaker button** in the big player opens Android's output picker.

**Likes and History**
- **Heart any song** in a tracklist or either player. Search, swipe to remove, share, or open on Spotify or Apple Music.
- **Play them as a playlist:** in order or shuffled, across shows, with the screen off.
- **Continue listening:** the shows you were in the middle of.

**Extras**
- **Random show** (dice), **Mixtape** (endless random songs), **sleep timer** that fades the music out.
- **New show notifications:** the app checks about every 15 minutes, even when closed.
- **Top bar** with Back, Forward, Home, Likes, dice, Mixtape, sleep timer and Help on every page. The phone's back button goes back a page.

**Backup**
- **On this phone, always:** `Downloads/Soulection/Soulection Radio Backup.json`, updated after every change.
- **Google Drive (turn it on once):** in Likes, tap **Turn on** → **Save a backup file**, pick a folder in Drive and tap **Save**. That file is kept up to date and survives deleting the app or switching phones.
- **After a reinstall,** tap **Pick my backup file** when the app asks.
- **Works across phones:** an iPhone app backup restores here, and the other way round.

## How it's built

- Kotlin, built by GitHub Actions (no Android Studio). Push to `main` and the APK is attached to a release.
- The page tweaks are the iPhone app's injected JavaScript, in `app/src/main/assets/`: `nowplaying.js` and `favorites.js` (from the iPhone app, Android wording), plus `media-shim.js`, which passes "now playing" and the lock screen buttons between the page's player and Android.
- Native parts: `PlaybackService` (screen-off playback, notification, media session), `MediaHub` (talks to the page's players, phone calls), `FavoritesBridge` / `FavoritesStore` (likes, history, backups, sleep timer), `NewShows` (new-show checks).
