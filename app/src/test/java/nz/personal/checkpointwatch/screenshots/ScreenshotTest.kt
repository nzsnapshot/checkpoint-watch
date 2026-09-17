package nz.personal.checkpointwatch.screenshots

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import nz.personal.checkpointwatch.ui.home.HomeContent
import nz.personal.checkpointwatch.ui.home.HomeUiState
import nz.personal.checkpointwatch.ui.preview.NoCallbacks
import nz.personal.checkpointwatch.ui.preview.NoSettingsCallbacks
import nz.personal.checkpointwatch.ui.preview.SampleData
import nz.personal.checkpointwatch.ui.settings.SettingsContent
import nz.personal.checkpointwatch.ui.theme.CheckpointWatchTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Task 8b: JVM screenshot harness. Renders the real, stateless Compose screens
 * ([HomeContent]/[SettingsContent]) on Robolectric and writes PNGs so the visual design can be
 * judged without a device or emulator.
 *
 * Every state rendered here comes from [SampleData] — the exact same sample data the `@Preview`
 * functions in `ui.preview` use — so a preview that looks right is the thing this test locks in.
 *
 * Not part of the fast default suite by policy, but currently stable and fast enough (a few
 * seconds of Robolectric setup per state) to run inside `testDebugUnitTest` as well. To
 * (re)generate the PNGs on demand:
 *
 * ```
 * ./gradlew testDebugUnitTest --tests '*ScreenshotTest*' -Proborazzi.test.record=true
 * ```
 *
 * Images land in `app/build/outputs/roborazzi/<name>.png` (an absolute path is passed to
 * [captureRoboImage] below so the location does not depend on the working directory Gradle
 * happens to run the test JVM from).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    // Robolectric 4.17 can run SDK 36, but that sandbox requires Java 21; this project builds
    // with (and CI runs) Java 17, so 35 (Android 15, "VanillaIceCream") is the highest usable
    // runtime here. compileSdk/targetSdk stay at 36 in the production build — this only pins the
    // Robolectric-simulated OS version for these JVM-only screenshot renders.
    sdk = [35],
    application = android.app.Application::class,
    qualifiers = "w411dp-h891dp-420dpi",
)
class ScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // No automatic capture (default Options -> CaptureType.None); every image below is written
    // explicitly with a chosen name and, where needed, after driving the UI into a specific state.
    @get:Rule
    val roborazziRule = RoborazziRule()

    private val outputDir: File by lazy {
        // Test JVM working directory is the module directory (app/) under Gradle's testDebugUnitTest.
        File(File(System.getProperty("user.dir") ?: "."), "build/outputs/roborazzi").apply { mkdirs() }
    }

    private fun capture(name: String) {
        composeTestRule.onRoot().captureRoboImage(File(outputDir, name).absolutePath)
    }

    private fun setHome(state: HomeUiState, dark: Boolean) {
        composeTestRule.setContent {
            CheckpointWatchTheme(darkTheme = dark) {
                HomeContent(state = state, callbacks = NoCallbacks)
            }
        }
    }

    private fun setSettings(dark: Boolean) {
        composeTestRule.setContent {
            CheckpointWatchTheme(darkTheme = dark) {
                SettingsContent(state = SampleData.settings, callbacks = NoSettingsCallbacks)
            }
        }
    }

    // ---- Home: the realistic sample list, idle with a result banner ----

    @Test
    fun home_dark() {
        setHome(SampleData.home, dark = true)
        capture("home_dark.png")
    }

    @Test
    fun home_light() {
        setHome(SampleData.home, dark = false)
        capture("home_light.png")
    }

    @Test
    fun home_dark_scanning() {
        setHome(SampleData.scanning, dark = true)
        capture("home_dark_scanning.png")
    }

    /** Expansion is internal `rememberSaveable` state in [ReportCard][nz.personal.checkpointwatch.ui.home.ReportCard]; drive it with a real click. */
    @Test
    fun home_dark_expanded() {
        setHome(SampleData.home, dark = true)
        composeTestRule.onNodeWithText("Lincoln Road", substring = true).performClick()
        capture("home_dark_expanded.png")
    }

    @Test
    fun home_dark_empty_first_run() {
        setHome(SampleData.firstRun, dark = true)
        capture("home_dark_empty_first_run.png")
    }

    @Test
    fun home_dark_filtered_empty() {
        setHome(SampleData.filteredOut, dark = true)
        capture("home_dark_filtered_empty.png")
    }

    @Test
    fun home_dark_offline() {
        setHome(SampleData.offline, dark = true)
        capture("home_dark_offline.png")
    }

    @Test
    fun home_dark_font150() {
        composeTestRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density = base.density, fontScale = 1.5f)) {
                CheckpointWatchTheme(darkTheme = true) {
                    HomeContent(state = SampleData.home, callbacks = NoCallbacks)
                }
            }
        }
        capture("home_dark_font150.png")
    }

    /**
     * Scrolls past the summary/banner/filter header and the fresher reports so the sticky "Today"
     * day header, the history [gap divider][nz.personal.checkpointwatch.ui.home.ListItem.Gap] and
     * an old report (`Trig Road`) are all on screen together. Index 11 is the `Trig Road` report
     * in the LazyColumn: 0=summary, 1=banner, 2=filters, 3=day header, 4..9=the six reports before
     * the gap, 10=gap, 11=Trig Road.
     */
    @Test
    fun home_dark_scrolled() {
        setHome(SampleData.home, dark = true)
        composeTestRule.onNode(hasScrollToIndexAction()).performScrollToIndex(11)
        capture("home_dark_scrolled.png")
    }

    // ---- Settings ----

    @Test
    fun settings_dark() {
        setSettings(dark = true)
        capture("settings_dark.png")
    }

    @Test
    fun settings_light() {
        setSettings(dark = false)
        capture("settings_light.png")
    }

    @Test
    fun settings_dark_scrolled() {
        setSettings(dark = true)
        // Bring "About" to the bottom edge of the viewport first, then nudge a little further so
        // the whole About card is on screen too, with the tail of the scan history above it.
        composeTestRule.onNodeWithText("About").performScrollTo()
        composeTestRule.onRoot().performTouchInput { swipeUp() }
        capture("settings_dark_scrolled.png")
    }
}
