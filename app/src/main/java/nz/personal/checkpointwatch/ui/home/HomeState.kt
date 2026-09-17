package nz.personal.checkpointwatch.ui.home

import nz.personal.checkpointwatch.data.ReportRow
import nz.personal.checkpointwatch.data.ScrapeStatus
import nz.personal.checkpointwatch.model.ReportType
import nz.personal.checkpointwatch.scan.ScanSummary
import nz.personal.checkpointwatch.settings.Settings
import nz.personal.checkpointwatch.ui.AreaLinker
import nz.personal.checkpointwatch.ui.ReportUi
import nz.personal.checkpointwatch.ui.TimeFormat
import java.time.Duration
import java.time.Instant

/** One row of the report list: a day divider, a report card, or a history-gap marker. */
sealed interface ListItem {
    data class DayHeader(val label: String) : ListItem
    data class Report(val report: ReportUi) : ListItem
    data class Gap(val afterPostId: String) : ListItem
}

/** Counts per type for the last 2 hours ("what's happening now"), and the freshest report time. */
data class Summary(val counts: Map<ReportType, Int>, val freshest: Instant?)

/** The status banner's exact, pre-rendered copy; [BannerUi.None] shows nothing. */
sealed interface BannerUi {
    data object None : BannerUi
    data class Message(val text: String) : BannerUi
}

/** Turns a scan's raw state into the banner's exact copy. Pure; NZ English, never alarming. */
object BannerBuilder {

    fun build(scanning: Boolean, firstEver: Boolean, summary: ScanSummary?, newReportCount: Int): BannerUi {
        if (scanning) {
            val text = if (firstEver) "Finding checkpoints for the first time…" else "Finding checkpoints…"
            return BannerUi.Message(text)
        }
        if (summary == null) return BannerUi.None
        return when (summary.status) {
            ScrapeStatus.OK -> BannerUi.Message(
                if (newReportCount > 0) "Found $newReportCount new ${reportWord(newReportCount)}" else "No new reports",
            )
            ScrapeStatus.OK_WITH_GAP -> BannerUi.Message(
                "Found $newReportCount new ${reportWord(newReportCount)} · earlier posts unavailable",
            )
            ScrapeStatus.FAILED_NETWORK -> BannerUi.Message("Couldn't reach Facebook · showing saved reports")
            ScrapeStatus.FAILED_NO_DATA -> BannerUi.Message("Facebook returned no posts · showing saved reports")
            ScrapeStatus.CANCELLED -> BannerUi.None
        }
    }

    private fun reportWord(count: Int): String = if (count == 1) "report" else "reports"
}

/** Why the list has nothing in it, which decides which empty state the screen shows. */
enum class EmptyKind {
    /** The list has something in it; no empty state at all. */
    NONE,

    /** There are saved reports, but the owner's filters hide all of them. */
    FILTERED,

    /** A scan is running, or is about to: nothing has gone wrong yet. */
    SEARCHING,

    /** A scan finished and stored nothing. The page simply has not had anything worth keeping. */
    NO_REPORTS_YET,

    /** The phone could not reach Facebook. */
    OFFLINE,

    /** Facebook was reached and served no posts. */
    NO_POSTS,
}

/**
 * Which empty state to show, from what actually happened rather than from what is left over.
 *
 * The distinction matters: "Couldn't reach Facebook" after a scan that worked perfectly and found
 * nothing would send the owner off checking their connection for no reason, and a quiet "no reports
 * yet" after a real network failure would hide a problem they could fix.
 */
object EmptyStateBuilder {

    fun kind(
        visibleItems: Int,
        totalReports: Int,
        scanning: Boolean,
        lastStatus: ScrapeStatus?,
    ): EmptyKind = when {
        visibleItems > 0 -> EmptyKind.NONE
        totalReports > 0 -> EmptyKind.FILTERED
        scanning -> EmptyKind.SEARCHING
        lastStatus == null -> EmptyKind.SEARCHING
        lastStatus == ScrapeStatus.FAILED_NETWORK -> EmptyKind.OFFLINE
        lastStatus == ScrapeStatus.FAILED_NO_DATA -> EmptyKind.NO_POSTS
        // OK, OK_WITH_GAP and CANCELLED all mean "nothing to report", not "something is wrong".
        else -> EmptyKind.NO_REPORTS_YET
    }
}

/** Everything the home screen renders, built fresh from Room + settings + scan state each tick. */
data class HomeUiState(
    val loading: Boolean,
    val items: List<ListItem>,
    val summary: Summary,
    val suburbs: List<String>,
    val settings: Settings,
    val scanning: Boolean,
    val banner: BannerUi,
    val lastChecked: Instant?,
    val totalReports: Int,
    /** No scan has ever been recorded on this phone; the banner says so rather than "none". */
    val firstEver: Boolean,
    /** Why the list is empty, if it is. [EmptyKind.NONE] when there is something to show. */
    val emptyKind: EmptyKind,
    /**
     * A scan the owner asked for by pulling the list down, and only that. The refresh indicator
     * follows this rather than [scanning], so the automatic scan on open and any background scan
     * are reported by the banner's sweep alone — no spinner drops in unasked.
     */
    val pullRefreshing: Boolean,
    /**
     * The moment this state was built. Relative times ("12 min ago") are rendered against it
     * rather than against `Instant.now()` read inside a composable, so the whole screen agrees
     * with itself and a preview or screenshot test renders the same thing every run.
     */
    val now: Instant,
) {
    companion object {
        val Loading = HomeUiState(
            loading = true,
            items = emptyList(),
            summary = Summary(emptyMap(), null),
            suburbs = emptyList(),
            settings = Settings(),
            scanning = false,
            banner = BannerUi.None,
            lastChecked = null,
            totalReports = 0,
            firstEver = true,
            emptyKind = EmptyKind.SEARCHING,
            pullRefreshing = false,
            now = Instant.EPOCH,
        )
    }
}

/** Only reports made this recently count towards the "what's happening now" summary. */
private val SUMMARY_WINDOW: Duration = Duration.ofHours(2)

/** A report can be reported up to this far ahead of "now" (clock skew, resolver rounding). */
private val SUMMARY_FUTURE_SLACK: Duration = Duration.ofMinutes(15)

/**
 * Turns the DB's [ReportRow]s into what the list actually shows: filtered, day-grouped, gap
 * markers preserved, area mentions linked, freshness resolved. Pure and fully unit-tested; the
 * [HomeViewModel] does nothing but wire this (and [BannerBuilder]) to their live sources.
 */
object HomeStateBuilder {

    data class Built(val items: List<ListItem>, val summary: Summary, val totalReports: Int)

    fun build(rows: List<ReportRow>, settings: Settings, now: Instant, newSince: Instant?): Built {
        val summary = buildSummary(rows, now)
        val lastRowIdByPost = lastRowIdByPost(rows)
        val allReports = rows.map { row ->
            row.toReportUi(now = now, newSince = newSince, isLastInPost = row.id == lastRowIdByPost[row.postId])
        }
        val mentionsById = AreaLinker.link(allReports)
        val visibleById = allReports
            .asSequence()
            .map { it.copy(alsoInArea = mentionsById[it.id].orEmpty()) }
            .filter { isVisible(it, settings) }
            .associateBy(ReportUi::id)

        val items = mutableListOf<ListItem>()
        var currentDayLabel: String? = null
        var i = 0
        while (i < rows.size) {
            val postId = rows[i].postId
            var j = i
            var gapBefore = false
            while (j < rows.size && rows[j].postId == postId) {
                gapBefore = rows[j].gapBefore
                j++
            }
            val visibleInPost = (i until j).mapNotNull { visibleById[rows[it].id] }
            if (visibleInPost.isNotEmpty() || gapBefore) {
                val label = TimeFormat.dayHeader(Instant.ofEpochMilli(rows[i].postCreatedAt), now)
                if (label != currentDayLabel) {
                    items += ListItem.DayHeader(label)
                    currentDayLabel = label
                }
            }
            visibleInPost.forEach { items += ListItem.Report(it) }
            if (gapBefore) items += ListItem.Gap(postId)
            i = j
        }
        return Built(items, summary, rows.size)
    }

    /** "N new" for the banner: rows first seen at or after [newSince], regardless of filters. */
    fun newReportCount(rows: List<ReportRow>, newSince: Instant?): Int {
        val cutoff = newSince?.toEpochMilli() ?: return 0
        return rows.count { it.firstSeenAt >= cutoff }
    }

    /** The id of the last (highest indexInPost) row for each post, assuming same-post rows are contiguous. */
    private fun lastRowIdByPost(rows: List<ReportRow>): Map<String, Long> {
        val result = LinkedHashMap<String, Long>()
        for (row in rows) result[row.postId] = row.id
        return result
    }

    private fun isVisible(report: ReportUi, settings: Settings): Boolean {
        if (report.type in settings.hiddenTypes) return false
        val suburbFilter = settings.suburbFilter ?: return true
        return report.suburb?.equals(suburbFilter, ignoreCase = true) == true
    }

    private fun buildSummary(rows: List<ReportRow>, now: Instant): Summary {
        val counted = rows.mapNotNull { row ->
            val at = row.at()
            val age = Duration.between(at, now)
            val tooOld = age > SUMMARY_WINDOW
            val tooFarAhead = age < SUMMARY_FUTURE_SLACK.negated()
            if (tooOld || tooFarAhead) null else row.type.toReportType() to at
        }
        val counts = counted.groupingBy { it.first }.eachCount()
        val freshest = counted.maxOfOrNull { it.second }
        return Summary(counts, freshest)
    }

    private fun ReportRow.toReportUi(now: Instant, newSince: Instant?, isLastInPost: Boolean): ReportUi {
        val at = at()
        return ReportUi(
            id = id,
            postId = postId,
            type = type.toReportType(),
            typeLabel = typeLabel,
            road = road,
            suburb = suburb,
            details = details,
            at = at,
            atApprox = reportedAt == null && postCreatedAtApprox,
            reportedTimeText = reportedTimeText,
            source = source,
            postText = postText,
            postUrl = postUrl,
            freshness = TimeFormat.freshness(at, now),
            gapAfter = gapBefore && isLastInPost,
            isNew = newSince != null && firstSeenAt >= newSince.toEpochMilli(),
        )
    }

    private fun ReportRow.at(): Instant = reportedAt?.let(Instant::ofEpochMilli) ?: Instant.ofEpochMilli(postCreatedAt)

    private fun String.toReportType(): ReportType =
        ReportType.entries.firstOrNull { it.name == this } ?: ReportType.OTHER
}

/**
 * What tapping a summary tile does to the list's type filter.
 *
 * A tile is a shortcut, not a third filter: tapping one narrows the list to that type alone, and
 * tapping the same tile again puts everything back. Anything else the owner had hidden is
 * forgotten by the first tap, which is the point — it is a "just show me the checkpoints" button.
 *
 * Pure, so the behaviour is decided once and unit-tested rather than re-derived in a composable.
 */
object TypeFilter {

    /** The hidden set after tapping [type]'s tile, given what is hidden now. */
    fun solo(hidden: Set<ReportType>, type: ReportType): Set<ReportType> =
        if (isSolo(hidden, type)) emptySet() else ReportType.entries.toSet() - type

    /** True when [type] is the only type currently showing. */
    fun isSolo(hidden: Set<ReportType>, type: ReportType): Boolean =
        type !in hidden && hidden.size == ReportType.entries.size - 1
}
