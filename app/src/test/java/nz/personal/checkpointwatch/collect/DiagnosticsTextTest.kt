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

    @Test
    fun document_addsASecondBlockForTheSecondPass() {
        // A starved scan is two scans, and the question "why did the fallback bring back five
        // posts?" is answered by the second half, which has its own install facts and its own log.
        val text = DiagnosticsText.document(
            fields = listOf("end" to "NO_FEED"),
            json = """{"rounds":[]}""",
            plugin = DiagnosticsText.Block(
                fields = listOf("pluginMs" to "4200", "pluginPosts" to "5"),
                json = """{"seen":{"posts":5}}""",
            ),
        )

        assertEquals(
            "end=NO_FEED\n\n{\"rounds\":[]}\n" +
                "\n${DiagnosticsText.PLUGIN_HEADING}\n" +
                "pluginMs=4200\npluginPosts=5\n" +
                "\n{\"seen\":{\"posts\":5}}\n",
            text,
        )
    }

    @Test
    fun document_withoutASecondPass_isExactlyWhatItWasBefore() {
        val json = """{"rounds":[]}"""

        assertEquals(
            DiagnosticsText.document(listOf("end" to "LOGIN_WALL"), json),
            DiagnosticsText.document(listOf("end" to "LOGIN_WALL"), json, plugin = null),
        )
    }

    @Test
    fun document_secondBlockNamesItsOwnMissingLog() {
        val text = DiagnosticsText.document(
            fields = listOf("end" to "NO_FEED"),
            json = null,
            plugin = DiagnosticsText.Block(fields = listOf("pluginPosts" to "0"), json = null),
        )

        assertEquals(2, Regex(Regex.escape(DiagnosticsText.NO_LOG)).findAll(text).count())
    }
}
