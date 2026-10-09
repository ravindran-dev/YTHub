# YTHub for Android

A native Kotlin / Jetpack Compose app with the YTHub red play/download mark, dark and light themes, and local media processing. This project is separate from the Windows Tauri app.

## Build

Requirements: JDK 17 or 21, Android SDK platform 36, and an Internet connection for the first Gradle dependency download. Set `ANDROID_HOME` or create an ignored `local.properties` containing `sdk.dir=C\:/path/to/Android/Sdk`.

```powershell
cd android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The installable development APK is `app/build/outputs/apk/debug/app-debug.apk`. It is signed with the local Android development key. Keep that key to install subsequent updates without uninstalling the app. A store release needs a dedicated private release key and release validation.

```powershell
adb -s YOUR_DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s YOUR_DEVICE_SERIAL shell am start -n dev.ravindran.ythub.mobile/.MainActivity
```

Minimum Android 8; ARM64 phones and x86-64 emulators are included. The app requests no broad storage access. Android 10+ saves into `Downloads/YTHub` through pending MediaStore entries; a selected folder uses a persistent Storage Access Framework grant. Android 8–9 defaults to app-scoped storage. Share a YouTube link from another app to YTHub or paste it on Home.

## Implemented flows

- Onboarding checks the bundled Python, yt-dlp and FFmpeg initialization before enabling Get started.
- Home accepts HTTPS YouTube links, analyzes single media or playlists, and lets you choose MP3/MP4, bitrate/quality, ordering and ZIP export. MP3 defaults to 320 kbps.
- Media details show actual metadata and thumbnails. Playlists show available entries, capped at 500 per analysis; the UI explicitly displays the limit.
- A persistent FIFO queue runs 1–3 playlist/single jobs at once (default 2), with foreground progress notification, pause/cancel/resume, retry, network retries and partial-file continuation. Each playlist processes items sequentially.
- Finished files can be opened with an installed media player, folders through a file manager, and URLs copied. ZIP export streams local files; individual media retention is configurable.
- History includes search, Audio/Video/Playlist filters, details, retry and removal. Removing a history row leaves exported files intact.
- Settings include dark/light/system appearance, default quality, concurrency, auto start, folder selection, ZIP options, notifications, retries, imported Netscape cookies, proxy, engine updates and per-download error logs.
- Storage shows device free space, totals recorded in history, archive counts and temporary-file cleanup. Partial files for paused/interrupted tasks are retained.

## Recovery and practical limits

Queue records use a synced temporary JSON file renamed into place, with writes serialized. Active/queued tasks become Interrupted on process restart and require explicit Resume. Output paths are stored as Android content URIs; partial public exports are deleted on write failure. A task saves exported filenames to avoid exporting the same files again on retry. Progress is supplied by the engine; size is finalized after download. The latest yt-dlp progress line carries live transfer size/speed information.

Android may stop long-running background work or enforce the Android 15+ data-sync foreground-service time budget. The service pauses jobs on timeout. Resume from the app. OEM battery restrictions and network behavior require testing on the target phone. Downloads are not scheduled automatically after reboot. yt-dlp pause stops the native request and continues partial downloads on resume; conversion may restart rather than resume. This initial Android engine uses the upstream process wrapper; inherited FFmpeg child termination needs further stress verification.

Playlist duration/view metadata are shown only when available. Failed playlist retries attempt all entries and reuse local completed media. ZIPs with failed items are partial archives; the task remains Failed and records item counts. The archive filename is based on the playlist title. No iOS binary is included.

## Licensing

The Android application is GPL-3.0 because it links the GPL-3.0 youtubedl-android / FFmpeg wrapper. See `LICENSE` and `THIRD_PARTY_NOTICES.md`. The root Windows application's MIT license is unchanged. Do not describe the Android package as MIT-only.
