# Shelfie

A better podcast client for [Audiobookshelf](https://www.audiobookshelf.org/) on Android, inspired by Overcast on iOS — with full **Android Auto** support.

[![Build APK](../../actions/workflows/build.yml/badge.svg)](../../actions/workflows/build.yml)

## What it does

Shelfie connects to your self-hosted Audiobookshelf server and turns your podcast library into a fast, focused listening app:

- **Sign in to your server** — works with any Audiobookshelf instance (HTTPS or HTTP); supports both username/password and **OIDC single sign-on** (enter the server URL first, then pick whichever method your server offers)
- **Home / Latest / Library tabs** — Home shows Continue Listening and Recently Added shelves; Latest lists the newest episodes across your whole library; Library is the full cover-art grid
- **Search** — find podcasts and episodes by name from the top bar
- **Chromecast** — cast playback to a TV or speaker from the cast button in the top bar
- **Settings** — view your account and listening stats, switch servers/users
- **Downloads & offline** — download episodes for offline listening (with live progress and speed in Settings → Downloads); the library, episode lists, and listening progress are cached so the app works without a connection and plays downloaded episodes
- **Browse your podcast library** — cover-art grid, episode lists with publish dates and durations
- **Stream episodes** with background playback, media notification, lockscreen/Bluetooth controls
- **Android Auto** — browse podcasts and episodes and control playback from your car
- **Playback speed** from 0.75x to 3x
- **Overcast-style skips** — 30s forward, 10s back
- **Progress sync** — listening position is saved back to Audiobookshelf every few seconds and resumes on any device, picking up exactly where you left off

## Download

Grab the APK one of two ways:

1. **Releases** — the [`latest` release](../../releases/tag/latest) always carries `shelfie-debug.apk` built from the newest commit on the default branch.
2. **Actions artifacts** — every CI run on the [Actions tab](../../actions) uploads a `shelfie-debug-apk` artifact.

Sideload it by enabling *Install unknown apps* for your browser/file manager, then opening the APK.

> The APK is currently debug-signed. When updating you can install over the previous build as long as both came from CI; if signatures ever mismatch, uninstall first.

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
