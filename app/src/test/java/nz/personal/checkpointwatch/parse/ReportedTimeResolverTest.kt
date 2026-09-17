package nz.personal.checkpointwatch.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZonedDateTime

class ReportedTimeResolverTest {

    private fun nz(s: String) = ZonedDateTime.parse(s).toInstant()

    @Test
    fun sameEvening() = assertEquals(
        nz("2026-09-17T23:55:00+12:00"),
        ReportedTimeResolver.resolve("11:55PM", Instant.ofEpochSecond(1789646182)),
    )

    @Test
    fun justAfterMidnightStaysToday() = assertEquals(
        nz("2026-09-18T00:00:00+12:00"),
        ReportedTimeResolver.resolve("12:00AM", Instant.ofEpochSecond(1789646506)),
    )

    @Test
    fun lateReportCrossesMidnightBackwards() = assertEquals(
        nz("2026-09-17T23:50:00+12:00"),
        ReportedTimeResolver.resolve("11:50PM", nz("2026-09-18T00:05:00+12:00")),
    )

    @Test
    fun slightlyAheadOfPostIsAllowed() = assertEquals(
        nz("2026-09-18T00:10:00+12:00"),
        ReportedTimeResolver.resolve("12:10AM", nz("2026-09-18T00:01:00+12:00")),
    )

    @Test
    fun trailingNoteIgnored() = assertEquals(
        nz("2026-09-17T21:30:00+12:00"),
        ReportedTimeResolver.resolve("9:30PM (Pictured) ", Instant.ofEpochSecond(1789637686)),
    )

    @Test
    fun dotSeparatorAndSpaces() = assertEquals(
        nz("2026-09-17T20:14:00+12:00"),
        ReportedTimeResolver.resolve("8.14 pm", Instant.ofEpochSecond(1789633047)),
    )

    @Test
    fun hourOnly() = assertEquals(
        nz("2026-09-17T21:00:00+12:00"),
        ReportedTimeResolver.resolve("9pm", Instant.ofEpochSecond(1789637686)),
    )

    @Test
    fun daylightSavingGapDoesNotThrow() = // 2026-09-27 02:30 does not exist in NZ
        assertNotNull(ReportedTimeResolver.resolve("2:30AM", nz("2026-09-27T03:20:00+13:00")))

    @Test
    fun garbage() = assertNull(ReportedTimeResolver.resolve("soon", Instant.now()))

    @Test
    fun invalidHour() = assertNull(ReportedTimeResolver.resolve("25:00PM", Instant.now()))
}
