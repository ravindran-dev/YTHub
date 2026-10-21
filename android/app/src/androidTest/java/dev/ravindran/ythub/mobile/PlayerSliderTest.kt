package dev.ravindran.ythub.mobile

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.FrameMetricsAggregator
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Real installed player, physical touch gestures, Media3 position and Android media volume. */
class PlayerSliderTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun capture(name: String) {
        compose.waitForIdle()
        val i = InstrumentationRegistry.getInstrumentation()
        i.waitForIdleSync()
        android.os.SystemClock.sleep(100)
        val file = File(compose.activity.cacheDir, "slider-audit/$name.png").apply { parentFile!!.mkdirs() }
        checkNotNull(i.uiAutomation.takeScreenshot()).let { image ->
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun translucentSliderDragAndSeekUseActualPlayback() {
        compose.waitUntil(15000) { MusicRepository.ready.value }
        val before = MusicRepository.state.value
        val prefs = DownloadStore.prefs.value
        val audio = compose.activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val frames = FrameMetricsAggregator()
        try {
            val uri = MusicTestFixtures.audio(compose.activity, "glass-slider")
            runBlocking { MusicRepository.importAudio(listOf(uri)) }
            val track = MusicRepository.state.value.tracks.first { it.uri == uri.toString() }
            compose.runOnUiThread { MusicPlayback.play(listOf(track.id)) }
            compose.waitUntil(15000) { MusicPlayback.state.value.playing && MusicPlayback.state.value.duration > 10000 }
            compose.activity.setContent {
                MaterialTheme(colorScheme = darkColorScheme(primary = MobileUi.accent, onPrimary = androidx.compose.ui.graphics.Color.White, background = MobileUi.background, surface = MobileUi.surface, surfaceVariant = MobileUi.elevated), typography = musicTypography()) {
                    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                        NowPlayingScreen({}, {}, {})
                    }
                }
            }
            compose.onNodeWithContentDescription("Pause").performScrollTo().performClick()
            compose.waitUntil(5000) { !MusicPlayback.state.value.playing }
            val seek = compose.onNodeWithContentDescription("Playback position")
            seek.performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(5000f) }
            compose.waitUntil(5000) { abs(MusicPlayback.state.value.position - 5000) < 300 }
            capture("idle")
            frames.add(compose.activity)
            seek.performTouchInput {
                down(Offset(width * .25f, height / 2f))
                for (step in 1..30) moveTo(Offset(width * (.25f + .4f * step / 30f), height / 2f), delayMillis = 16)
            }
            capture("dragging")
            seek.performTouchInput { up() }
            val duration = MusicPlayback.state.value.duration
            compose.waitUntil(5000) { abs(MusicPlayback.state.value.position - duration * .65f) < 700 }
            capture("released")
            seek.performTouchInput { swipe(Offset(width * .65f, height / 2f), Offset(width * .2f, height / 2f), 500) }
            compose.waitUntil(5000) { abs(MusicPlayback.state.value.position - duration * .2f) < 700 }
            val resumedAt = MusicPlayback.state.value.position
            compose.onNodeWithContentDescription("Play").performScrollTo().performClick()
            compose.waitUntil(5000) { MusicPlayback.state.value.playing && MusicPlayback.state.value.position > resumedAt + 500 }
            val volume = compose.onNodeWithContentDescription("Media volume")
            volume.performScrollTo()
            if (!audio.isVolumeFixed) {
                val minimum = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
                val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val target = if (originalVolume > minimum) originalVolume - 1 else (minimum + 1).coerceAtMost(maximum)
                volume.performSemanticsAction(SemanticsActions.SetProgress) { it(target.toFloat()) }
                compose.waitUntil(5000) { audio.getStreamVolume(AudioManager.STREAM_MUSIC) == target }
            }
            compose.onNodeWithContentDescription("Track options").performScrollTo().performClick()
            compose.onNodeWithText("Play now").assertDoesNotExist()
            compose.onNodeWithText("Add to queue").assertIsDisplayed()
            capture("track-actions")
            compose.onNodeWithText("Track information").performScrollTo().performClick()
            compose.onNodeWithText("Title", substring = false).assertIsDisplayed()
            capture("track-information")
            compose.onNodeWithContentDescription("Back to track actions").performClick()
            compose.onNodeWithText("Manage file").performScrollTo().performClick()
            capture("manage-file")
            compose.onNodeWithText("Keep the audio file on your phone").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Delete audio file…").performClick()
            compose.onNodeWithText("Permanently delete this audio file?").assertIsDisplayed()
            compose.onNodeWithText("Cancel", substring = false).performClick()
            assertTrue(java.io.File(compose.activity.filesDir, "music/imports/tests/glass-slider.wav").exists())
            compose.onNodeWithContentDescription("Back to track actions").performClick()
            compose.onNodeWithText("Add to playlist").performScrollTo().performClick()
            capture("playlist-picker")
            compose.onNode(hasSetTextAction()).performTextInput("Player menu audit playlist")
            compose.onNodeWithText("Create and add").performClick()
            compose.waitUntil(5000) { MusicRepository.state.value.playlists.any { it.name == "Player menu audit playlist" && track.id in it.tracks } }
            compose.onNodeWithText("Timer", useUnmergedTree = true).performScrollTo().performClick()
            capture("sleep-timer")
            compose.onNodeWithText("30 min").performClick()
            compose.waitUntil(5000) { MusicPlayback.state.value.sleepDeadline > android.os.SystemClock.elapsedRealtime() + 1700000 }
            compose.runOnUiThread { MusicPlayback.cancelTimer() }
            val histogram = frames.remove(compose.activity)?.get(FrameMetricsAggregator.TOTAL_INDEX)
            var total = 0; var slow = 0
            if (histogram != null) for (n in 0 until histogram.size()) {
                total += histogram.valueAt(n)
                if (histogram.keyAt(n) > 32) slow += histogram.valueAt(n)
            }
            File(compose.activity.cacheDir, "slider-audit/result.txt").writeText("forwardSeekMs=${(duration * .65f).toLong()}, backwardSeekMs=${(duration * .2f).toLong()}, resumed=true, frames=$total, framesOver32Ms=$slow")
        } finally {
            frames.stop()
            if (!audio.isVolumeFixed) audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            compose.runOnUiThread { MusicPlayback.stop() }
            compose.waitUntil(15000) { MusicPlayback.state.value.track == null }
            MusicRepository.update { before }
            compose.runOnUiThread { DownloadStore.savePrefs(prefs) }
            File(compose.activity.filesDir, "music/imports/tests/glass-slider.wav").delete()
        }
    }
}
