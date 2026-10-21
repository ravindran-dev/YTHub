package dev.ravindran.ythub.mobile

import androidx.core.content.FileProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class MusicOfflineTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    @Test fun actualMp3DecodesWithoutAnyNetwork() = runBlocking<Unit> {
        val i = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Requires generated MP3 and externally disabled emulator networks", InstrumentationRegistry.getArguments().getString("offlineMp3") == "true")
        fun await(condition: () -> Boolean) {
            val end = android.os.SystemClock.elapsedRealtime() + 15000
            while (!condition() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(50)
            assertTrue("Offline MP3 state timeout: ${MusicPlayback.diagnostics}", condition())
        }
        await { MusicRepository.ready.value }
        val before = MusicRepository.state.value
        val source = File(i.targetContext.filesDir, "music/imports/tests/offline.mp3")
        try {
            assertTrue(source.exists())
            val uri = FileProvider.getUriForFile(i.targetContext, "${i.targetContext.packageName}.files", source)
            MusicRepository.importAudio(listOf(uri))
            val track = MusicRepository.state.value.tracks.first { it.uri == uri.toString() }
            assertEquals("MP3", track.format); assertTrue(track.durationMs >= 7900)
            i.runOnMainSync { MusicPlayback.play(listOf(track.id)) }
            await { MusicPlayback.state.value.playing && MusicPlayback.state.value.position > 1000 }
            i.runOnMainSync { MusicPlayback.seek(4000) }; await { MusicPlayback.state.value.position >= 4000 }
            i.runOnMainSync { MusicPlayback.toggle() }; await { !MusicPlayback.state.value.playing }
            i.runOnMainSync { MusicPlayback.toggle() }; await { MusicPlayback.state.value.playing }
        } finally {
            i.runOnMainSync { MusicPlayback.stop() }; await { MusicPlayback.state.value.track == null }
            MusicRepository.update { before }; source.delete()
        }
    }
}
