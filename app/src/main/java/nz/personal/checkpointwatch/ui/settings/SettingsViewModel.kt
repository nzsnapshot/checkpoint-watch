package nz.personal.checkpointwatch.ui.settings

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nz.personal.checkpointwatch.App
import nz.personal.checkpointwatch.R
import nz.personal.checkpointwatch.collect.EndReason
import nz.personal.checkpointwatch.data.CollectorKind
import nz.personal.checkpointwatch.data.ReportDao
import nz.personal.checkpointwatch.data.ScanTrigger
import nz.personal.checkpointwatch.data.ScrapeDao
import nz.personal.checkpointwatch.data.ScrapeEntity
import nz.personal.checkpointwatch.data.ScrapeStatus
import nz.personal.checkpointwatch.model.ReportType
import nz.personal.checkpointwatch.notify.Notifier
import nz.personal.checkpointwatch.scan.BackgroundScheduler
import nz.personal.checkpointwatch.scan.ScanDiagnosticsStore
import nz.personal.checkpointwatch.settings.Settings
import nz.personal.checkpointwatch.settings.SettingsStore
import java.time.Instant
import java.util.Locale

/** How many past scans the settings screen shows. */
private const val HISTORY_LIMIT = 10

/** One past scan, as the history list shows it. */
data class ScanHistoryRow(
    val id: Long,
    val startedAt: Instant,
    val trigger: ScanTrigger,
    val collector: CollectorKind,
    val status: ScrapeStatus,
    val new: Int,
    val seen: Int,
    /** Why the collector stopped; `null` when the stored name is one this version cannot read. */
    val endReason: EndReason? = null,
)

/** Everything the settings screen renders. */
data class SettingsUiState(
    val settings: Settings,
    val suburbs: List<String>,
    val history: List<ScanHistoryRow>,
    val version: String,
    /**
     * The owner asked for notifications but the system said no. Set by the screen, not the store:
     * a refused permission is not a preference, so it is never written to disk.
     */
    val notificationsBlocked: Boolean = false,
    /** Whether there is a stored account of a foreground scan to copy. */
    val hasForegroundDetails: Boolean = false,
    /** The same for a background scan, which on a new phone may not have run yet. */
    val hasBackgroundDetails: Boolean = false,
) {
    companion object {
        val Empty = SettingsUiState(
            settings = Settings(),
            suburbs = emptyList(),
            history = emptyList(),
            version = "",
        )
    }
}

/**
 * The scan log's plain-English vocabulary, and the reading of the enum names the database stores.
 *
 * Pure and exhaustive: adding a status or a collector to the data layer will not compile until it
 * has been given something to say here, which is the point — the history list exists to be honest
 * about what happened, so a new outcome must never quietly read as a familiar one.
 */
object ScanHistoryUi {

    fun rows(entities: List<ScrapeEntity>): List<ScanHistoryRow> = entities.map { entity ->
        ScanHistoryRow(
            id = entity.id,
            startedAt = Instant.ofEpochMilli(entity.startedAt),
            trigger = trigger(entity.trigger),
            collector = collector(entity.collector),
            status = status(entity.status),
            new = entity.postsNew,
            seen = entity.postsSeen,
            endReason = endReason(entity.endReason),
        )
    }

    /** An unreadable stored name means the row is from a version we do not understand. */
    fun status(name: String): ScrapeStatus =
        ScrapeStatus.entries.firstOrNull { it.name == name } ?: ScrapeStatus.FAILED_NO_DATA

    fun trigger(name: String): ScanTrigger =
        ScanTrigger.entries.firstOrNull { it.name == name } ?: ScanTrigger.BACKGROUND

    fun collector(name: String): CollectorKind =
        CollectorKind.entries.firstOrNull { it.name == name } ?: CollectorKind.NONE

    /**
     * Unlike the others this one has no safe default: every end reason says something specific
     * and confident about what happened, so a name this version cannot read is better shown as
     * nothing at all than as the nearest familiar sentence.
     */
    fun endReason(name: String): EndReason? = EndReason.entries.firstOrNull { it.name == name }

    @StringRes
    fun statusLabel(status: ScrapeStatus): Int = when (status) {
        ScrapeStatus.OK -> R.string.status_ok
        ScrapeStatus.OK_WITH_GAP -> R.string.status_ok_with_gap
        ScrapeStatus.FAILED_NETWORK -> R.string.status_failed_network
        ScrapeStatus.FAILED_NO_DATA -> R.string.status_failed_no_data
        ScrapeStatus.CANCELLED -> R.string.status_cancelled
    }

    @StringRes
    fun triggerLabel(trigger: ScanTrigger): Int = when (trigger) {
        ScanTrigger.FOREGROUND -> R.string.trigger_foreground
        ScanTrigger.BACKGROUND -> R.string.trigger_background
    }

    @StringRes
    fun endReasonLabel(reason: EndReason): Int = when (reason) {
        EndReason.LOGIN_WALL -> R.string.end_login_wall
        EndReason.NO_MORE_POSTS -> R.string.end_no_more_posts
        EndReason.TIMEOUT -> R.string.end_timeout
        EndReason.NETWORK_ERROR -> R.string.end_network_error
        EndReason.BLOCKED -> R.string.end_blocked
        EndReason.CANCELLED -> R.string.end_cancelled
    }

    @StringRes
    fun collectorLabel(collector: CollectorKind): Int = when (collector) {
        CollectorKind.WEBVIEW -> R.string.collector_webview
        CollectorKind.WEBVIEW_DOM -> R.string.collector_webview_dom
        CollectorKind.HTTP -> R.string.collector_http
        CollectorKind.NONE -> R.string.collector_none
    }
}

/**
 * Adding and removing a watched suburb.
 *
 * Suburbs come out of the parser upper-case ("HENDERSON") and are stored that way, but a value
 * saved by an earlier version — or a list that cased them differently — must still untick, so
 * matching is case-insensitive in both directions. A toggle that can add but not remove is the
 * worst kind of setting.
 */
object WatchedSuburbs {

    fun toggle(current: Set<String>, suburb: String): Set<String> {
        val name = suburb.trim().uppercase(Locale.ENGLISH)
        if (name.isEmpty()) return current
        val without = current.filterNotTo(LinkedHashSet()) { it.trim().equals(name, ignoreCase = true) }
        return if (without.size < current.size) without else without.apply { add(name) }
    }

    fun isWatched(current: Set<String>, suburb: String): Boolean =
        current.any { it.trim().equals(suburb.trim(), ignoreCase = true) }
}

/**
 * What each offered background interval is called, in the two forms the control needs: five
 * segments side by side have room for "15m" and nothing more, while the stacked radio list it
 * falls back to has room to say what that actually means.
 *
 * Pure, so the two forms cannot drift apart and the five buttons cannot disagree.
 */
object IntervalUi {

    @StringRes
    fun compactLabel(minutes: Int): Int = when (minutes) {
        0 -> R.string.settings_interval_off
        15 -> R.string.settings_interval_15
        30 -> R.string.settings_interval_30
        60 -> R.string.settings_interval_60
        120 -> R.string.settings_interval_120
        // Unreachable: the row is built from ALLOWED_BACKGROUND_MINUTES, which is exactly these.
        else -> R.string.settings_interval_off
    }

    @StringRes
    fun longLabel(minutes: Int): Int = when (minutes) {
        0 -> R.string.settings_interval_off
        15 -> R.string.settings_interval_15_long
        30 -> R.string.settings_interval_30_long
        60 -> R.string.settings_interval_60_long
        120 -> R.string.settings_interval_120_long
        else -> R.string.settings_interval_off
    }
}

/**
 * The settings screen's state and the side effects its switches have: rescheduling background
 * work, and making sure the notification channel exists before anything tries to post to it.
 */
class SettingsViewModel(
    private val appContext: Context,
    private val settingsStore: SettingsStore,
    private val notifier: Notifier,
    private val diagnostics: ScanDiagnosticsStore,
    scrapeDao: ScrapeDao,
    reportDao: ReportDao,
) : ViewModel() {

    private val version: String = readVersion(appContext)

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsStore.settings,
        scrapeDao.observeRecent(HISTORY_LIMIT),
        reportDao.observeSuburbs(),
    ) { settings, scrapes, suburbs ->
        SettingsUiState(
            settings = settings,
            suburbs = suburbs,
            history = ScanHistoryUi.rows(scrapes),
            version = version,
            // Re-read whenever anything else changes, which includes every recorded scan: the
            // files appear the moment one finishes, and there is nothing to observe them with.
            hasForegroundDetails = diagnostics.exists(ScanTrigger.FOREGROUND),
            hasBackgroundDetails = diagnostics.exists(ScanTrigger.BACKGROUND),
        )
        // The two existence checks touch the disk; the screen's thread is not the place for that.
    }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState.Empty)

    /** The text behind "Copy last scan", or `null` when there is nothing stored for [trigger]. */
    suspend fun details(trigger: ScanTrigger): String? = diagnostics.read(trigger)

    /**
     * Persists the interval and then re-applies the schedule. Both, always: a stored interval that
     * WorkManager never heard about is a setting that silently does nothing.
     */
    fun setInterval(minutes: Int) {
        viewModelScope.launch {
            settingsStore.update { it.copy(backgroundMinutes = minutes) }
            BackgroundScheduler.apply(appContext, minutes)
        }
    }

    /**
     * Called the instant the switch is flipped on, before the permission is asked for, so the
     * owner has a "New reports" category to tune in system settings rather than an app that claims
     * to notify and shows nothing there.
     */
    fun prepareNotifications() = notifier.ensureChannel()

    fun setNotify(enabled: Boolean) {
        if (enabled) notifier.ensureChannel()
        viewModelScope.launch { settingsStore.update { it.copy(notify = enabled) } }
    }

    fun toggleNotifyType(type: ReportType) {
        viewModelScope.launch {
            settingsStore.update { current ->
                val types = current.notifyTypes
                current.copy(notifyTypes = if (type in types) types - type else types + type)
            }
        }
    }

    /**
     * Ticks or unticks one suburb, deciding from the stored set inside the same atomic update.
     * Working out the new set from what a composition last saw would drop a tick whenever two taps
     * landed before the store emitted again.
     */
    fun toggleWatchedSuburb(suburb: String) {
        viewModelScope.launch {
            settingsStore.update { it.copy(watchedSuburbs = WatchedSuburbs.toggle(it.watchedSuburbs, suburb)) }
        }
    }

    /** "All areas": an empty set means anywhere can notify. */
    fun clearWatchedSuburbs() {
        viewModelScope.launch { settingsStore.update { it.copy(watchedSuburbs = emptySet()) } }
    }

    /**
     * Whether a notification posted right now would actually be seen. Notifications can be off for
     * the whole app, and the "New reports" channel can be muted on its own once it exists — the
     * switch should tell the same truth in both cases.
     */
    fun notificationsBlocked(): Boolean = notifier.notificationsBlocked()

    private fun readVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (_: PackageManager.NameNotFoundException) {
        ""
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val container = (application as App).container
            return SettingsViewModel(
                appContext = application,
                settingsStore = container.settings,
                notifier = container.notifier,
                diagnostics = container.scanDiagnostics,
                scrapeDao = container.scrapeDao,
                reportDao = container.reportDao,
            ) as T
        }
    }
}
