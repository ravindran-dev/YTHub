package dev.ravindran.ythub.mobile

import android.app.KeyguardManager
import android.os.PowerManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.FileInputStream

/** Opt-in because this operates the physical screen and native system media controls. */
class MusicBackgroundTest {
    @Test fun physicalMp3ContinuesAtHomeAndWithScreenOff() {
        val i = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Explicit device check only", InstrumentationRegistry.getArguments().getString("physicalBackground") == "true")
        fun shell(command: String) = i.uiAutomation.executeShellCommand(command).use { FileInputStream(it.fileDescriptor).readBytes().toString(Charsets.UTF_8) }
        fun await(condition: () -> Boolean) {
            val end = android.os.SystemClock.elapsedRealtime() + 15000
            while (!condition() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(50)
            assertTrue("Background state timeout: ${MusicPlayback.diagnostics}", condition())
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        await { MusicRepository.ready.value }
        val before = MusicRepository.state.value
        val power = i.targetContext.getSystemService(PowerManager::class.java)
        try {
            val track = before.tracks.first { it.available && it.format == "MP3" && it.durationMs > 30000 }
            i.runOnMainSync {
                MusicRepository.settings(before.settings.copy(background = true, speed = 1f))
                MusicPlayback.play(listOf(track.id))
            }
            await { MusicPlayback.state.value.playing && MusicPlayback.state.value.position > 200 }
            shell("input keyevent KEYCODE_HOME")
            val home = MusicPlayback.state.value.position
            Thread.sleep(10000)
            assertTrue("Home interrupted playback: ${MusicPlayback.diagnostics}", MusicPlayback.state.value.playing && MusicPlayback.state.value.position - home > 8000)
            if (power.isInteractive) shell("input keyevent KEYCODE_POWER")
            val off = MusicPlayback.state.value.position
            Thread.sleep(10000)
            val keyguard = i.targetContext.getSystemService(KeyguardManager::class.java)
            android.util.Log.i("YTHubDeviceCheck", "Screen interactive=${power.isInteractive}; keyguardLocked=${keyguard.isKeyguardLocked}; deviceSecure=${keyguard.isDeviceSecure}; off-screen progress=${MusicPlayback.state.value.position - off}; ${MusicPlayback.diagnostics}")
            assertFalse("Screen must be off", power.isInteractive)
            assertTrue("Screen off interrupted playback: ${MusicPlayback.diagnostics}", MusicPlayback.state.value.playing && MusicPlayback.state.value.position - off > 8000)
            shell("cmd media_session dispatch pause"); await { !MusicPlayback.state.value.playing }
            shell("cmd media_session dispatch play"); await { MusicPlayback.state.value.playing }
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
            i.runOnMainSync { MusicPlayback.stop() }
            await { MusicPlayback.state.value.track == null }
            MusicRepository.update { before }
            scenario.close()
        }
    }
}
