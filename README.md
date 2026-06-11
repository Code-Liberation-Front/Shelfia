# Shelfie

A better podcast client for [Audiobookshelf](https://www.audiobookshelf.org/) on Android, inspired by Overcast on iOS — with full **Android Auto** support.

[![Build APK](../../actions/workflows/build.yml/badge.svg)](../../actions/workflows/build.yml)

## What it does

Shelfie connects to your self-hosted Audiobookshelf server and turns your podcast library into a fast, focused listening app:

- **Sign in to your server** — works with any Audiobookshelf instance (HTTPS or HTTP)
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

Shelfie ships a Media3 `MediaLibraryService`, so it appears as a media app in Android Auto automatically once installed and signed in. Because sideloaded apps are hidden by default, enable developer mode in the Android Auto settings on your phone and check **"Unknown sources"**, then Shelfie will show up on the car launcher with a *Podcasts → Episodes* browse tree.

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
