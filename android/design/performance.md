# Android motion and scrolling performance

Top contextual glass samples a shared scenic/artwork layer that is blurred once at half resolution in each dimension. Moving panels sample this prepared image without running their own blur filters. The bottom dock uses a dedicated underlay: only the narrow strip beneath it is recorded at half resolution in each dimension, blurred through one shared graphics layer, then sampled by Home, the mini-player and Search. This reveals actual scrolling rows and artwork instead of the almost-black bottom of the faded scenic background. The dock is outside the captured subtree, so it cannot capture itself. No full-screen UI texture is made for the dock. The capture is disabled while the keyboard hides the dock. Surface coordinates are read during drawing, avoiding whole-control recompositions as content moves. Only a change of track or playback status updates the mini-player; position ticks stay in the player. Touch highlights read animation values during drawing, and player drag/settle offsets update graphics layers without recomposing the whole player every frame.

Browsing destinations share one scenic background rather than crossfading identical full-screen images. Navigation uses short translations and fades. Library Back changes the destination immediately, with a short entrance animation instead of waiting for an exit animation. Track, collection and download rows use explicit lazy content types for reuse; song/download placement animations were removed from the scrolling path.

Android's large splash icon is replaced with a transparent drawable. The app has no forced logo reveal or minimum loading duration. A small progress indicator is shown only while the real library is loading; ready launches go directly to the app.

## Glass finish

The top Add/More control is one continuous 102 × 48 dp glass capsule, with two 48 dp touch targets and one shared press/haptic response. The icons have no individual circular surfaces, borders or shadows. The capsule and contextual Back button sample the ambient image with a restrained 1.035× lens effect. The dock uses the live cropped underlay described above, with lighter tint so real content shows through. A brighter reflective rim and reduced tint opacity make the frost visible in both themes. The contextual controls retain the scene-only blur path. The dock samples only its cropped strip of real content. The refinement passed 21 unit tests and five device checks, including both Add entry points, contextual menus, single haptic/release behavior, light-theme panels/search, playback/next-track and Library navigation. Track IDs and both playlist contents matched the pre-update snapshot.

The live dock change passed 21 unit tests and six device checks: two rendered-pixel tests prove that changing the content beneath the dock changes its visible material in dark and light themes; four existing checks cover search/light panels, real playback/next-track, Library navigation, and player pull-down/Back. Saved song IDs and both playlists were preserved.

## Liquid popups

Menus, sheets and confirmation dialogs share `LiquidGlassPanel`. Its lighter translucent material uses a 1.065× ambient-image lens, reflective edges and an angled sheen. A passive gesture observer moves a local soft reflection toward the finger and applies a small spring deformation: up to 0.8% in width, 1.8% in height, 1.5 dp vertically and 0.09° tilt. Animation values are read in graphics/drawing phases. The observer does not consume touch events or produce extra haptics; each action keeps its own release and scroll cancellation behavior.

Menus bloom from their anchor; confirmation panels have a restrained spring reveal; sheets retain their native drag/dismiss physics. Compose animation primitives respect the system duration scale. Native dialog background blur is reduced to 16 px on supported Android versions; there is no new continuous full-screen capture. Phone-music rows and track-action groups use translucent grouping rather than opaque cards.

The panel gesture and app tests passed for the new popup material. The new interaction regression checks visible pressure feedback, one haptic, activation only on release, and scrolling without accidental action activation. Existing app tests cover menu destinations, phone-music sheets, local search and light theme, confirmation dialogs (cancelled), real playback/next-track and collections. The two app tests touching the final row refinements were rerun after that change. Saved track IDs and playlist contents were unchanged.

App-window menus now use `GlassPopoverHost`: one overlay in the existing window, anchored to the requesting control and clamped to safe drawing insets. It consumes outside touches, handles Android Back before page navigation, moves focus to the panel and hides the underlying screen from accessibility while open. Menus launched inside a native sheet/dialog retain a native popup window. This avoids repeated Android-window creation for normal app menus.

Eight open/close cycles on compiled Preview builds measured p95 frame time of 81 ms with native popup windows, 36 ms with the shared host, and 21 ms after sharing the half-resolution ambient blur. These are individual CPH2603 samples, not universal frame-rate guarantees. Raw frame captures are saved under ignored `artifacts/performance/`.

The final white material uses white tint and a bright rim with a faint gray edge for contrast. Dock circles are 50 dp, the mini-player is at least 56 dp high, and its artwork is 32 dp. The functional dock still contains Home, mini-player and Search. The final combined validation passed 22 unit tests and seven device checks, including the actual engine-freshness/network path, both dock pixel tests, liquid gesture feedback, modal menu dismissal, light-theme search/panels and playback/collections. The selected failed audio job was also retried successfully in a separate live test. Original song IDs and playlist contents were preserved; the completed download added one song (96 total).

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
| Refined glass capsule Preview after ahead-of-time compilation | 18 ms | 23 ms | 9 / 339 (2.65%) |
| Live cropped dock Preview after ahead-of-time compilation | 15 ms | 23 ms | 6 / 337 (1.78%) |

Cold starts after compilation reported 640 ms and 577 ms through `am start -W`. Timed screenshots showed the plain launch surface handing off to Home without the former large static logo. The phone denied screen-recording output paths, so no continuous startup video was captured.

Local raw captures, screenshots and library snapshots remain in ignored `artifacts/performance/`; private library data is not committed.

Current UI files changed: `GlassNavigationBar.kt`, `GlassOverlays.kt`, `LiquidGlassPanel.kt`, `GlassPopoverHost.kt`, `ScreenAtmosphere.kt`, `MainActivity.kt`, `DeviceMusicPicker.kt`, and `MusicScreens.kt`. New interaction regressions are in `LiquidPopupDeviceTest.kt` and `PopupHostDeviceTest.kt`. This note records the device measurements and existing UI regressions used for validation.
