# Music player integration audit — 20 October 2026

This audit applies to the native Kotlin/Compose Android player and the existing download engine. Desktop checks cover the existing React/TypeScript application. There is no React Native or iOS target in this repository. Test media and generated metadata exist only in instrumentation tests.

## Corrections made during the audit

- Replaced repeated queue/playlist linear searches with indexed lookups. Playback snapshots reuse the native timeline's queue rather than rebuilding 1,000 IDs every 500 ms.
- Debounced music search by 200 ms and moved filtering to a worker dispatcher. Memoized derived track lists and playlist summaries. Library and queue rows remain lazy/virtualized.
- Moved music-state loading off the main thread. Playback commands wait for repository readiness; service restoration waits for the same state and cannot save an empty queue over startup data.
- Added an additive v1→v2 saved-state migration, preference validation and corrupt/future-version protection. Unreadable saved data is preserved and an error is shown rather than silently replaced. AtomicFile and the serialized, conflated IO writer remain in use.
- Suppressed unchanged persistence requests. Playback position is checkpointed every five seconds, plus pause/transition/destruction; the 500 ms UI tick does not write to disk. A hard kill can lose the latest five seconds.
- Capped extracted artwork at 64 MiB using oldest-write eviction. Missing artwork falls back to the music glyph; clearing/evicting artwork never removes source audio or metadata.
- Corrected play/pause while buffering to use the engine's play intent. Background playback disabled now pauses the connected controller even during buffering.
- Reserved the measured bottom navigation/mini-player space in the actual content viewport. Previously, a scrolled playlist row could sit beneath the floating bar and its tap could activate the Player tab.
- Excluded generated Android build/report directories from desktop ESLint, after a real lint run incorrectly inspected Gradle's generated JavaScript report.

## Native configuration inspected

`MusicPlaybackService` owns one ExoPlayer and one MediaSession. Screens use a shared MediaController. The manifest declares `mediaPlayback`, its foreground-service permission, and the MediaSessionService intent. ExoPlayer uses media/music audio attributes, Android audio-focus handling, becoming-noisy handling, and a local playback wake lock. The CPH2603 exposed the actual foreground media service with notification ID 1001 and type `0x2`; this was checked with Android system dumps, not inferred from UI controls.

Playback restoration prepares a paused queue. It does not automatically start audio. Favorites, history, playlists, queue IDs/occurrences, preferences and checkpoints share a serialized AtomicFile snapshot. The storage is JSON, not a SQL database; migration tests therefore cover this format.

## Verification and limitations

Raw build/device logs are retained outside the repository on the verification machine. Private device backups and downloaded media are not repository assets.

| Check | Recorded result |
| --- | --- |
| Latest Android debug APK / test APK | Built successfully; installed on CPH2603 and API 35 emulator. |
| Latest Android unit tests | 21 passed. |
| Latest Android lint | 0 errors, 41 warnings. |
| Desktop ESLint / strict TypeScript / Vite | Passed. |
| Desktop Vitest | 32 passed. |
| Unsigned Android release | Built during the audit, before the subsequent phone-music picker addition; release signing is not configured. |
| Latest full player emulator suite | 9 passed, 4 opt-in checks skipped (runner reports 13 tests). Conditional network/physical/restart/offline checks are not counted as verified by skipped tests; separate opt-in runs are listed below. |
| Offline real MP3, emulator | Generated an actual MP3 with FFmpeg; disabled emulator Wi-Fi and mobile data; metadata, decoding, pause/resume and seek passed. Networks restored afterward. |
| Abrupt termination, emulator | Seeded durable state, sent SIGKILL to the actual application process, then started separate instrumentation: queue, repeated references, playlist, favorites, history, speed and position restored paused. Passed. |
| CPH2603 UI | Playlist Play All, mini-player, navigation, quick/full settings and confirmation cancellation passed. |
| Phone music picker, CPH2603 and emulator | Actual MediaStore audio appeared, a second file appeared live, selection added the URI directly to a playlist, existing membership prevented duplicates, and the selected audio played. Passed. CPH2603 music permission was granted by the user through Android's prompt. |
| CPH2603 real download | Initial test URL unavailable; second source returned HTTP 403 with the old runtime. The existing engine updater installed yt-dlp 2026.08.19, after which a real MP3 download, automatic library entry and local playback passed. Failed row had zero output URIs and was absent from the library. A fresh installation may need the existing engine update before YouTube downloads work. |
| CPH2603 Home/background | A real local MP3 continued advancing for the 10-second Home check; native foreground service was present. |
| Secure lock-screen / screen-off acceptance | **Inconclusive.** Automated attempts observed the screen awake; launching the activity also invalidated a lock attempt. The screen-off assertion failed. No secure lock-screen UI/control pass is claimed. |
| Wired headset / Bluetooth / real calls | Not tested; Android focus/noisy configuration inspected. Transient focus loss/regain tested through the real AudioManager on the emulator. |
| iOS | Not run: no iOS target or suitable build environment. |

### Generated-data performance

The runtime test created 1,000 local metadata entries and a real 1,000-item Media3 queue, using one test PCM source. No online bulk download was performed. Queue startup measured 542 ms in the accepted emulator run. Native previous/next, shuffle flag, repeat-one, repeat-all wrap, natural advancement, queue completion, speed and missing-file handling passed.

Both library layouts use lazy rows. The 1,000-entry library test confirmed fewer than 50 composed track nodes, debounce/search results, and actual scrolling. A direct indexed jump to the final row measured 339 ms on CPH2603 and 355 ms on the emulator. The earlier test helper's sequential traversal took 30.7 seconds on the phone; that result prompted separating test traversal overhead from direct layout timing rather than claiming it measured one UI operation.

A short three-fling sample recorded 9 physical-device frames, 6 over 32 ms; the software-rendered emulator recorded 8/8 over 32 ms. **Smooth sustained scrolling is therefore not certified.** The sample is small and instrumentation affects timing; release-mode profiling and glass/scroll rendering optimization remain performance acceptance work. Search/virtualization and bounded disk artwork are verified, not a blanket frame-rate claim.

### Failed checks and practical limits

The original overlap test failed because a playlist row was behind navigation; the viewport correction and device rerun passed. Background-only test starts were denied audio focus by Android 15; transport tests now launch a real visible activity and clean up focus requests. Search assertions were corrected to distinguish editable query text from song rows. Corrupt/future format rejection, v1 migration/defaults, preference clamping and artwork eviction pass unit tests. Valid ZIP ordering and rollback after a valid first entry plus a malicious path pass actual repository tests.

The latest implementation is usable as a development build. Store readiness remains blocked by release signing/distribution configuration, the lock-screen and route/interruption acceptance gaps, the measured scrolling concern, and the runtime engine-update dependency described above.

### Settings

| Setting | Runtime behavior / limitation |
| --- | --- |
| Playback speed | Calls the actual engine's `setPlaybackSpeed`; test measures decoded position advancement. |
| Skip intervals | Seek actions use persisted forward/backward seconds. |
| Auto next | Pauses on native automatic transitions when disabled. |
| Equalizer / bass boost | Android AudioEffect objects use the actual audio-session ID. Available bands, ranges and presets are queried; unavailable effects are disabled/reported. Audible correctness on each physical route remains a manual check. |
| Sleep timer | Real elapsed-time deadline and native end transitions; integration test forces a short expiry. Timer does not survive process death. |
| Background playback | Native media service plus audio focus/wake configuration; turning it off pauses on activity stop/task removal. OEM policy still applies. |
| Auto-add / auto-play download | Completion hooks inspect completed audio records; auto-play requests real controller playback. Android can restrict background-started playback. |
| Restore last track / position | Restores stable queue references and selected position, paused. |
| Crossfade / loudness normalization / spatial effects | Unsupported, explained in settings; no active fake controls. |
| Gapless | Media3 decoder/file-metadata dependent; explanatory text, no misleading override switch. Seamless acoustic transitions are not claimed. |

### Remaining physical acceptance coverage

Bluetooth route changes, wired-headset unplug, real calls/alarms, audible equalizer/bass behavior, revoked SAF permissions/removable storage, overnight OEM suspension, battery optimization, and notification-permission denial need suitable physical conditions. Audio-focus tests are not substitutes for actual telephone/Bluetooth tests. No iOS build or iOS background behavior is claimed on this Linux/Android repository.

The installed APK is development signed. A store release needs a release signing configuration, versioning/release process, and the remaining device acceptance coverage. A successful unsigned release build does not establish store readiness.
