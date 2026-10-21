package dev.ravindran.ythub.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Runs against the actual service and file-backed tracks; restores the user's music state. */
class MusicUiRefinementTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun playlistMiniPlayerAndSettingsKeepRealPlayback() {
        val before = MusicRepository.state.value
        val oldPreferences = DownloadStore.prefs.value
        try {
            compose.runOnUiThread { if (!oldPreferences.onboardingDone) DownloadStore.savePrefs(oldPreferences.copy(onboardingDone = true)) }
            val first = MusicTestFixtures.audio(compose.activity, "refinement-a")
            val second = MusicTestFixtures.audio(compose.activity, "refinement-b")
            runBlocking { MusicRepository.importAudio(listOf(first, second)) }
            val ids = listOf(first, second).map { uri -> MusicRepository.state.value.tracks.first { it.uri == uri.toString() }.id }
            MusicRepository.playlist("UI verification playlist", ids)
            compose.onNodeWithTag("nav-Library").performClick()
            compose.waitUntil(15000) { runCatching { compose.onAllNodesWithText("Your music").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
            compose.onNodeWithText("Your music").assertIsDisplayed()
            compose.onNodeWithTag("music-library-list").performScrollToNode(hasText("UI verification playlist"))
            compose.onNodeWithText("UI verification playlist").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("music-library-list").performScrollToNode(hasText("Play all"))
            compose.onNodeWithText("Play all").performClick()
            compose.waitUntil(15000) { MusicPlayback.state.value.playing && MusicPlayback.state.value.queue == ids }
            compose.onNodeWithContentDescription("Minimize player").performClick()
            compose.onNodeWithTag("music-mini-player").assertIsDisplayed().performClick()
            compose.onNodeWithContentDescription("Playback position").assertExists()
            compose.onNodeWithContentDescription("Quick audio settings").performClick()
            compose.onNodeWithText("Audio settings").assertExists()
            compose.onNodeWithText("All player settings").performClick()
            compose.onNodeWithText("Playback").assertExists()
            compose.onNodeWithText("Privacy and Data").performScrollTo().performClick()
            val history = MusicRepository.state.value.history
            compose.onNodeWithText("Clear playback history").performScrollTo().performClick()
            compose.onNodeWithText("Clear playback history?").assertExists()
            compose.onNodeWithText("Cancel").performClick()
            assertEquals(history, MusicRepository.state.value.history)
            assertTrue(MusicPlayback.state.value.playing)
            Espresso.pressBack()
            compose.onNodeWithContentDescription("Playback position").assertExists()
        } finally {
            compose.runOnUiThread { MusicPlayback.stop() }
            compose.waitUntil(15000) { MusicPlayback.state.value.track == null }
            MusicRepository.update { before }
            compose.runOnUiThread { DownloadStore.savePrefs(oldPreferences) }
            listOf("refinement-a.wav", "refinement-b.wav").forEach { java.io.File(compose.activity.filesDir, "music/imports/tests/$it").delete() }
        }
    }
    @Test fun thousandEntryLibraryIsVirtualizedAndScrollable() {
        compose.waitUntil(15000) { MusicRepository.ready.value }
        val before = MusicRepository.state.value
        val oldPreferences = DownloadStore.prefs.value
        try {
            compose.runOnUiThread {
                DownloadStore.savePrefs(oldPreferences.copy(onboardingDone = true))
                MusicRepository.update { it.copy(tracks = (0 until 1000).map { n -> LocalTrack("performance-$n", "content://test/$n", "Performance track $n", added = (1000 - n).toLong()) }, settings = it.settings.copy(libraryLayout = "Compact")) }
            }
            compose.onNodeWithTag("nav-Library").performClick()
            compose.onNodeWithText("All Songs").performClick()
            val start = android.os.SystemClock.elapsedRealtime()
            // Header, actions and layout occupy the first three lazy items.
            compose.onNodeWithTag("music-library-list").performScrollToIndex(1002)
            compose.onNode(hasText("Performance track 999") and !hasSetTextAction()).assertIsDisplayed()
            val elapsed = android.os.SystemClock.elapsedRealtime() - start
            android.util.Log.i("YTHubAudit", "1000-entry library indexed jump to last item: ${elapsed}ms")
            assertTrue("Indexed jump exceeded 5s: $elapsed", elapsed < 5000)
            assertTrue("Only visible rows should be composed", compose.onAllNodes(hasText("Performance track", substring = true)).fetchSemanticsNodes().size < 50)
            compose.onNodeWithTag("music-library-list").performScrollToIndex(0)
            val frames = androidx.core.app.FrameMetricsAggregator()
            frames.add(compose.activity)
            repeat(3) { compose.onNodeWithTag("music-library-list").performTouchInput { swipeUp() } }
            compose.waitForIdle()
            val histogram = frames.remove(compose.activity)?.get(androidx.core.app.FrameMetricsAggregator.TOTAL_INDEX)
            if (histogram != null) {
                var total = 0; var slow = 0
                for (n in 0 until histogram.size()) { total += histogram.valueAt(n); if (histogram.keyAt(n) > 32) slow += histogram.valueAt(n) }
                android.util.Log.i("YTHubAudit", "Library fling frame samples=$total; frames over 32ms=$slow")
                java.io.File(compose.activity.cacheDir, "music-audit-performance.txt").writeText("scrollToLastMs=$elapsed, frames=$total, framesOver32Ms=$slow")
            }
            compose.onNodeWithTag("music-library-list").performScrollToIndex(0)
            compose.onNodeWithText("Your music").assertIsDisplayed()
            compose.onNodeWithContentDescription("Search music library").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("Performance track 999")
            compose.waitUntil(5000) { compose.onAllNodesWithText("1 result").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("music-library-list").performScrollToNode(hasText("Performance track 999") and !hasSetTextAction())
            compose.onNode(hasText("Performance track 999") and !hasSetTextAction()).assertIsDisplayed()
        } finally {
            MusicRepository.update { before }
            compose.runOnUiThread { DownloadStore.savePrefs(oldPreferences) }
        }
    }

}
