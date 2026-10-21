package dev.ravindran.ythub.mobile

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Installed app at externally configured dp widths/font scales. Fixtures never ship in the app. */
class CompactLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val destinations = listOf("Home", "Downloads", "Library", "Settings")
    private fun capture(name: String) {
        compose.waitForIdle()
        val i = InstrumentationRegistry.getInstrumentation()
        i.waitForIdleSync(); android.os.SystemClock.sleep(150)
        val folder = InstrumentationRegistry.getArguments().getString("layoutCase") ?: "default"
        val output = File(compose.activity.cacheDir, "ui-audit/$folder/$name.png").apply { parentFile!!.mkdirs() }
        val bitmap = checkNotNull(i.uiAutomation.takeScreenshot())
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun labelsFit() {
        for (label in destinations) {
            val results = mutableListOf<TextLayoutResult>()
            val text = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("nav-$label")), useUnmergedTree = true)
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertTrue("Missing text layout for $label", results.isNotEmpty())
            results.forEach { layout ->
                assertFalse("$label overflow at ${compose.activity.resources.configuration}", layout.hasVisualOverflow)
                repeat(layout.lineCount) { assertFalse("$label was ellipsized", layout.isLineEllipsized(it)) }
            }
            val tab = compose.onNodeWithTag("nav-$label").fetchSemanticsNode().boundsInRoot
            val bounds = text.fetchSemanticsNode().boundsInRoot
            assertTrue("$label escaped its tab", bounds.left >= tab.left - 1 && bounds.right <= tab.right + 1 && bounds.top >= tab.top - 1 && bounds.bottom <= tab.bottom + 1)
        }
    }
    /** A glass overlay intentionally draws over content; scroll the target clear before tapping. */
    private fun libraryVisible(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val list = compose.onNodeWithTag("music-library-list")
        list.performScrollToNode(matcher)
        val node = compose.onNode(matcher)
        node.performScrollTo() // Also reveal chips within the horizontal category strip.
        val coverTop = listOf("floating-glass-navigation", "music-mini-player").flatMap { tag ->
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
        }.minOfOrNull { it.boundsInRoot.top }
        if (coverTop != null) {
            val distance = node.fetchSemanticsNode().boundsInRoot.bottom - coverTop + 16f
            if (distance > 0) list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, distance) }
            compose.waitForIdle()
            assertTrue("Target must scroll above the glass controls", node.fetchSemanticsNode().boundsInRoot.bottom <= coverTop + 1f)
        }
        return node
    }
    @Suppress("UNCHECKED_CAST")
    @Test fun destinationsLongContentPlayerSheetsAndKeyboard() {
        compose.waitUntil(15000) { MusicRepository.ready.value }
        val audio = compose.activity.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val originalVolume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val before = MusicRepository.state.value
        val preferences = DownloadStore.prefs.value
        val field = DownloadStore::class.java.getDeclaredField("_items").apply { isAccessible = true }
        val downloads = field.get(null) as MutableStateFlow<List<DownloadItem>>
        val oldDownloads = downloads.value
        val source = MusicTestFixtures.audio(compose.activity, "compact-layout")
        val playlistName = "Layout playlist with a deliberately long name for a small phone"
        val downloadTitle = "Download with an extremely long title " + "and descriptive metadata ".repeat(8)
        try {
            compose.runOnUiThread { DownloadStore.savePrefs(preferences.copy(onboardingDone = true)) }
            runBlocking { MusicRepository.importAudio(listOf(source)) }
            val id = MusicRepository.state.value.tracks.first { it.uri == source.toString() }.id
            MusicRepository.update { it.copy(tracks = it.tracks.map { t -> if (t.id == id) t.copy(title = "Long track title " + "that should wrap gracefully ".repeat(8), artist = "A very long artist name ".repeat(8)) else t }) }
            MusicRepository.playlist(playlistName, listOf(id, id), "compact-layout-test")
            downloads.value = listOf(DownloadItem("compact-layout-active", MediaInfo("https://www.youtube.com/watch?v=layout", downloadTitle), audio = true, quality = "320", state = "Downloading", progress = 52f, line = "Generated UI fixture · 2 MiB of 4 MiB · 1 MiB/s", eta = 2))
            for (destination in destinations) {
                compose.onNodeWithTag("nav-$destination").performClick()
                compose.onNodeWithTag("nav-$destination").assertIsSelected()
                labelsFit(); capture(destination.lowercase())
            }
            compose.onNodeWithTag("nav-Downloads").performClick()
            compose.onNodeWithContentDescription("Download options").performClick()
            compose.onNodeWithText("Details", substring = false).performClick()
            compose.onNodeWithText(downloadTitle).assertExists()
            capture("download-details"); Espresso.pressBack()
            compose.onNodeWithTag("nav-Home").performClick()
            compose.onNode(hasSetTextAction()).performClick().performTextInput("https://www.youtube.com/watch?v=layout")
            capture("keyboard")
            Espresso.closeSoftKeyboard()
            compose.onNodeWithTag("nav-Library").performClick()
            compose.onNodeWithContentDescription("Search music library").performClick()
            compose.onNodeWithTag("music-search-field").performTextInput("Long track title")
            capture("search-keyboard")
            compose.onNodeWithTag("music-search-field").performImeAction()
            Espresso.closeSoftKeyboard()
            compose.waitUntil(5000) { compose.onAllNodesWithText("1 result").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("music-layout-control").assertDoesNotExist()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("floating-glass-navigation").fetchSemanticsNodes().isNotEmpty() }
            val searchBounds = compose.onNodeWithTag("music-search-field").fetchSemanticsNode().boundsInRoot
            val glassBounds = compose.onNodeWithTag("floating-glass-navigation").fetchSemanticsNode().boundsInRoot
            assertTrue("Search input must stay above navigation", searchBounds.bottom <= glassBounds.top)
            capture("search-results")
            compose.onNodeWithContentDescription("Clear music search").performScrollTo().performClick()
            compose.onNodeWithTag("music-search-field").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
            compose.onNodeWithContentDescription("Close music search").performClick()
            compose.onNodeWithTag("music-search-field").assertDoesNotExist()
            libraryVisible(hasText("All Songs")).performClick()
            libraryVisible(hasTestTag("music-track-count"))
            compose.onNodeWithTag("music-track-count").performScrollTo()
            val countLayout = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("music-track-count").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(countLayout) }
            assertTrue("Count must remain on one readable line", countLayout.isNotEmpty() && countLayout.all { it.lineCount == 1 && !it.hasVisualOverflow })
            listOf("Grid", "Compact").forEach { layout ->
                libraryVisible(hasContentDescription("$layout layout")).performClick().assertIsOn()
            }
            capture("library-layout-controls")
            compose.onNodeWithTag("music-library-list").performScrollToIndex(0)
            libraryVisible(hasText("Playlists")).performClick()
            libraryVisible(hasText(playlistName)).performClick()
            compose.onNodeWithTag("music-layout-control").assertDoesNotExist()
            capture("playlist")
            libraryVisible(hasText("Play all")).performClick()
            compose.waitUntil(15000) { MusicPlayback.state.value.playing }
            compose.onNodeWithContentDescription("Pause").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("Playback position").assertExists()
            capture("now-playing")
            compose.onNodeWithContentDescription("Playback position").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(5000f) }
            compose.waitUntil(5000) { kotlin.math.abs(MusicPlayback.state.value.position - 5000) < 1500 }
            capture("slider-seek")
            compose.onNodeWithContentDescription("Media volume").performScrollTo().assertIsDisplayed()
            if (!audio.isVolumeFixed) {
                val maximum = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                val minimum = audio.getStreamMinVolume(android.media.AudioManager.STREAM_MUSIC)
                val target = if (originalVolume > minimum) originalVolume - 1 else (minimum + 1).coerceAtMost(maximum)
                compose.onNodeWithContentDescription("Media volume").performSemanticsAction(SemanticsActions.SetProgress) { it(target.toFloat()) }
                compose.waitUntil(5000) { audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == target }
                audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, originalVolume, 0)
            }
            compose.onNodeWithText("Queue", useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithText("Player queue").assertIsDisplayed(); capture("queue")
            Espresso.pressBack()
            compose.onNodeWithText("Timer", useUnmergedTree = true).performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performScrollTo().performClick().performTextInput("30")
            capture("timer-keyboard")
            Espresso.closeSoftKeyboard(); Espresso.pressBack()
            compose.onNodeWithContentDescription("Minimize player").performScrollTo().performClick()
            val mini = compose.onNodeWithTag("music-mini-player").fetchSemanticsNode().boundsInRoot
            val nav = compose.onNodeWithTag("floating-glass-navigation").fetchSemanticsNode().boundsInRoot
            assertTrue("Mini-player overlaps navigation", mini.bottom <= nav.top)
            for (destination in destinations) {
                compose.onNodeWithTag("nav-$destination").performClick()
                labelsFit(); compose.onNodeWithTag("music-mini-player").assertIsDisplayed()
            }
            capture("mini-player")
            compose.onNodeWithTag("nav-Home").performClick()
            compose.onNode(hasSetTextAction()).assertTextContains("https://www.youtube.com/watch?v=layout")
            compose.runOnUiThread { MusicPlayback.stop() }
            compose.waitUntil(15000) { MusicPlayback.state.value.track == null }
            compose.activity.setContent {
                MaterialTheme(colorScheme = darkColorScheme(), typography = musicTypography()) {
                    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                        MediaScreen(MediaInfo("https://www.youtube.com/playlist?list=layout", downloadTitle, channel = "Long creator name ".repeat(6), playlist = true, count = 1000,
                            entries = List(1000) { n -> MediaInfo("https://www.youtube.com/watch?v=$n", "Item $n " + downloadTitle, duration = 180) }),
                            true, {}, "320", {}, preferences, {})
                    }
                }
            }
            compose.onNodeWithText("Download playlist").assertIsDisplayed()
            capture("media-preview")
        } catch (failure: Throwable) {
            runCatching { capture("failure") }
            throw failure
        } finally {
            if (!audio.isVolumeFixed) audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, originalVolume, 0)
            compose.runOnUiThread { MusicPlayback.stop() }
            compose.waitUntil(15000) { MusicPlayback.state.value.track == null }
            downloads.value = oldDownloads
            runBlocking { MusicRepository.rescan() }
            MusicRepository.update { before }
            compose.runOnUiThread { DownloadStore.savePrefs(preferences) }
            File(compose.activity.filesDir, "music/imports/tests/compact-layout.wav").delete()
        }
    }
}
