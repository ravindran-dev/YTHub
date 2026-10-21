package dev.ravindran.ythub.mobile

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DeviceMusicPickerTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun phoneAudioRefreshesAndAddsDirectlyToPlaylist() {
        val i = InstrumentationRegistry.getInstrumentation()
        if (!DeviceMusicCatalog.allowed(i.targetContext)) runCatching { i.uiAutomation.grantRuntimePermission(i.targetContext.packageName, DeviceMusicCatalog.permission) }
        compose.waitUntil(15000) { MusicRepository.ready.value }
        val before = MusicRepository.state.value
        val prefs = DownloadStore.prefs.value
        val sources = mutableListOf<Uri>()
        val fixture = MusicTestFixtures.audio(compose.activity, "phone-picker")
        val bytes = compose.activity.contentResolver.openInputStream(fixture)!!.use { it.readBytes() }
        fun phoneSong(label: String): Uri {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "$label.wav")
                put(MediaStore.Audio.Media.TITLE, label)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.DURATION, 20000)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/YTHubVerification")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = compose.activity.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
            sources.add(uri)
            compose.activity.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            compose.activity.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            return uri
        }
        try {
            compose.runOnUiThread { DownloadStore.savePrefs(prefs.copy(onboardingDone = true)) }
            val first = phoneSong("YTHub phone picker first")
            if (DeviceMusicCatalog.allowed(compose.activity)) compose.waitUntil(15000) { DeviceMusicCatalog.query(compose.activity).any { it.uri == first.toString() } }
            MusicRepository.playlist("Phone music verification", emptyList(), "phone-picker-test")
            compose.onNodeWithTag("nav-Library").performClick()
            compose.onNodeWithText("Playlists").performScrollTo().performClick()
            compose.onNodeWithTag("music-library-list").performScrollToNode(hasText("Phone music verification"))
            compose.onNodeWithText("Phone music verification").performClick()
            compose.onNodeWithContentDescription("Add phone music to playlist").performClick()
            compose.waitUntil(60000) { DeviceMusicCatalog.allowed(compose.activity) }
            compose.onNodeWithText("Music on this phone").assertIsDisplayed()
            compose.onNode(hasSetTextAction()).performTextInput("YTHub phone picker")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.waitUntil(15000) { compose.onAllNodesWithText("YTHub phone picker first").fetchSemanticsNodes().isNotEmpty() }
            phoneSong("YTHub phone picker new download")
            compose.waitUntil(15000) { compose.onAllNodesWithText("YTHub phone picker new download").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("YTHub phone picker first").performClick()
            compose.onNodeWithText("Add 1 to playlist").performClick()
            compose.waitUntil(15000) { MusicRepository.state.value.playlists.first { it.id == "phone-picker-test" }.tracks.size == 1 }
            val id = MusicRepository.state.value.playlists.first { it.id == "phone-picker-test" }.tracks.single()
            assertEquals(first.toString(), MusicRepository.state.value.tracks.first { it.id == id }.uri)
            compose.onNodeWithContentDescription("Add phone music to playlist").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("YTHub phone picker first")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.waitUntil(15000) { compose.onAllNodesWithText("Already in playlist").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, MusicRepository.state.value.playlists.first { it.id == "phone-picker-test" }.tracks.size)
            compose.runOnUiThread { MusicPlayback.play(listOf(id)) }
            compose.waitUntil(15000) { MusicPlayback.state.value.playing && MusicPlayback.state.value.position > 200 }
        } finally {
            compose.runOnUiThread { MusicPlayback.stop() }
            compose.waitUntil(15000) { MusicPlayback.state.value.track == null }
            MusicRepository.update { before }
            compose.runOnUiThread { DownloadStore.savePrefs(prefs) }
            sources.forEach { compose.activity.contentResolver.delete(it, null, null) }
            java.io.File(compose.activity.filesDir, "music/imports/tests/phone-picker.wav").delete()
        }
    }
}
