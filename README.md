# android-app-template

Android version of my-ios-app-template. No Android Studio needed: push to `main`, GitHub Actions builds a signed APK and attaches it to a release.

## New app

1. **Use this template** on GitHub to make a new repo.
2. Edit the settings block at the top of `app/build.gradle.kts`:
   - `appName` - name under the icon
   - `appId` - unique ID, e.g. `com.hotwheelz3000.soulection`
   - `startUrl` - site the app opens
   - `appVersion` - bump for a new version
3. Put page tweaks in `app/src/main/assets/inject.js` (runs on every page, like the iOS injected JS).
4. Push. The direct APK link shows in the Actions run summary.
5. In this README, replace `REPO` in the Obtainium link below with the new repo's name.

## Builds

- `main`: syntax-checks `inject.js`, builds, lints, publishes a release
- Any other branch or PR: same checks and build, no release. The APK is in the run's **test-apk** artifact.

## What it does

- Full-screen WebView with JS, local storage and file uploads
- Links to other sites open in the browser
- Back button goes back a page
- Pull down to refresh
- `window.Android.postMessage("text")` from JS shows a toast

## APK names

- First build of a version: `MyApp-1.0.apk`
- Later builds: `MyApp-1.0-12.apk` (12 = build number)

## Installing

Open the APK link on the phone and allow installs from your browser. Every build uses the same key (`keystore/sideload.jks`), so new versions install over old ones and keep your data. This key is for sideloading only, not the Play Store.

## Updates

- **In the app:** on launch it checks the latest release's `update.json` and offers to download a newer build.
- **Obtainium:** with [Obtainium](https://github.com/ImranR98/Obtainium) installed, open this link on the phone:

```
obtainium://add/https://github.com/hotwheelz3000/REPO
```
