package dev.ravindran.ythub.mobile

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test

class ScenicTabsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun scenicBackgroundAcrossBrowsingRoutesAndCompactPlaylistActions() {
        compose.waitUntil(30000) { MusicRepository.ready.value }
        listOf("Home", "Library", "Downloads", "Settings").forEach { destination ->
            compose.onNodeWithTag("nav-Home").performClick()
            if (destination != "Home") {
                compose.onNodeWithContentDescription("App menu").performClick()
                compose.onAllNodesWithText(destination, useUnmergedTree = true).onLast().performClick()
            }
            compose.onNodeWithTag("scenic-screen-background").assertIsDisplayed()
            capture(destination.lowercase())
        }
        compose.onNodeWithTag("nav-Home").performClick()
        compose.onNodeWithTag("home-library").performScrollTo().performClick()
        compose.onNodeWithText("Playlists", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Create playlist").assertIsDisplayed().assertHasClickAction()
        capture("playlist-pills")
        val playlist = MusicRepository.state.value.playlists.first()
        compose.onNodeWithText(playlist.name).performScrollTo().performClick()
        compose.onNodeWithText("Play all").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Shuffle").assertIsDisplayed().assertHasClickAction()
        capture("playlist-actions")
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.cacheDir, "scenic-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
