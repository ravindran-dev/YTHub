package dev.ravindran.ythub.mobile

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Samples rendered disc pixels to verify visible motion and a stable pause, without modifying music. */
class DiscMotionDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun discMovesWhilePlayingAndHoldsItsAngleOnPause() {
        compose.waitUntil(30000) { MusicRepository.ready.value }
        val track = MusicRepository.state.value.tracks.first { it.artwork.isNotBlank() && File(it.artwork).exists() }
        var playing by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { DiscArtwork(track, 0, playing, Modifier.size(280.dp).testTag("disc-sample")) }
        }
        compose.mainClock.advanceTimeBy(400)
        val first = compose.onNodeWithTag("disc-sample").captureToImage()
        compose.mainClock.advanceTimeBy(1800)
        val second = compose.onNodeWithTag("disc-sample").captureToImage()
        assertTrue("The exposed disc must visibly rotate", changed(first, second) > 20)
        compose.runOnIdle { playing = false }
        compose.mainClock.advanceTimeBy(100)
        val paused = compose.onNodeWithTag("disc-sample").captureToImage()
        compose.mainClock.advanceTimeBy(1800)
        val stillPaused = compose.onNodeWithTag("disc-sample").captureToImage()
        assertEquals("Paused disc must retain its orientation", 0, changed(paused, stillPaused))
        compose.mainClock.autoAdvance = true
    }
    private fun changed(a: ImageBitmap, b: ImageBitmap): Int {
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        val x = IntArray(a.width * a.height); val y = IntArray(x.size)
        a.readPixels(x); b.readPixels(y)
        var count = 0
        for (row in 10 until a.height - 10) for (column in (a.width * .84f).toInt() until a.width - 5) {
            val index = row * a.width + column
            if (x[index] != y[index]) count++
        }
        return count
    }
}
