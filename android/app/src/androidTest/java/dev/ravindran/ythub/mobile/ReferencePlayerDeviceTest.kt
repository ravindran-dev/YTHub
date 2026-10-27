package dev.ravindran.ythub.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import android.graphics.Bitmap

class ReferencePlayerDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun referenceLayoutAndPlayback() {
        compose.waitUntil(30000) { MusicRepository.ready.value }
        val before = MusicRepository.state.value
        val prefs = DownloadStore.prefs.value
        val oldPlayer = MusicPlayback.state.value
        try {
            compose.runOnUiThread { DownloadStore.savePrefs(prefs.copy(onboardingDone = true, theme = "Dark")) }
            val uri = MusicTestFixtures.audio(compose.activity, "reference-player")
            runBlocking { MusicRepository.importAudio(listOf(uri)) }
            val track = MusicRepository.state.value.tracks.first { it.uri == uri.toString() }
            MusicRepository.update { it.copy(tracks = it.tracks.map { t -> if (t.id == track.id) t.copy(title = "A long track title that wraps gracefully across two lines", artist = "YTHub local audio · An artist with a long name") else t }) }
            compose.runOnUiThread { MusicPlayback.play(listOf(track.id, track.id)) }
            compose.waitUntil(15000) { MusicPlayback.state.value.playing && MusicPlayback.state.value.duration > 0 }
            compose.onNodeWithTag("music-mini-player").performClick()
            compose.onNodeWithTag("primary-playback").assertIsDisplayed().performClick()
            compose.waitUntil(5000) { !MusicPlayback.state.value.playing }
            if (InstrumentationRegistry.getArguments().getString("layoutOnly") == "true") {
                compose.onNodeWithContentDescription("Media volume").performScrollTo().assertIsDisplayed()
                val dockBounds = compose.onNodeWithTag("floating-playback-dock").fetchSemanticsNode().boundsInRoot
                val volumeBounds = compose.onNodeWithContentDescription("Media volume").fetchSemanticsNode().boundsInRoot
                assertTrue("Volume must clear dock", volumeBounds.bottom <= dockBounds.top)
                compose.onNodeWithTag("nav-Home").assertIsDisplayed()
                compose.onNodeWithTag("nav-Search").assertIsDisplayed()
                compose.onNodeWithTag("floating-glass-navigation").assertDoesNotExist()
                val size = InstrumentationRegistry.getArguments().getString("layoutCase") ?: "native"
                val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(compose.activity.cacheDir, "reference-$size.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                return
            }
            compose.onNodeWithContentDescription("Playback position").performSemanticsAction(SemanticsActions.SetProgress) { it(5000f) }
            compose.waitUntil(5000) { kotlin.math.abs(MusicPlayback.state.value.position - 5000) < 500 }
            compose.onNodeWithContentDescription("Toggle favorite").performClick()
            compose.waitUntil(5000) { (track.id in MusicRepository.state.value.favorites) != (track.id in before.favorites) }
            val shuffled = MusicPlayback.state.value.shuffle
            compose.onNodeWithContentDescription("Shuffle ${if (shuffled) "on" else "off"}").performClick()
            compose.waitUntil(5000) { MusicPlayback.state.value.shuffle != shuffled }
            repeat(3) {
                val mode = MusicPlayback.state.value.repeat
                compose.onNodeWithContentDescription("Repeat ${listOf("off", "one", "all")[mode]}").performClick()
                compose.waitUntil(5000) { MusicPlayback.state.value.repeat == (mode + 1) % 3 }
            }
            compose.onNodeWithContentDescription("Add to playlist").performClick()
            compose.onNodeWithText("Create and add").assertExists()
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            compose.onNodeWithContentDescription("Lyrics").performClick()
            compose.onNodeWithText("No local lyrics").assertIsDisplayed()
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            compose.onNodeWithContentDescription("Add to Queue").performClick()
            compose.waitUntil(5000) { MusicPlayback.state.value.queue.size == 3 }
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            compose.onNodeWithContentDescription("Sleep Timer").performClick()
            compose.onNodeWithText("End of current track").performClick()
            compose.waitUntil(5000) { MusicPlayback.state.value.sleepMode == "End of track" }
            compose.runOnUiThread { MusicPlayback.cancelTimer() }
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(compose.activity.cacheDir, "reference-inspect.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            compose.onNodeWithContentDescription("Media volume").assertIsDisplayed()
            val dock = compose.onNodeWithTag("floating-playback-dock").fetchSemanticsNode().boundsInRoot
            val volume = compose.onNodeWithContentDescription("Media volume").fetchSemanticsNode().boundsInRoot
            assertTrue("Volume must remain above dock", volume.bottom <= dock.top)
            val size = InstrumentationRegistry.getArguments().getString("layoutCase") ?: "native"
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(compose.activity.cacheDir, "reference-$size.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            compose.onNodeWithContentDescription("Minimize player").performClick()
            compose.onNodeWithTag("nav-Home").performClick()
            compose.onNodeWithTag("nav-Search").performClick()
            compose.onNodeWithTag("music-search-field").assertIsDisplayed()
            assertEquals(track.id, MusicPlayback.state.value.track?.id)
        } finally {
            compose.runOnUiThread { MusicPlayback.stop(); DownloadStore.savePrefs(prefs) }
            compose.waitUntil(10000) { MusicPlayback.state.value.track == null }
            MusicRepository.update { before }
            if (oldPlayer.queue.isNotEmpty()) compose.runOnUiThread {
                MusicPlayback.play(oldPlayer.queue, oldPlayer.index); MusicPlayback.seek(oldPlayer.position)
                if (!oldPlayer.playing) MusicPlayback.pauseIfConnected()
            }
            File(compose.activity.filesDir, "music/imports/tests/reference-player.wav").delete()
        }
    }
}
