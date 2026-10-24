package dev.ravindran.ythub.mobile

import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Read-access audit of recovered user content. Does not import, delete, or replace user data. */
class LibraryRecoveryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun restoredFilesAndPlaylistsAreAccessible() {
        compose.waitUntil(30000) { MusicRepository.ready.value }
        runBlocking { MusicRepository.rescan() }
        val state = MusicRepository.state.value
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val inaccessible = state.tracks.filter { track ->
            !runCatching { context.contentResolver.openAssetFileDescriptor(Uri.parse(track.uri), "r")?.use { true } ?: false }.getOrDefault(false)
        }
        val report = mapOf("tracks" to state.tracks.size, "accessible" to state.tracks.size - inaccessible.size,
            "playlists" to state.playlists.map { mapOf("name" to it.name, "tracks" to it.tracks.size) },
            "inaccessible" to inaccessible.map { mapOf("id" to it.id, "title" to it.title, "uri" to it.uri) })
        File(context.cacheDir, "library-recovery-report.json").writeText(Gson().toJson(report))
        assertTrue("Both original playlists must be preserved", state.playlists.map { it.name }.containsAll(listOf("ravi hits", "tamil hits")))
        assertTrue("All original metadata must be recovered", state.tracks.size >= 95)
        assertTrue("Restored phone music must be readable", inaccessible.isEmpty())
    }
}
