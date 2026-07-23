# Veldt

**A local + self-hosted music player for Android — with the Veldt Wisp pill built in.**

> ⚠️ **Early development.** Veldt is being built in phases. This repo currently
> contains the **P1.1 playback foundation** and is not yet a usable player.

Veldt is the full-player companion to [**Veldt Wisp**](https://github.com/kaislate/veldt-wisp)
(the standalone One UI-style now-playing pill). Where Veldt Wisp rides *any*
app's media session, Veldt has its own playback engine and library, and bundles
the same pill as a built-in feature.

- **Playback:** Media3 `ExoPlayer` inside a `MediaLibraryService`, so Veldt is a
  proper MediaSession *producer* (audio focus, becoming-noisy, an auto-generated
  media notification, and later Android Auto / Assistant for free).
- **The seam:** Veldt mirrors its own player state into the same `MediaSessionBus`
  the pill overlay reads — so the pill can reflect Veldt's playback directly, with
  no notification-listener permission needed.
- **Planned:** on-device library (MediaStore + tag parsing), browse / now-playing
  UI, the built-in pill with a built-in / defer-to-Veldt-Wisp / off toggle, lyrics
  (local + optional LRCLIB), then self-hosted backends (OpenSubsonic, then Jellyfin).

Like Veldt Wisp, Veldt is **pure Kotlin/Compose with no native code we author**
(Media3 decodes via the platform `MediaCodec`), so it runs on 32-bit and modern
arm64 devices alike.

## Status — what works today (P1.1)

- A Media3 `PlaybackService` that plays an on-device audio file (audio focus +
  media notification + background playback via a `mediaPlayback` foreground service).
- A `PlayerBusAdapter` that mirrors player state into `MediaSessionBus`.
- A temporary developer screen (pick an audio file, play/pause/seek, and watch the
  bus state update live) that proves the producer→bus seam end-to-end.

There is **no library, browse UI, or pill yet** — those arrive in the next slices.

## Requirements

- Android 10+ (API 29)

## Build

```
git clone https://github.com/kaislate/Veldt.git
cd Veldt
./gradlew assembleDebug
```

Requires JDK 17+ and the Android SDK (compileSdk 36).

## Credits & license

Veldt reuses the overlay/pill and media-session plumbing from Veldt Wisp, itself a
fork of [DynamicIslandMusic](https://github.com/bguerraDev/DynamicIslandMusic) by
**Bryan Guerra**. Distributed under the MIT License with Attribution Requirement —
see [LICENSE](LICENSE).
