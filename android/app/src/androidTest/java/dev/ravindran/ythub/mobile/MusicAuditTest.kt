package dev.ravindran.ythub.mobile

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MusicAuditTest {
    @get:org.junit.Rule val activity = androidx.test.ext.junit.rules.ActivityScenarioRule(MainActivity::class.java)
    @org.junit.Before fun repositoryReady() {
        val end = android.os.SystemClock.elapsedRealtime() + 15000
        while (!MusicRepository.ready.value && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(50)
        assertTrue(MusicRepository.ready.value)
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15000
        while (!condition() && SystemClock.elapsedRealtime() < end) Thread.sleep(50)
        assertTrue("Media state timeout: ${MusicPlayback.state.value}", condition())
    }
    @Test fun thousandEntryQueueAndActualTransport() = runBlocking<Unit> {
        val before = MusicRepository.state.value
        val uri = MusicTestFixtures.audio(instrumentation.targetContext, "audit")
        try {
            val tracks = (0 until 1000).map { LocalTrack("audit-$it", uri.toString(), "Track $it", artist = "Artist ${it % 20}", durationMs = 20000) }
            MusicRepository.update { it.copy(tracks = tracks, queue = emptyList(), settings = it.settings.copy(speed = 1f, autoNext = true)) }
            val start = SystemClock.elapsedRealtime()
            main { MusicPlayback.play(tracks.map { it.id }) }
            await { MusicPlayback.state.value.playing && MusicPlayback.state.value.queue.size == 1000 }
            val elapsed = SystemClock.elapsedRealtime() - start
            android.util.Log.i("YTHubAudit", "1000-entry queue ready in ${elapsed}ms")
            assertTrue("1000-entry queue startup took ${elapsed}ms", elapsed < 10000)
            main { MusicPlayback.toggle() }; await { !MusicPlayback.state.value.playing }
            val paused = MusicPlayback.state.value.position; Thread.sleep(600)
            assertTrue(kotlin.math.abs(MusicPlayback.state.value.position - paused) < 100)
            main { MusicPlayback.toggle(); MusicPlayback.seek(6000) }; await { MusicPlayback.state.value.position >= 6000 }
            main { MusicRepository.settings(MusicRepository.state.value.settings.copy(speed = 2f)) }
            val normal = MusicPlayback.state.value.position; val wall = SystemClock.elapsedRealtime()
            await { MusicPlayback.state.value.position - normal > 2500 }
            assertTrue("2x playback must advance faster than wall time", SystemClock.elapsedRealtime() - wall < 2300)
            val audio = instrumentation.targetContext.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            val focus = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener { }.build()
            try {
                main { assertEquals(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(focus)) }
                await { !MusicPlayback.state.value.playing }
            } finally { main { audio.abandonAudioFocusRequest(focus) } }
            await { MusicPlayback.state.value.playing }
            main { MusicPlayback.next() }; await { MusicPlayback.state.value.index == 1 }
            main { MusicPlayback.seek(0); MusicPlayback.previous() }; await { MusicPlayback.state.value.index == 0 }
            main { MusicPlayback.shuffle() }; await { MusicPlayback.state.value.shuffle }
            main { MusicPlayback.shuffle(); MusicPlayback.repeat() }; await { MusicPlayback.state.value.repeat == 1 }
            main { MusicPlayback.seek(18000) }; await { MusicPlayback.state.value.position >= 18000 }; await { MusicPlayback.state.value.position < 5000 }
            assertEquals(0, MusicPlayback.state.value.index)
            main { MusicPlayback.repeat(); MusicPlayback.repeat(); MusicPlayback.seek(19500) }
            await { MusicPlayback.state.value.index == 1 }
            main { MusicPlayback.clearUpcoming(); MusicPlayback.repeat(); MusicPlayback.repeat(); MusicPlayback.seek(18000) }
            await { MusicPlayback.state.value.position >= 18000 }; await { MusicPlayback.state.value.index == 0 }
            main { MusicPlayback.repeat(); MusicPlayback.next() }; await { MusicPlayback.state.value.index == 1 }
            main { MusicPlayback.seek(19500) }
            await { !MusicPlayback.state.value.playing && MusicPlayback.state.value.position >= 19500 }
            // A disappearing provider file must produce a useful real engine error.
            java.io.File(instrumentation.targetContext.filesDir, "music/imports/tests/audit.wav").delete()
            main { MusicPlayback.play(listOf(tracks.first().id)) }
            await { MusicPlayback.lastPlaybackError.contains("Cannot play this file") }
        } finally {
            main { MusicPlayback.stop(); MusicPlayback.error.value = null }
            await { MusicPlayback.state.value.track == null }
            MusicRepository.update { before }
        }
    }
}
