package dev.ravindran.ythub.mobile

import org.junit.Assert.*
import org.junit.Test

class ModelsTest {
    @Test fun acceptsYouTubeLinks() {
        assertEquals("https://youtu.be/abc", validYouTubeUrl(" https://youtu.be/abc "))
        assertEquals("https://www.youtube.com/playlist?list=abc", validYouTubeUrl("https://www.youtube.com/playlist?list=abc"))
    }
    @Test fun rejectsLookalikeAndUnsafeLinks() {
        listOf("http://youtube.com/watch?v=1", "https://youtube.com.evil.test/watch", "file:///etc/passwd", "https://youtube.com@evil.test/", "https://youtube.com:8080/").forEach {
            assertTrue("Expected rejection: $it", runCatching { validYouTubeUrl(it) }.isFailure)
        }
    }
    @Test fun rendersRealUnitsAndDurations() {
        assertEquals("1.0 MB", bytesLabel(1024 * 1024))
        assertEquals("1:01:01", durationLabel(3661))
        assertEquals("320 kbps", qualityLabel(true, "320"))
        assertEquals("Best available", qualityLabel(false, "best"))
    }
    @Test fun defaultsMatchMobileRequirements() {
        assertEquals("320", Preferences().audioQuality)
        assertEquals(2, Preferences().concurrency)
        assertEquals("Dark", Preferences().theme)
    }
}
