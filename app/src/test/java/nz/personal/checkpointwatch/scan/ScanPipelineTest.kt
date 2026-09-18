package nz.personal.checkpointwatch.scan

import nz.personal.checkpointwatch.collect.CollectResult
import nz.personal.checkpointwatch.collect.DomPost
import nz.personal.checkpointwatch.collect.EndReason
import nz.personal.checkpointwatch.collect.PluginPost
import nz.personal.checkpointwatch.collect.RawPost
import nz.personal.checkpointwatch.data.CollectorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ScanPipelineTest {

    private val scanTime: Instant = Instant.parse("2026-09-18T09:00:00Z")

    private fun jsonChunk(postId: String, text: String) =
        """{"post_id":"$postId","creation_time":1758186000,"message":{"text":"$text"}}"""

    private fun pluginPost(text: String, utime: Long = 1758186000, image: String? = null) =
        PluginPost(utime = utime, link = null, text = text, image = image)

    private fun result(
        jsonChunks: List<String> = emptyList(),
        domPosts: List<DomPost> = emptyList(),
        end: EndReason = EndReason.NO_MORE_POSTS,
        pluginPosts: List<PluginPost> = emptyList(),
    ) = CollectResult(jsonChunks, domPosts, end, pluginPosts = pluginPosts)

    @Test
    fun `json posts win and the http fetch is never needed`() {
        var httpCalls = 0
        val collected = result(
            jsonChunks = listOf(jsonChunk("111", "CHECKPOINT - Lincoln Road, HENDERSON")),
            domPosts = listOf(DomPost(text = "CRASH - Queen Street", age = "20m", link = null)),
        )

        val (posts, kind) = ScanPipeline.choose(collected, { httpCalls++; emptyList() }, scanTime)

        assertEquals(CollectorKind.WEBVIEW, kind)
        assertEquals(listOf("111"), posts.map { it.postId })
        assertEquals(0, httpCalls)
    }

    @Test
    fun `dom posts are used when the json yields nothing`() {
        var httpCalls = 0
        val collected = result(domPosts = listOf(DomPost(text = "CRASH - Queen Street", age = "20m", link = null)))

        val (posts, kind) = ScanPipeline.choose(collected, { httpCalls++; emptyList() }, scanTime)

        assertEquals(CollectorKind.WEBVIEW_DOM, kind)
        assertEquals(1, posts.size)
        assertTrue(posts.single().postId.startsWith("dom:"))
        assertEquals(scanTime.minusSeconds(20 * 60), posts.single().createdAt)
        assertEquals(0, httpCalls)
    }

    @Test
    fun `http chunks are used when the webview yields nothing`() {
        val collected = result(end = EndReason.NETWORK_ERROR)

        val (posts, kind) = ScanPipeline.choose(
            collected,
            { listOf(jsonChunk("222", "CHECKPOINT - Great South Road, PAPAKURA")) },
            scanTime,
        )

        assertEquals(CollectorKind.HTTP, kind)
        assertEquals(listOf("222"), posts.map { it.postId })
    }

    @Test
    fun `http chunks are used when there is no collect result at all`() {
        val (posts, kind) = ScanPipeline.choose(
            null,
            { listOf(jsonChunk("333", "CRASH - Pakuranga Road")) },
            scanTime,
        )

        assertEquals(CollectorKind.HTTP, kind)
        assertEquals(listOf("333"), posts.map { it.postId })
    }

    // --- the starved scan, which is the one the owner's phone actually has ------------------

    @Test
    fun `the widget's posts join the one post the page gave up, and the label says so`() {
        // The real VPN scan: the page hands over the single post embedded in its HTML and nothing
        // else, and the widget then renders the five newest — one of which is that same post. The
        // scan is worth five posts, not six and not one, and the log should say where they came
        // from rather than crediting the page with four posts it never produced.
        val shared = "CHECKPOINT - Cavendish Drive, MANUKAU"
        val collected = result(
            jsonChunks = listOf(jsonChunk("111", shared)),
            pluginPosts = listOf(
                pluginPost(shared),
                pluginPost("CRASH - Pakuranga Road"),
                pluginPost("SPEED CAMERA - Trig Road"),
                pluginPost("HAZARD - SH1"),
                pluginPost("CHECKPOINT - Lincoln Road, HENDERSON"),
            ),
        )

        val (posts, kind) = ScanPipeline.choose(collected, { emptyList() }, scanTime)

        assertEquals(CollectorKind.PLUGIN, kind)
        assertEquals(5, posts.size)
        // The post both sources found is kept under its real numeric id, not the placeholder.
        assertEquals(listOf("111"), posts.filter { it.text == shared }.map { it.postId })
        assertEquals(4, posts.count { it.postId.startsWith("dom:") })
    }

    @Test
    fun `a widget that only repeats what the page already gave is not credited with the scan`() {
        val shared = "CHECKPOINT - Cavendish Drive, MANUKAU"
        val collected = result(
            jsonChunks = listOf(jsonChunk("111", shared)),
            pluginPosts = listOf(pluginPost(shared)),
        )

        val (posts, kind) = ScanPipeline.choose(collected, { emptyList() }, scanTime)

        assertEquals(CollectorKind.WEBVIEW, kind)
        assertEquals(listOf("111"), posts.map { it.postId })
    }

    @Test
    fun `a photo the widget saw is kept when the page's copy of the post had none`() {
        // Same post, two sources: the numeric id wins, but the photo is not thrown away with the
        // copy that carried it.
        val shared = "CHECKPOINT - Cavendish Drive, MANUKAU"
        val collected = result(
            jsonChunks = listOf(jsonChunk("111", shared)),
            pluginPosts = listOf(pluginPost(shared, image = "https://scontent.test.fbcdn.net/photo.jpg")),
        )

        val (posts, _) = ScanPipeline.choose(collected, { emptyList() }, scanTime)

        assertEquals("https://scontent.test.fbcdn.net/photo.jpg", posts.single().imageUrl)
    }

    @Test
    fun `widget posts alone are the whole scan`() {
        val collected = result(pluginPosts = listOf(pluginPost("CRASH - Pakuranga Road")))

        val (posts, kind) = ScanPipeline.choose(collected, { emptyList() }, scanTime)

        assertEquals(CollectorKind.PLUGIN, kind)
        assertEquals(1, posts.size)
        assertEquals(Instant.ofEpochSecond(1758186000), posts.single().createdAt)
        // Exact, because the widget hands over unix seconds rather than "22m".
        assertEquals(false, posts.single().createdAtApprox)
    }

    @Test
    fun `the page's scraped text is not added on top of the widget's`() {
        // In a starved scan the DOM fallback is the same single embedded post, read less well.
        // Adding it would put a second placeholder row beside the widget's copy of it.
        val collected = result(
            domPosts = listOf(DomPost(text = "CRASH - Queen Street", age = "20m", link = null)),
            pluginPosts = listOf(pluginPost("CHECKPOINT - Trig Road")),
        )

        val (posts, kind) = ScanPipeline.choose(collected, { emptyList() }, scanTime)

        assertEquals(CollectorKind.PLUGIN, kind)
        assertEquals(listOf("CHECKPOINT - Trig Road"), posts.map { it.text })
    }

    @Test
    fun `a widget that rendered nothing usable leaves the old order alone`() {
        val collected = result(
            domPosts = listOf(DomPost(text = "CRASH - Queen Street", age = "20m", link = null)),
            pluginPosts = listOf(pluginPost("   ")),
        )

        val (posts, kind) = ScanPipeline.choose(collected, { emptyList() }, scanTime)

        assertEquals(CollectorKind.WEBVIEW_DOM, kind)
        assertEquals(1, posts.size)
        assertNull(posts.single().imageUrl)
    }

    @Test
    fun `nothing anywhere is NONE`() {
        val (posts, kind) = ScanPipeline.choose(result(), { emptyList() }, scanTime)

        assertEquals(CollectorKind.NONE, kind)
        assertTrue(posts.isEmpty())
    }

    @Test
    fun `unusable chunks and blank dom posts are NONE`() {
        val collected = result(
            jsonChunks = listOf("not json at all"),
            domPosts = listOf(DomPost(text = "   ", age = "2m", link = null)),
        )

        val (posts, kind) = ScanPipeline.choose(collected, { listOf("{}") }, scanTime)

        assertEquals(CollectorKind.NONE, kind)
        assertTrue(posts.isEmpty())
    }

    // --- the home collector's posts, joined to whatever the phone found ---------------------

    private fun relayPost(id: String, text: String, at: Long = 1758186000, image: String? = null) = RawPost(
        postId = id,
        createdAt = Instant.ofEpochSecond(at),
        createdAtApprox = false,
        text = text,
        url = "https://www.facebook.com/CheckpointNZ/posts/$id",
        imageUrl = image,
    )

    @Test
    fun `relay posts the phone did not find are added, and the scan keeps its own label`() {
        val scan = ScanPipeline.choose(
            result(jsonChunks = listOf(jsonChunk("111", "CHECKPOINT - Lincoln Road, HENDERSON"))),
            { emptyList() },
            scanTime,
        )

        val (posts, kind) = ScanPipeline.withRelay(
            scan,
            listOf(relayPost("222", "CRASH - Queen Street", at = 1758186600), relayPost("111", "CHECKPOINT - Lincoln Road, HENDERSON")),
        )

        assertEquals(CollectorKind.WEBVIEW, kind)
        assertEquals(listOf("222", "111"), posts.map { it.postId })
    }

    @Test
    fun `a scan that found nothing is rescued by the relay and labelled as such`() {
        val scan = ScanPipeline.choose(result(), { emptyList() }, scanTime)

        val (posts, kind) = ScanPipeline.withRelay(scan, listOf(relayPost("222", "CRASH - Queen Street")))

        assertEquals(CollectorKind.RELAY, kind)
        assertEquals(listOf("222"), posts.map { it.postId })
    }

    @Test
    fun `no relay posts leaves the scan exactly as it was`() {
        val scan = ScanPipeline.choose(result(), { emptyList() }, scanTime)

        assertEquals(scan, ScanPipeline.withRelay(scan, emptyList()))
    }

    @Test
    fun `a widget post and its relay twin are one post, and the one with the real id is kept`() {
        // The widget only ever has a synthetic id. The relay's copy of the same words carries the
        // real one, so it is the copy worth keeping - with the widget's photo if it has none.
        val photo = "https://scontent.fakl1-3.fna.fbcdn.net/v/a.png"
        val scan = ScanPipeline.choose(
            result(pluginPosts = listOf(pluginPost("CHECKPOINT - Trig Road", image = photo))),
            { emptyList() },
            scanTime,
        )

        val (posts, kind) = ScanPipeline.withRelay(scan, listOf(relayPost("333", "CHECKPOINT - Trig Road")))

        assertEquals(CollectorKind.PLUGIN, kind)
        assertEquals(listOf("333"), posts.map { it.postId })
        assertEquals(photo, posts.single().imageUrl)
    }

    @Test
    fun `a photo only the relay saw is carried onto the phone's copy`() {
        val photo = "https://scontent.fakl1-3.fna.fbcdn.net/v/a.png"
        val scan = ScanPipeline.choose(
            result(jsonChunks = listOf(jsonChunk("111", "CHECKPOINT - Lincoln Road, HENDERSON"))),
            { emptyList() },
            scanTime,
        )

        val (posts, _) = ScanPipeline.withRelay(
            scan,
            listOf(relayPost("111", "CHECKPOINT - Lincoln Road, HENDERSON", image = photo)),
        )

        assertEquals(photo, posts.single().imageUrl)
    }
}
