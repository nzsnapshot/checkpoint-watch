package nz.personal.checkpointwatch.notify

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import nz.personal.checkpointwatch.R
import nz.personal.checkpointwatch.ui.MainActivity

/** One channel, so the owner can silence or tune road reports without silencing the app. */
private const val CHANNEL_ID = "new_reports"
private const val CHANNEL_NAME = "New reports"

/** One id: each scan's notification replaces the last, rather than stacking up overnight. */
private const val NOTIFICATION_ID = 1

/** The app's amber accent, used for the notification's icon tint. */
private const val ACCENT_COLOUR = 0xFFFFB020.toInt()

/**
 * Posts the one notification a background scan earned, as [NotificationPlanner] worded it.
 *
 * Silent in every case where posting would be wrong: no plan, notifications turned off for the
 * app in system settings, or `POST_NOTIFICATIONS` not granted. The app asks for that permission
 * from the settings screen when the owner switches notifications on, and never from here.
 */
class Notifier(context: Context) {

    private val context = context.applicationContext

    /**
     * Creates the channel, so it is there the moment the owner switches notifications on rather
     * than only after the first background scan happens to find something. Until the channel
     * exists, the system's own notification settings for this app have nothing to show and no
     * "New reports" category to tune, which makes the switch look like it did nothing.
     *
     * Creating a channel that already exists is a no-op, so this is safe to call every time.
     */
    fun ensureChannel() {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(CHANNEL_NAME)
                .build(),
        )
    }

    fun show(plan: NotificationPlanner.Plan?) {
        if (plan == null || plan.lines.isEmpty()) return

        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureChannel()

        val style = NotificationCompat.InboxStyle().setBigContentTitle(plan.title)
        plan.lines.forEach(style::addLine)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_beacon)
            .setContentTitle(plan.title)
            .setContentText(plan.lines.first())
            .setStyle(style)
            .setColor(ACCENT_COLOUR)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openTheApp())
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    /** Tapping the notification opens the app on the list, without a second copy of it. */
    private fun openTheApp(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
