package nz.personal.checkpointwatch.collect

import nz.personal.checkpointwatch.Constants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The collector must never leave the Checkpoint NZ page: a login/checkpoint redirect would put a
 * Facebook sign-in form behind the app's own UI.
 */
class NavigationGuardTest {

    @Test
    fun allows_thePageUrlItself() {
        assertTrue(isAllowedNavigation(Constants.PAGE_URL))
    }

    @Test
    fun allows_trailingSlashQueryAndFragment() {
        assertTrue(isAllowedNavigation("https://www.facebook.com/CheckpointNZ/"))
        assertTrue(isAllowedNavigation("https://www.facebook.com/CheckpointNZ?locale=en_GB"))
        assertTrue(isAllowedNavigation("https://www.facebook.com/CheckpointNZ/?ref=page_internal&sk=timeline"))
        assertTrue(isAllowedNavigation("https://www.facebook.com/CheckpointNZ#top"))
    }

    @Test
    fun allows_differentCasingOfTheUsername() {
        assertTrue(isAllowedNavigation("https://www.facebook.com/checkpointnz"))
    }

    @Test
    fun allows_aboutBlank_usedByCleanup() {
        assertTrue(isAllowedNavigation("about:blank"))
    }

    @Test
    fun blocks_loginAndCheckpointRedirects() {
        assertFalse(isAllowedNavigation("https://www.facebook.com/login/?next=https%3A%2F%2Fwww.facebook.com%2FCheckpointNZ"))
        assertFalse(isAllowedNavigation("https://www.facebook.com/checkpoint/1501092823525282/"))
        assertFalse(isAllowedNavigation("https://www.facebook.com/r.php"))
    }

    @Test
    fun blocks_otherPathsOnTheSameHost() {
        assertFalse(isAllowedNavigation("https://www.facebook.com/"))
        assertFalse(isAllowedNavigation("https://www.facebook.com/CheckpointNZ/posts/123"))
        assertFalse(isAllowedNavigation("https://www.facebook.com/CheckpointNZother"))
    }

    @Test
    fun blocks_otherHostsAndSchemes() {
        assertFalse(isAllowedNavigation("https://m.facebook.com/CheckpointNZ"))
        assertFalse(isAllowedNavigation("https://facebook.com/CheckpointNZ"))
        assertFalse(isAllowedNavigation("https://www.facebook.com.evil.example/CheckpointNZ"))
        assertFalse(isAllowedNavigation("http://www.facebook.com/CheckpointNZ"))
        assertFalse(isAllowedNavigation("intent://www.facebook.com/CheckpointNZ#Intent;scheme=https;end"))
        assertFalse(isAllowedNavigation("market://details?id=com.facebook.katana"))
        assertFalse(isAllowedNavigation("javascript:alert(1)"))
    }

    @Test
    fun blocks_nullEmptyAndMalformedUrls() {
        assertFalse(isAllowedNavigation(null))
        assertFalse(isAllowedNavigation(""))
        assertFalse(isAllowedNavigation("   "))
        assertFalse(isAllowedNavigation("https://www.facebook.com/Checkpoint NZ"))
        assertFalse(isAllowedNavigation("::::"))
    }

    @Test
    fun looksLikeLoginRedirect_recognisesFacebooksSignInUrls() {
        assertTrue(looksLikeLoginRedirect("https://www.facebook.com/login/?next=x"))
        assertTrue(looksLikeLoginRedirect("https://www.facebook.com/checkpoint/123"))
        assertTrue(looksLikeLoginRedirect("https://www.facebook.com/r.php"))
        assertTrue(looksLikeLoginRedirect("https://www.facebook.com/privacy/consent/gdp/"))
        assertFalse(looksLikeLoginRedirect("https://www.facebook.com/CheckpointNZ/posts/123"))
        assertFalse(looksLikeLoginRedirect(null))
    }
}
