package dev.ravindran.ythub.mobile

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class MusicPersistenceTest {
    @Test fun migratesV1AndRestoresDefaultsWithoutLosingIdentityOrOrder() {
        val state = MusicPersistence.decode("""{"version":1,"queue":["b","a","b"],"queueIndex":2,"position":7300,"settings":{"speed":1.5},"favorites":["a"],"playlists":[{"id":"p","name":"Mix","tracks":["b","a"]}]}""")
        assertEquals(2, state.version); assertEquals(listOf("b", "a", "b"), state.queue)
        assertEquals(2, state.queueIndex); assertEquals(7300L, state.position)
        assertEquals("Grid", state.settings.libraryLayout); assertTrue(state.settings.background)
        assertEquals(listOf("b", "a"), state.playlists.single().tracks)
    }
    @Test fun rejectsFutureCorruptAndNullData() {
        listOf("{", """{"version":99}""", """{"tracks":null}""").forEach { assertTrue(runCatching { MusicPersistence.decode(it) }.isFailure) }
    }
    @Test fun clampsInvalidRuntimePreferences() {
        val state = MusicPersistence.decode("""{"position":-1,"queueIndex":-2,"repeat":99,"settings":{"speed":99,"bass":9000}}""")
        assertEquals(0L, state.position); assertEquals(0, state.queueIndex); assertEquals(2, state.repeat)
        assertEquals(1f, state.settings.speed); assertEquals(1000, state.settings.bass)
    }
    @Test fun artworkCacheEvictsOldestAndStaysWithinBudget() {
        val dir = Files.createTempDirectory("music-artwork").toFile()
        try {
            repeat(10) { n -> java.io.File(dir, "$n.jpg").apply { writeBytes(ByteArray(1024)); setLastModified(n * 1000L) } }
            MusicPersistence.trimArtwork(dir, 3072)
            assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 3072)
            assertTrue(java.io.File(dir, "9.jpg").exists()); assertFalse(java.io.File(dir, "0.jpg").exists())
        } finally { dir.deleteRecursively() }
    }
}
