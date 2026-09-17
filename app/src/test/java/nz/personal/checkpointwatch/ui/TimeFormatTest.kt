package nz.personal.checkpointwatch.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZonedDateTime

class TimeFormatTest {

    private fun nz(s: String) = ZonedDateTime.parse(s).toInstant()

    // --- ago: seconds/minutes boundaries -----------------------------------------------------

    private val now = Instant.parse("2026-09-18T09:00:00Z")

    @Test
    fun `zero seconds is just now`() = assertEquals("just now", TimeFormat.ago(now, now))

    @Test
    fun `59 seconds is still just now`() =
        assertEquals("just now", TimeFormat.ago(now.minusSeconds(59), now))

    @Test
    fun `60 seconds becomes 1 min ago`() =
        assertEquals("1 min ago", TimeFormat.ago(now.minusSeconds(60), now))

    @Test
    fun `59 minutes ago`() =
        assertEquals("59 min ago", TimeFormat.ago(now.minusSeconds(59 * 60L), now))

    @Test
    fun `60 minutes becomes 1 h ago`() =
        assertEquals("1 h ago", TimeFormat.ago(now.minusSeconds(60 * 60L), now))

    @Test
    fun `23 hours ago`() =
        assertEquals("23 h ago", TimeFormat.ago(now.minusSeconds(23 * 3600L), now))

    @Test
    fun `24 hours becomes yesterday`() =
        assertEquals("yesterday", TimeFormat.ago(now.minusSeconds(24 * 3600L), now))

    @Test
    fun `47 hours 59 minutes is still yesterday`() =
        assertEquals("yesterday", TimeFormat.ago(now.minusSeconds(47 * 3600L + 59 * 60L), now))

    @Test
    fun `48 hours becomes 2 days ago`() =
        assertEquals("2 days ago", TimeFormat.ago(now.minusSeconds(48 * 3600L), now))

    @Test
    fun `3 days ago`() =
        assertEquals("3 days ago", TimeFormat.ago(now.minusSeconds(3 * 86400L), now))

    @Test
    fun `a future time reads as just now`() =
        assertEquals("just now", TimeFormat.ago(now.plusSeconds(3600), now))

    // --- clock: NZ local time, lower-case am-pm, no leading zero on the hour -----------------

    @Test
    fun `evening time in NZDT-free September`() =
        assertEquals("9:30 pm", TimeFormat.clock(nz("2026-09-18T21:30:00+12:00")))

    @Test
    fun `single-digit hour keeps zero-padded minutes`() =
        assertEquals("9:05 am", TimeFormat.clock(nz("2026-09-18T09:05:00+12:00")))

    @Test
    fun `midnight is 12 am, not 0 am`() =
        assertEquals("12:05 am", TimeFormat.clock(nz("2026-09-18T00:05:00+12:00")))

    @Test
    fun `noon is 12 pm, not 0 pm`() =
        assertEquals("12:00 pm", TimeFormat.clock(nz("2026-09-18T12:00:00+12:00")))

    @Test
    fun `one minute before noon`() =
        assertEquals("11:59 am", TimeFormat.clock(nz("2026-09-18T11:59:00+12:00")))

    // --- dayHeader: Today / Yesterday / date, by NZ calendar day, across midnight ------------

    private val friday0900 = nz("2026-09-18T09:00:00+12:00")

    @Test
    fun `same NZ calendar day is Today`() =
        assertEquals("Today", TimeFormat.dayHeader(nz("2026-09-18T00:05:00+12:00"), friday0900))

    @Test
    fun `previous NZ calendar day is Yesterday`() =
        assertEquals("Yesterday", TimeFormat.dayHeader(nz("2026-09-17T23:50:00+12:00"), friday0900))

    @Test
    fun `older days use the EEE d MMM pattern`() {
        assertEquals("Wed 16 Sep", TimeFormat.dayHeader(nz("2026-09-16T20:00:00+12:00"), friday0900))
        assertEquals("Tue 15 Sep", TimeFormat.dayHeader(nz("2026-09-15T10:00:00+12:00"), friday0900))
    }

    @Test
    fun `a report just before NZ midnight is Yesterday for a now just after it`() {
        val justAfterMidnight = nz("2026-09-18T00:10:00+12:00")
        val justBeforeMidnight = nz("2026-09-17T23:50:00+12:00")
        // Only 20 minutes apart in real time, but different NZ calendar days.
        assertEquals("Yesterday", TimeFormat.dayHeader(justBeforeMidnight, justAfterMidnight))
    }

    @Test
    fun `both sides of NZ midnight on the same calendar day are Today`() {
        val lateEvening = nz("2026-09-17T23:50:00+12:00")
        val earlyThatMorning = nz("2026-09-17T00:05:00+12:00")
        assertEquals("Today", TimeFormat.dayHeader(earlyThatMorning, lateEvening))
    }

    // --- freshness: <2 h FRESH, 2-6 h OLDER (2 h and 6 h count as OLDER), >6 h OLD -----------

    @Test
    fun `just under 2 hours is FRESH`() =
        assertEquals(Freshness.FRESH, TimeFormat.freshness(now.minusSeconds(2 * 3600L - 1), now))

    @Test
    fun `exactly 2 hours is OLDER`() =
        assertEquals(Freshness.OLDER, TimeFormat.freshness(now.minusSeconds(2 * 3600L), now))

    @Test
    fun `4 hours is OLDER`() =
        assertEquals(Freshness.OLDER, TimeFormat.freshness(now.minusSeconds(4 * 3600L), now))

    @Test
    fun `exactly 6 hours is still OLDER`() =
        assertEquals(Freshness.OLDER, TimeFormat.freshness(now.minusSeconds(6 * 3600L), now))

    @Test
    fun `just over 6 hours is OLD`() =
        assertEquals(Freshness.OLD, TimeFormat.freshness(now.minusSeconds(6 * 3600L + 1), now))

    @Test
    fun `a future report is FRESH`() =
        assertEquals(Freshness.FRESH, TimeFormat.freshness(now.plusSeconds(600), now))
}
