package nz.personal.checkpointwatch.data

import nz.personal.checkpointwatch.model.ReportType
import java.time.Instant

/** Outcome of a single scrape run, stored as [ScrapeEntity.status]. */
enum class ScrapeStatus { OK, OK_WITH_GAP, FAILED_NETWORK, FAILED_NO_DATA, CANCELLED }

/** Whether a scan was run by the user opening the app or by the background worker. */
enum class ScanTrigger { FOREGROUND, BACKGROUND }

/** Which collection strategy produced the posts for a scan. */
enum class CollectorKind {
    /** The page's own feed responses: a full scan, up to about ten posts. */
    WEBVIEW,

    /** The rendered page, scraped: whatever the feed managed to draw before it stopped. */
    WEBVIEW_DOM,

    /**
     * Facebook's embeddable Page Plugin, read after the feed refused to paginate. Always the five
     * newest posts and never more, so a scan labelled this way is a scan that was rationed.
     */
    PLUGIN,

    /** The plain HTTPS GET, which can only ever see the newest post. */
    HTTP,

    NONE,
}

/** A report from a genuinely new post, surfaced by [ScrapeRecorder.record] for notifications. */
data class NewReport(
    val type: ReportType,
    val typeLabel: String,
    val road: String?,
    val suburb: String?,
    val at: Instant,
)

/** Result of [ScrapeRecorder.record]: how the scan went and what's new since last time. */
data class ScrapeOutcome(
    val scrapeId: Long,
    val status: ScrapeStatus,
    val seen: Int,
    val new: Int,
    val updated: Int,
    val newReports: List<NewReport>,
)
