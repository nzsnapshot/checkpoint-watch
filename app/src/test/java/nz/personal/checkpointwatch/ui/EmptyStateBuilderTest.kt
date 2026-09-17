package nz.personal.checkpointwatch.ui

import nz.personal.checkpointwatch.data.ScrapeStatus
import nz.personal.checkpointwatch.ui.home.EmptyKind
import nz.personal.checkpointwatch.ui.home.EmptyStateBuilder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An empty list has several quite different causes, and telling the owner the wrong one is worse
 * than saying nothing: "Couldn't reach Facebook" after a scan that worked perfectly and simply
 * found nothing would send them off checking their connection.
 */
class EmptyStateBuilderTest {

    private fun kind(
        visibleItems: Int = 0,
        totalReports: Int = 0,
        scanning: Boolean = false,
        lastStatus: ScrapeStatus? = null,
    ) = EmptyStateBuilder.kind(visibleItems, totalReports, scanning, lastStatus)

    @Test
    fun `a list with anything in it has no empty state`() {
        assertEquals(EmptyKind.NONE, kind(visibleItems = 1))
        // Even when the only thing showing is a gap marker with no reports around it.
        assertEquals(EmptyKind.NONE, kind(visibleItems = 2, totalReports = 0))
    }

    @Test
    fun `reports exist but none are showing, so it is the filters`() {
        assertEquals(EmptyKind.FILTERED, kind(totalReports = 5))
    }

    @Test
    fun `the filters win over whatever the last scan did`() {
        ScrapeStatus.entries.forEach { status ->
            assertEquals(EmptyKind.FILTERED, kind(totalReports = 5, lastStatus = status))
        }
    }

    @Test
    fun `a scan in progress with nothing saved is the first-run state`() {
        assertEquals(EmptyKind.SEARCHING, kind(scanning = true))
    }

    @Test
    fun `nothing has run yet, so nothing has failed yet`() {
        assertEquals(EmptyKind.SEARCHING, kind(lastStatus = null))
    }

    @Test
    fun `a network failure is the only thing that says the phone could not reach Facebook`() {
        assertEquals(EmptyKind.OFFLINE, kind(lastStatus = ScrapeStatus.FAILED_NETWORK))
    }

    @Test
    fun `a page that returned nothing says so, rather than blaming the connection`() {
        assertEquals(EmptyKind.NO_POSTS, kind(lastStatus = ScrapeStatus.FAILED_NO_DATA))
    }

    @Test
    fun `a scan that worked and stored nothing is calm about it`() {
        assertEquals(EmptyKind.NO_REPORTS_YET, kind(lastStatus = ScrapeStatus.OK))
        assertEquals(EmptyKind.NO_REPORTS_YET, kind(lastStatus = ScrapeStatus.OK_WITH_GAP))
    }

    @Test
    fun `a scan cut short claims nothing about the page`() {
        assertEquals(EmptyKind.NO_REPORTS_YET, kind(lastStatus = ScrapeStatus.CANCELLED))
    }

    @Test
    fun `a scan running again after a failure shows the scan, not the old failure`() {
        assertEquals(EmptyKind.SEARCHING, kind(scanning = true, lastStatus = ScrapeStatus.FAILED_NETWORK))
    }
}
