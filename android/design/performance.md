# Android motion and scrolling performance

The persistent glass controls sample the shared scenic/artwork layer. They no longer record and blur the scrolling screen or the rotating player disc. Only a change of track or playback status updates the mini-player; position ticks stay in the player. Touch highlights read animation values during drawing, and player drag/settle offsets update graphics layers without recomposing the whole player every frame.

Browsing destinations share one scenic background rather than crossfading identical full-screen images. Navigation uses short translations and fades. Library Back changes the destination immediately, with a short entrance animation instead of waiting for an exit animation. Track, collection and download rows use explicit lazy content types for reuse; song/download placement animations were removed from the scrolling path.

Android's large splash icon is replaced with a transparent drawable. The app has no forced logo reveal or minimum loading duration. A small progress indicator is shown only while the real library is loading; ready launches go directly to the app.

## Phone build

`./android/gradlew -p android :app:assemblePreview` builds an internal APK without debugging enabled or UI inspection tooling. It uses the local debug signing configuration so it can update the existing development install without replacing its data. It does not change public release signing. Output: `android/app/build/outputs/apk/preview/app-preview.apk`.

Install using `adb install -r`, never uninstall or clear storage on a user's phone. Keep debug APKs for development and instrumentation; use Preview or a properly signed Release APK for perceived performance checks. `adb shell cmd package compile -m speed -f dev.ravindran.ythub.mobile` compiles the installed package ahead of time on the local test phone.

## Validation on CPH2603

Tests were run directly through `am instrument`, without Gradle's connected-device cleanup. The 21 unit tests passed. Seven debug device checks passed: loader readiness, glass release/haptic behavior, player pull-down and Back preserving playback, Library entry points, search/light glass, file/playlist access, and scenic browsing destinations. Player gestures/playback preservation and file/playlist access also passed against the installed Preview APK (two additional checks). Saved track IDs and both playlist contents matched the pre-update snapshot: 95 songs; `ravi hits` (87 entries), `tamil hits` (31 entries).

Frame sampling uses `dumpsys gfxinfo ... reset`, eight alternating 350 ms vertical swipes per screen with 250 ms between gestures, then `dumpsys gfxinfo ... framestats`. The same CPH2603 and normal system animation/refresh settings were used. Samples include cold row composition and artwork loads. These are individual device samples, not a claim of universal frame rates or zero jank. The original installed Debug APK and final Preview APK differ in runtime debugging as well as source fixes; the comparison describes the installed experience, not an isolated source-only benchmark.

| Installed build | Home p95 | Songs p95 | Songs missed deadlines |
| --- | ---: | ---: | ---: |
| Original Debug | 24 ms | 40 ms | 23 / 326 (7.06%) |
| Updated Preview before ahead-of-time compilation | 18 ms | 25 ms | 12 / 339 (3.54%) |
| Installed Preview after ahead-of-time compilation | 19 ms | 23 ms | 11 / 336 (3.27%) |

Cold starts after compilation reported 640 ms and 577 ms through `am start -W`. Timed screenshots showed the plain launch surface handing off to Home without the former large static logo. The phone denied screen-recording output paths, so no continuous startup video was captured.

Local raw captures, screenshots and library snapshots remain in ignored `artifacts/performance/`; private library data is not committed.
