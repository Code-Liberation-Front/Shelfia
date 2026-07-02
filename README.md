# Shelfie

A better podcast client for [Audiobookshelf](https://www.audiobookshelf.org/) on Android, inspired by Overcast on iOS — with full **Android Auto** support.

[![Build APK](../../actions/workflows/build.yml/badge.svg)](../../actions/workflows/build.yml)

## What it does

Shelfie connects to your self-hosted Audiobookshelf server and turns your podcast library into a fast, focused listening app:

- **Sign in to your server** — works with any Audiobookshelf instance (HTTPS or HTTP); supports both username/password and **OIDC single sign-on** (enter the server URL first, then pick whichever method your server offers)
- **Home / Latest / Library tabs** — Home shows Continue Listening and Recently Added shelves; Latest lists the newest episodes across your whole library; Library is the full cover-art grid
- **Search** — find podcasts and episodes by name from the top bar
- **Chromecast** — cast playback to a TV or speaker from the cast button in the top bar
- **Settings** — view your account and listening stats, switch servers/users, and switch between podcast libraries on the server
- **Downloads & offline** — download episodes for offline listening (with live progress and speed in Settings → Downloads); the library, episode lists, and listening progress are cached so the app works without a connection and plays downloaded episodes
- **Browse your podcast library** — cover-art grid, episode lists with publish dates and durations
- **Stream episodes** with background playback, media notification, lockscreen/Bluetooth controls
- **Android Auto** — browse podcasts and episodes and control playback from your car
- **Playback speed** from 0.75x to 3x
- **Overcast-style skips** — 30s forward, 10s back
- **Progress sync** — listening position is saved back to Audiobookshelf every few seconds and resumes on any device, picking up exactly where you left off

## Download

Grab the APK one of two ways:

1. **Releases** — each [release](../../releases) is built from a version tag (`vX.Y.Z`) and carries `shelfie-debug.apk` (sideload) and `shelfie-release.aab` (Google Play). Grab the newest one.
2. **Actions artifacts** — any manual CI run on the [Actions tab](../../actions) uploads `shelfie-debug-apk` and `shelfie-release-aab` artifacts.

Sideload it by enabling *Install unknown apps* for your browser/file manager, then opening the APK.

> All CI builds are signed with a shared key committed to the repo, so newer APKs install directly over older ones. If you installed a build from before this key existed (≤ v0.8.1), uninstall once — after that, updates apply in place.

## Android Auto

Shelfie ships a Media3 `MediaLibraryService`, so it appears as a media app in Android Auto automatically once installed and signed in. In the car you get:

- **Continue Listening** tab — episodes you've started, one tap to resume from where you left off
- **Podcasts** tab — your library as a cover grid, episodes as lists with played/in-progress badges
- **Full-podcast queueing** — playing an episode queues the rest of the show so next/previous track buttons work
- **Search** — both the browse search UI and voice ("play *<podcast>* on Shelfie")
- **Resume** — Auto's resume card restores your last episode and position even after the app was killed

Because sideloaded apps are hidden by default, enable developer mode in the Android Auto settings on your phone and check **"Unknown sources"**, then Shelfie will show up on the car launcher.

## OIDC / single sign-on setup

Shelfie signs in through Audiobookshelf's mobile OAuth flow using the redirect URI `audiobookshelf://oauth`.

**Getting HTTP 400 in the browser when you tap the SSO button?** The server is rejecting that redirect URI. In the Audiobookshelf web UI go to **Settings → Authentication → OpenID Connect Auth** and check **Allowed Mobile Redirect URIs**:

- it must contain `audiobookshelf://oauth` (this is the server default, but it disappears if the list was ever edited for another app), **or**
- set it to `*` to allow any mobile redirect URI.

Save the setting and try again — no app changes needed. Multiple entries are supported, so you can keep URIs for other apps (e.g. the official app or ShelfPlayer) alongside Shelfie's.

## Google Play (internal testing)

Every CI run also produces `shelfie-release.aab`, a Play-ready Android App Bundle (minified release build). To distribute through the Play Console:

1. In the [Play Console](https://play.google.com/console) choose **Create app** (name *Shelfie*, App, Free).
2. Go to **Testing → Internal testing → Create new release**.
3. Accept **Play App Signing** when prompted (Google holds the final signing key; the AAB is signed with our upload key).
4. Upload `shelfie-release.aab` from the newest tagged release or a manual CI run.
5. Add tester email addresses under the **Testers** tab, save, and share the opt-in link — testers install/update through Play like any app.
6. Each new upload needs a higher `versionCode` (bumped with each release in this repo).

By default the AAB is signed with the repo's shared keystore as the upload key. To use a private upload key instead, add repository secrets `UPLOAD_KEYSTORE_BASE64` (base64 of the keystore), `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, and `UPLOAD_KEY_PASSWORD` — CI switches to it automatically. If the upload key ever needs replacing, Play App Signing supports an upload-key reset.

## Building locally

Requirements: JDK 17+ and the Android SDK (API 35).

```bash
./gradlew assembleDebug
# APK lands in app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

- **Kotlin + Jetpack Compose** (Material 3, dark Overcast-style theme)
- **Media3 / ExoPlayer** for playback; a single `MediaLibraryService` powers the app UI, the media notification, and Android Auto
- **Retrofit + kotlinx.serialization** client for the Audiobookshelf REST API (`/login`, `/api/libraries`, `/api/items`, `/api/me/progress`)
- **DataStore** for server credentials

## Roadmap

- Smart Speed–style silence trimming and Voice Boost
- Episode downloads for offline listening
- Playlists / queue management
- Multiple library support and library switching
- Search

## License

[MIT](LICENSE)

## iOS (TestFlight + CarPlay)

A native SwiftUI companion app lives in `ios/` (login, library, episode lists, Continue Listening, streaming playback with 10s/30s skips and speed control, progress sync, and a CarPlay audio app). CI builds it on a macOS runner (`.github/workflows/ios.yml`).

One-time setup:

1. **CarPlay entitlement** — apply at https://developer.apple.com/contact/carplay/ (category *Audio*). After Apple approves it for your App ID, uncomment the `com.apple.developer.carplay-audio` key in `ios/Shelfie/Shelfie.entitlements`.
2. **App record** — register the bundle ID `com.shelfie.zbuddy` under Certificates → Identifiers, then create the app in App Store Connect (My Apps → **+**).
3. **API key** — App Store Connect → Users and Access → Integrations → App Store Connect API → Team Keys → **+** (role: App Manager). Download the `.p8` once; note the Key ID and Issuer ID.
4. **Repo secrets** — add `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8` (base64 of the `.p8`), and `APPLE_TEAM_ID` (from Membership details).
5. Run the **Build iOS** workflow (or push a `v*` tag). The signed build uploads to TestFlight automatically; add yourself as an internal tester in App Store Connect → TestFlight and install from the TestFlight app.

Without the secrets, the workflow still compile-checks the app (unsigned).
