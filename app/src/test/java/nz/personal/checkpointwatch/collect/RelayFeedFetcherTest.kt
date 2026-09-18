package nz.personal.checkpointwatch.collect

import nz.personal.checkpointwatch.scan.RelayResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant

class RelayFeedFetcherTest {

    @Test
    fun urlFor_isTheContractsAddress_withTheMinuteAsACacheBuster() {
        // 1789741768 s is minute 29829029. Nothing about the phone or its owner is in the address.
        val url = RelayFeedFetcher.urlFor(Instant.ofEpochSecond(1789741768))

        assertEquals(
            "https://raw.githubusercontent.com/nzsnapshot/checkpoint-watch/data/feed.json?t=29829029",
            url,
        )
    }

    @Test
    fun urlFor_changesOncePerMinute_soARetryInsideTheMinuteCanBeServedFromCache() {
        val first = RelayFeedFetcher.urlFor(Instant.ofEpochSecond(1789741740))
        val sameMinute = RelayFeedFetcher.urlFor(Instant.ofEpochSecond(1789741799))
        val nextMinute = RelayFeedFetcher.urlFor(Instant.ofEpochSecond(1789741800))

        assertEquals(first, sameMinute)
        assertTrue(first != nextMinute)
    }

    @Test
    fun resultFor_noBodyIsUnreachable_aBadBodyIsInvalid_aGoodOneIsLoaded() {
        val good = """{"version":1,"page":"CheckpointNZ","generatedAt":1789741768,"lastFullScanAt":1789741768,""" +
            """"collector":{"outcome":"FEED","posts":0},"posts":[]}"""

        assertEquals(RelayResult.Unreachable, RelayFeedFetcher.resultFor(null))
        assertEquals(RelayResult.Invalid, RelayFeedFetcher.resultFor("<html>404: Not Found</html>"))
        assertTrue(RelayFeedFetcher.resultFor(good) is RelayResult.Loaded)
    }

    @Test
    fun readBounded_aBodyWithinTheLimitIsReadWhole() {
        val body = "x".repeat(1000)

        assertEquals(body, RelayFeedFetcher.readBounded(ByteArrayInputStream(body.toByteArray()), limit = 1000))
    }

    @Test
    fun readBounded_aBodyOverTheLimitIsRefusedRatherThanCutShort() {
        // Half a JSON document parses as nothing, so there is no point keeping the first 2 MB of
        // something that was never going to be the feed.
        val body = "x".repeat(1001)

        assertNull(RelayFeedFetcher.readBounded(ByteArrayInputStream(body.toByteArray()), limit = 1000))
    }
}
