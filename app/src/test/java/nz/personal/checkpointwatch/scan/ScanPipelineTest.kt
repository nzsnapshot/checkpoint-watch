package nz.personal.checkpointwatch.scan

import nz.personal.checkpointwatch.collect.CollectResult
import nz.personal.checkpointwatch.collect.DomPost
import nz.personal.checkpointwatch.collect.EndReason
import nz.personal.checkpointwatch.data.CollectorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ScanPipelineTest {

    private val scanTime: Instant = Instant.parse("2026-09-18T09:00:00Z")

    private fun jsonChunk(postId: String, text: String) =
        """{"post_id":"$postId","creation_time":1758186000,"message":{"text":"$text"}}"""

    private fun result(
        jsonChunks: List<String> = emptyList(),
        domPosts: List<DomPost> = emptyList(),
        end: EndReason = EndReason.NO_MORE_POSTS,
    ) = CollectResult(jsonChunks, domPosts, end)

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
}
