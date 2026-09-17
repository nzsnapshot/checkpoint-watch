package nz.personal.checkpointwatch.ui

import nz.personal.checkpointwatch.data.ReportRow
import nz.personal.checkpointwatch.data.ScanTrigger
import nz.personal.checkpointwatch.data.ScrapeStatus
import nz.personal.checkpointwatch.model.ReportType
import nz.personal.checkpointwatch.scan.ScanSummary
import nz.personal.checkpointwatch.settings.Settings
import nz.personal.checkpointwatch.ui.home.BannerBuilder
import nz.personal.checkpointwatch.ui.home.BannerUi
import nz.personal.checkpointwatch.ui.home.HomeStateBuilder
import nz.personal.checkpointwatch.ui.home.ListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HomeStateBuilderTest {

    private val now = Instant.parse("2026-09-18T09:00:00Z")

    private fun row(
        id: Long,
        postId: String,
        indexInPost: Int = 0,
        type: String = "CHECKPOINT",
        typeLabel: String = "Checkpoint",
        road: String? = "Lincoln Road",
        suburb: String? = "Henderson",
        details: String = "",
        reportedTimeText: String? = null,
        reportedAt: Long? = null,
        source: String? = null,
        postCreatedAt: Long,
        postCreatedAtApprox: Boolean = false,
        postUrl: String = "https://www.facebook.com/CheckpointNZ/posts/$postId",
        postText: String = "post text",
        gapBefore: Boolean = false,
        firstSeenAt: Long = postCreatedAt,
    ) = ReportRow(
        id = id,
        postId = postId,
        indexInPost = indexInPost,
        type = type,
        typeLabel = typeLabel,
        road = road,
        suburb = suburb,
        details = details,
        reportedTimeText = reportedTimeText,
        reportedAt = reportedAt,
        source = source,
        postCreatedAt = postCreatedAt,
        postCreatedAtApprox = postCreatedAtApprox,
        postUrl = postUrl,
        postText = postText,
        gapBefore = gapBefore,
        firstSeenAt = firstSeenAt,
    )

    private fun reportItems(built: HomeStateBuilder.Built) =
        built.items.filterIsInstance<ListItem.Report>().map { it.report }

    // --- totalReports -------------------------------------------------------------------------

    @Test
    fun `totalReports counts every row before filtering`() {
        val rows = listOf(
            row(1, "p1", type = "CHECKPOINT", postCreatedAt = now.toEpochMilli()),
            row(2, "p1", indexInPost = 1, type = "CRASH", postCreatedAt = now.toEpochMilli()),
        )
        val settings = Settings(hiddenTypes = setOf(ReportType.CRASH))

        val built = HomeStateBuilder.build(rows, settings, now, newSince = null)

        assertEquals(2, built.totalReports)
        assertEquals(1, reportItems(built).size)
    }

    // --- filters --------------------------------------------------------------------------

    @Test
    fun `hidden types are removed from the visible list`() {
        val rows = listOf(
            row(1, "p1", type = "CHECKPOINT", postCreatedAt = now.toEpochMilli()),
            row(2, "p2", type = "CRASH", postCreatedAt = now.toEpochMilli() - 1000),
        )
        val settings = Settings(hiddenTypes = setOf(ReportType.CRASH))

        val built = HomeStateBuilder.build(rows, settings, now, newSince = null)

        assertEquals(listOf(ReportType.CHECKPOINT), reportItems(built).map { it.type })
    }

    @Test
    fun `suburb filter matches case-insensitively`() {
        val rows = listOf(
            row(1, "p1", suburb = "Pakuranga", postCreatedAt = now.toEpochMilli()),
            row(2, "p2", suburb = "Henderson", postCreatedAt = now.toEpochMilli() - 1000),
        )
        val settings = Settings(suburbFilter = "PAKURANGA")

        val built = HomeStateBuilder.build(rows, settings, now, newSince = null)

        assertEquals(listOf("Pakuranga"), reportItems(built).map { it.suburb })
    }

    @Test
    fun `suburb filter excludes reports with no suburb`() {
        val rows = listOf(row(1, "p1", suburb = null, postCreatedAt = now.toEpochMilli()))
        val settings = Settings(suburbFilter = "PAKURANGA")

        val built = HomeStateBuilder.build(rows, settings, now, newSince = null)

        assertTrue(reportItems(built).isEmpty())
    }

    // --- type mapping and at/atApprox -------------------------------------------------------

    @Test
    fun `unknown type strings map to OTHER`() {
        val rows = listOf(row(1, "p1", type = "SOMETHING_NEW", postCreatedAt = now.toEpochMilli()))

        val built = HomeStateBuilder.build(rows, Settings(), now, newSince = null)

        assertEquals(ReportType.OTHER, reportItems(built).single().type)
    }

    @Test
    fun `at falls back to the post's created time when reportedAt is absent`() {
        val postCreatedAt = now.minusSeconds(3600).toEpochMilli()
        val rows = listOf(row(1, "p1", reportedAt = null, postCreatedAt = postCreatedAt))

        val built = HomeStateBuilder.build(rows, Settings(), now, newSince = null)

        assertEquals(Instant.ofEpochMilli(postCreatedAt), reportItems(built).single().at)
    }

    @Test
    fun `at prefers reportedAt over the post's created time`() {
        val reportedAt = now.minusSeconds(120).toEpochMilli()
        val postCreatedAt = now.minusSeconds(3600).toEpochMilli()
        val rows = listOf(row(1, "p1", reportedAt = reportedAt, postCreatedAt = postCreatedAt))

        val built = HomeStateBuilder.build(rows, Settings(), now, newSince = null)

        assertEquals(Instant.ofEpochMilli(reportedAt), reportItems(built).single().at)
    }

    @Test
    fun `atApprox is true only when reportedAt is absent and the post time is approximate`() {
        val postCreatedAt = now.toEpochMilli()
        val rows = listOf(
            row(1, "p1", reportedAt = null, postCreatedAt = postCreatedAt, postCreatedAtApprox = true),
            row(2, "p2", reportedAt = now.minusSeconds(60).toEpochMilli(), postCreatedAt = postCreatedAt, postCreatedAtApprox = true),
        )

        val built = HomeStateBuilder.build(rows, Settings(), now, newSince = null)

        val byId = reportItems(built).associateBy { it.id }
        assertTrue(byId.getValue(1).atApprox)
        assertTrue(!byId.getValue(2).atApprox)
    }

    // --- isNew --------------------------------------------------------------------------------

    @Test
    fun `isNew is true only when firstSeenAt is at or after newSince`() {
        val newSince = now.minusSeconds(600)
        val rows = listOf(
            row(1, "p1", postCreatedAt = now.toEpochMilli(), firstSeenAt = newSince.toEpochMilli()),
            row(2, "p2", postCreatedAt = now.toEpochMilli() - 1, firstSeenAt = newSince.minusSeconds(1).toEpochMilli()),
        )

        val built = HomeStateBuilder.build(rows, Settings(), now, newSince = newSince)

        val byId = reportItems(built).associateBy { it.id }
        assertTrue(byId.getValue(1).isNew)
        assertTrue(!byId.getValue(2).isNew)
    }

    @Test
    fun `isNew is always false when newSince is null`() {
        val rows = listOf(row(1, "p1", postCreatedAt = now.toEpochMilli(), firstSeenAt = now.toEpochMilli()))

        val built = HomeStateBuilder.build(rows, Settings(), now, newSince = null)

        assertTrue(!reportItems(built).single().isNew)
    }

    // --- ordering and day headers -----------------------------------------------------------

    @Test
    fun `report order within a post follows indexInPost, preserving row order`() {
        val postCreatedAt = now.toEpochMilli()
        val rows = listOf(
            row(1, "p1", indexInPost = 0, postCreatedAt = postCreatedAt),
            row(2, "p1", indexInPost = 1, postCreatedAt = postCreatedAt),
        )

        val built = HomeStateBuilder.build(rows, Settings(), now, newSince = null)

        assertEquals(listOf(1L, 2L), reportItems(built).map { it.id })
    }

    @Test
    fun `two posts on the same day get a single Today header`() {
        val rows = listOf(
            row(1, "p1", postCreatedAt = now.toEpochMilli()),
            row(2, "p2", postCreatedAt = now.toEpochMilli() - 1000),
        )

        val built = HomeStateBuilder.build(rows, Settings(), now, newSince = null)

        assertEquals(listOf(ListItem.DayHeader("Today")), built.items.filterIsInstance<ListItem.DayHeader>())
    }

    @Test
    fun `posts on different days get separate headers, newest day first`() {
        val today = now.toEpochMilli()
        val yesterday = now.minusSeconds(26 * 3600L).toEpochMilli()
        val rows = listOf(
            row(1, "p1", postCreatedAt = today),
            row(2, "p2", postCreatedAt = yesterday),
        )

        val built = HomeStateBuilder.build(rows, Settings(), now, newSince = null)

        assertEquals(
            listOf(ListItem.DayHeader("Today"), ListItem.Report(reportItems(built)[0]), ListItem.DayHeader("Yesterday"), ListItem.Report(reportItems(built)[1])),
            built.items,
        )
    }

    @Test
    fun `a day with nothing visible and no gap gets no header`() {
        val today = now.toEpochMilli()
        val yesterday = now.minusSeconds(26 * 3600L).toEpochMilli()
        val rows = listOf(
            row(1, "p1", type = "CRASH", postCreatedAt = today),
            row(2, "p2", type = "CHECKPOINT", postCreatedAt = yesterday),
        )
        val settings = Settings(hiddenTypes = setOf(ReportType.CRASH))

        val built = HomeStateBuilder.build(rows, settings, now, newSince = null)

        assertEquals(listOf(ListItem.DayHeader("Yesterday")), built.items.filterIsInstance<ListItem.DayHeader>())
    }

    // --- gap marker -----------------------------------------------------------------------

    @Test
    fun `a gap is emitted after the last report of a gapBefore post`() {
        val postCreatedAt = now.toEpochMilli()
        val rows = listOf(
            row(1, "p1", indexInPost = 0, postCreatedAt = postCreatedAt, gapBefore = true),
            row(2, "p1", indexInPost = 1, postCreatedAt = postCreatedAt, gapBefore = true),
            row(3, "p2", postCreatedAt = postCreatedAt + 1000, gapBefore = false),
        )

        val built = HomeStateBuilder.build(rows, Settings(), now, newSince = null)

        val gapIndex = built.items.indexOfFirst { it is ListItem.Gap }
        assertEquals(ListItem.Gap("p1"), built.items[gapIndex])
        // the gap comes right after report id 2, the last (highest indexInPost) report of p1
        assertEquals(ListItem.Report(reportItems(built).last { it.id == 2L }), built.items[gapIndex - 1])
    }

    @Test
    fun `the gap marker survives even when every report of that post is filtered out`() {
        val postCreatedAt = now.toEpochMilli()
        val rows = listOf(
            row(1, "p1", type = "CRASH", postCreatedAt = postCreatedAt, gapBefore = true),
            row(2, "p2", type = "CHECKPOINT", postCreatedAt = postCreatedAt + 1000, gapBefore = false),
        )
        val settings = Settings(hiddenTypes = setOf(ReportType.CRASH))

        val built = HomeStateBuilder.build(rows, settings, now, newSince = null)

        assertEquals(listOf(ListItem.Gap("p1")), built.items.filterIsInstance<ListItem.Gap>())
    }

    @Test
    fun `a gap day header shows even with nothing visible that day, because of the gap`() {
        val today = now.toEpochMilli()
        val yesterday = now.minusSeconds(26 * 3600L).toEpochMilli()
        val rows = listOf(
            row(1, "p1", type = "CHECKPOINT", postCreatedAt = today),
            row(2, "p2", type = "CRASH", postCreatedAt = yesterday, gapBefore = true),
        )
        val settings = Settings(hiddenTypes = setOf(ReportType.CRASH))

        val built = HomeStateBuilder.build(rows, settings, now, newSince = null)

        assertEquals(
            listOf(ListItem.DayHeader("Today"), ListItem.DayHeader("Yesterday")),
            built.items.filterIsInstance<ListItem.DayHeader>(),
        )
        assertTrue(built.items.contains(ListItem.Gap("p2")))
    }

    // --- summary --------------------------------------------------------------------------

    @Test
    fun `summary counts only reports within 2 hours of now`() {
        val rows = listOf(
            row(1, "p1", postCreatedAt = now.minusSeconds(3600).toEpochMilli()), // within
            row(2, "p2", postCreatedAt = now.minusSeconds(3 * 3600L).toEpochMilli()), // outside
            row(3, "p3", postCreatedAt = now.minusSeconds(2 * 3600L).toEpochMilli()), // exactly 2h: within
        )

        val built = HomeStateBuilder.build(rows, Settings(), now, newSince = null)

        assertEquals(2, built.summary.counts.values.sum())
    }

    @Test
    fun `summary excludes reports more than 15 minutes in the future but allows closer ones`() {
        val rows = listOf(
            row(1, "p1", postCreatedAt = now.plusSeconds(20 * 60L).toEpochMilli()), // too far ahead
            row(2, "p2", postCreatedAt = now.plusSeconds(10 * 60L).toEpochMilli()), // allowed
        )

        val built = HomeStateBuilder.build(rows, Settings(), now, newSince = null)

        assertEquals(1, built.summary.counts.values.sum())
    }

    @Test
    fun `summary ignores the type and suburb filters entirely`() {
        val rows = listOf(
            row(1, "p1", type = "SPEED_CAMERA", suburb = "Nowhere", postCreatedAt = now.toEpochMilli()),
        )
        val settings = Settings(hiddenTypes = setOf(ReportType.SPEED_CAMERA), suburbFilter = "Elsewhere")

        val built = HomeStateBuilder.build(rows, settings, now, newSince = null)

        assertEquals(1, built.summary.counts[ReportType.SPEED_CAMERA])
        assertTrue(reportItems(built).isEmpty())
    }

    @Test
    fun `summary freshest is the max at among counted reports`() {
        val newest = now.minusSeconds(300)
        val rows = listOf(
            row(1, "p1", postCreatedAt = now.minusSeconds(3600).toEpochMilli()),
            row(2, "p2", postCreatedAt = newest.toEpochMilli()),
        )

        val built = HomeStateBuilder.build(rows, Settings(), now, newSince = null)

        assertEquals(newest, built.summary.freshest)
    }

    @Test
    fun `summary freshest is null when nothing is within the window`() {
        val rows = listOf(row(1, "p1", postCreatedAt = now.minusSeconds(10 * 3600L).toEpochMilli()))

        val built = HomeStateBuilder.build(rows, Settings(), now, newSince = null)

        assertNull(built.summary.freshest)
    }

    // --- newReportCount helper --------------------------------------------------------------

    @Test
    fun `newReportCount counts rows first seen at or after newSince`() {
        val newSince = now.minusSeconds(600)
        val rows = listOf(
            row(1, "p1", postCreatedAt = now.toEpochMilli(), firstSeenAt = newSince.toEpochMilli()),
            row(2, "p2", postCreatedAt = now.toEpochMilli(), firstSeenAt = newSince.plusSeconds(1).toEpochMilli()),
            row(3, "p3", postCreatedAt = now.toEpochMilli(), firstSeenAt = newSince.minusSeconds(1).toEpochMilli()),
        )

        assertEquals(2, HomeStateBuilder.newReportCount(rows, newSince))
    }

    @Test
    fun `newReportCount is zero when newSince is null`() {
        val rows = listOf(row(1, "p1", postCreatedAt = now.toEpochMilli()))

        assertEquals(0, HomeStateBuilder.newReportCount(rows, null))
    }

    // --- area linking is wired through --------------------------------------------------------

    @Test
    fun `also-in-area mentions are populated from the unfiltered rows`() {
        val rows = listOf(
            row(1, "p1", suburb = "Pakuranga", road = "Ti Rakau Drive", type = "CHECKPOINT", postCreatedAt = now.toEpochMilli()),
            row(2, "p2", suburb = "Pakuranga", road = "Pakuranga Road", type = "CRASH", postCreatedAt = now.minusSeconds(1000).toEpochMilli()),
        )
        // Crash is hidden from the list, but should still be mentioned on the checkpoint's card.
        val settings = Settings(hiddenTypes = setOf(ReportType.CRASH))

        val built = HomeStateBuilder.build(rows, settings, now, newSince = null)

        val checkpoint = reportItems(built).single()
        assertEquals(listOf(ReportType.CRASH), checkpoint.alsoInArea.map { it.type })
    }
}

class BannerBuilderTest {

    private val finishedAt = Instant.parse("2026-09-18T09:00:00Z")

    private fun summary(status: ScrapeStatus, new: Int = 0) =
        ScanSummary(status = status, new = new, finishedAt = finishedAt, trigger = ScanTrigger.FOREGROUND)

    @Test
    fun `scanning shows the normal finding-checkpoints message`() {
        val banner = BannerBuilder.build(scanning = true, firstEver = false, summary = null, newReportCount = 0)
        assertEquals(BannerUi.Message("Finding checkpoints…"), banner)
    }

    @Test
    fun `scanning for the first time ever has its own copy`() {
        val banner = BannerBuilder.build(scanning = true, firstEver = true, summary = null, newReportCount = 0)
        assertEquals(BannerUi.Message("Finding checkpoints for the first time…"), banner)
    }

    @Test
    fun `idle with no summary shows nothing`() {
        val banner = BannerBuilder.build(scanning = false, firstEver = false, summary = null, newReportCount = 0)
        assertEquals(BannerUi.None, banner)
    }

    @Test
    fun `OK with new reports pluralises correctly`() {
        assertEquals(
            BannerUi.Message("Found 1 new report"),
            BannerBuilder.build(false, false, summary(ScrapeStatus.OK), newReportCount = 1),
        )
        assertEquals(
            BannerUi.Message("Found 3 new reports"),
            BannerBuilder.build(false, false, summary(ScrapeStatus.OK), newReportCount = 3),
        )
    }

    @Test
    fun `OK with zero new reports says so plainly`() {
        assertEquals(
            BannerUi.Message("No new reports"),
            BannerBuilder.build(false, false, summary(ScrapeStatus.OK), newReportCount = 0),
        )
    }

    @Test
    fun `OK_WITH_GAP mentions the gap alongside the count`() {
        assertEquals(
            BannerUi.Message("Found 2 new reports · earlier posts unavailable"),
            BannerBuilder.build(false, false, summary(ScrapeStatus.OK_WITH_GAP), newReportCount = 2),
        )
        assertEquals(
            BannerUi.Message("Found 1 new report · earlier posts unavailable"),
            BannerBuilder.build(false, false, summary(ScrapeStatus.OK_WITH_GAP), newReportCount = 1),
        )
    }

    @Test
    fun `FAILED_NETWORK copy`() {
        assertEquals(
            BannerUi.Message("Couldn't reach Facebook · showing saved reports"),
            BannerBuilder.build(false, false, summary(ScrapeStatus.FAILED_NETWORK), newReportCount = 0),
        )
    }

    @Test
    fun `FAILED_NO_DATA copy`() {
        assertEquals(
            BannerUi.Message("Facebook returned no posts · showing saved reports"),
            BannerBuilder.build(false, false, summary(ScrapeStatus.FAILED_NO_DATA), newReportCount = 0),
        )
    }

    @Test
    fun `CANCELLED shows nothing, same as no summary at all`() {
        assertEquals(
            BannerUi.None,
            BannerBuilder.build(false, false, summary(ScrapeStatus.CANCELLED), newReportCount = 5),
        )
    }
}
