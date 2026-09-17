package nz.personal.checkpointwatch.collect

import nz.personal.checkpointwatch.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FeedJsonExtractorTest {

    private fun fixture(name: String): String =
        javaClass.getResource("/fixtures/$name")!!.readText()

    @Test
    fun extract_graphql1_yieldsThreePostsNewestFirstWithCreationTimes() {
        val posts = FeedJsonExtractor.extract(listOf(fixture("graphql_1.txt")))

        assertEquals(
            listOf("1614130810501801", "1614083877173161", "1614056027175946"),
            posts.map { it.postId },
        )
        assertEquals(
            listOf(1789646182L, 1789642021L, 1789639287L),
            posts.map { it.createdAt.epochSecond },
        )
        assertTrue(posts[0].text.startsWith("🛑 CHECKPOINT – Lincoln Road"))
        posts.forEach { assertEquals(false, it.createdAtApprox) }
    }

    @Test
    fun extract_allFourFixtures_yieldsTenUniquePostsSortedNewestFirst() {
        val chunks = listOf(
            fixture("graphql_1.txt"),
            fixture("graphql_2.txt"),
            fixture("graphql_3.txt"),
            fixture("initial_block.json"),
        )

        val posts = FeedJsonExtractor.extract(chunks)

        assertEquals(
            listOf(
                "1614134890501393",
                "1614130810501801",
                "1614083877173161",
                "1614056027175946",
                "1614039387177610",
                "1614038027177746",
                "1614025060512376",
                "1614004273847788",
                "1613994660515416",
                "1613992630515619",
            ),
            posts.map { it.postId },
        )
    }

    @Test
    fun extract_duplicateChunks_collapseToUniquePosts() {
        val chunk = fixture("graphql_1.txt")

        val posts = FeedJsonExtractor.extract(listOf(chunk, chunk))

        assertEquals(3, posts.size)
        assertEquals(
            setOf("1614130810501801", "1614083877173161", "1614056027175946"),
            posts.map { it.postId }.toSet(),
        )
    }

    @Test
    fun extract_malformedLineBetweenValidLines_isSkipped() {
        val chunk = """
            {"data":{"node":{"post_id":"1","creation_time":1000,"message":{"text":"first post"}}}}
            not valid json {{{
            {"data":{"node":{"post_id":"2","creation_time":2000,"message":{"text":"second post"}}}}
        """.trimIndent()

        val posts = FeedJsonExtractor.extract(listOf(chunk))

        assertEquals(listOf("2", "1"), posts.map { it.postId })
    }

    @Test
    fun extract_forLoopPrefix_parses() {
        val chunk = "for (;;);" +
            """{"data":{"node":{"post_id":"1","creation_time":1000,"message":{"text":"prefixed post"}}}}"""

        val posts = FeedJsonExtractor.extract(listOf(chunk))

        assertEquals(listOf("1"), posts.map { it.postId })
        assertEquals("prefixed post", posts[0].text)
    }

    @Test
    fun extract_postIdWithoutMessage_isDropped() {
        val chunk = """{"data":{"node":{"post_id":"1","creation_time":1000}}}"""

        val posts = FeedJsonExtractor.extract(listOf(chunk))

        assertTrue(posts.isEmpty())
    }

    @Test
    fun jsonBlocksFromHtml_pageMin_returnsExactlyOneBlock() {
        val html = fixture("page_min.html")

        val blocks = FeedJsonExtractor.jsonBlocksFromHtml(html)

        assertEquals(1, blocks.size)

        val posts = FeedJsonExtractor.extract(blocks)
        assertEquals(listOf("1614134890501393"), posts.map { it.postId })
    }

    @Test
    fun extract_urlUsesConstantsPostUrl() {
        val posts = FeedJsonExtractor.extract(listOf(fixture("initial_block.json")))

        assertEquals(1, posts.size)
        assertEquals(Constants.postUrl("1614134890501393"), posts[0].url)
        assertEquals("https://www.facebook.com/CheckpointNZ/posts/1614134890501393", posts[0].url)
    }

    @Test
    fun extract_creationTimeBecomesInstant() {
        val posts = FeedJsonExtractor.extract(listOf(fixture("initial_block.json")))

        assertEquals(Instant.ofEpochSecond(1789646506L), posts[0].createdAt)
    }

    // --- photos ---------------------------------------------------------------------------------

    @Test
    fun extract_photoAttachment_yieldsTheImageUrl() {
        // Captured live: a checkpoint post with a photo on it. The uri is signed and expires,
        // which is why the app downloads a copy rather than pointing at it.
        val posts = FeedJsonExtractor.extract(listOf(fixture("initial_block_with_photo.json")))

        assertEquals(1, posts.size)
        assertEquals("1614480107133538", posts[0].postId)
        assertTrue(posts[0].text.startsWith("🛑 CHECKPOINT – Cavendish Drive"))
        assertEquals(
            "https://scontent.fakl1-4.fna.fbcdn.net/v/t39.99422-6/" +
                "814926055_1738813407377727_6580760051676800301_n.png?_nc_sig=REDACTED",
            posts[0].imageUrl,
        )
    }

    @Test
    fun extract_storyWithoutAttachments_hasNoImage() {
        val posts = FeedJsonExtractor.extract(listOf(fixture("initial_block.json")))

        assertEquals(1, posts.size)
        assertNull(posts[0].imageUrl)
    }

    @Test
    fun extract_everyOtherFixture_hasNoImage() {
        val posts = FeedJsonExtractor.extract(listOf(fixture("graphql_1.txt")))

        posts.forEach { assertNull(it.imageUrl) }
    }

    @Test
    fun extract_photoOnAHostThatIsNotFacebooks_isDropped() {
        // The feed is untrusted input and the app fetches whatever address it keeps, unattended,
        // from a background worker. Anything that is not plainly https on *.fbcdn.net is no image.
        listOf(
            "http://scontent.test.fbcdn.net/photo.jpg",
            "https://fbcdn.net.evil.example/photo.jpg",
            "https://example.com/photo.jpg",
            "javascript:alert(1)",
            "",
        ).forEach { uri ->
            val chunk = """
                {"post_id":"1","creation_time":1000,"message":{"text":"a post"},
                 "attachments":[{"styles":{"attachment":{"media":{"photo_image":{"uri":"$uri"}}}}}]}
            """.trimIndent()

            assertNull("uri was $uri", FeedJsonExtractor.extract(listOf(chunk)).single().imageUrl)
        }
    }

    @Test
    fun extract_firstPhotoWins_whenAStoryCarriesSeveral() {
        val chunk = """
            {"post_id":"1","creation_time":1000,"message":{"text":"a post"},
             "attachments":[
               {"styles":{"attachment":{"media":{"photo_image":{"uri":"https://scontent.a.fbcdn.net/first.jpg"}}}}},
               {"styles":{"attachment":{"media":{"photo_image":{"uri":"https://scontent.a.fbcdn.net/second.jpg"}}}}}
             ]}
        """.trimIndent()

        assertEquals(
            "https://scontent.a.fbcdn.net/first.jpg",
            FeedJsonExtractor.extract(listOf(chunk)).single().imageUrl,
        )
    }

    @Test
    fun extract_aPhotoWithoutAUri_isNoPhoto() {
        val chunk = """
            {"post_id":"1","creation_time":1000,"message":{"text":"a post"},
             "attachments":[{"styles":{"attachment":{"media":{"photo_image":{"width":526}}}}}]}
        """.trimIndent()

        assertNull(FeedJsonExtractor.extract(listOf(chunk)).single().imageUrl)
    }

    @Test
    fun extract_postNestedAtRealisticDepth_isStillFound() {
        // Real Facebook payloads nest the post object about 25 levels deep under wrapper keys.
        val depth = 30
        val core = """{"post_id":"1","creation_time":1000,"message":{"text":"deep post"}}"""
        val chunk = "{\"a\":".repeat(depth) + core + "}".repeat(depth)

        val posts = FeedJsonExtractor.extract(listOf(chunk))

        assertEquals(listOf("1"), posts.map { it.postId })
        assertEquals("deep post", posts[0].text)
    }

    @Test
    fun extract_pathologicallyDeepJson_returnsWithoutThrowing() {
        // 50,000 nested arrays: deep enough to overflow the stack either while kotlinx parses it
        // or while we walk it, if not guarded. extract() must never let that propagate.
        val depth = 50_000
        val chunk = "[".repeat(depth) + "1" + "]".repeat(depth)

        val posts = FeedJsonExtractor.extract(listOf(chunk))

        assertTrue(posts.isEmpty())
    }

    @Test
    fun extract_blankPostId_treatedAsAbsent() {
        val chunk = """{"data":{"node":{"post_id":"","creation_time":1000,"message":{"text":"nope"}}}}"""

        val posts = FeedJsonExtractor.extract(listOf(chunk))

        assertTrue(posts.isEmpty())
    }
}
