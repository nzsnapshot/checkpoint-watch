package nz.personal.checkpointwatch.parse

import nz.personal.checkpointwatch.Constants
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Resolves a free-text "Time:" value from a Facebook post (e.g. "11:55PM", "8.14 pm") into an
 * [Instant], anchored to the day the post was created. Facebook posts only ever give a
 * wall-clock time, never a date, so the resolver has to infer which calendar day is meant:
 * the reported time is assumed to be on the same day as [createdAt] unless that would put it
 * more than 15 minutes in the future, in which case it must have been the previous day
 * (a post can be created shortly before the reported time, e.g. "posted at 11:58PM, time:
 * 12:00AM", but not e.g. 20 hours before it).
 */
object ReportedTimeResolver {
    private val TIME_RX = Regex("""(\d{1,2})(?:[:.](\d{2}))?\s*([AaPp])\.?\s*[Mm]""")
    private val ALLOWED_FUTURE_SLACK: Duration = Duration.ofMinutes(15)

    fun resolve(timeText: String, createdAt: Instant, zone: ZoneId = Constants.NZ): Instant? {
        val match = TIME_RX.find(timeText) ?: return null
        val hour12 = match.groupValues[1].toInt()
        val minute = match.groupValues[2].ifEmpty { "0" }.toInt()
        if (hour12 !in 1..12 || minute !in 0..59) return null
        val isPm = match.groupValues[3].equals("p", ignoreCase = true)
        val hour24 = (hour12 % 12) + if (isPm) 12 else 0

        val createdDate = createdAt.atZone(zone).toLocalDate()
        var candidate = ZonedDateTime.of(createdDate, LocalTime.of(hour24, minute), zone)
        if (candidate.toInstant().isAfter(createdAt.plus(ALLOWED_FUTURE_SLACK))) {
            candidate = candidate.minusDays(1)
        }
        return candidate.toInstant()
    }
}
