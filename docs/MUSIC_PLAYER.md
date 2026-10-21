# Android local music player

The existing Android app is Kotlin / Jetpack Compose. This feature extends that app; it does not introduce a second mobile framework or change the desktop TypeScript application. The existing GPL-3.0 Android license continues to apply.

## Implementation

- `MusicPlaybackService.kt`: one service-owned Media3 ExoPlayer and MediaSession, audio focus, noisy-route pausing, media notification/system controls, controller connection, queue commands, sleep deadlines, and session-scoped Android equalizer/bass effects. Screens do not own or recreate the engine.
- `MusicModels.kt`: typed durable state, URI-derived track IDs, playlists, preferences, history, restoration rules, file classification, LRC parsing, and archive path validation.
- `MusicRepository.kt`: atomic JSON persistence with validated v1→v2 migration, serialized/conflated writes, download reconciliation, metadata/artwork extraction, SAF imports, associated lyrics attachment, playlist CRUD, favorites, history and bounded archive extraction.
- `EmbeddedLyrics.kt`: bounded ID3v2.3/v2.4 USLT text extraction. Unsupported/encrypted/compressed/unsynchronised tags return no embedded lyrics; a local LRC/text file can be attached instead.
- `MusicStorage.kt`: accessible exported audio/video/archive sizes, private imports, artwork and temporary bytes. External imported source files are excluded from app-owned storage totals.
- `PlayerControls.kt`: rounded animated player glyphs, Android media-volume slider synchronized with hardware volume, and a shortcut to the system audio panel.
- `MusicScreens.kt`, `NowPlayingScreen.kt`, `PlayerSettingsScreen.kt`: searchable/filterable/sortable library, album/artist/folder collections, playlists, multi-selection, track actions, persistent mini-player, real seek/progress, queue editing/drag handles, lyrics, timer and capability-aware settings.

Media3 ExoPlayer and Session **1.8.0** are the only added dependencies. Background service configuration follows [Android's MediaSessionService guidance](https://developer.android.com/media/media3/session/background-playback).

## Download integration

`DownloadStore` remains the source of download state and output URIs. Only successfully completed audio jobs are automatically reconciled; archives and videos are not classified as songs. Cached track metadata references each download ID and Android content URI. A repeated scan updates metadata without duplicating IDs or resetting date-added/attached lyrics.

Download details and completion notifications offer Play Now. Explicit playback verifies each URI, scans metadata and can reintroduce a deliberately hidden library entry. An automatic download playlist has a stable download-derived ID and retains exported-file order. User edits to an existing playlist are preserved during scans. Auto-add, download autoplay, playlist creation and playlist notifications are preferences. Normal download notification preferences also apply.

ZIP import is explicit. It extracts into a new private directory, refuses traversal, absolute/Windows paths and duplicate destination files, limits entries and expanded bytes, checks available disk space and rolls back the whole import on failure. It never extracts over an existing destination. Audio and matching LRC/text files are imported in relative-path order. Maximums: 2,000 entries, 512 MiB per retained file and 2 GiB total expansion, including ignored entries. A ZIP containing only videos is not playable.

## Playback and persistence

- The mini-player remains above the four-tab glass navigation (Home, Downloads, Library, Settings). It cannot be swiped away; minimizing the full player continues audio. Compose's system animation duration scale applies to navigation, including zero-duration/reduced-motion settings.
- ExoPlayer manages next/previous, repeat modes, shuffle and supported gapless decoding. Upcoming items can be appended, inserted next, removed, shuffled, reordered and cleared without replacing the current item. Enqueue actions intentionally allow repeated tracks; rapid repeated enqueue taps are suppressed for 500 ms; later explicit actions can intentionally repeat a track.
- Queue, current index, repeat/shuffle and position are saved approximately every five seconds and on pause/transition/destruction. Restoration filters known unavailable references, preserves the selected occurrence where possible, and prepares paused audio. “Restore last track” does not unexpectedly start sound at launch.
- History counts a listen after five seconds of actual playback. History/favorites/playlist/preferences updates are durable. Clearing history and removing a library entry do not delete source files. File deletion requires explicit confirmation and provider permission.
- Duration timers use elapsed real time, independent of screen lifecycle and wall-clock changes. End-of-track/end-of-playlist modes respond to real engine transitions, including repeats. The playback wake lock keeps local playback running during device sleep. Timers survive navigation/backgrounding, but do not survive process termination.
- LRC timestamps follow actual player position. Auto-scroll can be disabled, manually scrolled, and returned to the current line. No network lyrics source is used.
- Effects use the active engine's audio session. Bands, gain range, presets and bass support are queried from the platform. Unsupported controls are disabled. Changes configure actual Android effects.

## Platform limits and device checks

There is no iOS project in this repository, so no iOS playback/background configuration is claimed. Media3 relies on Android's installed decoders for formats such as FLAC/OGG/OPUS; support varies by device and file encoding.

Crossfade, virtualizer/spatial processing, ReplayGain/loudness normalization, user-selectable interruption policy, sleep fade-out, and in-app identification of the active output route are not implemented. Settings explain the unavailable effects instead of presenting working-looking controls. Android handles audio focus/ducking and media output selection. Gapless playback depends on the decoder and media metadata.

Folder grouping uses exposed SAF document parent paths, the selected download folder and extracted playlist folders, falling back to an import collection; the app cannot infer arbitrary parent folders from every SAF provider. Local sidecar files can be attached explicitly; matching sidecars in extracted ZIPs are attached automatically. Embedded lyrics extraction covers ID3 USLT, not every container/tag format.

Physical-device acceptance still needs: Bluetooth/headset commands and unplug events, calls/alarms/audio-focus interruptions, notification permission denial, locked-screen playback, OEM battery policies, long-duration sleep timers while locked, actual equalizer/bass audibility per output route, removable/provider-revoked files, large playlist downloads and archive imports, and process-death restoration. Android may restrict autoplay initiated by a background download; a visible Play Now action remains available.

## Verification commands

```sh
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
# Run against an authorized emulator/device:
./gradlew :app:connectedDebugAndroidTest
```

The test suite includes queue ordering/restoration/shuffle/repeat/previous rules, persistence round trips, duplicate detection, audio classification, elapsed timer boundaries, LRC/embedded lyric parsing and archive path validation. Android instrumentation exercises actual PCM file metadata, repository reconciliation from download records, playlist CRUD, favorites/history, persisted settings, missing files, non-destructive removal, real Media3 playback/seeking/queue edits and sleep expiry, plus archive traversal rollback. Test PCM/download records are created only by tests; no sample tracks or fake downloads ship in the app.

## Initial implementation results — 20 October 2026

These are the initial results. See [the final integration audit](MUSIC_PLAYER_AUDIT.md) for subsequent fixes, expanded tests, and CPH2603 verification.

Verified on an Android 15 / API 35 x86-64 emulator, with JDK 17 and compile SDK 36:

| Check | Result |
| --- | --- |
| Android debug APK | Built successfully; `android/app/build/outputs/apk/debug/app-debug.apk` |
| Android unit tests | 17 passed: 13 music rules/persistence/lyrics cases and 4 existing model cases |
| Android lint | Passed, 0 errors and 38 warnings; dependency-update suggestions and platform/style warnings remain |
| Android instrumentation APK | Built successfully |
| Installed emulator instrumentation | All 4 tests passed, including actual local PCM playback and native Python/yt-dlp initialization |
| Desktop ESLint | Passed |
| Desktop strict TypeScript + Vite | Passed |
| Desktop Vitest | 32 tests passed |
| Git whitespace check | Passed |

The installed tests exercised the library/player/queue navigation, download-state reconciliation, verified local metadata, duplicate scans, playlist edits, favorites/history, settings written to disk, missing files, actual seeking and next-track commands, queue reordering without resetting position, elapsed sleep expiry and malicious ZIP rejection. Generated PCM files and synthetic download fixtures are confined to `androidTest`; they are not application assets or runtime seed data.

This is a development-signed APK, not a release/store build. No physical-phone, iOS, Bluetooth, audible-effect, or live YouTube playlist-download verification is claimed.

Existing integration files changed: `android/app/build.gradle.kts`, `AndroidManifest.xml`, `res/xml/file_paths.xml`, `MainActivity.kt`, `GlassNavigationBar.kt`, `DownloadStore.kt`, `DownloadService.kt`, `android/README.md`, and the Android smoke test. The Linux Gradle wrapper is now executable. New feature files are described above; additional tests are `MusicRulesTest.kt`, `MusicIntegrationTest.kt`, and `MusicTestFixtures.kt`.

## Music already on the phone

Open Library → **Phone music**, or open a playlist and tap **+** beside its name. Allow Android's music/audio permission once, search the phone's indexed audio, select songs, and add them directly. The picker refreshes when Android indexes new audio and marks songs already in the destination playlist. It stores playable MediaStore references and reads metadata; it does not copy or delete the source audio. **Choose a file instead** retains the existing document-picker route for files not indexed by Android. The audio permission is requested only when opening the phone-music picker (READ_MEDIA_AUDIO on Android 13+, READ_EXTERNAL_STORAGE on older supported releases).
