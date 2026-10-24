package dev.ravindran.ythub.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import android.graphics.Bitmap

/** Device regression for the floating dock; test audio and preferences are restored afterward. */
class FloatingDockDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun navigationSearchAndRealPlayback() {
        compose.waitUntil(30000) { MusicRepository.ready.value }
        val oldPrefs = DownloadStore.prefs.value
        val oldMusic = MusicRepository.state.value
        try {
            compose.runOnUiThread { DownloadStore.savePrefs(oldPrefs.copy(onboardingDone = true)) }
            compose.onNodeWithTag("floating-playback-dock").assertIsDisplayed()
            compose.onNodeWithTag("floating-glass-navigation").assertDoesNotExist()
            compose.onNodeWithText("Download from YouTube").assertIsDisplayed()
            capture("home")
            compose.onNodeWithContentDescription("App menu").performClick()
            capture("app-menu")
            compose.onAllNodesWithText("Downloads", useUnmergedTree = true).onLast().performClick()
            compose.onNodeWithTag("floating-playback-dock").assertIsDisplayed()
            capture("downloads")
            compose.onNodeWithTag("nav-Search").performClick()
            compose.onNodeWithTag("music-search-field").assertIsDisplayed().performTextInput("no-match-ythub-dock-test")
            capture("search-keyboard")
            Espresso.closeSoftKeyboard()
            compose.onNodeWithContentDescription("Close music search").performClick()
            compose.onNodeWithTag("nav-Home").performClick()
            val fixture = MusicTestFixtures.audio(compose.activity, "floating-dock-device")
            runBlocking { MusicRepository.importAudio(listOf(fixture)) }
            val track = MusicRepository.state.value.tracks.first { it.uri == fixture.toString() }
            compose.runOnUiThread { MusicPlayback.play(listOf(track.id)) }
            compose.waitUntil(15000) { MusicPlayback.state.value.playing }
            compose.onNodeWithContentDescription("Pause").performClick()
            compose.waitUntil(5000) { !MusicPlayback.state.value.playing }
            compose.onNodeWithContentDescription("Play").performClick()
            compose.waitUntil(5000) { MusicPlayback.state.value.playing }
            compose.onNodeWithTag("music-mini-player").performClick()
            compose.onNodeWithContentDescription("Playback position").assertExists()
            capture("now-playing")
            compose.onNodeWithContentDescription("Phone music").performClick()
            compose.onNodeWithText("Music on this phone").assertIsDisplayed()
            capture("add-to-playlist")
            Espresso.pressBack()
            compose.onNodeWithContentDescription("More options").performClick()
            capture("track-options")
            Espresso.pressBack()
            compose.onNodeWithContentDescription("Minimize player").performScrollTo().performClick()
            compose.onNodeWithTag("nav-Search").performClick()
            assertTrue("Playback must survive navigation", MusicPlayback.state.value.track?.id == track.id)
            Espresso.closeSoftKeyboard()
            compose.onNodeWithContentDescription("Close music search").performClick()
            compose.onNodeWithTag("nav-Home").performClick()
            compose.runOnUiThread { DownloadStore.savePrefs(DownloadStore.prefs.value.copy(theme = "Light")) }
            capture("home-light")
            compose.onNodeWithContentDescription("App menu").performClick()
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithTag("settings-title").assertIsDisplayed()
            capture("settings-light")
            compose.onNodeWithTag("nav-Home").performClick()
            compose.waitUntil(120000) { DownloadStore.engine.value.contains("ready") || DownloadStore.engine.value.contains("unavailable") }
            assertTrue("Media engine: ${DownloadStore.engine.value}", DownloadStore.engine.value.contains("ready"))
            val version = com.yausername.youtubedl_android.YoutubeDL.getInstance().execute(com.yausername.youtubedl_android.YoutubeDLRequest(emptyList<String>()).addOption("--version")).out
            assertTrue("Native media engine must execute", version.trim().isNotBlank())
        } finally {
            compose.runOnUiThread { MusicPlayback.stop(); DownloadStore.savePrefs(oldPrefs) }
            MusicRepository.update { oldMusic }
            File(compose.activity.filesDir, "music/imports/tests/floating-dock-device.wav").delete()
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val target = File(compose.activity.cacheDir, "dock-$name.png")
        target.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
