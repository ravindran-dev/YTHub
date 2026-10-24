package dev.ravindran.ythub.mobile

import android.os.Bundle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Explicit network check of the normal analysis path; no duplicate download or library replacement. */
class EngineFreshnessDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun analysisChecksOfficialEngineAndCompletedAudioRemainsReadable() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("checkEngineFreshness") == "true")
        runBlocking { DownloadStore.engine.first { it.contains("ready") || it.contains("unavailable") }; MusicRepository.ready.first { it } }
        val library = MusicRepository.state.value
        val download = DownloadStore.items.value.first { it.audio && it.state == "Completed" }
        val prefs = compose.activity.getSharedPreferences("ythub-engine", android.content.Context.MODE_PRIVATE)
        prefs.edit().remove("last_check").commit()
        val began = System.currentTimeMillis()
        val metadata = runBlocking { DownloadStore.analyze(download.media.url, false) }
        val checked = prefs.getLong("last_check", 0)
        assertTrue("Analysis must check the engine when its last check is absent", checked >= began)
        assertFalse(engineUpdateDue(System.currentTimeMillis(), checked))
        assertTrue(metadata.title.isNotBlank())
        download.uris.forEach { uri ->
            val reader = android.media.MediaMetadataRetriever()
            try {
                reader.setDataSource(compose.activity, android.net.Uri.parse(uri))
                assertEquals("audio/mpeg", reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE))
                assertTrue((reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0)
            } finally { reader.release() }
        }
        assertEquals(library.playlists, MusicRepository.state.value.playlists)
        assertTrue(MusicRepository.state.value.tracks.map { it.id }.containsAll(library.tracks.map { it.id }))
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("version", DownloadStore.engineVersion())
            putString("analyzed_title", metadata.title)
            putString("exported_audio", "audio/mpeg; readable duration")
        })
    }
}
