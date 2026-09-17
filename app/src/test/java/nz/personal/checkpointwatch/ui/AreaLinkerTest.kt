package nz.personal.checkpointwatch.ui

import nz.personal.checkpointwatch.model.ReportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AreaLinkerTest {

    private val base = Instant.parse("2026-09-18T09:00:00Z")

    private fun report(
        id: Long,
        postId: String = "post-$id",
        suburb: String? = "Pakuranga",
        road: String? = "Ti Rakau Drive",
        at: Instant = base,
        type: ReportType = ReportType.CHECKPOINT,
    ) = ReportUi(
        id = id,
        postId = postId,
        type = type,
        typeLabel = type.name,
        road = road,
        suburb = suburb,
        details = "",
        at = at,
        atApprox = false,
        reportedTimeText = null,
        source = null,
        postText = "",
        postUrl = "",
        freshness = Freshness.FRESH,
        gapAfter = false,
        alsoInArea = emptyList(),
        isNew = false,
    )

    @Test
    fun `same suburb links regardless of road, case-insensitively`() {
        val a = report(id = 1, suburb = "Pakuranga", road = "Ti Rakau Drive")
        val b = report(id = 2, suburb = "PAKURANGA", road = "Pakuranga Road")

        val links = AreaLinker.link(listOf(a, b))

        assertEquals(listOf(AreaMention(b.type, b.at)), links[a.id])
        assertEquals(listOf(AreaMention(a.type, a.at)), links[b.id])
    }

    @Test
    fun `no suburb falls back to matching road, case-insensitively`() {
        val a = report(id = 1, suburb = null, road = "Lincoln Road")
        val b = report(id = 2, suburb = null, road = "LINCOLN ROAD")

        val links = AreaLinker.link(listOf(a, b))

        assertEquals(listOf(AreaMention(b.type, b.at)), links[a.id])
        assertEquals(listOf(AreaMention(a.type, a.at)), links[b.id])
    }

    @Test
    fun `no suburb and different roads do not link`() {
        val a = report(id = 1, suburb = null, road = "Lincoln Road")
        val b = report(id = 2, suburb = null, road = "Universal Drive")

        val links = AreaLinker.link(listOf(a, b))

        assertTrue(links[a.id].orEmpty().isEmpty())
        assertTrue(links[b.id].orEmpty().isEmpty())
    }

    @Test
    fun `a suburb report and a suburb-less report never match, even on the same road`() {
        val a = report(id = 1, suburb = "Henderson", road = "Lincoln Road")
        val b = report(id = 2, suburb = null, road = "Lincoln Road")

        val links = AreaLinker.link(listOf(a, b))

        assertTrue(links[a.id].orEmpty().isEmpty())
        assertTrue(links[b.id].orEmpty().isEmpty())
    }

    @Test
    fun `a report never mentions itself`() {
        val a = report(id = 1)

        val links = AreaLinker.link(listOf(a))

        assertTrue(links[a.id].orEmpty().isEmpty())
    }

    @Test
    fun `same-post siblings are treated like any other report, matched by suburb not road`() {
        val a = report(id = 1, postId = "shared-post", suburb = "Pakuranga", road = "Ti Rakau Drive")
        val b = report(id = 2, postId = "shared-post", suburb = "Pakuranga", road = "Pakuranga Road")

        val links = AreaLinker.link(listOf(a, b))

        assertEquals(listOf(AreaMention(b.type, b.at)), links[a.id])
        assertEquals(listOf(AreaMention(a.type, a.at)), links[b.id])
    }

    @Test
    fun `exactly 3 hours apart still links`() {
        val a = report(id = 1, at = base)
        val b = report(id = 2, at = base.minusSeconds(3 * 3600L))

        val links = AreaLinker.link(listOf(a, b))

        assertEquals(1, links[a.id]?.size)
        assertEquals(1, links[b.id]?.size)
    }

    @Test
    fun `just over 3 hours apart does not link`() {
        val a = report(id = 1, at = base)
        val b = report(id = 2, at = base.minusSeconds(3 * 3600L + 1))

        val links = AreaLinker.link(listOf(a, b))

        assertTrue(links[a.id].orEmpty().isEmpty())
        assertTrue(links[b.id].orEmpty().isEmpty())
    }

    @Test
    fun `is symmetric - a later report also sees the earlier one`() {
        val earlier = report(id = 1, at = base.minusSeconds(3600))
        val later = report(id = 2, at = base)

        val links = AreaLinker.link(listOf(earlier, later))

        assertEquals(listOf(AreaMention(later.type, later.at)), links[earlier.id])
        assertEquals(listOf(AreaMention(earlier.type, earlier.at)), links[later.id])
    }

    @Test
    fun `caps at 3, newest first`() {
        val subject = report(id = 0, at = base)
        val candidates = listOf(
            report(id = 1, at = base.minusSeconds(30 * 60L)), // newest
            report(id = 2, at = base.minusSeconds(60 * 60L)),
            report(id = 3, at = base.minusSeconds(90 * 60L)),
            report(id = 4, at = base.minusSeconds(120 * 60L)),
            report(id = 5, at = base.minusSeconds(150 * 60L)), // oldest, should be dropped
        )

        val links = AreaLinker.link(listOf(subject) + candidates)

        val mentions = links[subject.id].orEmpty()
        assertEquals(3, mentions.size)
        assertEquals(
            listOf(
                base.minusSeconds(30 * 60L),
                base.minusSeconds(60 * 60L),
                base.minusSeconds(90 * 60L),
            ),
            mentions.map { it.at },
        )
    }
}
