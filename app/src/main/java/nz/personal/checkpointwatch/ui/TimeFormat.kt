package nz.personal.checkpointwatch.ui

import nz.personal.checkpointwatch.Constants
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How recently a report was made, for card styling. Edges: <2 h, 2-6 h (inclusive), >6 h. */
enum class Freshness { FRESH, OLDER, OLD }

private val DAY_HEADER_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 60L * SECONDS_PER_MINUTE
private const val SECONDS_PER_DAY = 24L * SECONDS_PER_HOUR

/** Calm, NZ-English relative and absolute time formatting for the report list. */
object TimeFormat {

    /** "just now" / "12 min ago" / "2 h ago" / "yesterday" / "3 days ago". A future [at] is "just now". */
    fun ago(at: Instant, now: Instant): String {
        val seconds = Duration.between(at, now).seconds
        return when {
            seconds < SECONDS_PER_MINUTE -> "just now"
            seconds < SECONDS_PER_HOUR -> "${seconds / SECONDS_PER_MINUTE} min ago"
            seconds < SECONDS_PER_DAY -> "${seconds / SECONDS_PER_HOUR} h ago"
            seconds < 2 * SECONDS_PER_DAY -> "yesterday"
            else -> "${seconds / SECONDS_PER_DAY} days ago"
        }
    }

    /** "9:30 pm" in NZ local time: lower-case am/pm, no leading zero on the hour. */
    fun clock(at: Instant): String {
        val zoned = at.atZone(Constants.NZ)
        val hour24 = zoned.hour
        val hour12 = if (hour24 % 12 == 0) 12 else hour24 % 12
        val minute = zoned.minute.toString().padStart(2, '0')
        val amPm = if (hour24 < 12) "am" else "pm"
        return "$hour12:$minute $amPm"
    }

    /** "Today" / "Yesterday" / "Tue 15 Sep", by NZ calendar day (not by elapsed hours). */
    fun dayHeader(at: Instant, now: Instant): String {
        val day = at.atZone(Constants.NZ).toLocalDate()
        val today = now.atZone(Constants.NZ).toLocalDate()
        return when (day) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> DAY_HEADER_FORMATTER.format(at.atZone(Constants.NZ))
        }
    }

    /** FRESH under 2 h, OLDER from 2 h to 6 h inclusive, OLD past 6 h. A future [at] is FRESH. */
    fun freshness(at: Instant, now: Instant): Freshness {
        val seconds = Duration.between(at, now).seconds
        return when {
            seconds < 2 * SECONDS_PER_HOUR -> Freshness.FRESH
            seconds <= 6 * SECONDS_PER_HOUR -> Freshness.OLDER
            else -> Freshness.OLD
        }
    }
}
