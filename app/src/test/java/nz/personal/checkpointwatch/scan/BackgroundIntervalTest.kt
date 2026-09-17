package nz.personal.checkpointwatch.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundIntervalTest {

    @Test
    fun `the allowed intervals are kept as they are`() {
        assertEquals(listOf(0, 15, 30, 60, 120), ALLOWED_BACKGROUND_MINUTES.map(::nearestBackgroundMinutes))
    }

    @Test
    fun `anything else lands on the nearest allowed interval`() {
        assertEquals(0, nearestBackgroundMinutes(-5))
        assertEquals(0, nearestBackgroundMinutes(7))
        assertEquals(15, nearestBackgroundMinutes(8))
        assertEquals(15, nearestBackgroundMinutes(20))
        assertEquals(30, nearestBackgroundMinutes(40))
        assertEquals(60, nearestBackgroundMinutes(50))
        assertEquals(120, nearestBackgroundMinutes(600))
    }
}
