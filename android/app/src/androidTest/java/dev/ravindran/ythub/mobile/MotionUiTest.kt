package dev.ravindran.ythub.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.FrameMetricsAggregator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Motion must leave transport, song identity and navigation usable on the installed app. */
class MotionUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun animatedTransportSongsAndTabsRemainSynchronized() {
        compose.waitUntil(15000) { MusicRepository.ready.value }
        val before = MusicRepository.state.value
        val prefs = DownloadStore.prefs.value
        val frames = FrameMetricsAggregator()
        try {
            compose.runOnUiThread { DownloadStore.savePrefs(prefs.copy(onboardingDone = true)) }
            val uris = listOf("motion-a", "motion-b").map { MusicTestFixtures.audio(compose.activity, it) }
            runBlocking { MusicRepository.importAudio(uris) }
            val ids = uris.map { uri -> MusicRepository.state.value.tracks.first { it.uri == uri.toString() }.id }
            compose.runOnUiThread { MusicPlayback.play(ids) }
            compose.waitUntil(15000) { MusicPlayback.state.value.playing && MusicPlayback.state.value.track?.id == ids[0] }
            compose.onNodeWithTag("music-mini-player").performClick()
            frames.add(compose.activity)
            repeat(3) {
                compose.onNodeWithContentDescription("Pause").performScrollTo().performClick()
                compose.waitUntil(5000) { !MusicPlayback.state.value.playing }
                compose.onNodeWithContentDescription("Play").performClick()
                compose.waitUntil(5000) { MusicPlayback.state.value.playing }
            }
            compose.onNodeWithContentDescription("Next track").performClick()
            compose.waitUntil(5000) { MusicPlayback.state.value.track?.id == ids[1] }
            compose.onNodeWithContentDescription("Previous track").performClick()
            compose.waitUntil(5000) { MusicPlayback.state.value.track?.id == ids[0] }
            compose.onNodeWithContentDescription("Minimize player").performScrollTo().performClick()
            val position = MusicPlayback.state.value.position
            repeat(2) {
                listOf("Downloads", "Library", "Settings", "Home").forEach { tab ->
                    compose.onNodeWithTag("nav-$tab").performClick().assertIsSelected()
                    assertTrue("Tab $tab stopped audio", MusicPlayback.state.value.playing)
                    assertEquals(ids, MusicPlayback.state.value.queue)
                    compose.onNodeWithTag("music-mini-player").assertIsDisplayed()
                }
            }
            compose.waitUntil(5000) { MusicPlayback.state.value.position > position + 500 }
            val histogram = frames.remove(compose.activity)?.get(FrameMetricsAggregator.TOTAL_INDEX)
            val samples = mutableListOf<Int>()
            if (histogram != null) for (n in 0 until histogram.size()) repeat(histogram.valueAt(n)) { samples += histogram.keyAt(n) }
            samples.sort()
            val report = "frames=${samples.size}, framesOver32Ms=${samples.count { it > 32 }}, p95Ms=${samples.getOrNull(((samples.size - 1) * .95).toInt())}; transport/song/tab commands passed"
            File(compose.activity.cacheDir, "motion-audit.txt").writeText(report)
            android.util.Log.i("YTHubMotion", report)
        } finally {
            frames.stop()
            compose.runOnUiThread { MusicPlayback.stop() }
            compose.waitUntil(15000) { MusicPlayback.state.value.track == null }
            MusicRepository.update { before }
            compose.runOnUiThread { DownloadStore.savePrefs(prefs) }
            listOf("motion-a", "motion-b").forEach { File(compose.activity.filesDir, "music/imports/tests/$it.wav").delete() }
        }
    }
}
