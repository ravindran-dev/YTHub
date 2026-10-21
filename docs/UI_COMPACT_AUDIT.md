# Compact mobile UI and reference player audit

Date: 2026-10-21. Native Android Jetpack Compose; existing Media3 service and downloader retained.

## Implemented

- Four primary destinations: Home, Downloads, Library, Settings. The full player opens from playback or the mini-player, with origin-aware Back/minimize behavior.
- Bottom labels measured at the actual font scale. Selected state animates the icon instead of enlarging text. Slots adapt to label widths; unusually large text can wrap. Safe-area insets and measured bottom content prevent overlap with the mini-player, keyboard and system navigation.
- Shared charcoal/crimson surfaces, compact typography, 16dp screen spacing, smaller cards/artwork rows and contextual secondary actions. Long track/download/playlist names have deliberate wrapping or ellipsis; full metadata remains accessible.
- Compact Home URL entry and analysis, expandable playlist options, download status/actions and details, library/playlists, queue, settings, onboarding and import sheets.
- Reference-style full player: rounded artwork card, title/artist and favorite, translucent seek bar, elapsed/remaining time, five transport controls, volume below. YTHub dark/red branding retained.
- Seek and volume use one centered translucent capsule without a circular thumb. Both expand from 4dp to 8dp during interaction, animate opacity/progress and keep a stable 48dp accessible touch target. Both support native Slider gestures and semantics; seeking remains tied to Media3.
- Rounded Material icons use brief crossfades, spring press feedback and animated state tint. Accessible touch targets remain stable; Compose respects system animation-duration settings.
- Real Android media-volume slider tracks hardware changes while the screen is active. Audio output opens Android's system volume/audio panel; it does not claim to enumerate available devices. Queue, lyrics, timer, quick settings, favorites and track actions remain connected to existing behavior.
- No new online download backend or playback engine was introduced for this UI change.

## Validation method

`CompactLayoutTest` launches the installed production UI with a generated local audio file, long metadata, a test-only active-download fixture and a 1,000-entry media preview. It checks every label in all four selected states using actual text-layout results, label bounds, keyboard entry, playlist playback, player controls, real system volume, queue, timer, tab state and mini-player/nav separation. Generated downloads are UI fixtures, not network-download verification. Tests restore saved music/download preferences and the original system volume.

The reference player before the final translucent-slider change passed ten emulator configurations: widths 320/360/393/412dp, 160% fonts at 320dp, short 360dp, landscape 640dp, large 600dp, and portrait/landscape three-button navigation. Physical CPH2603 passed the compact layout, Phone music picker and two UI/library tests. An intermediate repeat failed because a navigation node was absent. A fresh-launch physical rerun after the action-sheet changes passed all four compact-layout, UI/library and Phone music tests.

The final slider-specific physical results are recorded separately below. Existing detailed playback/background limitations remain in [MUSIC_PLAYER_AUDIT.md](MUSIC_PLAYER_AUDIT.md).

## Production limitations

- Short/landscape screens and large text scroll vertically to preserve controls and touch targets.
- No claim of sustained 60fps: physical 1,000-entry library testing reached the last row in 261ms, but the brief fling sample had 6/8 frames over 32ms. This is a small instrumentation sample, not a sustained frame-time benchmark.
- Android release APK requires production signing. This repository/environment has no suitable iOS build target.
- Crossfade and unsupported effects remain explained/disabled. This UI pass does not establish physical lock-screen, Bluetooth, headphone or interruption coverage; consult the music audit for exact earlier results.

## Transparent slider follow-up

Installed and tested on physical CPH2603. `PlayerSliderTest` passed: actual local Media3 playback paused, sought to 5s, dragged forward to approximately 13s, dragged backward to approximately 4s, and resumed with advancing position. The volume control changed Android's media volume and restored its original value. Screenshots confirmed both translucent tracks are centered and the seek capsule expands during drag then contracts after release. The initial interaction sample recorded 48 frames with 6 over 32ms; this does not establish sustained frame-rate performance. Android debug build, unit tests and lint passed (warnings remain).

## Player action sheets

The oversized all-red text list is replaced by compact icon rows and grouped surfaces. The currently playing track omits redundant Play now. Track metadata has its own readable page. File location, non-destructive library removal and confirmed file deletion live under Manage file. Playlist selection uses icon/count rows and a compact empty state; the sleep timer uses duration buttons and grouped end-of-track/playlist actions. Normal actions use foreground text, while destructive file deletion stays crimson. Sheet pages crossfade and retain native press feedback, scrolling, Back behavior and accessible touch targets.

The physical action-sheet test passed track-information navigation, file-management navigation, delete confirmation/cancellation with the source file preserved, real playlist creation/membership, and an actual 30-minute playback timer. Related sheets open fully expanded so controls are not initially concealed by a partial sheet. The physical regression suite also passed compact navigation/keyboard/player checks, both UI/library tests and Phone music import (four tests). Android unit tests: 21 passed. Lint: zero errors, 30 existing warnings.

Final delivery: rebuilt after the expanded-sheet adjustment and installed in place on CPH2603. The combined slider/action-sheet and compact-layout tests passed again (two tests, 39.992s). The sleep-timer screenshot shows the custom-timer action above the system gesture area. Final Android build/unit/lint checks passed; no source file was deleted by verification.

## Motion refinement

- Shared finite timing: 140ms feedback, 220ms content transitions, 260ms navigation. Compose applies Android's animation-duration scale.
- Play/pause and favorite glyphs have press compression, a brief state-change enlargement and spring settling. The mini-player uses the same animated transport controls.
- Song artwork fades/scales gently; titles crossfade; mini-player artwork and progress animate independently of the service.
- Tabs retain the moving glass lens and active icon enlargement. Content moves in tab order; opening/minimizing the player uses a short vertical transition. Navigation labels never scale.
- Downloads ease toward actual byte progress; processing/export/ZIP phases use indeterminate progress, and stage icons transition into completion/error/paused states. No download behavior or backend was changed.
- Settings sections expand/collapse with fading content and rotating disclosure icons. Sheet action icons compress on press.
- Library/queue/download row placement animates on changes, with stable occurrence keys for repeated songs. There is no per-row entrance cascade during large-list scrolling.
- The navigation lens reuses the base glass blur instead of rendering the entire backdrop through a second blur effect.

The first physical motion run passed repeated transport actions, next/previous identity, eight tab changes with continuing audio, slider/action-sheet checks and 1,000-entry library/UI checks (five tests). A short mixed-interaction frame sample had 249 frames, 17 over 32ms and p95 69ms before removing the duplicate blur pass. These instrumentation samples include interaction/layout work and are not sustained frame-rate benchmarks. Final optimized results follow below.

## Consistent sizing and real glass follow-up

The narrow vertical track count came from a full-width choice row competing with weighted count text. Playlists now omit the redundant layout selector (their numbered view is compact), while the general library uses a bounded 96dp pair of accessible 48dp Grid/Compact view buttons. Track counts are tested as a single untruncated line. Shared choice fields have explicit width behavior, 12dp corners, consistent padding and bounded labels. Playlist metadata uses the secondary text style; artwork/list cards and primary playlist actions follow the shared spacing/radius scale.

The Library navigation glyph is now a rounded music-card stack. Navigation and the mini-player use live recorded content, native Android 12+ blur and translucent dark/light tint. Content extends underneath these surfaces; scroll containers reserve the actual measured bottom inset, so the last rows remain reachable above the controls. Full player, keyboard, media preview and empty states retain appropriate bottom padding. This is actual backdrop compositing; older Android versions retain translucency without RenderEffect blur.

Final CPH2603 run: six tests passed in 68.865s (motion transport/song/tab interactions, real seek/volume and action sheets, compact layout/count/view-toggle/keyboard checks, both library/UI tests including 1,000 entries, and Phone music picker). Build, 21 Android unit tests and lint passed; lint has zero errors and 30 warnings. The latest mixed-interaction sample had 270 frames, 18 over 32ms and p95 44ms. Occasional slow frames remain; no sustained 60fps guarantee is claimed. Responsive matrix results are recorded after completion below.

## Library search refinement

Search now uses a compact filled field with no floating label, clear/close actions and keyboard Search support. Search mode replaces the import/filter/category controls with a result count and working Play results action. Results use compact rows; long press enables selection. Closing search restores the normal library controls.

Latest validation: debug APK, instrumentation APK, unsigned release APK, 21 unit tests and lint completed successfully (zero lint errors, 31 warnings). The updated build was installed on physical CPH2603; all six device tests passed in 69.124s, including search keyboard/filter/clear/close, playback, sliders, menus, navigation and Phone music. Six responsive configurations had passed at publication: 320dp with 160% fonts, 640dp landscape with gesture and three-button navigation, 320dp normal fonts, 360dp with 115% fonts and 393dp normal fonts. Remaining configurations were still running when the user requested publication; they are not claimed as verified for this latest refinement.
