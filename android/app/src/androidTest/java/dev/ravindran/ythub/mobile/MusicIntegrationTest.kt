package dev.ravindran.ythub.mobile

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Synthetic PCM is a test fixture only; the product ships no sample media or download rows. */
@RunWith(AndroidJUnit4::class)
class MusicIntegrationTest {
    @get:org.junit.Rule val activity = androidx.test.ext.junit.rules.ActivityScenarioRule(MainActivity::class.java)
    @org.junit.Before fun repositoryReady() {
        val end = android.os.SystemClock.elapsedRealtime() + 15000
        while (!MusicRepository.ready.value && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(50)
        assertTrue(MusicRepository.ready.value)
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun audio(name: String) = MusicTestFixtures.audio(context, name)
    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15000
        while (!condition() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue("Timed out waiting for media session: ${MusicPlayback.diagnostics}; ${MusicPlayback.error.value}; ${MusicPlayback.lastPlayIntent}", condition())
    }
    @Test fun repositoryAndRealPlaybackLifecycle() = runBlocking<Unit> {
        val before = MusicRepository.state.value
        try {
            // App-owned provider grants are already held; use repository metadata import directly.
            val firstUri = audio("first"); val secondUri = audio("second")
            MusicRepository.importAudio(listOf(firstUri, secondUri))
            MusicRepository.importAudio(listOf(firstUri))
            val tracks = MusicRepository.state.value.tracks.filter { it.uri in listOf(firstUri.toString(), secondUri.toString()) }
            assertEquals(2, tracks.size); assertTrue(tracks.all { it.durationMs >= 19000 })
            MusicRepository.playlist("Test playlist", tracks.map { it.id })
            val playlist = MusicRepository.state.value.playlists.last()
            MusicRepository.playlist("Renamed", playlist.tracks.reversed(), playlist.id)
            assertEquals("Renamed", MusicRepository.state.value.playlists.last().name)
            MusicRepository.addToPlaylist(playlist.id, listOf(tracks[0].id))
            assertEquals(2, MusicRepository.state.value.playlists.last().tracks.size)
            instrumentation.runOnMainSync { MusicPlayback.play(tracks.map { it.id }) }
            await { MusicPlayback.state.value.playing && MusicPlayback.state.value.position > 200 }
            instrumentation.runOnMainSync { MusicPlayback.seek(6000); MusicPlayback.enqueue(listOf(tracks[0].id)) }
            await { MusicPlayback.state.value.position >= 6000 && MusicPlayback.state.value.queue.size == 3 }
            val position = MusicPlayback.state.value.position
            instrumentation.runOnMainSync { MusicPlayback.move(2, 1) }
            await { MusicPlayback.state.value.queue[1] == tracks[0].id }
            assertTrue(MusicPlayback.state.value.position >= position)
            instrumentation.runOnMainSync { MusicPlayback.next() }
            await { MusicPlayback.state.value.index == 1 }
            instrumentation.runOnMainSync { MusicPlayback.timer("Duration", 1) }
            assertTrue(MusicPlayback.state.value.playing)
            MusicPlayback.deadline = android.os.SystemClock.elapsedRealtime() + 100
            await { !MusicPlayback.state.value.playing }
            instrumentation.runOnMainSync { MusicPlayback.cancelTimer(); MusicRepository.settings(MusicRepository.state.value.settings.copy(speed = 1.5f)) }
            assertEquals(1.5f, MusicRepository.state.value.settings.speed)
            MusicRepository.played(tracks[0].id); MusicRepository.favorite(tracks[0].id)
            assertTrue(MusicRepository.state.value.history[tracks[0].id]!!.count > 0)
            File(context.filesDir, "music/imports/tests/first.wav").delete()
            MusicRepository.rescan()
            assertFalse(MusicRepository.state.value.tracks.first { it.id == tracks[0].id }.available)
            MusicRepository.remove(setOf(tracks[1].id))
            assertTrue(MusicRepository.exists(secondUri.toString()))
            MusicRepository.deletePlaylist(playlist.id)
            assertFalse(MusicRepository.state.value.playlists.any { it.id == playlist.id })
            await {
                runCatching {
                    val saved = com.google.gson.Gson().fromJson(File(context.filesDir, "music/library-v1.json").readText(), MusicState::class.java)
                    saved.settings.speed == 1.5f && (saved.history[tracks[0].id]?.count ?: 0) > 0
                }.getOrDefault(false)
            }
        } finally {
            instrumentation.runOnMainSync { MusicPlayback.stop() }
            await { MusicPlayback.state.value.track == null }
            MusicRepository.update { before }
        }
    }
    @Suppress("UNCHECKED_CAST")
    @Test fun downloadCompletionUsesExistingRepositoryAndVerifiesFiles() = runBlocking<Unit> {
        val field = DownloadStore::class.java.getDeclaredField("_items").apply { isAccessible = true }
        val downloads = field.get(null) as kotlinx.coroutines.flow.MutableStateFlow<List<DownloadItem>>
        val oldDownloads = downloads.value
        val oldMusic = MusicRepository.state.value
        val uri = audio("download-integration")
        val job = DownloadItem("integration-download", MediaInfo("https://youtu.be/test", "Test download", playlist = true), audio = true, quality = "320", uris = listOf(uri.toString()), exportedNames = listOf("download-integration.wav"))
        try {
            downloads.value = listOf(job.copy(state = "Queued"))
            MusicRepository.rescan(true)
            assertFalse(MusicRepository.state.value.tracks.any { it.downloadId == job.id })
            downloads.value = listOf(job.copy(state = "Completed", audio = false))
            MusicRepository.rescan(true)
            assertFalse(MusicRepository.state.value.tracks.any { it.downloadId == job.id })
            downloads.value = listOf(job.copy(state = "Completed"))
            MusicRepository.rescan(true); MusicRepository.rescan(true)
            val imported = MusicRepository.state.value.tracks.filter { it.downloadId == job.id }
            assertEquals(1, imported.size)
            assertEquals(listOf(imported.single().id), MusicRepository.state.value.playlists.first { it.downloadId == job.id }.tracks)
            assertEquals(listOf(imported.single().id), MusicRepository.downloadedTracks(job.id))
            File(context.filesDir, "music/imports/tests/download-integration.wav").delete()
            MusicRepository.rescan(true)
            assertFalse(MusicRepository.state.value.tracks.first { it.id == imported.single().id }.available)
        } finally {
            downloads.value = oldDownloads
            MusicRepository.rescan()
            MusicRepository.update { oldMusic }
        }
    }
    @Test fun zipImportRejectsTraversalWithoutLeavingPartialAudio() = runBlocking<Unit> {
        val zip = File(context.filesDir, "music/imports/tests/unsafe.zip").apply { parentFile?.mkdirs() }
        ZipOutputStream(zip.outputStream()).use { output -> output.putNextEntry(ZipEntry("../../escape.mp3")); output.write(byteArrayOf(1, 2, 3)); output.closeEntry() }
        val before = MusicRepository.state.value
        val result = runCatching { MusicRepository.extractPlaylist(FileProvider.getUriForFile(context, "${context.packageName}.files", zip), "Unsafe") }
        assertTrue(result.isFailure); assertEquals(before, MusicRepository.state.value)
        assertFalse(File(context.filesDir, "escape.mp3").exists()); zip.delete()
    }
    @Test fun successfulZipIndexesOnlyAfterCompleteExtractionInOriginalOrder() = runBlocking<Unit> {
        val before = MusicRepository.state.value
        val fixture = audio("zip-source")
        val bytes = context.contentResolver.openInputStream(fixture)!!.use { it.readBytes() }
        val zip = File(context.filesDir, "music/imports/tests/ordered.zip")
        try {
            ZipOutputStream(zip.outputStream()).use { output ->
                listOf("02 Second.wav", "01 First.wav").forEach { name -> output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry() }
            }
            MusicRepository.extractPlaylist(FileProvider.getUriForFile(context, "${context.packageName}.files", zip), "Ordered archive")
            val playlist = MusicRepository.state.value.playlists.last()
            val byId = MusicRepository.state.value.tracks.associateBy { it.id }
            assertEquals(listOf("01 First.wav", "02 Second.wav"), playlist.tracks.map { byId[it]!!.filename })
            val successful = MusicRepository.state.value
            ZipOutputStream(zip.outputStream()).use { output ->
                output.putNextEntry(ZipEntry("01 Valid.wav")); output.write(bytes); output.closeEntry()
                output.putNextEntry(ZipEntry("../escape.wav")); output.write(bytes); output.closeEntry()
            }
            assertTrue(runCatching { MusicRepository.extractPlaylist(FileProvider.getUriForFile(context, "${context.packageName}.files", zip), "Must rollback") }.isFailure)
            assertEquals(successful, MusicRepository.state.value)
            playlist.tracks.forEach { id ->
                val uri = android.net.Uri.parse(byId[id]!!.uri)
                context.contentResolver.delete(uri, null, null)
            }
        } finally { MusicRepository.update { before }; zip.delete(); File(context.filesDir, "music/imports/tests/zip-source.wav").delete() }
    }

}
