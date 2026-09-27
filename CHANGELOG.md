# Changelog

All notable changes to Veldt. Versions follow `major.minor.patch`; the version code is
`major*10000 + minor*100 + patch`.

## 0.9.2 — 2026-09-27 (public beta)

### Added
- **Your queue comes back.** After Veldt is closed, the phone restarts or the app updates,
  the last queue returns paused at the same track and position, with shuffle and repeat.
  Pressing play on headphones, in the car or on Android's media controls picks it up.
- **Sleep timer:** 15, 30, 45 or 60 minutes, the end of the current track, or a custom
  time, from the moon button on now playing. The last 30 seconds fade out; add 10 minutes
  or cancel from the sheet or the notification.
- **ReplayGain** evens out loudness between songs using their tags, from local files and
  your server. Auto mode (the default) uses album gain for an album played in order and
  track gain otherwise, and never lets a boost clip. Settings → Playback has Off / Track /
  Album / Auto and a pre-amp.
- **Android Auto and Assistant:** browse Recent, Playlists, Albums, Artists and Songs in
  the car, search, and ask for music by voice.
- **Home-screen widget** in three sizes, with the cover, title and controls, tinted from
  the artwork.
- **Support Veldt** in Settings → About.

### Fixed
- Lyrics that are only a web link (a download-site watermark in some files) no longer show;
  Veldt looks for real lyrics instead.
- After a server rejects a password, fixing the username or a successful sync now lifts the
  block and sends the plays that were waiting, instead of needing a new password.

## 0.9.1 — 2026-09-27 (public beta)

### Changed
- Veldt has its own app icon: a record rising like a sun over green hills. 0.9.0 shipped
  with a copy of Veldt Wisp's icon, so the two apps looked identical on a home screen. The
  new icon also has a themed (single-colour) version for Android 13 and later.

## 0.9.0 — 2026-09-27 (public beta)

The first public release. It covers everything built since the project began. It is a beta:
expect rough edges, and please report them.

### Library
- On-device scan through `MediaStore`, with eAlvaTag reading the files' own tags (album
  artist, disc and track numbers, embedded art), stored in a Room index.
- The library stays live: a `MediaStore` observer picks up added, changed and removed files
  without a manual rescan.
- Audiobooks and podcasts are included.
- Songs, Albums (grid) and Artists tabs, plus search across songs, albums and artists.
- A Folders tab that shows the library as it sits on disk, across internal storage and SD
  cards, with breadcrumbs. You can sort by filename, track number, title or date modified,
  in either direction, and play or shuffle a folder with or without its subfolders.
- Hide a folder from the library (long-press in Folders). Its songs leave Songs, Albums,
  Artists and search, but it stays browsable and playable in Folders. Hidden folders are
  listed in Settings to show again, with no rescan needed.
- Rename a storage volume (long-press in Folders), or reset it to its default name.
- Playlists: create, reorder, add from any browse screen, shuffle, append to the queue, and
  import `.m3u` / `.m3u8`. Entries are keyed on a rescan-stable identity, so a moved file
  or a remounted volume re-links instead of going blank.

### Playback
- A Media3 `ExoPlayer` inside a `MediaLibraryService`, with audio focus, pause on
  headphone unplug, a media notification with real cover art, and background playback.
- A now-playing screen with an album-art backdrop, a palette taken from the artwork,
  text colours solved for legible contrast against it, and a wave scrub bar.
- A persistent mini-player, and a queue sheet with tap-to-jump.

### Servers
- Add an OpenSubsonic server (tested against Navidrome), with a connection test. The
  password is stored encrypted with an Android Keystore key.
- Plain `http://` is allowed for LAN and Tailscale setups, with a warning as you type it.
- The server's catalogue syncs into the same browse screens, with its cover art, and its
  tracks stream.
- An optional bitrate cap on mobile data (320, 192 or 128 kbps, or original).
- A network error pauses the stream in place instead of skipping through the queue, and
  playback resumes when a different network comes up.
- A rejected password stops streaming instead of retrying.
- Playlist entries for server tracks re-link when the server's track ids change.

### Lyrics
- Synced or plain lyrics from a sidecar `.lrc` file, the file's own tags, or the server.
- An opt-in online lookup (LRCLIB), off by default, with an on-disk cache.
- Lyrics show in place of the artwork or full screen.

### Scrobbling
- Plays are scrobbled to the server.
- An offline queue that survives restarts and retries with a bounded backoff when the
  server is unreachable.

### Floating pill
- Veldt Wisp's floating now-playing pill, built in. It appears when you leave Veldt while
  music plays, and expands into a card with the artwork, a scrub bar and transport. It needs
  "Display over other apps".
- Floating pill settings: one switch, with Veldt Wisp detected automatically and a link to
  get it.
- Configurable look: anchor, width, wave style and colour, artwork crossfade, hide delay
  and pill buttons.

### Settings
- Light, Dark or Follow-system theme.
- Library: hidden folders.
- Servers, lyrics (online lookup) and streaming quality.
- Bundled third-party licence notices.

### Fixes
- Android 10: shared files are read by path again, so tags, embedded art, embedded lyrics
  and sidecar `.lrc` files work. Libraries scanned before the fix are re-tagged
  automatically.
- On Android 13–14 the pill no longer blocks taps around it.
- Search opens fresh each time, and its clear button always clears.
- The launch window follows the chosen theme, so Light no longer opens on a dark frame.
- The system bars follow the chosen theme.

### Build
- Release builds are minified with R8. Release signing reads an optional, untracked
  `key.properties`; without it, `assembleRelease` produces an unsigned APK.
