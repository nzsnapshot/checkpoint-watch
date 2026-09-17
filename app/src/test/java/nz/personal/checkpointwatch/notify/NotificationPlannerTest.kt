package nz.personal.checkpointwatch.notify

import nz.personal.checkpointwatch.data.NewReport
import nz.personal.checkpointwatch.model.ReportType
import nz.personal.checkpointwatch.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

class NotificationPlannerTest {

    private val now: Instant = Instant.parse("2026-09-18T09:00:00Z")
    private val on = Settings(notify = true)

    private fun report(
        type: ReportType = ReportType.CHECKPOINT,
        typeLabel: String = "CHECKPOINT",
        road: String? = "Lincoln Road",
        suburb: String? = "HENDERSON",
        minutesAgo: Long = 5,
    ) = NewReport(
        type = type,
        typeLabel = typeLabel,
        road = road,
        suburb = suburb,
        at = now.minus(Duration.ofMinutes(minutesAgo)),
    )

    @Test
    fun `no plan when notifications are off`() {
        assertNull(NotificationPlanner.plan(listOf(report()), Settings(notify = false), now))
    }

    @Test
    fun `no plan when there are no new reports`() {
        assertNull(NotificationPlanner.plan(emptyList(), on, now))
    }

    @Test
    fun `keeps only the chosen types`() {
        val reports = listOf(
            report(type = ReportType.CHECKPOINT),
            report(type = ReportType.CRASH, typeLabel = "CRASH", road = "Pakuranga Road", suburb = null),
        )
        val settings = on.copy(notifyTypes = setOf(ReportType.CRASH))

        val plan = NotificationPlanner.plan(reports, settings, now)

        assertEquals(listOf("Crash – Pakuranga Road"), plan?.lines)
    }

    @Test
    fun `no plan when every report is of an unwatched type`() {
        val settings = on.copy(notifyTypes = setOf(ReportType.SPEED_CAMERA))

        assertNull(NotificationPlanner.plan(listOf(report()), settings, now))
    }

    @Test
    fun `keeps only watched suburbs when any are watched`() {
        val reports = listOf(
            report(suburb = "HENDERSON"),
            report(road = "Great South Road", suburb = "PAPAKURA"),
            report(road = "Queen Street", suburb = null),
        )
        val settings = on.copy(watchedSuburbs = setOf("PAPAKURA"))

        val plan = NotificationPlanner.plan(reports, settings, now)

        assertEquals(listOf("Checkpoint – Great South Road, PAPAKURA"), plan?.lines)
    }

    @Test
    fun `every suburb counts when none are watched`() {
        val reports = listOf(report(suburb = "HENDERSON"), report(road = "Queen Street", suburb = null))

        val plan = NotificationPlanner.plan(reports, on, now)

        assertEquals(2, plan?.lines?.size)
    }

    @Test
    fun `drops reports older than two hours`() {
        val reports = listOf(
            report(road = "Fresh Road", minutesAgo = 119),
            report(road = "Stale Road", minutesAgo = 121),
        )

        val plan = NotificationPlanner.plan(reports, on, now)

        assertEquals(listOf("Checkpoint – Fresh Road, HENDERSON"), plan?.lines)
    }

    @Test
    fun `a single report titles itself`() {
        val plan = NotificationPlanner.plan(listOf(report()), on, now)

        assertEquals("Checkpoint – Lincoln Road, HENDERSON", plan?.title)
        assertEquals(listOf("Checkpoint – Lincoln Road, HENDERSON"), plan?.lines)
    }

    @Test
    fun `several reports are counted in the title`() {
        val reports = listOf(report(), report(road = "Great North Road"))

        val plan = NotificationPlanner.plan(reports, on, now)

        assertEquals("2 new reports", plan?.title)
    }

    @Test
    fun `eight reports become six lines and a more line`() {
        val reports = (1..8).map { report(road = "Road $it") }

        val plan = NotificationPlanner.plan(reports, on, now)

        assertEquals("8 new reports", plan?.title)
        assertEquals(7, plan?.lines?.size)
        assertEquals("Checkpoint – Road 1, HENDERSON", plan?.lines?.first())
        assertEquals("Checkpoint – Road 6, HENDERSON", plan?.lines?.get(5))
        assertEquals("+2 more", plan?.lines?.last())
    }

    @Test
    fun `six reports are all shown`() {
        val reports = (1..6).map { report(road = "Road $it") }

        assertEquals(6, NotificationPlanner.plan(reports, on, now)?.lines?.size)
    }

    @Test
    fun `lines omit the parts a report does not have`() {
        val reports = listOf(
            report(type = ReportType.POLICE_PRESENCE, typeLabel = "POLICE", road = "Te Irirangi Drive", suburb = null),
            report(type = ReportType.SPEED_CAMERA, typeLabel = "SPEED CAMERA", road = null, suburb = "MANUKAU"),
            report(type = ReportType.CRASH, typeLabel = "CRASH", road = null, suburb = null),
        )
        val settings = on.copy(notifyTypes = ReportType.entries.toSet())

        val plan = NotificationPlanner.plan(reports, settings, now)

        assertEquals(
            listOf(
                "Police presence – Te Irirangi Drive",
                "Speed camera – MANUKAU",
                "Crash",
            ),
            plan?.lines,
        )
    }

    @Test
    fun `other reports use their own label, or Report when they have none`() {
        val reports = listOf(
            report(type = ReportType.OTHER, typeLabel = "ROAD WORKS", road = "Dominion Road", suburb = null),
            report(type = ReportType.OTHER, typeLabel = "  ", road = "Sandringham Road", suburb = null),
        )
        val settings = on.copy(notifyTypes = setOf(ReportType.OTHER))

        val plan = NotificationPlanner.plan(reports, settings, now)

        assertEquals(
            listOf("Road Works – Dominion Road", "Report – Sandringham Road"),
            plan?.lines,
        )
    }
}
