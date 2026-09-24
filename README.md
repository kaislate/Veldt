# Veldt

**A local + self-hosted music player for Android — with the Veldt Wisp pill built in.**

> ⚠️ **Early development.** Veldt is being built in phases. The local library, browse,
> now-playing, playlists, folders and settings are in, and server accounts can be added;
> streaming from a server, the built-in pill and lyrics are not yet. Treat it as a working
> preview, not a release.

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

## Status — what works today

- **Playback.** A Media3 `PlaybackService` with audio focus, a media notification,
  and background playback via a `mediaPlayback` foreground service. A
  `PlayerBusAdapter` mirrors player state into `MediaSessionBus`, which is the seam
  the built-in pill will read.
- **Library.** On-device scan via `MediaStore`, augmented with eAlvaTag tag reading,
  stored in Room and kept live by a `MediaStore` observer.
- **Browse and now-playing.** Songs / albums / artists / search, an album-art
  backdrop with a palette extracted from the current artwork, and a scrub bar.
- **Playlists.** Create, reorder, add from browse, and `.m3u` / `.m3u8` import.
  Entries are keyed on a rescan-stable source identity, so a track that moves — or a
  volume that remounts — re-links itself instead of going permanently blank.

- **Folders.** Browse the library as it sits on disk, across internal storage and SD
  cards, with audiobooks and podcasts included.
- **Settings.** Light / Dark / Follow-system theme, with now-playing colours solved for
  legible contrast against the artwork actually on screen.
- **Server accounts.** Add an OpenSubsonic server (tested against Navidrome), test the
  connection, and store the password encrypted with an Android Keystore key. Plain
  `http://` is allowed for LAN and Tailscale setups, with a warning as you type it.

Not yet: browsing and streaming a server's library (under way — **N2**), the built-in
pill, lyrics, and scrobbling.

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

Veldt is original work, written from scratch. It is the sibling of
[**Veldt Wisp**](https://github.com/kaislate/veldt-wisp) and shares its design
language, but no code is carried over — the two files that once were (the palette
extractor and the media-session bus) were both rewritten clean-room during Veldt's
own development. Distributed under the GNU General Public License v3.0 or later
— see [LICENSE](LICENSE). If you distribute a modified version, it must carry the
same licence and ship its source.

Bundled third-party assets: the [Bricolage Grotesque](https://github.com/ateliertriay/bricolage)
typeface, under the SIL Open Font License 1.1 — see
[licenses/bricolage-grotesque-OFL.txt](licenses/bricolage-grotesque-OFL.txt).
