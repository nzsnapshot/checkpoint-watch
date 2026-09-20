package nz.personal.checkpointwatch.screenshots

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import nz.personal.checkpointwatch.ui.home.HomeContent
import nz.personal.checkpointwatch.ui.home.HomeUiState
import nz.personal.checkpointwatch.ui.home.ListItem
import nz.personal.checkpointwatch.ui.home.LocalPhotoFiles
import nz.personal.checkpointwatch.ui.preview.NoCallbacks
import nz.personal.checkpointwatch.ui.preview.NoSettingsCallbacks
import nz.personal.checkpointwatch.ui.preview.SampleData
import nz.personal.checkpointwatch.ui.promo.StashwiseSheetContent
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

    /**
     * Expansion is internal `rememberSaveable` state in
     * [ReportCard][nz.personal.checkpointwatch.ui.home.ReportCard]; drive it with a real click.
     *
     * Opened on the Pakuranga Road crash, which shares its post with a second report — the case
     * where the full post genuinely adds something the card does not already show, so the expanded
     * section renders in full rather than collapsing to just the source and the link.
     */
    @Test
    fun home_dark_expanded() {
        // The multi-report post on its own, so the opened card is on screen whole rather than
        // being scrolled to — a LazyColumn disposes what it scrolls past, taking the opened
        // card's content with it.
        setHome(SampleData.multiReport, dark = true)
        // Both reports in that post are on Pakuranga Road; the crash is the first of the two.
        composeTestRule.onAllNodesWithText("Pakuranga Road", substring = true)[0].performClick()
        capture("home_dark_expanded.png")
    }

    /** The same card in light, where its edge comes from a hairline rather than from being lighter. */
    @Test
    fun home_light_expanded() {
        setHome(SampleData.multiReport, dark = false)
        composeTestRule.onAllNodesWithText("Pakuranga Road", substring = true)[0].performClick()
        capture("home_light_expanded.png")
    }

    // ---- Photos: a post's picture, as a strip on the closed card and whole on the opened one ----

    /** A stand-in for a post's photo: wide, like the street views the page posts. */
    private fun samplePhoto(): File {
        val bitmap = android.graphics.Bitmap.createBitmap(918, 516, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint()
        canvas.drawColor(android.graphics.Color.rgb(96, 125, 139))
        paint.color = android.graphics.Color.rgb(55, 71, 79)
        canvas.drawRect(0f, 330f, 918f, 516f, paint)
        paint.color = android.graphics.Color.rgb(255, 213, 79)
        for (x in 40 until 918 step 160) canvas.drawRect(x.toFloat(), 410f, x + 90f, 424f, paint)
        paint.color = android.graphics.Color.rgb(236, 239, 241)
        canvas.drawCircle(760f, 110f, 60f, paint)
        return File.createTempFile("photo", ".img").apply {
            deleteOnExit()
            outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun setHomeWithPhoto(dark: Boolean) {
        val photo = samplePhoto()
        var first = true
        val items = SampleData.multiReport.items.map { item ->
            if (item is ListItem.Report && first) {
                first = false
                ListItem.Report(item.report.copy(imagePath = "photo.img"))
            } else {
                item
            }
        }
        composeTestRule.setContent {
            CompositionLocalProvider(LocalPhotoFiles provides { _: String -> photo }) {
                CheckpointWatchTheme(darkTheme = dark) {
                    HomeContent(state = SampleData.multiReport.copy(items = items), callbacks = NoCallbacks)
                }
            }
        }
    }

    /** The decode is on another thread, which the compose rule does not know to wait for. */
    private fun awaitPhoto() {
        Thread.sleep(800)
        composeTestRule.waitForIdle()
    }

    @Test
    fun home_dark_photo() {
        setHomeWithPhoto(dark = true)
        awaitPhoto()
        capture("home_dark_photo.png")
    }

    @Test
    fun home_light_photo_expanded() {
        setHomeWithPhoto(dark = false)
        composeTestRule.onAllNodesWithText("Pakuranga Road", substring = true)[0].performClick()
        awaitPhoto()
        capture("home_light_photo_expanded.png")
    }

    /** The other half of that decision: a single-report post, where the card already said it all. */
    @Test
    fun home_dark_expanded_plain() {
        setHome(SampleData.home, dark = true)
        composeTestRule.onNodeWithText("Lincoln Road", substring = true).performClick()
        capture("home_dark_expanded_plain.png")
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
     * Scrolls deep into the list so three things are on screen at once: the pinned filter bar and
     * collapsed title at the top, the sticky "Today" day header, and the history
     * [gap divider][nz.personal.checkpointwatch.ui.home.ListItem.Gap] with an old report after it.
     *
     * The filter row is no longer a list item, so the indices are 0=summary, 1=banner,
     * 2=day header, 3..8=the six reports before the gap, 9=gap, 10=Trig Road.
     */
    @Test
    fun home_dark_scrolled() {
        setHome(SampleData.home, dark = true)
        scrollDeep()
        capture("home_dark_scrolled.png")
    }

    /** The same, in light, where the cards and tiles have to earn their edge from a hairline. */
    @Test
    fun home_light_scrolled() {
        setHome(SampleData.home, dark = false)
        scrollDeep()
        capture("home_light_scrolled.png")
    }

    /**
     * Scrolls with real drags rather than `performScrollToIndex`, which moves the list without ever
     * touching the nested-scroll chain — so the collapsing app bar would stay fully expanded and
     * the capture would not show the thing it exists to show.
     */
    private fun scrollDeep(drags: Int = 4) {
        repeat(drags) {
            composeTestRule.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp() }
            composeTestRule.waitForIdle()
        }
    }

    // ---- Settings ----

    // ---- Stashwise: the sheet's insides, which the card opens on either screen ----

    private fun setStashwiseSheet(dark: Boolean) {
        composeTestRule.setContent {
            CheckpointWatchTheme(darkTheme = dark) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    StashwiseSheetContent(onOpenLink = {}, modifier = Modifier.padding(top = 24.dp))
                }
            }
        }
    }

    @Test
    fun stashwise_sheet_dark() {
        setStashwiseSheet(dark = true)
        capture("stashwise_sheet_dark.png")
    }

    @Test
    fun stashwise_sheet_light() {
        setStashwiseSheet(dark = false)
        capture("stashwise_sheet_light.png")
    }

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
