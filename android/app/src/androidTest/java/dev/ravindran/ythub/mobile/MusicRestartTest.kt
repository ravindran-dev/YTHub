package dev.ravindran.ythub.mobile

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Two separately launched instrumentations, with external SIGKILL between them. */
class MusicRestartTest {
    @Test fun processRestartRestoresDurableState() = runBlocking<Unit> {
        val i = InstrumentationRegistry.getInstrumentation()
        val phase = InstrumentationRegistry.getArguments().getString("restartPhase")
        assumeTrue("Two-phase audit only", phase == "seed" || phase == "check")
        fun await(condition: () -> Boolean) {
            val end = android.os.SystemClock.elapsedRealtime() + 15000
            while (!condition() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(50)
            assertTrue("Restart state timeout", condition())
        }
        await { MusicRepository.ready.value }
        val backup = File(i.targetContext.cacheDir, "music-restart-before.json")
        if (phase == "seed") {
            backup.writeText(Gson().toJson(MusicRepository.state.value))
            val uri = MusicTestFixtures.audio(i.targetContext, "restart")
            MusicRepository.importAudio(listOf(uri))
            val id = MusicRepository.state.value.tracks.first { it.uri == uri.toString() }.id
            MusicRepository.playlist("Restart audit", listOf(id, id), "restart-audit")
            if (id !in MusicRepository.state.value.favorites) MusicRepository.favorite(id)
            MusicRepository.played(id)
            i.runOnMainSync {
                MusicRepository.settings(MusicRepository.state.value.settings.copy(restoreTrack = true, rememberPosition = true, speed = 1.25f))
                MusicPlayback.play(listOf(id, id))
            }
            await { MusicPlayback.state.value.playing }
            i.runOnMainSync { MusicPlayback.seek(7000); MusicPlayback.toggle() }
            await { !MusicPlayback.state.value.playing && MusicPlayback.state.value.position >= 7000 }
            await { runCatching { MusicPersistence.decode(File(i.targetContext.filesDir, "music/library-v1.json").readText()).let { it.position >= 7000 && it.queue.size == 2 } }.getOrDefault(false) }
            if (InstrumentationRegistry.getArguments().getString("waitForKill") == "true") {
                File(i.targetContext.cacheDir, "music-restart-ready").writeText("ready")
                Thread.sleep(60000)
                fail("External SIGKILL was not delivered")
            }
        } else {
            try {
                val saved = MusicRepository.state.value
                val playlist = saved.playlists.first { it.id == "restart-audit" }
                assertEquals(playlist.tracks, saved.queue); assertEquals(2, saved.queue.size)
                assertTrue(saved.queue.first() in saved.favorites); assertTrue(saved.queue.first() in saved.history)
                assertEquals(1.25f, saved.settings.speed); assertTrue(saved.position >= 7000)
                i.runOnMainSync { MusicPlayback.restore() }
                await { MusicPlayback.state.value.queue == playlist.tracks && MusicPlayback.state.value.position >= 7000 }
                assertFalse("Restore must not start unexpected audio", MusicPlayback.state.value.playing)
            } finally {
                i.runOnMainSync { MusicPlayback.stop() }
                MusicRepository.update { MusicPersistence.decode(backup.readText()) }
                backup.delete(); File(i.targetContext.cacheDir, "music-restart-ready").delete(); File(i.targetContext.filesDir, "music/imports/tests/restart.wav").delete()
            }
        }
    }
}
