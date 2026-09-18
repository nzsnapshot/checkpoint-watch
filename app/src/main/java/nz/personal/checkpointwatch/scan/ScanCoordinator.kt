package nz.personal.checkpointwatch.scan

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import nz.personal.checkpointwatch.collect.CollectResult
import nz.personal.checkpointwatch.collect.DiagnosticsText
import nz.personal.checkpointwatch.collect.EndReason
import nz.personal.checkpointwatch.collect.RawPost
import nz.personal.checkpointwatch.collect.WebViewHost
import nz.personal.checkpointwatch.collect.shouldRunPluginPass
import nz.personal.checkpointwatch.data.CollectorKind
import nz.personal.checkpointwatch.data.ScanTrigger
import nz.personal.checkpointwatch.data.ScrapeOutcome
import nz.personal.checkpointwatch.data.ScrapeRecorder
import nz.personal.checkpointwatch.data.ScrapeStatus
import java.time.Duration
import java.time.Instant

/** A scan sooner than this after the last one finished is not worth Facebook's bandwidth. */
private val MIN_INTERVAL: Duration = Duration.ofMinutes(2)

/** Nothing to fall back on: used for the pass that decides whether an HTTP fetch is needed. */
private val NO_CHUNKS: () -> List<String> = { emptyList() }

/** What a scan the relay answered is recorded as ending with: no WebView ran, so nothing ended. */
private const val RELAY_END_REASON = "RELAY"

/** Whether a scan is running right now; the UI's banner follows this. */
sealed interface ScanState {
    data object Idle : ScanState
    data object Scanning : ScanState
}

/** How the most recent recorded scan went, for the banner and for throttling the next one. */
data class ScanSummary(
    val status: ScrapeStatus,
    val new: Int,
    val finishedAt: Instant,
    val trigger: ScanTrigger,
    /**
     * Facebook never answered the feed, so whatever this scan brought back came from the post
     * embedded in the page or from the page widget's five newest. Not a failure — the scan may
     * well have succeeded — but the reason a thin scan was thin.
     */
    val starved: Boolean = false,
    /** Whether the phone was on a VPN when the scan ran, which is why it was starved. */
    val vpnActive: Boolean = false,
)

/**
 * The WebView scan, as the coordinator needs it. `FeedCollector` is the real one; the interface
 * keeps the coordinator testable on the JVM.
 */
interface PostCollector {
    suspend fun collect(host: WebViewHost): CollectResult

    /** What the collector has captured so far, readable after a cancelled [collect]. */
    fun snapshot(): CollectResult
}

/** Downloading the photos of posts that have one; `ImageStore` is the real one. */
fun interface PhotoSync {
    suspend fun sync()
}

/** The plain HTTPS GET fallback; `HttpLatestFetcher` is the real one. */
fun interface LatestFetcher {
    suspend fun fetchChunks(): List<String>
}

/**
 * Runs one scan at a time for the whole process, whoever asks: the screen on open and on
 * pull-to-refresh, or [ScanWorker] in the background.
 *
 * Rules that live here rather than in a caller, so both callers get them:
 *  - the home collector first — a PC on an ordinary connection sees the feed this phone, on its
 *    VPN, is refused. When its published feed is fresh it *is* the scan and the page is never
 *    opened; when it is not, the phone scans for itself and keeps the relay's posts as well;
 *  - one at a time — a scan while another is running is skipped (`null`), never queued;
 *  - not too often — without `force`, a scan within two minutes of the last one is skipped;
 *  - every run that got as far as collecting is recorded, success or failure, including a run
 *    cancelled because the owner left the app: whatever it had captured is still saved.
 */
class ScanCoordinator(
    private val collector: PostCollector,
    private val httpFetcher: LatestFetcher,
    private val recorder: ScrapeRecorder,
    private val lastFinishedAt: suspend () -> Long?,
    /**
     * Where the scan's diagnostics are left for the owner to copy. Every ending writes one,
     * including a failure and a cancellation — those are the scans worth explaining. Optional
     * because nothing else depends on it: a scan with nowhere to leave its notes is still a scan.
     */
    private val diagnostics: ScanDiagnosticsStore? = null,
    /**
     * Whether the phone is on a VPN. Only ever used to choose which sentence the banner adds to a
     * scan Facebook rationed; it never decides whether or how a scan runs.
     */
    private val network: NetworkInfoProvider = NetworkInfoProvider { false },
    /**
     * The home collector's published feed. Defaults to one that is never there, which is simply
     * the app as it was before the relay existed.
     */
    private val relay: RelaySource = RelaySource { RelayResult.Unreachable },
    /**
     * Run after every scan that was recorded, once the scan itself is over: the posts are saved
     * and on screen by then, so the photos arrive behind them rather than holding them up.
     */
    private val photos: PhotoSync = PhotoSync { },
    private val clock: () -> Instant = Instant::now,
    /**
     * Where the parsing and the database work happen. Callers are on the main thread — the screen
     * on open and on pull-to-refresh — and the pipeline parses the whole feed (half a megabyte or
     * more of JSON) into element trees before the recorder walks it. Injected so tests can pin it.
     */
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    private val running = Mutex()

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private val _lastSummary = MutableStateFlow<ScanSummary?>(null)
    val lastSummary: StateFlow<ScanSummary?> = _lastSummary.asStateFlow()

    /**
     * @return the recorded outcome, or `null` when this scan was skipped (another one is running,
     *   or the last one finished less than two minutes ago and [force] is false).
     */
    suspend fun scan(trigger: ScanTrigger, host: WebViewHost, force: Boolean = false): ScrapeOutcome? {
        if (!running.tryLock()) return null
        try {
            val startedAt = clock()
            if (!force && finishedRecently(startedAt)) return null
            _state.value = ScanState.Scanning
            val outcome = runScan(trigger, host, startedAt)
            // Still holding the lock, so two syncs never race for the same file — but no longer
            // "scanning", because as far as the owner is concerned the scan is done.
            _state.value = ScanState.Idle
            syncPhotos()
            return outcome
        } finally {
            _state.value = ScanState.Idle
            running.unlock()
        }
    }

    /** Uses the database and this process's own last scan, whichever is later. */
    private suspend fun finishedRecently(now: Instant): Boolean {
        val stored = lastFinishedAt()
        val remembered = _lastSummary.value?.finishedAt?.toEpochMilli()
        val last = maxOf(stored ?: Long.MIN_VALUE, remembered ?: Long.MIN_VALUE)
        if (last == Long.MIN_VALUE) return false
        return now.toEpochMilli() - last < MIN_INTERVAL.toMillis()
    }

    private suspend fun runScan(
        trigger: ScanTrigger,
        host: WebViewHost,
        startedAt: Instant,
    ): ScrapeOutcome {
        // Before the try: a scan abandoned while it was still asking the relay never reached the
        // page, so there is no partial scan to save and nothing for the cancellation path to do.
        val relayed = fetchRelay()
        val relayState = RelayFreshness.stateOf(relayed, startedAt)
        val relayPosts = (relayed as? RelayResult.Loaded)?.feed?.posts.orEmpty()
        if (relayState == RelayState.FRESH && relayPosts.isNotEmpty()) {
            return recordRelay(trigger, startedAt, relayPosts)
        }

        try {
            val collected = collect(host)

            // choose() is pure but the HTTP fetch suspends, so the decision is made here: fetch
            // only once the WebView's own sources have come back empty, then let choose() work
            // on the chunks. The second call passes no CollectResult because this one is already
            // known to hold nothing.
            val collectedAt = clock()
            val webView = withContext(computeDispatcher) {
                ScanPipeline.choose(collected, NO_CHUNKS, collectedAt)
            }
            val needsHttp = webView.second == CollectorKind.NONE
            val chunks = if (needsHttp) httpFetcher.fetchChunks() else emptyList()
            val finishedAt = if (needsHttp) clock() else collectedAt

            val (outcome, chosen) = withContext(computeDispatcher) {
                val scanned = if (needsHttp) {
                    ScanPipeline.choose(null, { chunks }, finishedAt)
                } else {
                    webView
                }
                // Too old to stand in for the scan, but a post it knows about is still a post.
                val chosen = ScanPipeline.withRelay(scanned, relayPosts)
                val (posts, kind) = chosen
                recorder.record(
                    posts = posts,
                    startedAt = startedAt,
                    finishedAt = finishedAt,
                    trigger = trigger,
                    collector = kind,
                    endReason = collected.end.name,
                    failure = failureFor(posts.isEmpty(), collected.end),
                ) to chosen
            }
            writeDiagnostics(trigger, collected.end.name, collected.diagnostics, chosen, outcome.status, relayState)
            _lastSummary.value = ScanSummary(
                status = outcome.status,
                new = outcome.new,
                finishedAt = finishedAt,
                trigger = trigger,
                // The same question the collector asked before it reached for the page widget: did
                // the feed ever answer? Asked again here because the banner has to explain a thin
                // scan whether or not the fallback then rescued it.
                starved = shouldRunPluginPass(collected.graphqlBodies, collected.end),
                vpnActive = isVpnActive(),
            )
            return outcome
        } catch (cancellation: CancellationException) {
            // The app was left mid-scan. Recording has to finish outside the cancelled job, or
            // the posts collected so far would be thrown away with it — and it is the same
            // parsing and database work as the happy path, so it stays off the caller's thread.
            withContext(NonCancellable + computeDispatcher) { recordCancelled(trigger, startedAt) }
            throw cancellation
        }
    }

    /** The relay is a shortcut, never a dependency: anything wrong with it is "not there". */
    private suspend fun fetchRelay(): RelayResult =
        try {
            relay.fetch()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            RelayResult.Unreachable
        }

    /**
     * The scan, when the home collector's feed is fresh: its posts, recorded like any other
     * scan's, with no WebView and no request to Facebook at all.
     *
     * Never [ScanSummary.starved] — nothing was rationed, because nothing was asked for.
     */
    private suspend fun recordRelay(
        trigger: ScanTrigger,
        startedAt: Instant,
        posts: List<RawPost>,
    ): ScrapeOutcome {
        val finishedAt = clock()
        val outcome = withContext(computeDispatcher) {
            recorder.record(
                posts = posts,
                startedAt = startedAt,
                finishedAt = finishedAt,
                trigger = trigger,
                collector = CollectorKind.RELAY,
                endReason = RELAY_END_REASON,
                failure = null,
            )
        }
        writeDiagnostics(trigger, RELAY_END_REASON, null, posts to CollectorKind.RELAY, outcome.status, RelayState.FRESH)
        _lastSummary.value = ScanSummary(
            status = outcome.status,
            new = outcome.new,
            finishedAt = finishedAt,
            trigger = trigger,
        )
        return outcome
    }

    /**
     * A collector that throws is a scan that reached nothing — an unusable WebView, say — so it
     * is treated exactly like a page that failed to load: whatever was buffered, marked as a
     * network error, which lets the HTTP fallback have its turn.
     */
    private suspend fun collect(host: WebViewHost): CollectResult =
        try {
            collector.collect(host)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            collector.snapshot().copy(end = EndReason.NETWORK_ERROR)
        }

    /** Housekeeping. A photo that does not arrive today is still pending tomorrow. */
    private suspend fun syncPhotos() {
        try {
            photos.sync()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // ignore
        }
    }

    /** Never worth a failed scan: a missing sentence in a banner is a very small loss. */
    private fun isVpnActive(): Boolean = try {
        network.isVpnActive()
    } catch (_: Exception) {
        false
    }

    private fun failureFor(noPosts: Boolean, end: EndReason): ScrapeStatus? = when {
        !noPosts -> null
        end == EndReason.NETWORK_ERROR -> ScrapeStatus.FAILED_NETWORK
        else -> ScrapeStatus.FAILED_NO_DATA
    }

    /**
     * Records the partial scan. No HTTP fallback: the owner has left, and a fetch here would
     * outlive the screen that asked for it. [lastSummary] is left alone, because a cancelled
     * scan is not a result the banner should report.
     */
    private suspend fun recordCancelled(trigger: ScanTrigger, startedAt: Instant) {
        try {
            val snapshot = collector.snapshot()
            val finishedAt = clock()
            val chosen = ScanPipeline.choose(snapshot, NO_CHUNKS, finishedAt)
            val (posts, kind) = chosen
            recorder.record(
                posts = posts,
                startedAt = startedAt,
                finishedAt = finishedAt,
                trigger = trigger,
                collector = kind,
                endReason = snapshot.end.name,
                failure = ScrapeStatus.CANCELLED,
            )
            writeDiagnostics(trigger, snapshot.end.name, snapshot.diagnostics, chosen, ScrapeStatus.CANCELLED, null)
        } catch (_: Exception) {
            // Losing the record of a cancelled scan is a shame; replacing the cancellation with
            // a database error on the way out would be worse.
        }
    }

    /**
     * Leaves the scan's account of itself where Settings can copy it from: the coordinator's own
     * facts — which trigger, which source was chosen, how many posts came out, how it was recorded
     * — above the collector's block.
     *
     * Best effort by design. The diagnostics exist to explain a disappointing scan, and must never
     * be able to cause one.
     */
    private suspend fun writeDiagnostics(
        trigger: ScanTrigger,
        endReason: String,
        collectorDiagnostics: String?,
        chosen: Pair<List<RawPost>, CollectorKind>,
        status: ScrapeStatus,
        /** `null` when the scan was cancelled: the relay's part in it no longer matters. */
        relayState: RelayState?,
    ) {
        val store = diagnostics ?: return
        try {
            val header = DiagnosticsText.header(
                listOfNotNull(
                    "trigger" to trigger.name,
                    "status" to status.name,
                    "endReason" to endReason,
                    "collector" to chosen.second.name,
                    "postsExtracted" to chosen.first.size.toString(),
                    relayState?.let { "relay" to it.name.lowercase() },
                ),
            )
            store.write(trigger, header + collectorDiagnostics.orEmpty())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // ignore
        }
    }
}
