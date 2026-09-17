package nz.personal.checkpointwatch.ui.home

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nz.personal.checkpointwatch.App
import nz.personal.checkpointwatch.collect.WebViewHost
import nz.personal.checkpointwatch.data.ReportDao
import nz.personal.checkpointwatch.data.ReportRow
import nz.personal.checkpointwatch.data.ScanTrigger
import nz.personal.checkpointwatch.data.ScrapeStatus
import nz.personal.checkpointwatch.model.ReportType
import nz.personal.checkpointwatch.scan.ScanCoordinator
import nz.personal.checkpointwatch.scan.ScanState
import nz.personal.checkpointwatch.scan.ScanSummary
import nz.personal.checkpointwatch.settings.Settings
import nz.personal.checkpointwatch.settings.SettingsStore
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

/** How often [HomeViewModel] refreshes "now" for relative times and the 2 h summary window. */
private const val TICK_INTERVAL_MS = 30_000L

/**
 * The home screen's single source of truth: saved reports, live scan progress and the owner's
 * filters, combined into one [HomeUiState]. All the actual logic lives in [HomeStateBuilder] and
 * [BannerBuilder], which are pure and unit-tested; this class only wires them to their live Flows.
 */
class HomeViewModel(
    private val reportDao: ReportDao,
    private val settingsStore: SettingsStore,
    private val coordinator: ScanCoordinator,
    private val clock: () -> Instant = Instant::now,
) : ViewModel() {

    /** Start of the most recent scan this process began; reports first seen since then are "new". */
    @Volatile
    private var newSince: Instant? = null

    init {
        viewModelScope.launch {
            coordinator.state.collect { state ->
                if (state is ScanState.Scanning) newSince = clock()
            }
        }
    }

    private val ticker: Flow<Instant> = flow {
        while (true) {
            emit(clock())
            delay(TICK_INTERVAL_MS)
        }
    }

    val uiState: StateFlow<HomeUiState> = combine(
        combine(
            reportDao.observeRows(),
            reportDao.observeSuburbs(),
            settingsStore.settings,
            coordinator.state,
            coordinator.lastSummary,
        ) { rows, suburbs, settings, scanState, lastSummary ->
            Snapshot(rows, suburbs, settings, scanState, lastSummary)
        },
        ticker,
    ) { snapshot, now -> buildState(snapshot, now) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Loading)

    private fun buildState(snapshot: Snapshot, now: Instant): HomeUiState {
        val scanning = snapshot.scanState is ScanState.Scanning
        val built = HomeStateBuilder.build(snapshot.rows, snapshot.settings, now, newSince)
        val newCount = HomeStateBuilder.newReportCount(snapshot.rows, newSince)
        val firstEver = snapshot.rows.isEmpty() && snapshot.lastSummary == null
        val banner = BannerBuilder.build(scanning, firstEver, snapshot.lastSummary, newCount)
        return HomeUiState(
            loading = false,
            items = built.items,
            summary = built.summary,
            suburbs = snapshot.suburbs,
            settings = snapshot.settings,
            scanning = scanning,
            banner = banner,
            lastChecked = snapshot.lastSummary?.finishedAt,
            totalReports = built.totalReports,
        )
    }

    fun refresh(host: WebViewHost, force: Boolean) {
        viewModelScope.launch { coordinator.scan(ScanTrigger.FOREGROUND, host, force) }
    }

    fun toggleType(type: ReportType) {
        viewModelScope.launch {
            settingsStore.update { current ->
                val hidden = current.hiddenTypes
                current.copy(hiddenTypes = if (type in hidden) hidden - type else hidden + type)
            }
        }
    }

    fun setSuburb(suburb: String?) {
        viewModelScope.launch { settingsStore.update { it.copy(suburbFilter = suburb) } }
    }

    fun clearFilters() {
        viewModelScope.launch {
            settingsStore.update { it.copy(hiddenTypes = emptySet(), suburbFilter = null) }
        }
    }

    private data class Snapshot(
        val rows: List<ReportRow>,
        val suburbs: List<String>,
        val settings: Settings,
        val scanState: ScanState,
        val lastSummary: ScanSummary?,
    )

    /** Pulled from [App.container] so the ViewModel needs no DI framework. */
    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val container = (application as App).container
            return HomeViewModel(
                reportDao = container.reportDao,
                settingsStore = container.settings,
                coordinator = container.scanCoordinator,
            ) as T
        }
    }
}
