package dev.ravindran.ythub.mobile

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Explicit live repair check. Requires a selected failed job; never runs downloads in normal CI. */
class EngineRepairDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun updateOfficialEngineAndRetrySelectedAudioDownload() {
        val id = InstrumentationRegistry.getArguments().getString("repairDownload")
        assumeTrue("Pass -e repairDownload <failed audio job ID> for the live check", id != null)
        compose.runOnUiThread { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        runBlocking { DownloadStore.engine.first { it.contains("ready") || it.contains("unavailable") } }
        runBlocking { MusicRepository.ready.first { it } }
        val job = DownloadStore.items.value.first { it.id == id }
        assertTrue(job.audio)
        assertEquals("Failed", job.state)
        val libraryBefore = MusicRepository.state.value
        val before = YoutubeDL.getInstance().execute(YoutubeDLRequest(emptyList<String>()).addOption("--version")).out.trim()
        report("engine_before", before)
        report("engine_update", runBlocking { DownloadStore.updateEngine() })
        val after = YoutubeDL.getInstance().execute(YoutubeDLRequest(emptyList<String>()).addOption("--version")).out.trim()
        report("engine_after", after)
        compose.runOnUiThread { DownloadStore.resume(job.id) }
        val started = SystemClock.elapsedRealtime()
        var current = DownloadStore.items.value.first { it.id == id }
        while (SystemClock.elapsedRealtime() - started < 240000) {
            Thread.sleep(2000)
            current = DownloadStore.items.value.first { it.id == id }
            report("download", "${current.state} ${current.progress.toInt()}% ${current.line.takeLast(160)}")
            if (current.state == "Completed" || current.state == "Failed") break
        }
        assertEquals("Actual audio download failed: ${current.error}", "Completed", current.state)
        assertTrue(current.uris.isNotEmpty())
        current.uris.forEach { uri ->
            val reader = android.media.MediaMetadataRetriever()
            try {
                reader.setDataSource(compose.activity, android.net.Uri.parse(uri))
                val duration = reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                assertTrue("Exported audio must have readable duration", duration > 0)
                report("audio_duration_ms", duration.toString())
            } finally { reader.release() }
        }
        val libraryAfter = MusicRepository.state.value
        assertTrue(libraryAfter.tracks.map { it.id }.containsAll(libraryBefore.tracks.map { it.id }))
        assertEquals(libraryBefore.playlists, libraryAfter.playlists)
    }
    private fun report(key: String, value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString(key, value) })
    }
}
