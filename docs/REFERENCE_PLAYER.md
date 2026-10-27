# Reference Now Playing redesign

The Compose player retains the shared Media3 playback service, repository favorites,
queue, timer, local lyrics, playlists, and Android media-volume integration. No new
playback instance or dependencies are introduced. The bottom dock contains only
Home, the synchronized mini-player capsule, and Search. Other destinations remain
in the existing app menu.

The supplied reference drives the sleeve/disc composition, metadata, crimson
waveform and central playback ring. Artwork height depends on available space and
font size; the page can scroll with large accessibility text. Per the subsequent
request, secondary actions use icons without visible labels, with accessible names
and 48 dp touch targets.

## Liquid Glass guidance adapted for Android

Sources:
- https://developer.apple.com/documentation/technologyoverviews/adopting-liquid-glass
- https://developer.apple.com/design/human-interface-guidelines/materials

Apple describes glass as a functional layer for navigation and controls floating
above content. This implementation confines the glass treatment to the top controls
and dock, preserving the album art and text as the content layer. It reuses the
app's sampled ambient backdrop, translucent charcoal tint, fine reflective rim,
soft shadow, and shared pill press animation. The paired top actions share one
material with a subtle divider. Native Apple Liquid Glass APIs are unavailable on
Android; this is a Compose material adaptation. Existing API-dependent blur and
translucent fallback rendering remain in place.

The waveform is deterministic decorative data, not an audio amplitude analysis;
its elapsed color, seek position, and timestamps come from real playback. Playback
snapshots already update at 500 ms intervals. The mini-player observes only track
and play/pause changes. Audio output exposes Android media controls and Bluetooth
settings; device selection depends on the OS and connected route.

## Validation

- `:app:testDebugUnitTest`: 22 tests passed.
- `:app:assembleDebug` and `:app:assembleDebugAndroidTest`: passed.
- Android 15 emulator: layout regression passed at 320×640, 360×800,
  393×852, and 412×915 dp, including long titles, branded missing-artwork
  fallback, visible playback controls, scrolling to volume, dock clearance, and
  absence of the former navigation component.
- Functional device regression passed: actual pause, seeking, favorites,
  shuffle, all repeat states, playlist picker, lyrics empty state, real queue
  insertion, end-of-track sleep timer, minimize, Home, Search, and track
  continuity through navigation.
- Physical phone testing initially exposed clipping at 115% font size; the
  artwork budget was corrected. The phone disconnected before the final run,
  so final verification used the emulator. External output devices and download
  network flows were not exercised in this UI regression.

## Navigation performance and theme follow-up

Now Playing follows the selected Light, Dark, or System theme; the earlier
player-only dark override has been removed. Glass dock capture records content
once and reuses that layer for the screen and blurred strip. Library sorting runs
on Dispatchers.Default, rapid searches wait 120 ms before filtering, recent
shelves reuse cached results, and player navigation uses a shorter 220 ms motion.
Async list updates capture the items and their occurrence keys in one composition
snapshot to prevent lazy-list key races.

Physical CPH2603 validation passed four device checks: repeated navigation and
scrolling with queue/paused-state continuity, actual light/dark player pixels,
and live dock-glass updates in both themes. The 22 JVM tests and debug APK build
also passed. The scripted scrolling sample recorded 146/240 frames over 16 ms
before and 133/247 afterward; over-32-ms frames were 102/240 and 103/247. These
instrumented debug samples show a modest improvement, not a guarantee of a fixed
frame rate across all operations.

## Scrolling latency follow-up

The static screen atmosphere now has its own retained graphics layer. During a
drag or fling the dock switches to a cached ambient-only crop, dropping references
to moving list RenderNodes; live content is captured again after 120 ms of idle.
Scrolling itself draws directly to the window rather than a full-screen capture
layer. The single idle watcher does not consume or delay nested-scroll input.

The non-debug `preview` APK, signed with the existing install key, was installed
on CPH2603 and Android's package compiler ran in `speed` mode. Eight native swipes
through the same 33-song playlist measured the following with `dumpsys gfxinfo`:

| Metric | Previous debug install | Final non-debug install |
| --- | ---: | ---: |
| Rendered frames | 205 | 219 |
| Median frame time | 24 ms | 21 ms |
| 90th percentile | 32 ms | 25 ms |
| 99th percentile | 53 ms | 29 ms |
| Frame deadline misses | 4 | 0 |
| High-input-latency counter | 403 | 308 |

This compares the complete delivered changes, including runtime build mode and
compilation; it does not isolate each optimization. Legacy jank counters remained
high (75/205 before, 178/219 afterward), so the zero modern deadline-miss count
must not be interpreted as perfect frame pacing or guaranteed 120 fps.

The 22 JVM tests/build checks passed. Final preview light/dark player verification
passed on the physical phone. The two glass pixel regressions passed on the
Android emulator using the same updated renderer in the debug APK. Their test-only
ComponentActivity host is absent from the non-debug APK, so those two tests could
not launch against preview itself.
