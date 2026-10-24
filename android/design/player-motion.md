# Player and disc motion

Reference: https://media.invisioncic.com/r156363/monthly_2025_02/ezgif-328d64440bd71.gif.c3f3f8e4b04762cba27c785ed136eca0.gif

The reference was inspected as coalesced frames. The app uses an original Compose-rendered foreground disc and actual local artwork, rather than bundling the reference GIF. Rotation follows the real playing state, retains its angle on pause, stops offscreen, and honors disabled system animations. Track changes animate the outgoing sleeve/disc sideways and bring the next actual artwork into view. No tonearm or decorative playback controls are added.

The startup page overlays an already-mounted Home screen and fades away after library readiness and the logo reveal. It does not replace the entire app composition when initialization completes.

Physical-device checks must preserve user data: back up app-local music state, install with `adb install -r`, and invoke the instrumentation runner directly. Do not use a test workflow that clears or uninstalls the user's app. Test fixtures must never replace a user's library with empty sample data.

All app-owned sheets and confirmation forms use the shared glass overlay shell. Android-owned permission and file-picker windows keep their system appearance. Library search filters a cached text index on the background dispatcher without a fixed typing delay; row-placement animations are disabled for search results.
