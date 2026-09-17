package nz.personal.checkpointwatch.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import nz.personal.checkpointwatch.collect.ActivityHost
import nz.personal.checkpointwatch.ui.home.HomeViewModel
import nz.personal.checkpointwatch.ui.theme.CheckpointWatchTheme

/**
 * The app's only Activity: the two screens are Compose destinations inside it.
 *
 * It also owns the one [ActivityHost] the collector attaches its WebView to. That WebView is added
 * as child 0 of the content frame, underneath the opaque Compose surface added after it, so
 * Facebook is laid out — which is what makes its lazy loading run — and never seen.
 *
 * The manifest declares `configChanges` for everything Compose handles itself (rotation, size,
 * density, font scale, locale, light/dark). Without it, turning the phone over mid-scan destroys
 * the Activity, taking the WebView and the scan with it — and the two-minute throttle then
 * refuses to start the replacement, so a rotation could cost the owner a scan. Compose reads all
 * of these through `LocalConfiguration`, which is updated on `onConfigurationChanged`, so the
 * theme still follows the system's dark mode. (`themes.xml`'s `windowBackground` no longer
 * switches with `uiMode`, since that is resolved once at Activity creation — harmless: it is only
 * ever seen for the frame before Compose draws, and the opaque Surface covers it after that.)
 */
class MainActivity : ComponentActivity() {

    /** One host for the life of the Activity; a second would mean a second place to attach to. */
    private val host: ActivityHost by lazy { ActivityHost(this) }

    private val homeViewModel: HomeViewModel by viewModels { HomeViewModel.Factory(application) }

    /** The pull-to-refresh scan, so it can be cut off when the owner leaves. */
    private var refreshJob: Job? = null

    /** Bumped when a notification brings the owner back; the nav host pops to the list. */
    private var openHomeSignal by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Transparent bars, with the icon contrast following light/dark exactly as the Compose
        // theme does, so the clock and the back gesture hint stay legible in both.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        setContent {
            CheckpointWatchTheme {
                // One opaque surface under everything. Each screen already paints its own
                // background, but a navigation cross-fade has both of them part-transparent for a
                // few frames, and there is a WebView showing Facebook directly behind this view.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNav(
                        homeViewModel = homeViewModel,
                        onRefresh = ::refresh,
                        openHomeSignal = openHomeSignal,
                    )
                }
            }
        }

        // The on-open scan. `repeatOnLifecycle` starts it on every ON_START and cancels it on
        // ON_STOP, which is exactly the contract the design asks for: leaving the app stops the
        // scan, and whatever it had already collected is still recorded.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                homeViewModel.scanOnOpen(host)
            }
        }
    }

    /** Tapping the notification just brings the list forward; nothing is read from the intent. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openHomeSignal++
    }

    /**
     * Pull to refresh runs in the Activity's scope rather than the ViewModel's, and is cancelled
     * below on stop, so a refresh started a second before the screen is locked dies with it.
     */
    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch { homeViewModel.refresh(host) }
    }

    override fun onStop() {
        // Before super, which is what dispatches ON_STOP and so cancels the scan: the ViewModel has
        // to know whether this is the owner leaving or just a rotation before the cancellation
        // reaches it.
        homeViewModel.onStopping(changingConfiguration = isChangingConfigurations)
        refreshJob?.cancel()
        super.onStop()
    }
}
