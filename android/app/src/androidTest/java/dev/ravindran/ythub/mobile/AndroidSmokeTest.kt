package dev.ravindran.ythub.mobile

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AndroidSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun nativeEngineAndMainNavigation() {
        compose.waitUntil(120_000) { DownloadStore.engine.value.contains("ready") || DownloadStore.engine.value.contains("unavailable") }
        assertTrue(DownloadStore.engine.value, DownloadStore.engine.value.contains("ready"))
        val version = YoutubeDL.getInstance().execute(YoutubeDLRequest(emptyList<String>()).addOption("--version")).out
        assertTrue("Native Python / yt-dlp did not run", version.trim().isNotEmpty())
        compose.runOnUiThread { DownloadStore.savePrefs(DownloadStore.prefs.value.copy(onboardingDone = true)) }
        compose.onNodeWithText("Download from YouTube").assertIsDisplayed()
        compose.onNodeWithTag("floating-glass-navigation").assertIsDisplayed()
        compose.onNodeWithTag("nav-Home").assertIsSelected()
        capture("home-dark")
        compose.onNodeWithTag("nav-Home").performTouchInput { down(center) }
        capture("home-pressed")
        compose.onNodeWithTag("nav-Home").performTouchInput { up() }
        compose.onNodeWithTag("nav-Downloads").performClick()
        compose.onNodeWithTag("nav-Downloads").assertIsSelected()
        compose.onNodeWithText("Your queue is clear").assertIsDisplayed()
        capture("downloads-dark")
        compose.onNodeWithTag("nav-Library").performClick()
        compose.onNodeWithTag("nav-Library").assertIsSelected()
        compose.onNodeWithText("Your music").assertIsDisplayed()
        capture("library-dark")
        val oldMusic = MusicRepository.state.value
        val fixture = MusicTestFixtures.audio(compose.activity, "player-ui")
        kotlinx.coroutines.runBlocking { MusicRepository.importAudio(listOf(fixture)) }
        val track = MusicRepository.state.value.tracks.first { it.uri == fixture.toString() }
        compose.runOnUiThread { MusicPlayback.play(listOf(track.id)) }
        compose.waitUntil(15000) { MusicPlayback.state.value.playing }
        compose.onNodeWithTag("music-mini-player").performClick()
        compose.onNodeWithContentDescription("Playback position").assertExists()
        compose.onNodeWithContentDescription("Pause").assertExists()
        capture("player-local-audio")
        compose.onNodeWithText("Queue", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Player queue").assertExists()
        capture("player-queue")
        androidx.test.espresso.Espresso.pressBack()
        compose.runOnUiThread { MusicPlayback.stop() }
        compose.waitUntil(15000) { MusicPlayback.state.value.track == null }
        MusicRepository.update { oldMusic }
        java.io.File(compose.activity.filesDir, "music/imports/tests/player-ui.wav").delete()

        compose.onNodeWithTag("nav-Settings").performClick()
        compose.onNodeWithTag("nav-Settings").assertIsSelected()
        compose.onNodeWithTag("settings-title").assertIsDisplayed()
        capture("settings-dark")
        compose.runOnUiThread { DownloadStore.savePrefs(DownloadStore.prefs.value.copy(theme = "Light")) }
        compose.onNodeWithTag("nav-Home").performClick()
        compose.onNodeWithText("Download from YouTube").assertIsDisplayed()
        compose.onNodeWithTag("floating-glass-navigation").assertIsDisplayed()
        capture("home-light")
        compose.runOnUiThread { DownloadStore.savePrefs(DownloadStore.prefs.value.copy(theme = "Dark")) }
    }
    private fun capture(name: String) {
        if (Build.VERSION.SDK_INT < 29) return
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(150) // Allow the native screenshot surface to present the settled Compose frame.
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) { "Android did not provide a screenshot." }
        val resolver = compose.activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "v102-$name.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/YTHub-Design")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }
}
