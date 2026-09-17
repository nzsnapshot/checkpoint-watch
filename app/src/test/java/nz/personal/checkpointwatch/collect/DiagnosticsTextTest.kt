package nz.personal.checkpointwatch.collect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTextTest {

    @Test
    fun header_writesOneKeyValueLinePerField() {
        val text = DiagnosticsText.header(listOf("host" to "ActivityHost", "end" to "LOGIN_WALL"))

        assertEquals("host=ActivityHost\nend=LOGIN_WALL\n", text)
    }

    @Test
    fun header_leavesOutFieldsWithNothingToSay() {
        val text = DiagnosticsText.header(listOf("host" to "HeadlessHost", "httpStatus" to null))

        assertEquals("host=HeadlessHost\n", text)
    }

    @Test
    fun header_flattensValuesSoNothingCanForgeAKeyOfItsOwn() {
        val text = DiagnosticsText.header(listOf("error" to "  net::ERR_FAILED\nend=OK  "))

        assertEquals("error=net::ERR_FAILED end=OK\n", text)
        assertEquals(1, text.count { it == '\n' })
    }

    @Test
    fun header_ofNothingIsNothing() {
        assertEquals("", DiagnosticsText.header(emptyList()))
    }

    @Test
    fun document_isTheHeaderThenABlankLineThenTheLog() {
        val json = """{"install":{"href":"https://www.facebook.com/CheckpointNZ"},"rounds":[]}"""

        val text = DiagnosticsText.document(listOf("end" to "LOGIN_WALL"), json)

        assertEquals("end=LOGIN_WALL\n\n$json\n", text)
    }

    @Test
    fun document_namesTheAbsenceWhenTheScriptSentNoLog() {
        val text = DiagnosticsText.document(listOf("end" to "NETWORK_ERROR"), null)

        assertTrue(text.contains(DiagnosticsText.NO_LOG))
        assertFalse(text.contains("null"))
    }
}
