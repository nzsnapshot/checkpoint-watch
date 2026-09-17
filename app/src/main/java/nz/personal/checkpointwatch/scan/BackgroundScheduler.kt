package nz.personal.checkpointwatch.scan

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Name of the one periodic scan; re-using it means a setting change replaces, never stacks. */
const val SCAN_WORK_NAME: String = "cw-scan"

/** Interval values the settings screen offers, in minutes. 0 is off; 15 is the platform minimum. */
val ALLOWED_BACKGROUND_MINUTES: List<Int> = listOf(0, 15, 30, 60, 120)

/** Anything else — an old stored value, say — lands on the closest interval we do offer. */
internal fun nearestBackgroundMinutes(minutes: Int): Int =
    ALLOWED_BACKGROUND_MINUTES.minBy { abs(it - minutes) }

/**
 * Keeps WorkManager's schedule in step with the owner's chosen interval. Periodic work survives
 * reboots and app updates, so this only has to run when the setting changes and once at startup.
 */
object BackgroundScheduler {

    fun apply(context: Context, minutes: Int) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        val interval = nearestBackgroundMinutes(minutes)

        if (interval == 0) {
            workManager.cancelUniqueWork(SCAN_WORK_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<ScanWorker>(interval.toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .build()

        // UPDATE so changing the interval reschedules the existing work instead of leaving the
        // old period running (KEEP) or losing the next run's timing entirely (REPLACE).
        workManager.enqueueUniquePeriodicWork(
            SCAN_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
