package nz.personal.checkpointwatch.collect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a scan of the page is followed by a scan of Facebook's page widget.
 *
 * The rule is deliberately about one measurement rather than about the ending: a scan that
 * forwarded no feed response collected nothing from the feed, whatever story the ending tells.
 */
class PluginPassTest {

    @Test
    fun oneFeedResponseIsEnoughToSkipTheFallback() {
        EndReason.entries.forEach { end ->
            assertFalse("end was $end", shouldRunPluginPass(graphqlBodies = 1, end = end))
            assertFalse("end was $end", shouldRunPluginPass(graphqlBodies = 12, end = end))
        }
    }

    @Test
    fun theVpnCase_noFeedAtAll_runsTheFallback() {
        assertTrue(shouldRunPluginPass(graphqlBodies = 0, end = EndReason.NO_FEED))
    }

    @Test
    fun aStarvedScanThatEndedSomeOtherWayRunsItToo() {
        // A page can run out of the script's clock, reach a sign-in wall, or simply stop growing,
        // and still never have had a single feed response. All three are worth a second pass.
        assertTrue(shouldRunPluginPass(graphqlBodies = 0, end = EndReason.TIMEOUT))
        assertTrue(shouldRunPluginPass(graphqlBodies = 0, end = EndReason.LOGIN_WALL))
        assertTrue(shouldRunPluginPass(graphqlBodies = 0, end = EndReason.NO_MORE_POSTS))
    }

    @Test
    fun aScanThatNeverReachedFacebook_orWasRefused_orWasLeft_doesNotTryAgain() {
        // No network to try on; Facebook already refusing us; or the owner has left the app and
        // nothing further should be started on their behalf.
        assertFalse(shouldRunPluginPass(graphqlBodies = 0, end = EndReason.NETWORK_ERROR))
        assertFalse(shouldRunPluginPass(graphqlBodies = 0, end = EndReason.BLOCKED))
        assertFalse(shouldRunPluginPass(graphqlBodies = 0, end = EndReason.CANCELLED))
    }
}
