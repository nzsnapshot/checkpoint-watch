package nz.personal.checkpointwatch.ui

import nz.personal.checkpointwatch.model.ReportType
import nz.personal.checkpointwatch.ui.home.TypeFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The summary tiles' one job: "just show me the checkpoints", and back again. */
class TypeFilterTest {

    private val all = ReportType.entries.toSet()

    @Test
    fun `tapping a tile with nothing hidden hides every other type`() {
        val hidden = TypeFilter.solo(emptySet(), ReportType.CHECKPOINT)

        assertEquals(all - ReportType.CHECKPOINT, hidden)
    }

    @Test
    fun `tapping the same tile again shows everything`() {
        val soloed = TypeFilter.solo(emptySet(), ReportType.CRASH)

        assertEquals(emptySet<ReportType>(), TypeFilter.solo(soloed, ReportType.CRASH))
    }

    @Test
    fun `tapping a different tile moves the solo rather than clearing it`() {
        val soloed = TypeFilter.solo(emptySet(), ReportType.CRASH)

        val moved = TypeFilter.solo(soloed, ReportType.SPEED_CAMERA)

        assertEquals(all - ReportType.SPEED_CAMERA, moved)
    }

    @Test
    fun `a tile tapped over an unrelated partial filter still solos`() {
        val partial = setOf(ReportType.OTHER)

        val hidden = TypeFilter.solo(partial, ReportType.POLICE_PRESENCE)

        assertEquals(all - ReportType.POLICE_PRESENCE, hidden)
    }

    @Test
    fun `isSolo is true only when the type is the single visible one`() {
        assertTrue(TypeFilter.isSolo(all - ReportType.CHECKPOINT, ReportType.CHECKPOINT))
        assertFalse(TypeFilter.isSolo(emptySet(), ReportType.CHECKPOINT))
        assertFalse(TypeFilter.isSolo(setOf(ReportType.OTHER), ReportType.CHECKPOINT))
        // Hidden itself: never "the only one showing", whatever else is hidden.
        assertFalse(TypeFilter.isSolo(all - ReportType.CRASH, ReportType.CHECKPOINT))
    }

    @Test
    fun `everything hidden is not a solo of anything`() {
        ReportType.entries.forEach { type ->
            assertFalse(TypeFilter.isSolo(all, type))
        }
    }
}
