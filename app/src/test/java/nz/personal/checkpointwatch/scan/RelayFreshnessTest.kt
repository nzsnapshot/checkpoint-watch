package nz.personal.checkpointwatch.scan

import nz.personal.checkpointwatch.collect.RelayFeed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class RelayFreshnessTest {

    private val now: Instant = Instant.parse("2026-09-18T14:30:00Z")

    private fun feed(generatedAgo: Duration, fullScanAgo: Duration?) = RelayFeed(
        generatedAt = now.minus(generatedAgo),
        lastFullScanAt = fullScanAgo?.let(now::minus),
        outcome = "FEED",
        posts = emptyList(),
    )

    @Test
    fun `a feed written minutes ago by a collector that is getting full scans is fresh`() {
        assertTrue(RelayFreshness.isFresh(feed(Duration.ofMinutes(4), Duration.ofMinutes(4)), now))
    }

    @Test
    fun `thirty minutes is the last moment a feed is fresh`() {
        assertTrue(RelayFreshness.isFresh(feed(Duration.ofMinutes(30), Duration.ofMinutes(30)), now))
        assertFalse(RelayFreshness.isFresh(feed(Duration.ofMinutes(30).plusSeconds(1), Duration.ofMinutes(30)), now))
    }

    @Test
    fun `a quiet night's oldest honest feed is still fresh`() {
        // Nothing posted, so the collector only writes its heartbeat: due at fifteen minutes, sent
        // on the first five-minute cycle after that, then held up to five more by GitHub's cache.
        // That is a healthy collector, and the phone must not open the page because of it.
        assertTrue(RelayFreshness.isFresh(feed(Duration.ofMinutes(26), Duration.ofMinutes(26)), now))
    }

    @Test
    fun `a collector still writing but rationed for over an hour is stale`() {
        // The home PC can be starved too. Its heartbeat keeps coming, but it is no longer seeing
        // the page, so the phone should look for itself.
        assertTrue(RelayFreshness.isFresh(feed(Duration.ofMinutes(1), Duration.ofMinutes(60)), now))
        assertFalse(RelayFreshness.isFresh(feed(Duration.ofMinutes(1), Duration.ofMinutes(60).plusSeconds(1)), now))
    }

    @Test
    fun `a collector that has never had a full scan is stale`() {
        assertFalse(RelayFreshness.isFresh(feed(Duration.ofMinutes(1), null), now))
    }

    @Test
    fun `a feed from the future is not believed, beyond a little clock drift`() {
        assertTrue(RelayFreshness.isFresh(feed(Duration.ofMinutes(-2), Duration.ofMinutes(-2)), now))
        assertFalse(RelayFreshness.isFresh(feed(Duration.ofMinutes(-6), Duration.ofMinutes(1)), now))
        assertFalse(RelayFreshness.isFresh(feed(Duration.ofMinutes(1), Duration.ofMinutes(-6)), now))
    }
}
