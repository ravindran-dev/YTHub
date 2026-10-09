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
        compose.onNodeWithText("Make it yours.").assertIsDisplayed()
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
        compose.onNodeWithTag("nav-History").performClick()
        compose.onNodeWithTag("nav-History").assertIsSelected()
        compose.onNodeWithText("Your library").assertIsDisplayed()
        capture("history-dark")
        compose.onNodeWithTag("nav-Settings").performClick()
        compose.onNodeWithTag("nav-Settings").assertIsSelected()
        compose.onNodeWithText("Make it work for you.").assertIsDisplayed()
        capture("settings-dark")
        compose.runOnUiThread { DownloadStore.savePrefs(DownloadStore.prefs.value.copy(theme = "Light")) }
        compose.onNodeWithTag("nav-Home").performClick()
        compose.onNodeWithText("Make it yours.").assertIsDisplayed()
        compose.onNodeWithTag("floating-glass-navigation").assertIsDisplayed()
        capture("home-light")
        compose.runOnUiThread { DownloadStore.savePrefs(DownloadStore.prefs.value.copy(theme = "Dark")) }
    }
    private fun capture(name: String) {
        if (Build.VERSION.SDK_INT < 29) return
        compose.waitForIdle()
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
