package dev.ravindran.ythub.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The passive liquid gesture must preserve row release, cancellation and scrolling semantics. */
class LiquidPopupDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun liquidPressFlexesWithoutStealingClicksOrScrolling() {
        val clicks = AtomicInteger()
        val haptics = AtomicInteger()
        val feedback = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics.incrementAndGet() }
        }
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                    LiquidGlassPanel(Modifier.padding(24.dp).width(280.dp).height(230.dp).testTag("liquid-test-panel")) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            repeat(10) { index -> GlassMenuItem({ Text("Action $index") }, { clicks.incrementAndGet() }) }
                        }
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        val rest = compose.onNodeWithTag("liquid-test-panel").captureToImage()
        compose.onNodeWithText("Action 0").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        val held = compose.onNodeWithTag("liquid-test-panel").captureToImage()
        assertEquals(0, clicks.get())
        assertEquals(1, haptics.get())
        assertTrue("Pressure must visibly change the liquid surface", changed(rest, held) > 50)
        compose.onNodeWithText("Action 0").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertEquals(1, clicks.get())
        assertEquals(1, haptics.get())
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("liquid-test-panel").performTouchInput { swipeUp() }
        compose.onNodeWithText("Action 9").performScrollTo().assertIsDisplayed()
        assertEquals("Scrolling must not activate a menu action", 1, clicks.get())
    }

    private fun changed(a: ImageBitmap, b: ImageBitmap): Int {
        val first = IntArray(a.width * a.height); val second = IntArray(b.width * b.height)
        a.readPixels(first); b.readPixels(second)
        return first.indices.count { first[it] != second[it] }
    }
}
