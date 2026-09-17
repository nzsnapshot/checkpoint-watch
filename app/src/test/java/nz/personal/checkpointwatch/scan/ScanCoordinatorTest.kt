package nz.personal.checkpointwatch.scan

import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
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
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class ScanCoordinatorTest {

    private val startTime: Instant = Instant.parse("2026-09-18T09:00:00Z")
    private var now: Instant = startTime

    private val store = FakeScrapeStore()
    private val recorder = ScrapeRecorder(SuspendingStore(store))
    private var lastFinishedAt: Long? = null

    private val collector = FakeCollector()
    private val fetcher = FakeFetcher()
    private val diagnostics = FakeDiagnosticsStore()

    /**
     * Stands in for `Dispatchers.Default` in production: a distinct dispatcher, so "the work ran
     * on the one that was injected" is a claim these tests can actually make, that still runs
     * inline and keeps them deterministic.
     */
    private val computeDispatcher: CoroutineDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
    }

    /**
     * [Dispatchers.Unconfined] by default so these tests read as straight-line code; the one test
     * that cares about which dispatcher the work lands on passes its own.
     */
    private fun coordinator(
        recorder: ScrapeRecorder = this.recorder,
        compute: CoroutineDispatcher = Dispatchers.Unconfined,
    ) = ScanCoordinator(
        collector = collector,
        httpFetcher = fetcher,
        recorder = recorder,
        lastFinishedAt = { lastFinishedAt },
        diagnostics = diagnostics,
        clock = { now },
        computeDispatcher = compute,
    )

    private fun jsonChunk(postId: String) =
        """{"post_id":"$postId","creation_time":1758186000,"message":{"text":"CHECKPOINT - Lincoln Road, HENDERSON"}}"""

    private fun webViewResult(postId: String = "111", diagnostics: String? = COLLECTOR_DIAGNOSTICS) =
        CollectResult(listOf(jsonChunk(postId)), emptyList(), EndReason.NO_MORE_POSTS, diagnostics)

    private fun emptyResult(end: EndReason, diagnostics: String? = COLLECTOR_DIAGNOSTICS) =
        CollectResult(emptyList(), emptyList(), end, diagnostics)

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

    // --- diagnostics -----------------------------------------------------------------------
    //
    // The owner's phone cannot be inspected from here, so the evidence has to survive the scan
    // and be somewhere they can copy it from. That means every ending: a scan that fails or is
    // cancelled is exactly the scan worth explaining.

    @Test
    fun `a successful scan hands its diagnostics to the store, under its own trigger`() = runTest {
        collector.result = webViewResult()

        coordinator().scan(ScanTrigger.FOREGROUND, FakeHost, force = true)

        val written = diagnostics.written.single()
        assertEquals(ScanTrigger.FOREGROUND, written.first)
        // The collector's own account is kept whole...
        assertTrue(written.second.contains(COLLECTOR_DIAGNOSTICS))
        // ...under the facts only the coordinator knows.
        assertTrue(written.second.contains("trigger=FOREGROUND"))
        assertTrue(written.second.contains("collector=${CollectorKind.WEBVIEW.name}"))
        assertTrue(written.second.contains("postsExtracted=1"))
    }

    @Test
    fun `a failed scan is written too, and says so`() = runTest {
        collector.result = emptyResult(EndReason.NETWORK_ERROR)
        fetcher.chunks = emptyList()

        coordinator().scan(ScanTrigger.BACKGROUND, FakeHost, force = true)

        val written = diagnostics.written.single()
        assertEquals(ScanTrigger.BACKGROUND, written.first)
        assertTrue(written.second.contains("status=${ScrapeStatus.FAILED_NETWORK.name}"))
        assertTrue(written.second.contains("postsExtracted=0"))
    }

    @Test
    fun `a cancelled scan writes what it had`() = runTest {
        collector.gate = CompletableDeferred()
        collector.snapshotResult = CollectResult(
            jsonChunks = listOf(jsonChunk("333")),
            domPosts = emptyList(),
            end = EndReason.CANCELLED,
            diagnostics = COLLECTOR_DIAGNOSTICS,
        )
        val coordinator = coordinator()

        val running = launch { coordinator.scan(ScanTrigger.FOREGROUND, FakeHost, force = true) }
        advanceUntilIdle()
        running.cancelAndJoin()

        val written = diagnostics.written.single()
        assertEquals(ScanTrigger.FOREGROUND, written.first)
        assertTrue(written.second.contains(COLLECTOR_DIAGNOSTICS))
        assertTrue(written.second.contains("status=${ScrapeStatus.CANCELLED.name}"))
    }

    @Test
    fun `a collector that sent no log still leaves a record of the scan`() = runTest {
        collector.result = emptyResult(EndReason.NETWORK_ERROR, diagnostics = null)
        fetcher.chunks = emptyList()

        coordinator().scan(ScanTrigger.BACKGROUND, FakeHost, force = true)

        val written = diagnostics.written.single()
        assertTrue(written.second.contains("endReason=${EndReason.NETWORK_ERROR.name}"))
    }

    @Test
    fun `a diagnostics store that throws cannot fail a scan`() = runTest {
        diagnostics.failure = IllegalStateException("no room on the phone")
        collector.result = webViewResult()

        val outcome = coordinator().scan(ScanTrigger.FOREGROUND, FakeHost, force = true)

        assertEquals(ScrapeStatus.OK, outcome?.status)
        assertEquals(1, store.scrapes.size)
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

    // --- where the work happens ------------------------------------------------------------
    //
    // The screen calls scan() from the main thread. Between them, ScanPipeline.choose parses half
    // a megabyte or more of feed JSON into element trees and the recorder walks every post: work
    // that must never run on the caller's thread, or the list janks for as long as a scan takes.

    @Test
    fun `the pipeline and the recorder run on the injected dispatcher, not the caller's`() = runTest {
        val capturing = ContextCapturingStore(store)
        val coordinator = coordinator(recorder = ScrapeRecorder(capturing), compute = computeDispatcher)
        collector.result = webViewResult()

        coordinator.scan(ScanTrigger.FOREGROUND, FakeHost, force = true)

        assertSame(computeDispatcher, capturing.interceptor)
        assertEquals(1, store.scrapes.size)
    }

    @Test
    fun `a cancelled scan is recorded off the caller's thread as well`() = runTest {
        val capturing = ContextCapturingStore(store)
        val coordinator = coordinator(recorder = ScrapeRecorder(capturing), compute = computeDispatcher)
        collector.gate = CompletableDeferred()
        collector.snapshotResult = CollectResult(listOf(jsonChunk("333")), emptyList(), EndReason.CANCELLED)

        val running = launch { coordinator.scan(ScanTrigger.FOREGROUND, FakeHost, force = true) }
        advanceUntilIdle()
        running.cancelAndJoin()

        assertSame(computeDispatcher, capturing.interceptor)
        assertEquals(ScrapeStatus.CANCELLED.name, store.scrapes.single().status)
    }

    // --- fakes -----------------------------------------------------------------------------

    /** Reports the dispatcher the recorder's transaction was actually running on. */
    private class ContextCapturingStore(private val delegate: ScrapeStore) : ScrapeStore by delegate {
        var interceptor: ContinuationInterceptor? = null

        override suspend fun <T> inTransaction(block: suspend () -> T): T {
            interceptor = currentCoroutineContext()[ContinuationInterceptor]
            yield()
            return delegate.inTransaction(block)
        }
    }

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
        override val name: String = "FakeHost"
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

    private class FakeDiagnosticsStore : ScanDiagnosticsStore {
        val written = mutableListOf<Pair<ScanTrigger, String>>()
        var failure: Exception? = null

        override suspend fun write(trigger: ScanTrigger, text: String) {
            failure?.let { throw it }
            written.add(trigger to text)
        }

        override suspend fun read(trigger: ScanTrigger): String? =
            written.lastOrNull { it.first == trigger }?.second

        override suspend fun exists(trigger: ScanTrigger): Boolean = read(trigger) != null
    }

    private companion object {
        /** Stands in for the block `FeedCollector` builds: opaque here, and kept whole. */
        const val COLLECTOR_DIAGNOSTICS = "host=ActivityHost\n\n{\"install\":{},\"rounds\":[]}\n"
    }
}
