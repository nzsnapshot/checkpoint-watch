package nz.personal.checkpointwatch.collect

import nz.personal.checkpointwatch.Constants
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

    @Test
    fun extract_url_usesLinkWhenPresent() {
        val posts = DomPostExtractor.extract(
            listOf(DomPost("checkpoint on Queen St", "5m", "https://facebook.com/specific-post")),
            scanTime,
        )

        assertEquals("https://facebook.com/specific-post", posts[0].url)
    }

    @Test
    fun extract_url_fallsBackToPageUrlWhenLinkNull() {
        val posts = DomPostExtractor.extract(
            listOf(DomPost("checkpoint on Queen St", "5m", null)),
            scanTime,
        )

        assertEquals(Constants.PAGE_URL, posts[0].url)
    }

    // --- the chrome around the post ----------------------------------------------------------
    //
    // innerText of a Facebook article is the post plus everything drawn around it: the page's
    // name, the relative age, the reaction counts, the action buttons. Those change between
    // scans, so an uncleaned text hash changes with them — the same post reads as a new one every
    // scan (duplicate rows, duplicate notifications) and can never bridge onto the JSON feed's
    // `message.text`. Cleaning has to land on exactly the text the JSON carries.

    /** A realistic article, as innerText renders it, around the first fixture post. */
    private val noisyArticle = """
        Online status indicator
        Active
        Checkpoint Watch Auckland
        22m
        ·
        🛑 CHECKPOINT – Lincoln Road, HENDERSON
        After the off-ramp coming from the motorway
        Time: 11:55PM
        All reactions:
        8
        1
        Like
        Comment
        Share
    """.trimIndent()

    /** The same post's `message.text`, straight out of the captured GraphQL response. */
    private fun fixturePostText(): String =
        FeedJsonExtractor.extract(listOf(javaClass.getResource("/fixtures/graphql_1.txt")!!.readText()))
            .first { it.postId == "1614130810501801" }
            .text

    @Test
    fun cleanText_stripsTheHeaderAndFooterChromeDownToTheMessage() {
        assertEquals(fixturePostText(), DomPostExtractor.cleanText(noisyArticle))
    }

    @Test
    fun cleanText_makesTheDomHashMatchTheJsonHash() {
        assertEquals(
            DomPostExtractor.textHash(fixturePostText()),
            DomPostExtractor.textHash(DomPostExtractor.cleanText(noisyArticle)),
        )
    }

    @Test
    fun extract_cleansTheTextItStoresAndHashes() {
        val posts = DomPostExtractor.extract(listOf(DomPost(noisyArticle, "22m", null)), scanTime)

        assertEquals(fixturePostText(), posts.single().text)
    }

    @Test
    fun cleanText_dropsTrailingCountsAndCommentAndShareLines() {
        val cleaned = DomPostExtractor.cleanText(
            """
            🛑 CHECKPOINT – Trig Road
            At the top
            All reactions:
            1.2K
            See more
            12 comments
            3 shares
            Like
            Comment
            Share
            """.trimIndent(),
        )

        assertEquals("🛑 CHECKPOINT – Trig Road\nAt the top", cleaned)
    }

    @Test
    fun cleanText_stripsTheHeaderChromeOffAHeaderlessPostToo() {
        // No report header anywhere, so there is nothing to anchor on: the leading lines have to
        // be recognised as chrome one at a time, and the post kept from the first line that isn't.
        val cleaned = DomPostExtractor.cleanText(
            "Online status indicator\nActive\nCheckpoint Watch Auckland\n22m\n·\n" +
                "Roads are clear tonight\nLike\nShare",
        )

        assertEquals("Roads are clear tonight", cleaned)
    }

    @Test
    fun cleanText_headerlessPostIsTheSamePostAnHourLater() {
        // The age line is the whole problem: it moves every scan. If it survives cleaning, the
        // same post gets a new hash, a new dom: id, a new row and a new-report notification every
        // single scan.
        fun article(age: String) =
            "Checkpoint Watch Auckland\n$age\n·\nRoads are clear tonight, nothing reported\nLike\nComment\nShare"

        val young = DomPostExtractor.extract(listOf(DomPost(article("22m"), "22m", null)), scanTime).single()
        val older = DomPostExtractor.extract(
            listOf(DomPost(article("3h"), "3h", null)),
            scanTime.plus(3, ChronoUnit.HOURS),
        ).single()

        assertEquals("Roads are clear tonight, nothing reported", young.text)
        assertEquals(young.text, older.text)
        assertEquals(young.postId, older.postId)
        assertEquals(DomPostExtractor.textHash(young.text), DomPostExtractor.textHash(older.text))
    }

    @Test
    fun cleanText_keepsAPreambleTheAuthorWroteBeforeTheFirstHeader() {
        // ReportParser deliberately keeps text before the first header (it becomes the first
        // report's details), so cleaning must not delete it — or the DOM copy of a post hashes
        // differently from the JSON copy and the two never bridge.
        val posted = "UPDATE\n🛑 CHECKPOINT – Trig Road\nStill there"
        val article = "Online status indicator\nActive\nCheckpoint Watch Auckland\n45m\n·\n" +
            posted + "\nAll reactions:\n4\nLike\nComment\nShare"

        assertEquals(posted, DomPostExtractor.cleanText(article))
        assertEquals(DomPostExtractor.textHash(posted), DomPostExtractor.textHash(DomPostExtractor.cleanText(article)))
    }

    @Test
    fun cleanText_onlyStripsAWholeLineThatIsExactlyChrome() {
        // "Active" on its own is the page's online-status line; "Active checkpoint on..." is the
        // post. Only the exact tokens are chrome.
        val posted = "Active checkpoint on Lincoln Road, still there"

        assertEquals(posted, DomPostExtractor.cleanText("Checkpoint Watch Auckland\n22m\n$posted"))
        assertEquals("Follow the diversion signs", DomPostExtractor.cleanText("Follow\nFollow the diversion signs"))
    }

    // An article that is nothing but chrome is a post with no text — a caption-less photo, say.
    // The spec drops those. Keeping the chrome instead would keep the age line with it, so the
    // same photo would get a new hash, a new dom: id, a new row and a new-report notification on
    // every single scan.

    @Test
    fun cleanText_isEmptyWhenTheArticleIsNothingButChrome() {
        val allChrome = "Online status indicator\nActive\nCheckpoint Watch Auckland\n22m\n·\n" +
            "All reactions:\n8\nLike\nComment\nShare"

        assertEquals("", DomPostExtractor.cleanText(allChrome))
    }

    @Test
    fun extract_dropsAnArticleThatIsNothingButChrome() {
        val posts = DomPostExtractor.extract(
            listOf(
                DomPost(
                    "Online status indicator\nActive\nCheckpoint Watch Auckland\n22m\n·\n" +
                        "All reactions:\n8\nLike\nComment\nShare",
                    "22m",
                    null,
                ),
                DomPost("Like\nComment\nShare", "5m", null),
                DomPost("🛑 CHECKPOINT – Trig Road\nBoth directions\nLike\nShare", "5m", null),
            ),
            scanTime,
        )

        assertEquals(1, posts.size)
        assertEquals("🛑 CHECKPOINT – Trig Road\nBoth directions", posts.single().text)
    }

    @Test
    fun cleanText_keepsAPostThatIsOnlyOneRealWord() {
        assertEquals("Clear", DomPostExtractor.cleanText("Checkpoint Watch Auckland\n22m\nClear\nLike\nShare"))
        assertEquals("Clear", DomPostExtractor.extract(listOf(DomPost("Clear", "5m", null)), scanTime).single().text)
    }

    // --- links --------------------------------------------------------------------------------

    @Test
    fun extract_url_rejectsLinksThatArentFacebookOverHttps() {
        val rejected = listOf(
            "http://www.facebook.com/CheckpointNZ/posts/1",
            "https://facebook.com.example.com/posts/1",
            "https://evil.example/posts/1",
            "javascript:alert(1)",
            "not a url at all",
        )

        rejected.forEach { link ->
            val posts = DomPostExtractor.extract(listOf(DomPost("checkpoint on Queen St", "5m", link)), scanTime)
            assertEquals(link, Constants.PAGE_URL, posts.single().url)
        }
    }

    @Test
    fun extract_url_keepsFacebookHostsAndSubdomains() {
        val kept = listOf(
            "https://www.facebook.com/CheckpointNZ/posts/1",
            "https://facebook.com/CheckpointNZ/posts/1",
            "https://m.facebook.com/CheckpointNZ/posts/1",
        )

        kept.forEach { link ->
            val posts = DomPostExtractor.extract(listOf(DomPost("checkpoint on Queen St", "5m", link)), scanTime)
            assertEquals(link, link, posts.single().url)
        }
    }

    // --- the ages Facebook actually renders ---------------------------------------------------

    @Test
    fun extract_secondsAge_isTreatedAsNow() {
        val posts = DomPostExtractor.extract(
            listOf(
                DomPost("checkpoint a", "45s", null),
                DomPost("checkpoint b", "30 secs", null),
            ),
            scanTime,
        )

        posts.forEach { assertEquals(scanTime, it.createdAt) }
    }

    @Test
    fun extract_weeksAge_parsedShortAndLongForm() {
        val posts = DomPostExtractor.extract(
            listOf(DomPost("checkpoint a", "1w", null), DomPost("checkpoint b", "2 weeks", null)),
            scanTime,
        )

        assertEquals(scanTime.minus(7, ChronoUnit.DAYS), posts[0].createdAt)
        assertEquals(scanTime.minus(14, ChronoUnit.DAYS), posts[1].createdAt)
    }

    @Test
    fun extract_yearsAge_parsedShortAndLongForm() {
        val posts = DomPostExtractor.extract(
            listOf(DomPost("checkpoint a", "1y", null), DomPost("checkpoint b", "2 yrs", null)),
            scanTime,
        )

        assertEquals(scanTime.minus(365, ChronoUnit.DAYS), posts[0].createdAt)
        assertEquals(scanTime.minus(730, ChronoUnit.DAYS), posts[1].createdAt)
    }

    @Test
    fun extract_age_caseInsensitive_hoursShortForm() {
        val posts = DomPostExtractor.extract(
            listOf(DomPost("checkpoint a", "2H", "https://facebook.com/x")),
            scanTime,
        )

        assertEquals(scanTime.minus(2, ChronoUnit.HOURS), posts[0].createdAt)
    }

    @Test
    fun extract_age_caseInsensitive_hoursLongForm() {
        val posts = DomPostExtractor.extract(
            listOf(DomPost("checkpoint b", "3 HRS", "https://facebook.com/x")),
            scanTime,
        )

        assertEquals(scanTime.minus(3, ChronoUnit.HOURS), posts[0].createdAt)
    }

    @Test
    fun extract_age_caseInsensitive_daysLongForm() {
        val posts = DomPostExtractor.extract(
            listOf(DomPost("checkpoint c", "2 DAYS", "https://facebook.com/x")),
            scanTime,
        )

        assertEquals(scanTime.minus(2, ChronoUnit.DAYS), posts[0].createdAt)
    }

    @Test
    fun extract_age_caseInsensitive_justNow() {
        val posts = DomPostExtractor.extract(
            listOf(DomPost("checkpoint d", "JUST NOW", "https://facebook.com/x")),
            scanTime,
        )

        assertEquals(scanTime, posts[0].createdAt)
    }
}
