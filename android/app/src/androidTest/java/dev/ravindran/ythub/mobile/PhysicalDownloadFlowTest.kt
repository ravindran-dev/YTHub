package dev.ravindran.ythub.mobile

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in network check: run only when a real test URL is explicitly supplied. */
class PhysicalDownloadFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun completedAudioAppearsAutomaticallyAndPlaysLocally() {
        val url = InstrumentationRegistry.getArguments().getString("physicalDownloadUrl")
        assumeTrue("Explicit physicalDownloadUrl required", !url.isNullOrBlank())
        val oldDownloads = DownloadStore.prefs.value
        val oldPlayer = MusicRepository.state.value.settings
        try {
            compose.waitUntil(120000) { DownloadStore.engine.value.contains("ready") || DownloadStore.engine.value.contains("unavailable") }
            assertTrue(DownloadStore.engine.value, DownloadStore.engine.value.contains("ready"))
            if (InstrumentationRegistry.getArguments().getString("updateEngine") == "true") {
                val version = runBlocking { DownloadStore.updateEngine() }
                android.util.Log.i("YTHubDeviceCheck", "Existing engine updater: $version")
            }
            compose.runOnUiThread {
                DownloadStore.savePrefs(oldDownloads.copy(autoStart = true, onboardingDone = true))
                MusicRepository.settings(oldPlayer.copy(autoAdd = true, autoPlayDownload = false))
            }
            val media = runBlocking { DownloadStore.analyze(url!!, false) }
            var id = ""
            compose.runOnUiThread { id = DownloadStore.enqueue(media, true, "128") }
            compose.waitUntil(180000) { DownloadStore.items.value.first { it.id == id }.terminal }
            val result = DownloadStore.items.value.first { it.id == id }
            assertEquals("Download error: ${result.error}", "Completed", result.state)
            assertTrue(result.uris.isNotEmpty())
            assertTrue(result.uris.all(MusicRepository::exists))
            compose.waitUntil(15000) { MusicRepository.state.value.tracks.any { it.downloadId == id && it.available && it.durationMs > 0 } }
            val track = MusicRepository.state.value.tracks.first { it.downloadId == id }
            assertEquals(result.uris.first(), track.uri)
            compose.runOnUiThread { MusicPlayback.play(listOf(track.id)) }
            compose.waitUntil(15000) { MusicPlayback.state.value.playing && MusicPlayback.state.value.position > 200 }
            android.util.Log.i("YTHubDeviceCheck", "Real download completed; output exists; library auto-added; local playback confirmed. Job=$id")
        } finally {
            compose.runOnUiThread {
                MusicPlayback.stop()
                MusicRepository.settings(oldPlayer)
                DownloadStore.savePrefs(oldDownloads)
            }
        }
    }
}
