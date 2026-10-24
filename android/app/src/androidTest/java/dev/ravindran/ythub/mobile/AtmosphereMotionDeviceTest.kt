package dev.ravindran.ythub.mobile

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Uses existing artwork and queues; never deletes/imports Library content. */
class AtmosphereMotionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun artworkPlayerPullDownAndBackPreservePlayback() {
        compose.waitUntil(30000) { MusicRepository.ready.value }
        val before = MusicRepository.state.value
        val previous = MusicPlayback.state.value
        val track = before.tracks.first { it.available && it.artwork.isNotBlank() && File(it.artwork).exists() }
        val ids = before.tracks.take(2).map { it.id }.let { listOf(track.id) + it.filterNot { id -> id == track.id } }
        try {
            capture("home")
            compose.runOnUiThread { MusicPlayback.play(ids); MusicPlayback.pauseIfConnected() }
            compose.waitUntil(15000) { MusicPlayback.state.value.track?.id == track.id && MusicPlayback.state.value.duration > 0 }
            compose.onNodeWithTag("music-mini-player").performTouchInput { click() }
            compose.onNodeWithContentDescription("Playback position").assertExists()
            capture("artwork-player")
            val position = MusicPlayback.state.value.position
            compose.onNodeWithTag("player-artwork-gesture").performTouchInput {
                swipe(Offset(center.x, height * .2f), Offset(center.x, height * .95f), 420)
            }
            compose.onNodeWithContentDescription("Minimize player").assertDoesNotExist()
            assertEquals(ids, MusicPlayback.state.value.queue)
            assertTrue(kotlin.math.abs(MusicPlayback.state.value.position - position) < 1500)
            compose.onNodeWithTag("music-mini-player").performTouchInput { click() }
            compose.onNodeWithContentDescription("Minimize player").assertIsDisplayed()
            Espresso.pressBack()
            compose.onNodeWithContentDescription("Minimize player").assertDoesNotExist()
            compose.onNodeWithTag("home-library").performScrollTo().performClick()
            capture("library")
            compose.onNodeWithText("Playlists", useUnmergedTree = true).performClick()
            val playlist = before.playlists.first()
            compose.onNodeWithText(playlist.name).performScrollTo().performClick()
            compose.onNodeWithContentDescription("Add phone music to playlist").assertExists()
            capture("playlist")
            Espresso.pressBack()
            compose.onNodeWithContentDescription("Add phone music to playlist").assertDoesNotExist()
            assertEquals(before.tracks.size, MusicRepository.state.value.tracks.size)
            assertEquals(before.playlists, MusicRepository.state.value.playlists)
        } finally {
            compose.runOnUiThread {
                if (previous.queue.isEmpty()) MusicPlayback.stop() else {
                    MusicPlayback.play(previous.queue, previous.index)
                    MusicPlayback.seek(previous.position)
                    if (!previous.playing) MusicPlayback.pauseIfConnected()
                }
            }
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(compose.activity.cacheDir, "atmosphere-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
