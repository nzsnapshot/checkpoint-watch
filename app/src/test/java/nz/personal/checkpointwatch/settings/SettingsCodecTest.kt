package nz.personal.checkpointwatch.settings

import nz.personal.checkpointwatch.model.ReportType
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCodecTest {

    @Test
    fun `stored names round-trip`() {
        val types = setOf(ReportType.CHECKPOINT, ReportType.CRASH)

        assertEquals(types, SettingsCodec.decodeTypes(SettingsCodec.encodeTypes(types)))
    }

    @Test
    fun `names we no longer know are ignored`() {
        val stored = setOf("CHECKPOINT", "ROADWORKS", "", "crash")

        assertEquals(setOf(ReportType.CHECKPOINT), SettingsCodec.decodeTypes(stored))
    }
}
