package nz.personal.checkpointwatch

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nz.personal.checkpointwatch.scan.BackgroundScheduler

class App : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    /** Lives as long as the process; only used for the one startup job below. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        applyStoredBackgroundSchedule()
    }

    /**
     * WorkManager keeps its own schedule across reboots, but not across a reinstall or a cleared
     * data directory — and an app update can drop work whose worker class it can no longer find.
     * Re-applying the stored setting once at startup makes the schedule follow the setting rather
     * than whatever WorkManager happens to still hold.
     */
    private fun applyStoredBackgroundSchedule() {
        appScope.launch {
            try {
                val minutes = container.settings.settings.first().backgroundMinutes
                BackgroundScheduler.apply(this@App, minutes)
            } catch (_: Exception) {
                // Nothing here is worth failing startup for; the settings screen re-applies it.
            }
        }
    }
}
