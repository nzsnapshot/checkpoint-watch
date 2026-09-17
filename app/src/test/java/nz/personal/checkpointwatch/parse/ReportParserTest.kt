package nz.personal.checkpointwatch.parse

import nz.personal.checkpointwatch.model.ReportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ReportParserTest {

    private val t = Instant.ofEpochSecond(1789637686)

    @Test
    fun simpleCheckpoint() {
        val r = ReportParser.parse(
            "🛑 CHECKPOINT – Lincoln Road, HENDERSON\nAfter the off-ramp coming from the motorway\nTime: 11:55PM",
            Instant.ofEpochSecond(1789646182),
        ).single()
        assertEquals(ReportType.CHECKPOINT, r.type)
        assertEquals("Lincoln Road", r.road)
        assertEquals("HENDERSON", r.suburb)
        assertEquals("After the off-ramp coming from the motorway", r.details)
        assertEquals("11:55PM", r.reportedTimeText)
        assertNotNull(r.reportedAt)
        assertNull(r.source)
    }

    @Test
    fun policePresence() {
        val r = ReportParser.parse(
            "⚠️ HEAVY POLICE PRESENCE – Roberton Road, AVONDALE\nMassive police presence reported\nTime: 12:00AM",
            t,
        ).single()
        assertEquals(ReportType.POLICE_PRESENCE, r.type)
        assertEquals("HEAVY POLICE PRESENCE", r.typeLabel)
        assertEquals("AVONDALE", r.suburb)
    }

    @Test
    fun crash() = assertEquals(
        ReportType.CRASH,
        ReportParser.parse(
            "⚠️ CRASH – Pakuranga Road, PAKURANGA\nOutside St Kentigern College.\nTime: 8:15PM",
            t,
        ).single().type,
    )

    @Test
    fun speedCameraWithTwoEmoji() {
        val r = ReportParser.parse(
            "📷⚠️ SPEED CAMERA – Hibiscus Coast Highway\nSpeed camera van\nTime: 8:14PM",
            t,
        ).single()
        assertEquals(ReportType.SPEED_CAMERA, r.type)
        assertEquals("Hibiscus Coast Highway", r.road)
        assertNull(r.suburb)
    }

    @Test
    fun noSuburb() {
        val r = ReportParser.parse("🛑 CHECKPOINT – Trig Road\nAt the top\nTime: 9:11PM", t).single()
        assertEquals("Trig Road", r.road)
        assertNull(r.suburb)
    }

    @Test
    fun multiWordSuburbAndCommaInRoad() {
        val r = ReportParser.parse(
            "🛑 CHECKPOINT – Auckland International Airport, MANGERE\nNear the pick-up",
            t,
        ).single()
        assertEquals("Auckland International Airport", r.road)
        assertEquals("MANGERE", r.suburb)
        assertNull(r.reportedTimeText)
    }

    @Test
    fun multiReportPost() {
        val rs = ReportParser.parse(
            "🛑 CHECKPOINT – Grafton On-Ramp\nTime: 9:30PM (Pictured) \n\n🛑 CHECKPOINT – Stancombe Road, FLAT BUSH\nNear the temple\nTime: 9:30PM",
            t,
        )
        assertEquals(2, rs.size)
        assertEquals(listOf(0, 1), rs.map { it.indexInPost })
        assertEquals("Grafton On-Ramp", rs[0].road)
        assertEquals("", rs[0].details)
        assertEquals("FLAT BUSH", rs[1].suburb)
        assertEquals("Near the temple", rs[1].details)
    }

    @Test
    fun sourceLine() = assertEquals(
        "WhatsApp subscriber",
        ReportParser.parse(
            "🛑 CHECKPOINT – Karaka Road, KARAKA\nBy BP\nTime: 9:59PM\n\nFrom: WhatsApp subscriber",
            t,
        ).single().source,
    )

    @Test
    fun hyphenSeparator() = assertEquals(
        "Queen Street",
        ReportParser.parse("CHECKPOINT - Queen Street, CBD", t).single().road,
    )

    @Test
    fun unknownHeaderIsOther() {
        val r = ReportParser.parse("🚧 ROAD CLOSED – Dominion Road, MT EDEN\nUntil 5am", t).single()
        assertEquals(ReportType.OTHER, r.type)
        assertEquals("ROAD CLOSED", r.typeLabel)
    }

    @Test
    fun headerlessTextKeptAsOther() {
        val r = ReportParser.parse("Win 3 months free rego! Subscribe now", t).single()
        assertEquals(ReportType.OTHER, r.type)
        assertEquals("Win 3 months free rego! Subscribe now", r.details)
        assertNull(r.road)
    }

    @Test
    fun preambleBeforeFirstHeaderGoesToFirstReportDetails() {
        val r = ReportParser.parse("UPDATE\n🛑 CHECKPOINT – Trig Road\nStill there", t).single()
        assertEquals("UPDATE\nStill there", r.details)
    }

    @Test
    fun blankText() = assertTrue(ReportParser.parse("  \n ", t).isEmpty())
}
