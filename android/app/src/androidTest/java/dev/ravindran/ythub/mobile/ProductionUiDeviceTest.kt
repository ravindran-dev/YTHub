package dev.ravindran.ythub.mobile

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProductionUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun libraryOpensFromEveryEntryAndClearsStaleSearch() {
        compose.waitUntil(30000) { MusicRepository.ready.value }
        val prefs = DownloadStore.prefs.value
        try {
            compose.runOnUiThread { DownloadStore.savePrefs(prefs.copy(onboardingDone = true)) }
            compose.onNodeWithContentDescription("App menu").performTouchInput { click() }
            compose.onNodeWithTag("glass-popover").assertIsDisplayed()
            compose.onAllNodesWithText("Library", useUnmergedTree = true).onLast().performTouchInput { click() }
            compose.onNodeWithText("Your music").assertIsDisplayed()
            compose.onNodeWithText("Songs").assertIsDisplayed()
            capture("library-overview")
            compose.onNodeWithText("Songs").performTouchInput { click() }
            compose.onNodeWithTag("nav-Home").performTouchInput { click() }
            compose.onNodeWithTag("home-library").performScrollTo().performTouchInput { click() }
            compose.onNodeWithText("Songs").assertIsDisplayed()
            compose.onNodeWithTag("nav-Search").performTouchInput { click() }
            compose.onNodeWithTag("music-search-field").performTextInput("stale search")
            Espresso.closeSoftKeyboard()
            compose.onNodeWithTag("nav-Home").performTouchInput { click() }
            compose.onNodeWithTag("home-library").performScrollTo().performTouchInput { click() }
            compose.onNodeWithTag("music-search-field").assertDoesNotExist()
            compose.onNodeWithText("Songs").assertIsDisplayed()
            compose.onNodeWithTag("nav-Home").performTouchInput { click() }
            if (MusicPlayback.state.value.track == null) {
                compose.onNodeWithContentDescription("Open Library").performTouchInput { click() }
                compose.onNodeWithText("Your music").assertIsDisplayed()
                compose.onNodeWithText("Songs").assertIsDisplayed()
                compose.onNodeWithTag("nav-Home").performTouchInput { click() }
                compose.onNodeWithTag("music-mini-player").performTouchInput { click() }
                compose.onNodeWithText("Your music").assertIsDisplayed()
            }
        } finally { compose.runOnUiThread { DownloadStore.savePrefs(prefs) } }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "production-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

class GlassInteractionDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun glassPressHasOneHapticAndActivatesOnlyOnRelease() {
        val haptics = AtomicInteger()
        val clicks = AtomicInteger()
        val feedback = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics.incrementAndGet() }
        }
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = MobileUi.accent, background = MobileUi.background), typography = musicTypography()) {
                CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF090A0D)).padding(32.dp)) {
                        GlassControlSurface {
                            GlassIconButton(Icons.Rounded.Add, "Test glass press", { clicks.incrementAndGet() })
                        }
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Test glass press").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(180)
        compose.waitForIdle()
        assertEquals(1, haptics.get())
        assertEquals(0, clicks.get())
        capture("glass-held")
        compose.onNodeWithContentDescription("Test glass press").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals(1, clicks.get())
        assertEquals(1, haptics.get())
        capture("glass-released")
        compose.mainClock.autoAdvance = true
    }

    @Test fun logoLoaderWaitsForReadiness() {
        var ready by mutableStateOf(false)
        val completed = AtomicInteger()
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = MobileUi.accent, background = MobileUi.background), typography = musicTypography()) { StartupScreen(ready) { completed.incrementAndGet() } }
        }
        compose.onNodeWithTag("startup-screen").assertIsDisplayed()
        compose.onNodeWithText("YTHub").assertIsDisplayed()
        assertEquals(0, completed.get())
        capture("startup")
        compose.runOnIdle { ready = true }
        compose.waitUntil(5000) { completed.get() == 1 }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "production-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
