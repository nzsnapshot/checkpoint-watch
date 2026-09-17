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

    @Test
    fun looksLikeLoginRedirect_recognisesThePhpForms() {
        // These are what Facebook actually redirects a logged-out visitor to.
        assertTrue(looksLikeLoginRedirect("https://www.facebook.com/login.php?next=https%3A%2F%2Fwww.facebook.com"))
        assertTrue(looksLikeLoginRedirect("https://www.facebook.com/checkpoint.php"))
        assertTrue(looksLikeLoginRedirect("https://www.facebook.com/recover.php?u=1"))
        assertTrue(looksLikeLoginRedirect("https://www.facebook.com/reg.php"))
        assertTrue(looksLikeLoginRedirect("https://www.facebook.com/login.php/extra"))
        assertTrue(looksLikeLoginRedirect("https://m.facebook.com/login/device-based/regular/login/"))
    }

    @Test
    fun looksLikeLoginRedirect_doesNotMatchThePageItself() {
        assertFalse(looksLikeLoginRedirect(Constants.PAGE_URL))
        assertFalse(looksLikeLoginRedirect("https://www.facebook.com/CheckpointNZ/"))
        assertFalse(looksLikeLoginRedirect("https://www.facebook.com/logins"))
        assertFalse(looksLikeLoginRedirect("https://www.facebook.com/registry"))
    }

    // --- the page widget, which only the second pass is allowed to load -------------------

    @Test
    fun isPluginNavigation_allowsExactlyThePagePlugin() {
        assertTrue(isPluginNavigation(Constants.PLUGIN_URL))
        assertTrue(isPluginNavigation("https://www.facebook.com/plugins/page.php"))
        assertTrue(isPluginNavigation("https://www.facebook.com/plugins/page.php?href=x&tabs=timeline"))
        assertTrue(isPluginNavigation("https://www.facebook.com/plugins/page.php/"))
    }

    @Test
    fun isPluginNavigation_refusesEveryOtherPlugin_hostAndScheme() {
        assertFalse(isPluginNavigation("https://www.facebook.com/plugins/post.php"))
        assertFalse(isPluginNavigation("https://www.facebook.com/plugins/like.php"))
        assertFalse(isPluginNavigation("https://www.facebook.com/plugins/page.php/extra"))
        assertFalse(isPluginNavigation("https://web.facebook.com/plugins/page.php"))
        assertFalse(isPluginNavigation("https://www.facebook.com.evil.example/plugins/page.php"))
        assertFalse(isPluginNavigation("http://www.facebook.com/plugins/page.php"))
        assertFalse(isPluginNavigation(Constants.PAGE_URL))
        assertFalse(isPluginNavigation(null))
        assertFalse(isPluginNavigation(""))
        assertFalse(isPluginNavigation("::::"))
    }

    @Test
    fun theWidgetIsNotAnAllowedNavigationOnItsOwn() {
        // Only the second pass opens it, and only by asking for it explicitly. Nothing on the page
        // itself may navigate there.
        assertFalse(isAllowedNavigation(Constants.PLUGIN_URL))
    }

    @Test
    fun isFacebookHost_matchesTheSiteAndItsSubdomains() {
        assertTrue(isFacebookHost("www.facebook.com"))
        assertTrue(isFacebookHost("facebook.com"))
        assertTrue(isFacebookHost("m.facebook.com"))
        assertTrue(isFacebookHost("WWW.FACEBOOK.COM"))
        assertFalse(isFacebookHost("facebook.com.evil.example"))
        assertFalse(isFacebookHost("notfacebook.com"))
        assertFalse(isFacebookHost(null))
    }

    @Test
    fun endsScanAsBlocked_anyFacebookPageOtherThanOurs_endsTheScan() {
        // Otherwise a blocked redirect leaves the scan idling until the 45 s timeout.
        assertTrue(endsScanAsBlocked("https://www.facebook.com/login.php?next=x"))
        assertTrue(endsScanAsBlocked("https://www.facebook.com/checkpoint/1501092823525282/"))
        assertTrue(endsScanAsBlocked("https://m.facebook.com/CheckpointNZ"))
        assertTrue(endsScanAsBlocked("http://www.facebook.com/CheckpointNZ"))
        assertTrue(endsScanAsBlocked("https://www.facebook.com/"))
    }

    @Test
    fun endsScanAsBlocked_loginPageOnAnotherFacebookProperty_endsTheScan() {
        assertTrue(endsScanAsBlocked("https://www.messenger.com/login.php"))
    }

    @Test
    fun endsScanAsBlocked_thePageItselfAndNonWebUrls_doNot() {
        assertFalse(endsScanAsBlocked(Constants.PAGE_URL))
        assertFalse(endsScanAsBlocked("https://www.facebook.com/CheckpointNZ/?ref=x"))
        assertFalse(endsScanAsBlocked("about:blank"))
        // App links are blocked, but they are not Facebook refusing us: the scan carries on.
        assertFalse(endsScanAsBlocked("intent://www.facebook.com/CheckpointNZ#Intent;scheme=https;end"))
        assertFalse(endsScanAsBlocked("market://details?id=com.facebook.katana"))
        assertFalse(endsScanAsBlocked("https://example.com/whatever"))
        assertFalse(endsScanAsBlocked(null))
    }
}
