package dev.ravindran.ythub.mobile

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GlassSearchDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun lightGlassPanelsAndResponsiveLocalSearch() {
        compose.waitUntil(30000) { MusicRepository.ready.value }
        val oldTheme = DownloadStore.prefs.value.theme
        try {
            compose.runOnUiThread {
                compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                DownloadStore.savePrefs(DownloadStore.prefs.value.copy(theme = "Light"))
            }
            compose.onNodeWithContentDescription("App menu").performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(160)
            capture("light-controls-held")
            compose.onNodeWithContentDescription("App menu").performTouchInput { up() }
            compose.onNodeWithTag("glass-popover").assertIsDisplayed()
            capture("light-menu")
            Espresso.pressBack()
            compose.onNodeWithContentDescription("Phone music").performClick()
            compose.onNodeWithText("Music on this phone").assertIsDisplayed()
            capture("light-phone-panel")
            Espresso.pressBack()
            compose.onNodeWithTag("nav-Search").performClick()
            compose.onNodeWithTag("music-search-field").performClick()
            // Measure the app's edit/filter/render path, excluding repeated IME focus/replacement setup.
            compose.mainClock.autoAdvance = false
            val start = SystemClock.elapsedRealtime()
            listOf("7", "Salambala", "7 Years").forEach { query ->
                compose.onNodeWithTag("music-search-field").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(query)) }
                compose.mainClock.advanceTimeByFrame()
            }
            compose.mainClock.autoAdvance = true
            val result = hasText("7 Years") and !hasSetTextAction() and hasAnyAncestor(hasTestTag("music-library-list"))
            compose.onNode(result).assertIsDisplayed()
            val elapsed = SystemClock.elapsedRealtime() - start
            assertTrue("Three successive queries should settle promptly; measured $elapsed ms", elapsed < 2500)
            File(compose.activity.cacheDir, "search-timing.txt").writeText("Three edit/filter/render updates plus Compose assertion overhead: $elapsed ms; 95-track Library.\n")
            capture("search-results")
            Espresso.closeSoftKeyboard()
            compose.onNodeWithContentDescription("Close music search").performClick()
            compose.onNodeWithTag("nav-Home").performClick()
            compose.onNodeWithTag("home-library").performScrollTo().performClick()
            compose.onNodeWithText("Playlists", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Create playlist").performScrollTo().performClick()
            capture("light-confirmation")
            compose.onNodeWithText("Cancel").performClick()
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnUiThread { DownloadStore.savePrefs(DownloadStore.prefs.value.copy(theme = oldTheme)) }
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.cacheDir, "glass-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
