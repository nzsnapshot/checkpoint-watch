package nz.personal.checkpointwatch.scan

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import nz.personal.checkpointwatch.App
import nz.personal.checkpointwatch.collect.HeadlessHost
import nz.personal.checkpointwatch.data.ScanTrigger
import nz.personal.checkpointwatch.notify.NotificationPlanner
import java.time.Instant

/**
 * One background update: the same scan the screen runs, in a headless WebView, followed by a
 * notification if the owner asked for one and anything new turned up.
 *
 * Always reports success. A periodic worker that returns `retry` or `failure` is backed off by
 * WorkManager and can end up silently not running at all, which for this app would look exactly
 * like "there is nothing happening on the roads". A scan that failed is already recorded in the
 * scrapes log, which is where the settings screen shows it.
 */
class ScanWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        try {
            val container = (applicationContext as? App)?.container ?: return Result.success()

            // Read once: a scan takes a while, and the plan should reflect the settings the scan
            // started under rather than a change made halfway through it.
            val settings = container.settings.settings.first()

            val outcome = container.scanCoordinator.scan(
                trigger = ScanTrigger.BACKGROUND,
                host = HeadlessHost(),
                force = false,
            ) ?: return Result.success()

            val plan = NotificationPlanner.plan(outcome.newReports, settings, Instant.now())
            container.notifier.show(plan)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Whatever went wrong, the next run gets a clean try.
        }
        return Result.success()
    }
}
