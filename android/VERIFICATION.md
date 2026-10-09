# Android 1.0.1 verification · 2026-10-09

## Floating glass navigation

- `assembleDebug`: passed.
- `testDebugUnitTest`: 4 tests passed; no failures/errors.
- `lintDebug`: passed; 0 errors, 22 warnings (including dependency update suggestions and existing storage/context guidance).
- `connectedDebugAndroidTest`: 1 integrated smoke test passed on the Android 17 preview / 16 KB x86-64 emulator.
- The integrated test runs bundled Python/yt-dlp with `--version`, opens Home / Downloads / History / Settings, checks each selected tab, switches to Light, and captures five UI screenshots.
- Visually inspected dark and light Home screenshots. The screen behind the pill is blurred, the labels/icons remain sharp, and the translucent selection lens is visible.
- Target phone: CPH2603, Android 16, ARM64. `adb install -r` succeeded; activity launch succeeded; installed package reports versionName `1.0.1`, versionCode `2`.
- The update preserves existing app data through an in-place APK installation. No uninstall/data-clear command was issued on the phone.

APK: `artifacts/android/YTHub_1.0.1_android.apk` (local development signing key).

SHA-256: `21E0DE24B729D9814F9EC3E32D5A305B75B5CCBF3C9789E6063D2B67E094AC87`.

Screenshots: `artifacts/android/glass-navigation/` — Home dark/light, Downloads, History and Settings. These are captures of the implemented app, not generated mockups.

The checks above validate navigation, theme rendering, native engine invocation, packaging and installation. Full live YouTube MP3/MP4/playlist transfers, background battery restrictions, all folder-provider implementations and extended conversion cancellation remain separate validation work; this report does not claim those passed.

## Android 1.0.2 navigation refinement · 2026-10-09

- CPH2603 in-place update and activity launch succeeded. Installed package reports versionName `1.0.2`, versionCode `3`.
- Debug APK build and 4 unit tests passed.
- Android lint passed with 0 errors and 22 warnings.
- The integrated emulator smoke test passed: native engine invocation, all four tabs, selected-state semantics, held-touch interaction, and dark/light rendering.
- Used Android UiAutomation screenshot capture to avoid the Compose screenshot helper's Android 17 preview redraw timeout.
- Visually inspected the held Home tab and light Home screenshots: rounded neutral selection capsule, consistent line icons, stable labels, and no rectangular pressed-state overlay.
- Screenshots: `artifacts/android/glass-navigation-v102/v102-*.png`.
- APK: `artifacts/android/YTHub_1.0.2_android.apk`.
- SHA-256: `91C9ABBFEA8B25D335C870CA569061334A22FD008BD10AE023E295B2E320543D`.
