package nz.personal.checkpointwatch.data

import java.time.Duration
import java.time.Instant

/**
 * How long the local database keeps things. The app is a personal log on one phone with no export
 * and no backup, so nothing here is recoverable once it goes — the two windows are deliberately
 * generous, and both live here so there is one place to change them.
 *
 * Applied inside [ScrapeRecorder]'s transaction, after the scan's own writes: retention is
 * housekeeping, not a step a scan should ever fail on its own account.
 */
object RetentionPolicy {

    /** Scrape rows are an audit trail of how the collector behaved; a month of them is plenty. */
    val SCRAPE_HISTORY: Duration = Duration.ofDays(30)

    /** Posts (and their reports) outlive the scrape log by a long way: six months. */
    val POST_HISTORY: Duration = Duration.ofDays(180)

    /** What one sweep removed, so the recorder's tests can see it happened. */
    data class Swept(val scrapes: Int, val posts: Int)

    suspend fun sweep(store: ScrapeStore, now: Instant): Swept {
        val scrapes = store.deleteScrapesStartedBefore(now.minus(SCRAPE_HISTORY).toEpochMilli())
        val posts = store.deleteStalePosts(now.minus(POST_HISTORY).toEpochMilli())
        return Swept(scrapes = scrapes, posts = posts)
    }
}
