package dev.ravindran.ythub.mobile

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.random.Random

class MusicRulesTest {
    private fun track(id: String, available: Boolean = true) = LocalTrack(id, "content://audio/$id", "Same title", available = available)
    @Test fun scanDeduplicatesByUriIdentityAndKeepsDistinctTitles() {
        val tracks = MusicRules.merge(listOf(track("a"), track("b")), listOf(track("a").copy(artist = "Updated")))
        assertEquals(listOf("a", "b"), tracks.map { it.id }); assertEquals("Updated", tracks[0].artist)
    }
    @Test fun restoreDropsMissingTracksAndPreservesCurrentOccurrence() {
        val (queue, index) = MusicRules.restore(listOf("missing", "a", "b", "a"), 3, listOf(track("a"), track("b", false)))
        assertEquals(listOf("a", "a"), queue); assertEquals(1, index)
    }
    @Test fun restorationHandlesAnUnavailableCurrentTrack() {
        assertEquals(listOf("b") to 0, MusicRules.restore(listOf("gone", "b"), 0, listOf(track("b"))))
        assertEquals(emptyList<String>() to 0, MusicRules.restore(listOf("gone"), 0, emptyList()))
    }
    @Test fun shuffleOnlyTouchesUpcomingAndPreservesMultiplicity() {
        val queue = listOf("a", "b", "c", "d", "d", "e")
        val shuffled = MusicRules.shuffleUpcoming(queue, 1, Random(8))
        assertEquals(queue.take(2), shuffled.take(2)); assertEquals(queue.sorted(), shuffled.sorted()); assertNotEquals(queue, shuffled)
    }
    @Test fun repeatModesAndQueueBoundaries() {
        assertNull(MusicRules.next(2, 3, 0)); assertEquals(2, MusicRules.next(2, 3, 1)); assertEquals(0, MusicRules.next(2, 3, 2))
        assertEquals(1, MusicRules.next(0, 3, 0)); assertNull(MusicRules.next(0, 0, 2))
    }
    @Test fun previousRestartsAfterThreeSecondsOtherwiseMovesBack() {
        assertEquals(2 to 0L, MusicRules.previous(2, 3001)); assertEquals(1 to 0L, MusicRules.previous(2, 2500)); assertEquals(0 to 0L, MusicRules.previous(0, 0))
    }
    @Test fun archiveRejectsTraversalAbsoluteAndWindowsPaths() {
        val root = File(System.getProperty("java.io.tmpdir"), "ythub-test")
        listOf("../escape.mp3", "folder/../../escape.mp3", "/tmp/escape.mp3", "C:/escape.mp3", "folder\\escape.mp3", "").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { MusicRules.archiveTarget(root, path) }
        }
        assertEquals(File(root, "album/song.mp3").canonicalFile, MusicRules.archiveTarget(root, "album/song.mp3"))
    }
    @Test fun audioDetectionDoesNotClassifyVideoOrZipAsMusic() {
        listOf("a.MP3", "a.m4a", "a.opus", "a.flac", "a.ogg", "a.aac", "a.wav").forEach { assertTrue(MusicRules.audioName(it)) }
        listOf("a.mp4", "a.zip", "a.mp3.exe").forEach { assertFalse(MusicRules.audioName(it)) }
    }
    @Test fun sleepDeadlineUsesElapsedTimeAndExpiresAtBoundary() {
        assertFalse(MusicRules.sleepExpired(0, 99999)); assertFalse(MusicRules.sleepExpired(60000, 59999)); assertTrue(MusicRules.sleepExpired(60000, 60000)); assertTrue(MusicRules.sleepExpired(60000, 90000))
    }
    @Test fun persistentStateRoundTripsQueueSettingsPlaylistsAndHistory() {
        val state = MusicState(tracks = listOf(track("a")), queue = listOf("a", "a"), queueIndex = 1, position = 12345,
            playlists = listOf(LocalPlaylist("stable-id", "Favorites", listOf("a", "a"))), favorites = setOf("a"),
            history = mapOf("a" to PlayRecord(3, 123456)), settings = PlayerPreferences(speed = 1.5f, autoAdd = false, bass = 500))
        assertEquals(state, Gson().fromJson(Gson().toJson(state), MusicState::class.java))
    }
    @Test fun parsesMultipleTimestampsFractionsAndIgnoresMetadataTags() {
        val lines = parseLyrics("[ar:Artist]\n[00:01.50][01:02.003]First\n[00:03]Second\nPlain text")
        assertEquals(listOf(1500L, 3000L, 62003L), lines.map { it.timeMs }); assertEquals(listOf("First", "Second", "First"), lines.map { it.text })
        assertTrue(parseLyrics("Plain text").isEmpty())
    }
    @Test fun embeddedLyricsReadUsltWithoutReadingAudioPayload() {
        val text = "These are local lyrics"
        val content = byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0) + text.toByteArray()
        val frame = "USLT".toByteArray() + byteArrayOf(0, 0, 0, content.size.toByte(), 0, 0) + content
        val tag = "ID3".toByteArray() + byteArrayOf(3, 0, 0, 0, 0, 0, frame.size.toByte()) + frame
        assertEquals(text, EmbeddedLyrics.read(tag.inputStream()))
        assertEquals("", EmbeddedLyrics.read(byteArrayOf(1, 2, 3).inputStream()))
        assertEquals("", EmbeddedLyrics.read(("ID3".toByteArray() + byteArrayOf(3, 0, 0, 127, 127, 127, 127)).inputStream()))
    }

    @Test fun filteredPlaylistEditsResolveOriginalOccurrence() {
        assertEquals(2, MusicRules.occurrenceIndex(listOf("hidden", "a", "b"), listOf("b"), 0))
        assertEquals(3, MusicRules.occurrenceIndex(listOf("hidden", "a", "b", "a"), listOf("a", "a"), 1))
        assertEquals(-1, MusicRules.occurrenceIndex(listOf("a"), emptyList(), 0))
    }

}
