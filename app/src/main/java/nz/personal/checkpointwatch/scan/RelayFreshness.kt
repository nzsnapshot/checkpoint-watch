package nz.personal.checkpointwatch.scan

import nz.personal.checkpointwatch.collect.RelayFeed
import java.time.Duration
import java.time.Instant

/**
 * How old the feed may be and still stand in for a scan. A healthy collector on a quiet night is
 * already some way towards this: its heartbeat is due at fifteen minutes, goes out on the first
 * five-minute cycle after that, and GitHub's raw host may serve the old copy for five more. Twenty
 * minutes, the first figure tried, called that collector stale; thirty does not.
 */
private val MAX_FEED_AGE: Duration = Duration.ofMinutes(30)

/** A collector that has not seen the whole page for this long is being rationed just as we are. */
private val MAX_FULL_SCAN_AGE: Duration = Duration.ofMinutes(60)

/** Two clocks never agree exactly. More than this ahead of ours is not drift, it is wrong. */
private val MAX_CLOCK_DRIFT: Duration = Duration.ofMinutes(5)

/** What came back from asking for the home collector's feed. */
sealed interface RelayResult {
    /** No answer: offline, GitHub down, a timeout, a body too big to be the feed. */
    data object Unreachable : RelayResult

    /** An answer that was not version 1 of this page's feed. */
    data object Invalid : RelayResult

    data class Loaded(val feed: RelayFeed) : RelayResult
}

/** The fetch of the home collector's feed; `RelayFeedFetcher` is the real one. Never throws. */
fun interface RelaySource {
    suspend fun fetch(): RelayResult
}

/** How the relay figured in a scan, as the diagnostics spell it: `relay=fresh`. */
enum class RelayState { FRESH, STALE, UNREACHABLE, INVALID }

/**
 * Whether the home collector's feed is recent enough to stand in for a scan of the phone's own.
 *
 * Two clocks, because they fail differently. [RelayFeed.generatedAt] stops when the home PC is off.
 * [RelayFeed.lastFullScanAt] stops when the PC is running but Facebook has started rationing it
 * too — the heartbeat keeps arriving, with nothing new behind it.
 */
object RelayFreshness {

    fun isFresh(feed: RelayFeed, now: Instant): Boolean {
        val fullScanAt = feed.lastFullScanAt ?: return false
        return within(feed.generatedAt, now, MAX_FEED_AGE) && within(fullScanAt, now, MAX_FULL_SCAN_AGE)
    }

    fun stateOf(result: RelayResult, now: Instant): RelayState = when (result) {
        RelayResult.Unreachable -> RelayState.UNREACHABLE
        RelayResult.Invalid -> RelayState.INVALID
        is RelayResult.Loaded -> if (isFresh(result.feed, now)) RelayState.FRESH else RelayState.STALE
    }

    private fun within(at: Instant, now: Instant, limit: Duration): Boolean {
        val age = Duration.between(at, now)
        return age <= limit && age >= MAX_CLOCK_DRIFT.negated()
    }
}
