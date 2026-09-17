package nz.personal.checkpointwatch.collect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class DomPostExtractorTest {

    private val scanTime: Instant = Instant.parse("2026-09-18T10:00:00Z")

    @Test
    fun extract_minutesAge_parsedWithAndWithoutSpace() {
        val posts = DomPostExtractor.extract(
            listOf(
                DomPost("checkpoint on Main St", "22m", "https://facebook.com/x"),
                DomPost("checkpoint on Main St 2", "5 m", "https://facebook.com/y"),
            ),
            scanTime,
        )

        assertEquals(scanTime.minus(22, ChronoUnit.MINUTES), posts[0].createdAt)
        assertEquals(scanTime.minus(5, ChronoUnit.MINUTES), posts[1].createdAt)
        posts.forEach { assertTrue(it.createdAtApprox) }
    }

    @Test
    fun extract_hoursAge_parsedShortAndLongForm() {
        val posts = DomPostExtractor.extract(
            listOf(
                DomPost("checkpoint a", "2h", "https://facebook.com/x"),
                DomPost("checkpoint b", "3 hrs", "https://facebook.com/y"),
            ),
            scanTime,
        )

        assertEquals(scanTime.minus(2, ChronoUnit.HOURS), posts[0].createdAt)
        assertEquals(scanTime.minus(3, ChronoUnit.HOURS), posts[1].createdAt)
    }

    @Test
    fun extract_daysAge_parsedShortAndLongForm() {
        val posts = DomPostExtractor.extract(
            listOf(
                DomPost("checkpoint a", "1d", "https://facebook.com/x"),
                DomPost("checkpoint b", "2 days", "https://facebook.com/y"),
            ),
            scanTime,
        )

        assertEquals(scanTime.minus(1, ChronoUnit.DAYS), posts[0].createdAt)
        assertEquals(scanTime.minus(2, ChronoUnit.DAYS), posts[1].createdAt)
    }

    @Test
    fun extract_justNow_isZeroOffset() {
        val posts = DomPostExtractor.extract(
            listOf(DomPost("checkpoint now", "Just now", "https://facebook.com/x")),
            scanTime,
        )

        assertEquals(scanTime, posts[0].createdAt)
        assertTrue(posts[0].createdAtApprox)
    }

    @Test
    fun extract_unknownAge_defaultsToZeroOffsetButStillApprox() {
        val posts = DomPostExtractor.extract(
            listOf(DomPost("checkpoint mystery", "yesterday", "https://facebook.com/x")),
            scanTime,
        )

        assertEquals(scanTime, posts[0].createdAt)
        assertTrue(posts[0].createdAtApprox)
    }

    @Test
    fun extract_blankText_isDropped() {
        val posts = DomPostExtractor.extract(
            listOf(
                DomPost("", "5m", "https://facebook.com/x"),
                DomPost("   ", "5m", "https://facebook.com/y"),
                DomPost("real post", "5m", "https://facebook.com/z"),
            ),
            scanTime,
        )

        assertEquals(1, posts.size)
        assertEquals("real post", posts[0].text)
    }

    @Test
    fun extract_idIsDomPrefixPlusSixteenHexChars() {
        val posts = DomPostExtractor.extract(
            listOf(DomPost("checkpoint on Queen St", "5m", "https://facebook.com/x")),
            scanTime,
        )

        assertTrue(posts[0].postId.matches(Regex("^dom:[0-9a-f]{16}$")))
    }

    @Test
    fun textHash_isStableUnderWhitespaceChanges() {
        val a = DomPostExtractor.textHash("Checkpoint   on\nQueen St")
        val b = DomPostExtractor.textHash("checkpoint on queen st")

        assertEquals(a, b)
    }

    @Test
    fun textHash_differsForDifferentText() {
        val a = DomPostExtractor.textHash("Checkpoint on Queen St")
        val b = DomPostExtractor.textHash("Checkpoint on King St")

        assertNotEquals(a, b)
    }
}
