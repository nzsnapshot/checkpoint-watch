package nz.personal.checkpointwatch.ui

import nz.personal.checkpointwatch.R
import nz.personal.checkpointwatch.data.CollectorKind
import nz.personal.checkpointwatch.data.ScanTrigger
import nz.personal.checkpointwatch.data.ScrapeEntity
import nz.personal.checkpointwatch.data.ScrapeStatus
import nz.personal.checkpointwatch.scan.ALLOWED_BACKGROUND_MINUTES
import nz.personal.checkpointwatch.ui.settings.IntervalUi
import nz.personal.checkpointwatch.ui.settings.ScanHistoryUi
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * The settings screen's scan log is the only place the owner can see what the collector actually
 * managed to do, so every stored value has to arrive there as itself and not as a near neighbour.
 */
class ScanHistoryUiTest {

    private fun entity(
        id: Long = 1,
        status: String = "OK",
        trigger: String = "FOREGROUND",
        collector: String = "WEBVIEW",
        startedAt: Long = 1_600_000_000_000,
        postsSeen: Int = 10,
        postsNew: Int = 3,
    ) = ScrapeEntity(
        id = id,
        startedAt = startedAt,
        finishedAt = startedAt + 20_000,
        status = status,
        endReason = "NO_MORE_POSTS",
        trigger = trigger,
        collector = collector,
        postsSeen = postsSeen,
        postsNew = postsNew,
        postsUpdated = 0,
    )

    @Test
    fun `a row carries the stored counts, time and enums through unchanged`() {
        val rows = ScanHistoryUi.rows(listOf(entity(id = 7, postsNew = 3, postsSeen = 10)))

        val row = rows.single()
        assertEquals(7L, row.id)
        assertEquals(Instant.ofEpochMilli(1_600_000_000_000), row.startedAt)
        assertEquals(ScanTrigger.FOREGROUND, row.trigger)
        assertEquals(CollectorKind.WEBVIEW, row.collector)
        assertEquals(ScrapeStatus.OK, row.status)
        assertEquals(3, row.new)
        assertEquals(10, row.seen)
    }

    @Test
    fun `order is preserved so the newest scan stays first`() {
        val rows = ScanHistoryUi.rows(listOf(entity(id = 2), entity(id = 1)))

        assertEquals(listOf(2L, 1L), rows.map { it.id })
    }

    @Test
    fun `every stored status name reads back as itself`() {
        ScrapeStatus.entries.forEach { status ->
            assertEquals(status, ScanHistoryUi.status(status.name))
        }
    }

    @Test
    fun `every stored trigger and collector name reads back as itself`() {
        ScanTrigger.entries.forEach { assertEquals(it, ScanHistoryUi.trigger(it.name)) }
        CollectorKind.entries.forEach { assertEquals(it, ScanHistoryUi.collector(it.name)) }
    }

    @Test
    fun `an unreadable stored name degrades instead of throwing`() {
        assertEquals(ScrapeStatus.FAILED_NO_DATA, ScanHistoryUi.status("FROM_A_LATER_VERSION"))
        assertEquals(ScanTrigger.BACKGROUND, ScanHistoryUi.trigger(""))
        assertEquals(CollectorKind.NONE, ScanHistoryUi.collector("SOMETHING_ELSE"))
    }

    @Test
    fun `each status has its own plain-English label`() {
        val labels = ScrapeStatus.entries.map(ScanHistoryUi::statusLabel)

        assertEquals(
            listOf(
                R.string.status_ok,
                R.string.status_ok_with_gap,
                R.string.status_failed_network,
                R.string.status_failed_no_data,
                R.string.status_cancelled,
            ),
            labels,
        )
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `each trigger and collector has its own label`() {
        assertEquals(R.string.trigger_foreground, ScanHistoryUi.triggerLabel(ScanTrigger.FOREGROUND))
        assertEquals(R.string.trigger_background, ScanHistoryUi.triggerLabel(ScanTrigger.BACKGROUND))

        val collectors = CollectorKind.entries.map(ScanHistoryUi::collectorLabel)
        assertEquals(collectors.size, collectors.toSet().size)
    }

    @Test
    fun `every interval the settings screen offers has its own compact label`() {
        val labels = ALLOWED_BACKGROUND_MINUTES.map(IntervalUi::compactLabel)

        assertEquals(
            listOf(
                R.string.settings_interval_off,
                R.string.settings_interval_15,
                R.string.settings_interval_30,
                R.string.settings_interval_60,
                R.string.settings_interval_120,
            ),
            labels,
        )
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `and its own long label, with only Off shared between the two forms`() {
        val long = ALLOWED_BACKGROUND_MINUTES.map(IntervalUi::longLabel)

        assertEquals(
            listOf(
                R.string.settings_interval_off,
                R.string.settings_interval_15_long,
                R.string.settings_interval_30_long,
                R.string.settings_interval_60_long,
                R.string.settings_interval_120_long,
            ),
            long,
        )
        assertEquals(long.size, long.toSet().size)
        val compact = ALLOWED_BACKGROUND_MINUTES.map(IntervalUi::compactLabel)
        assertEquals(listOf(R.string.settings_interval_off), long.intersect(compact.toSet()).toList())
    }
}
