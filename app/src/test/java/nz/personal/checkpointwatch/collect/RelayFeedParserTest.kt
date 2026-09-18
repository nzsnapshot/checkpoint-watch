package nz.personal.checkpointwatch.collect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class RelayFeedParserTest {

    private fun post(
        id: String = "1615051343743081",
        createdAt: String = "1789736673",
        text: String = "🛑 CHECKPOINT – Cavendish Drive, MANUKAU\\nNear McDonald’s\\nTime: 1:03AM",
        url: String = "https://www.facebook.com/CheckpointNZ/posts/1615051343743081",
        image: String = "null",
    ) = """{"id":"$id","createdAt":$createdAt,"text":"$text","url":"$url","image":$image}"""

    private fun feed(
        version: String = "1",
        page: String = "CheckpointNZ",
        generatedAt: String = "1789741768",
        lastFullScanAt: String = "1789741768",
        outcome: String = "FEED",
        posts: List<String> = listOf(post()),
    ) = """{"version":$version,"page":"$page","generatedAt":$generatedAt,""" +
        """"lastFullScanAt":$lastFullScanAt,"collector":{"outcome":"$outcome","posts":${posts.size}},""" +
        """"posts":[${posts.joinToString(",")}]}"""

    @Test
    fun parse_readsTheClocksTheOutcomeAndThePosts() {
        val parsed = RelayFeedParser.parse(feed())

        assertNotNull(parsed)
        assertEquals(Instant.ofEpochSecond(1789741768), parsed!!.generatedAt)
        assertEquals(Instant.ofEpochSecond(1789741768), parsed.lastFullScanAt)
        assertEquals("FEED", parsed.outcome)
        val only = parsed.posts.single()
        assertEquals("1615051343743081", only.postId)
        assertEquals(Instant.ofEpochSecond(1789736673), only.createdAt)
        assertFalse(only.createdAtApprox)
        assertEquals("🛑 CHECKPOINT – Cavendish Drive, MANUKAU\nNear McDonald’s\nTime: 1:03AM", only.text)
        assertNull(only.imageUrl)
    }

    @Test
    fun parse_aCollectorThatHasNeverHadAFullScanSaysSoWithNull() {
        val parsed = RelayFeedParser.parse(feed(lastFullScanAt = "null"))

        assertNotNull(parsed)
        assertNull(parsed!!.lastFullScanAt)
    }

    @Test
    fun parse_anythingThatIsNotTheFeedIsNull() {
        assertNull(RelayFeedParser.parse(""))
        assertNull(RelayFeedParser.parse("<html>404: Not Found</html>"))
        assertNull(RelayFeedParser.parse("[1,2,3]"))
        assertNull(RelayFeedParser.parse("""{"version":1}"""))
        assertNull(RelayFeedParser.parse(feed().dropLast(5)))
    }

    @Test
    fun parse_onlyVersionOneIsUnderstood() {
        // A later collector may change what the fields mean; guessing at them is worse than
        // falling back to the phone's own scan.
        assertNull(RelayFeedParser.parse(feed(version = "2")))
        assertNull(RelayFeedParser.parse(feed(version = "\"1\"")))
    }

    @Test
    fun parse_aFeedForSomeOtherPageIsNotOurs() {
        assertNull(RelayFeedParser.parse(feed(page = "SomeoneElse")))
    }

    @Test
    fun parse_thePostLinkIsBuiltFromTheIdAndNeverTakenFromTheFeed() {
        // The link is opened in the owner's browser, so it is never whatever a file on the internet
        // says it is.
        val parsed = RelayFeedParser.parse(feed(posts = listOf(post(url = "https://evil.example/phish"))))

        assertEquals(
            "https://www.facebook.com/CheckpointNZ/posts/1615051343743081",
            parsed!!.posts.single().url,
        )
    }

    @Test
    fun parse_aPostWithoutANumericIdIsDropped_andTheRestAreKept() {
        val parsed = RelayFeedParser.parse(
            feed(
                posts = listOf(
                    post(id = "../../etc/passwd"),
                    post(id = ""),
                    post(id = "dom:abcdef0123456789"),
                    post(id = "1614993713748844"),
                ),
            ),
        )

        assertEquals(listOf("1614993713748844"), parsed!!.posts.map { it.postId })
    }

    @Test
    fun parse_aPostWithNoWordsOrNoUsableTimeIsDropped() {
        val parsed = RelayFeedParser.parse(
            feed(
                posts = listOf(
                    post(id = "1", text = "   "),
                    post(id = "2", createdAt = "0"),
                    post(id = "3", createdAt = "-5"),
                    post(id = "4", createdAt = "\"yesterday\""),
                    post(id = "5"),
                ),
            ),
        )

        assertEquals(listOf("5"), parsed!!.posts.map { it.postId })
    }

    @Test
    fun parse_aPhotoIsKeptOnlyFromFacebooksContentHosts() {
        val parsed = RelayFeedParser.parse(
            feed(
                posts = listOf(
                    post(id = "1", image = "\"https://scontent.fakl1-3.fna.fbcdn.net/v/a.png?x=1\""),
                    post(id = "2", image = "\"https://fbcdn.net.evil.example/a.png\""),
                    post(id = "3", image = "\"http://scontent.fakl1-3.fna.fbcdn.net/a.png\""),
                    post(id = "4", image = "42"),
                ),
            ),
        )

        assertEquals(
            listOf("https://scontent.fakl1-3.fna.fbcdn.net/v/a.png?x=1", null, null, null),
            parsed!!.posts.map { it.imageUrl },
        )
    }

    @Test
    fun parse_noMoreThanTheContractsHundredAndFiftyPostsAreRead() {
        val many = (1..400).map { post(id = (1000 + it).toString()) }

        val parsed = RelayFeedParser.parse(feed(posts = many))

        assertEquals(150, parsed!!.posts.size)
        assertEquals("1001", parsed.posts.first().postId)
    }

    @Test
    fun parse_aDuplicatedPostIsReadOnce() {
        val parsed = RelayFeedParser.parse(feed(posts = listOf(post(id = "7"), post(id = "7"))))

        assertEquals(1, parsed!!.posts.size)
    }

    @Test
    fun parse_postsThatAreNotObjectsAreSkipped() {
        val parsed = RelayFeedParser.parse(feed(posts = listOf("null", "\"text\"", "[]", post(id = "9"))))

        assertEquals(listOf("9"), parsed!!.posts.map { it.postId })
    }

    @Test
    fun parse_theCollectorsRealOutput_everyPostSurvivesWithItsPhoto() {
        // The feed exactly as the home PC published it on 2026-09-19: 45 posts, 20 with a photo.
        // If the app ever drops one of these, the two sides no longer agree on the contract.
        val live = javaClass.getResource("/fixtures/relay_feed.json")!!.readText()

        val parsed = RelayFeedParser.parse(live)

        assertEquals(45, parsed!!.posts.size)
        assertEquals(20, parsed.posts.count { it.imageUrl != null })
        assertEquals("1615051343743081", parsed.posts.first().postId)
        assertEquals("FEED", parsed.outcome)
        assertEquals(parsed.posts.sortedByDescending { it.createdAt }, parsed.posts)
    }
}
