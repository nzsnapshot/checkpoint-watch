package nz.personal.checkpointwatch.ui

import nz.personal.checkpointwatch.model.ReportType
import nz.personal.checkpointwatch.ui.home.PostDetail
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Opening a card should be worth the tap. Most posts say exactly what the card already shows —
 * a header, the detail line, a time — so repeating them under a "Full post" heading is noise.
 */
class PostDetailTest {

    private fun report(
        road: String? = "Lincoln Road",
        suburb: String? = "HENDERSON",
        details: String = "After the off-ramp coming from the motorway",
        reportedTimeText: String? = "11:55PM",
        source: String? = null,
        postText: String,
    ) = ReportUi(
        id = 1,
        postId = "1",
        type = ReportType.CHECKPOINT,
        typeLabel = "CHECKPOINT",
        road = road,
        suburb = suburb,
        details = details,
        at = Instant.parse("2026-09-18T09:00:00Z"),
        atApprox = false,
        reportedTimeText = reportedTimeText,
        source = source,
        postText = postText,
        postUrl = "https://www.facebook.com/CheckpointNZ/posts/1",
        freshness = Freshness.FRESH,
        gapAfter = false,
        isNew = false,
    )

    @Test
    fun `a plain post that the card already shows in full adds nothing`() {
        val report = report(
            postText = "🛑 CHECKPOINT – Lincoln Road, HENDERSON\n" +
                "After the off-ramp coming from the motorway\n" +
                "Time: 11:55PM",
        )

        assertFalse(PostDetail.postAddsDetail(report))
    }

    @Test
    fun `the From line is already on the card, so it does not count`() {
        val report = report(
            source = "WhatsApp subscriber",
            postText = "🛑 CHECKPOINT – Lincoln Road, HENDERSON\n" +
                "After the off-ramp coming from the motorway\n" +
                "Time: 11:55PM\n" +
                "From: WhatsApp subscriber",
        )

        assertFalse(PostDetail.postAddsDetail(report))
    }

    @Test
    fun `a second report in the same post is worth showing`() {
        val report = report(
            road = "Pakuranga Road",
            suburb = "PAKURANGA",
            details = "Two cars, left lane blocked heading east",
            postText = "🚗 CRASH – Pakuranga Road, PAKURANGA\n" +
                "Two cars, left lane blocked heading east\n" +
                "Time: 8:20PM\n" +
                "\n" +
                "🛑 CHECKPOINT – Pakuranga Road, PAKURANGA\n" +
                "Just past the Ti Rakau Drive turn-off\n" +
                "Time: 8:20PM",
        )

        assertTrue(PostDetail.postAddsDetail(report))
    }

    @Test
    fun `a line the parser did not keep is worth showing`() {
        val report = report(
            postText = "🛑 CHECKPOINT – Lincoln Road, HENDERSON\n" +
                "After the off-ramp coming from the motorway\n" +
                "Time: 11:55PM\n" +
                "Please share and drive safe everyone",
        )

        assertTrue(PostDetail.postAddsDetail(report))
    }

    @Test
    fun `a headerless post whose whole text is the details adds nothing`() {
        val text = "Roadworks on the Southern Motorway tonight, one lane open until 5am."
        val report = report(road = null, suburb = null, details = text, reportedTimeText = null, postText = text)

        assertFalse(PostDetail.postAddsDetail(report))
    }

    @Test
    fun `blank lines, stray punctuation and case differences are not new information`() {
        val report = report(
            postText = "\n🛑  CHECKPOINT – Lincoln Road, HENDERSON  \n\n" +
                "after the off-ramp coming from the motorway.\n" +
                "   \n" +
                "TIME: 11:55PM\n" +
                "···",
        )

        assertFalse(PostDetail.postAddsDetail(report))
    }

    @Test
    fun `multi-line details are all accounted for`() {
        val report = report(
            details = "After the off-ramp\nBoth directions",
            postText = "🛑 CHECKPOINT – Lincoln Road, HENDERSON\n" +
                "After the off-ramp\n" +
                "Both directions\n" +
                "Time: 11:55PM",
        )

        assertFalse(PostDetail.postAddsDetail(report))
    }

    @Test
    fun `an empty post text adds nothing`() {
        assertFalse(PostDetail.postAddsDetail(report(postText = "")))
    }

    @Test
    fun `a report with no road still filters its own header line`() {
        val report = report(
            road = null,
            suburb = "HENDERSON",
            details = "Everyone being stopped",
            postText = "🛑 CHECKPOINT – HENDERSON\nEveryone being stopped\nTime: 11:55PM",
        )

        assertFalse(PostDetail.postAddsDetail(report))
    }
}
