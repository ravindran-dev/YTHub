package dev.ravindran.ythub.mobile

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProfessionalPlayerDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun phoneMusicDiscPlaybackAndCleanCollections() {
        compose.waitUntil(30000) { MusicRepository.ready.value }
        val original = MusicPlayback.state.value
        val library = MusicRepository.state.value
        val tracks = library.tracks.filter { it.available && it.artwork.isNotBlank() && File(it.artwork).exists() }.take(2)
        assertEquals(2, tracks.size)
        try {
            compose.onNodeWithContentDescription("Phone music").performClick()
            compose.onNodeWithText("Music on this phone").assertIsDisplayed()
            capture("phone-music")
            Espresso.pressBack()
            compose.runOnUiThread { MusicPlayback.play(tracks.map { it.id }); MusicPlayback.pauseIfConnected() }
            compose.waitUntil(15000) { MusicPlayback.state.value.track?.id == tracks[0].id && MusicPlayback.state.value.duration > 0 }
            compose.onNodeWithTag("music-mini-player").performClick()
            compose.onNodeWithTag("rotating-disc").assertIsDisplayed().assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Paused"))
            compose.onNodeWithTag("primary-playback").assertIsDisplayed().performClick()
            compose.waitUntil(5000) { MusicPlayback.state.value.playing }
            compose.onNodeWithTag("rotating-disc").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Playing"))
            capture("player")
            compose.onNodeWithContentDescription("Next track").performClick()
            compose.waitUntil(5000) { MusicPlayback.state.value.track?.id == tracks[1].id }
            assertEquals(tracks.map { it.id }, MusicPlayback.state.value.queue)
            compose.onNodeWithTag("primary-playback").performClick()
            compose.waitUntil(5000) { !MusicPlayback.state.value.playing }
            capture("next-track")
            compose.onNodeWithContentDescription("Phone music").performClick()
            compose.onNodeWithText("Music on this phone").assertIsDisplayed()
            Espresso.pressBack()
            compose.onNodeWithContentDescription("Minimize player").performClick()
            compose.onNodeWithTag("home-library").performScrollTo().performClick()
            compose.onNodeWithText("Songs").performClick()
            capture("songs")
            Espresso.pressBack()
            compose.onNodeWithText("Albums").performClick()
            capture("albums")
            compose.onNodeWithContentDescription("Go back").performScrollTo().performClick()
            compose.onNodeWithTag("music-library-list").performScrollToIndex(0)
            capture("collections-overview")
            compose.onNodeWithText("Playlists", useUnmergedTree = true).performClick()
            capture("playlists")
            assertEquals(library.tracks.size, MusicRepository.state.value.tracks.size)
            assertEquals(library.playlists, MusicRepository.state.value.playlists)
        } finally {
            compose.runOnUiThread {
                if (original.queue.isEmpty()) MusicPlayback.stop() else {
                    MusicPlayback.play(original.queue, original.index); MusicPlayback.seek(original.position)
                    if (!original.playing) MusicPlayback.pauseIfConnected()
                }
            }
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.cacheDir, "professional-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
