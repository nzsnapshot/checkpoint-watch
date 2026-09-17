package nz.personal.checkpointwatch.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import nz.personal.checkpointwatch.ui.theme.CheckpointWatchTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The status and navigation bars are transparent, so the app's own background shows through them
 * and their icons have to be the opposite of it. `enableEdgeToEdge` sets that once, at Activity
 * creation — which used to be enough, because a light/dark switch recreated the Activity.
 *
 * The manifest now handles `uiMode` (so a rotation can no longer kill a scan), so there is no
 * recreation: Compose recolours the app in place, and unless the bar appearance follows it live,
 * a switch to light mode leaves white icons on a white background.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SystemBarAppearanceTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun appearanceLightStatusBars(): Boolean {
        val window = composeTestRule.activity.window
        return WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars
    }

    private fun appearanceLightNavigationBars(): Boolean {
        val window = composeTestRule.activity.window
        return WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars
    }

    @Test
    fun `the light theme asks for dark bar icons`() {
        composeTestRule.setContent { CheckpointWatchTheme(darkTheme = false) {} }

        assertTrue(appearanceLightStatusBars())
        assertTrue(appearanceLightNavigationBars())
    }

    @Test
    fun `the dark theme asks for light bar icons`() {
        composeTestRule.setContent { CheckpointWatchTheme(darkTheme = true) {} }

        assertFalse(appearanceLightStatusBars())
        assertFalse(appearanceLightNavigationBars())
    }

    @Test
    fun `switching theme without recreating the Activity switches the bar icons with it`() {
        var dark by mutableStateOf(true)
        composeTestRule.setContent { CheckpointWatchTheme(darkTheme = dark) {} }
        composeTestRule.waitForIdle()
        assertFalse("dark theme starts with light icons", appearanceLightStatusBars())

        dark = false
        composeTestRule.waitForIdle()

        assertTrue("the switch to light must darken the icons", appearanceLightStatusBars())
        assertTrue(appearanceLightNavigationBars())
    }
}
