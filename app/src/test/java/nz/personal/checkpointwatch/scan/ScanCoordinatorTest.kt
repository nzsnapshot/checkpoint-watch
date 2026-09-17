package nz.personal.checkpointwatch.scan

import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import nz.personal.checkpointwatch.collect.CollectResult
import nz.personal.checkpointwatch.collect.DomPost
import nz.personal.checkpointwatch.collect.EndReason
import nz.personal.checkpointwatch.collect.WebViewHost
import nz.personal.checkpointwatch.data.CollectorKind
import nz.personal.checkpointwatch.data.FakeScrapeStore
import nz.personal.checkpointwatch.data.ScanTrigger
import nz.personal.checkpointwatch.data.ScrapeRecorder
import nz.personal.checkpointwatch.data.ScrapeStatus
import nz.personal.checkpointwatch.data.ScrapeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ScanCoordinatorTest {

    private val startTime: Instant = Instant.parse("2026-09-18T09:00:00Z")
    private var now: Instant = startTime

    private val store = FakeScrapeStore()
    private val recorder = ScrapeRecorder(SuspendingStore(store))
    private var lastFinishedAt: Long? = null

    private val collector = FakeCollector()
    private val fetcher = FakeFetcher()

    private fun coordinator() = ScanCoordinator(
        collector = collector,
        httpFetcher = fetcher,
        recorder = recorder,
        lastFinishedAt = { lastFinishedAt },
        clock = { now },
    )

    private fun jsonChunk(postId: String) =
        """{"post_id":"$postId","creation_time":1758186000,"message":{"text":"CHECKPOINT - Lincoln Road, HENDERSON"}}"""

    private fun webViewResult(postId: String = "111") =
        CollectResult(listOf(jsonChunk(postId)), emptyList(), EndReason.NO_MORE_POSTS)

    private fun emptyResult(end: EndReason) = CollectResult(emptyList(), emptyList(), end)

    // --- one at a time ---------------------------------------------------------------------

    @Test
    fun `a second scan is skipped while one is running`() = runTest {
        collector.result = webViewResult()
        collector.gate = CompletableDeferred()
        val coordinator = coordinator()

        val running = launch { coordinator.scan(ScanTrigger.FOREGROUND, FakeHost, force = true) }
        advanceUntilIdle()
        assertSame(ScanState.Scanning, coordinator.state.value)

        assertNull(coordinator.scan(ScanTrigger.BACKGROUND, FakeHost, force = true))
        assertEquals(1, collector.collectCalls)

        collector.gate?.complete(Unit)
        running.join()
        assertSame(ScanState.Idle, coordinator.state.value)
        assertEquals(1, store.scrapes.size)
    }

    // --- throttle --------------------------------------------------------------------------

    @Test
    fun `a scan less than two minutes after the last one is skipped`() = runTest {
        lastFinishedAt = now.minusSeconds(119).toEpochMilli()
        collector.result = webViewResult()

        assertNull(coordinator().scan(ScanTrigger.FOREGROUND, FakeHost, force = false))
        assertEquals(0, collector.collectCalls)
        assertTrue(store.scrapes.isEmpty())
    }

    @Test
    fun `force runs a scan even inside the throttle window`() = runTest {
        lastFinishedAt = now.minusSeconds(10).toEpochMilli()
        collector.result = webViewResult()

        assertNotNull(coordinator().scan(ScanTrigger.FOREGROUND, FakeHost, force = true))
        assertEquals(1, collector.collectCalls)
    }

    @Test
    fun `a scan more than two minutes after the last one runs`() = runTest {
        lastFinishedAt = now.minusSeconds(121).toEpochMilli()
        collector.result = webViewResult()

        assertNotNull(coordinator().scan(ScanTrigger.FOREGROUND, FakeHost, force = false))
    }

    @Test
    fun `the in-memory summary throttles the next scan too`() = runTest {
        collector.result = webViewResult()
        val coordinator = coordinator()

        assertNotNull(coordinator.scan(ScanTrigger.FOREGROUND, FakeHost, force = false))
        assertNull(coordinator.scan(ScanTrigger.FOREGROUND, FakeHost, force = false))
        assertEquals(1, collector.collectCalls)
    }

    // --- outcomes --------------------------------------------------------------------------

    @Test
    fun `a successful scan records the trigger, the collector and a summary`() = runTest {
        collector.result = webViewResult()
        val coordinator = coordinator()

        val outcome = coordinator.scan(ScanTrigger.FOREGROUND, FakeHost, force = true)

        assertEquals(ScrapeStatus.OK, outcome?.status)
        assertEquals(1, outcome?.new)
        val scrape = store.scrapes.single()
        assertEquals(ScanTrigger.FOREGROUND.name, scrape.trigger)
        assertEquals(CollectorKind.WEBVIEW.name, scrape.collector)
        assertEquals(EndReason.NO_MORE_POSTS.name, scrape.endReason)
        assertEquals(ScrapeStatus.OK.name, scrape.status)
        assertEquals(0, fetcher.calls)

        val summary = coordinator.lastSummary.value
        assertEquals(ScrapeStatus.OK, summary?.status)
        assertEquals(1, summary?.new)
        assertEquals(now, summary?.finishedAt)
        assertEquals(ScanTrigger.FOREGROUND, summary?.trigger)
        assertSame(ScanState.Idle, coordinator.state.value)
    }

    @Test
    fun `dom posts are recorded as the dom collector`() = runTest {
        collector.result = CollectResult(
            jsonChunks = emptyList(),
            domPosts = listOf(DomPost(text = "CRASH - Queen Street", age = "20m", link = null)),
            end = EndReason.NO_MORE_POSTS,
        )

        val outcome = coordinator().scan(ScanTrigger.FOREGROUND, FakeHost, force = true)

        assertEquals(ScrapeStatus.OK, outcome?.status)
        assertEquals(CollectorKind.WEBVIEW_DOM.name, store.scrapes.single().collector)
        assertEquals(0, fetcher.calls)
    }

    @Test
    fun `an empty webview scan falls back to the http fetcher`() = runTest {
        collector.result = emptyResult(EndReason.NETWORK_ERROR)
        fetcher.chunks = listOf(jsonChunk("222"))

        val outcome = coordinator().scan(ScanTrigger.BACKGROUND, FakeHost, force = true)

        assertEquals(ScrapeStatus.OK, outcome?.status)
        assertEquals(1, outcome?.new)
        assertEquals(1, fetcher.calls)
        val scrape = store.scrapes.single()
        assertEquals(CollectorKind.HTTP.name, scrape.collector)
        assertEquals(ScanTrigger.BACKGROUND.name, scrape.trigger)
    }

    @Test
    fun `a network error with nothing anywhere is a network failure`() = runTest {
        collector.result = emptyResult(EndReason.NETWORK_ERROR)
        fetcher.chunks = emptyList()
        val coordinator = coordinator()

        val outcome = coordinator.scan(ScanTrigger.BACKGROUND, FakeHost, force = true)

        assertEquals(ScrapeStatus.FAILED_NETWORK, outcome?.status)
        assertEquals(1, fetcher.calls)
        val scrape = store.scrapes.single()
        assertEquals(ScrapeStatus.FAILED_NETWORK.name, scrape.status)
        assertEquals(CollectorKind.NONE.name, scrape.collector)
        assertEquals(ScrapeStatus.FAILED_NETWORK, coordinator.lastSummary.value?.status)
    }

    @Test
    fun `a page with no posts is a no-data failure`() = runTest {
        collector.result = emptyResult(EndReason.LOGIN_WALL)
        fetcher.chunks = emptyList()

        val outcome = coordinator().scan(ScanTrigger.FOREGROUND, FakeHost, force = true)

        assertEquals(ScrapeStatus.FAILED_NO_DATA, outcome?.status)
        val scrape = store.scrapes.single()
        assertEquals(EndReason.LOGIN_WALL.name, scrape.endReason)
        assertEquals(CollectorKind.NONE.name, scrape.collector)
    }

    @Test
    fun `a collector that blows up is recorded as a network failure`() = runTest {
        collector.failure = IllegalStateException("no webview here")
        collector.snapshotResult = emptyResult(EndReason.CANCELLED)
        fetcher.chunks = emptyList()

        val outcome = coordinator().scan(ScanTrigger.BACKGROUND, FakeHost, force = true)

        assertEquals(ScrapeStatus.FAILED_NETWORK, outcome?.status)
        assertEquals(EndReason.NETWORK_ERROR.name, store.scrapes.single().endReason)
    }

    // --- cancellation ----------------------------------------------------------------------

    @Test
    fun `a cancelled scan records what it had captured`() = runTest {
        collector.gate = CompletableDeferred()
        collector.snapshotResult = CollectResult(listOf(jsonChunk("333")), emptyList(), EndReason.CANCELLED)
        val coordinator = coordinator()

        val running = launch { coordinator.scan(ScanTrigger.FOREGROUND, FakeHost, force = true) }
        advanceUntilIdle()
        running.cancelAndJoin()

        val scrape = store.scrapes.single()
        assertEquals(ScrapeStatus.CANCELLED.name, scrape.status)
        assertEquals(EndReason.CANCELLED.name, scrape.endReason)
        assertEquals(CollectorKind.WEBVIEW.name, scrape.collector)
        assertEquals(1, scrape.postsNew)
        assertEquals(1, store.posts.size)
        assertEquals(0, fetcher.calls)
        assertNull(coordinator.lastSummary.value)
        assertSame(ScanState.Idle, coordinator.state.value)
    }

    @Test
    fun `the coordinator is usable again after a cancelled scan`() = runTest {
        collector.gate = CompletableDeferred()
        collector.snapshotResult = emptyResult(EndReason.CANCELLED)
        val coordinator = coordinator()

        val running = launch { coordinator.scan(ScanTrigger.FOREGROUND, FakeHost, force = true) }
        advanceUntilIdle()
        running.cancelAndJoin()

        collector.gate = null
        collector.result = webViewResult()
        assertEquals(ScrapeStatus.OK, coordinator.scan(ScanTrigger.FOREGROUND, FakeHost, force = true)?.status)
    }

    // --- fakes -----------------------------------------------------------------------------

    /**
     * A real Room transaction suspends, so recording is a cancellation point: without the
     * coordinator's `NonCancellable`, a cancelled scan would never reach the database.
     * [FakeScrapeStore] on its own never suspends and would hide that.
     */
    private class SuspendingStore(private val delegate: ScrapeStore) : ScrapeStore by delegate {
        override suspend fun <T> inTransaction(block: suspend () -> T): T {
            yield()
            return delegate.inTransaction(block)
        }
    }

    private object FakeHost : WebViewHost {
        override fun attach(webView: WebView) = Unit
        override fun detach(webView: WebView) = Unit
    }

    private class FakeCollector : PostCollector {
        var result: CollectResult? = null
        var snapshotResult: CollectResult = CollectResult(emptyList(), emptyList(), EndReason.CANCELLED)
        var failure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        var collectCalls = 0

        override suspend fun collect(host: WebViewHost): CollectResult {
            collectCalls++
            gate?.await()
            failure?.let { throw it }
            return result ?: snapshotResult
        }

        override fun snapshot(): CollectResult = snapshotResult
    }

    private class FakeFetcher : LatestFetcher {
        var chunks: List<String> = emptyList()
        var calls = 0

        override suspend fun fetchChunks(): List<String> {
            calls++
            return chunks
        }
    }
}
