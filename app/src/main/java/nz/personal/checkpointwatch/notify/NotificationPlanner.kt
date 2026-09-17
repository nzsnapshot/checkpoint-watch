package nz.personal.checkpointwatch.notify

import nz.personal.checkpointwatch.data.NewReport
import nz.personal.checkpointwatch.model.displayName
import nz.personal.checkpointwatch.settings.Settings
import java.time.Duration
import java.time.Instant

/** A report this old at scan time is history, not news, whatever the scan just found. */
private val MAX_AGE: Duration = Duration.ofHours(2)

/** Beyond this the notification stops being readable, so the rest is summarised. */
private const val MAX_LINES = 6

/**
 * Turns a scan's new reports into the one notification it is worth posting, or nothing at all.
 *
 * Pure: it decides *what* to say, [Notifier] deals with *how*. Everything the owner can switch
 * off is honoured here — off entirely, types they don't care about, suburbs they don't drive
 * through, and anything that was already two hours old when the scan found it.
 */
object NotificationPlanner {

    data class Plan(val title: String, val lines: List<String>)

    fun plan(newReports: List<NewReport>, settings: Settings, now: Instant): Plan? {
        if (!settings.notify) return null

        val cutoff = now.minus(MAX_AGE)
        val worthTelling = newReports.filter { report ->
            report.type in settings.notifyTypes &&
                report.isInAWatchedSuburb(settings.watchedSuburbs) &&
                !report.at.isBefore(cutoff)
        }
        if (worthTelling.isEmpty()) return null

        val lines = worthTelling.map { it.line() }
        val shown = if (lines.size <= MAX_LINES) {
            lines
        } else {
            lines.take(MAX_LINES) + "+${lines.size - MAX_LINES} more"
        }

        val title = if (lines.size == 1) lines.single() else "${lines.size} new reports"
        return Plan(title, shown)
    }

    /** No watched suburbs means "everywhere"; otherwise a report with no suburb cannot match. */
    private fun NewReport.isInAWatchedSuburb(watched: Set<String>): Boolean {
        if (watched.isEmpty()) return true
        val suburb = suburb ?: return false
        return watched.any { it.equals(suburb, ignoreCase = true) }
    }

    /** "Checkpoint – Lincoln Road, HENDERSON", with whatever parts this report actually has. */
    private fun NewReport.line(): String {
        val where = listOfNotNull(road?.takeIf { it.isNotBlank() }, suburb?.takeIf { it.isNotBlank() })
            .joinToString(", ")
        val name = type.displayName(typeLabel)
        return if (where.isEmpty()) name else "$name – $where"
    }
}
