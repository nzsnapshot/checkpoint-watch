package nz.personal.checkpointwatch.ui.home

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nz.personal.checkpointwatch.App
import nz.personal.checkpointwatch.collect.WebViewHost
import nz.personal.checkpointwatch.data.ReportDao
import nz.personal.checkpointwatch.data.ReportRow
import nz.personal.checkpointwatch.data.ScanTrigger
import nz.personal.checkpointwatch.data.ScrapeDao
import nz.personal.checkpointwatch.model.ReportType
import nz.personal.checkpointwatch.scan.ScanCoordinator
import nz.personal.checkpointwatch.scan.ScanState
import nz.personal.checkpointwatch.scan.ScanSummary
import nz.personal.checkpointwatch.settings.Settings
import nz.personal.checkpointwatch.settings.SettingsStore
import java.time.Instant

/** How often [HomeViewModel] refreshes "now" for relative times and the 2 h summary window. */
private const val TICK_INTERVAL_MS = 30_000L

/**
 * The home screen's single source of truth: saved reports, live scan progress and the owner's
 * filters, combined into one [HomeUiState]. All the actual logic lives in [HomeStateBuilder] and
 * [BannerBuilder], which are pure and unit-tested; this class only wires them to their live Flows.
 */
class HomeViewModel(
    private val reportDao: ReportDao,
    private val scrapeDao: ScrapeDao,
    private val settingsStore: SettingsStore,
    private val coordinator: ScanCoordinator,
    private val clock: () -> Instant = Instant::now,
) : ViewModel() {

    /** Start of the most recent scan this process began; reports first seen since then are "new". */
    @Volatile
    private var newSince: Instant? = null

    /**
     * Set when a scan is cut short by the owner leaving the app. The coordinator throttles scans to
     * one every two minutes, and a cancelled scan counts as a scan — so without this the next open
     * would be skipped and the screen would sit on stale data. The next on-open scan forces itself
     * through instead.
     */
    @Volatile
    private var forceNextOpen = false

    /**
     * True while the screen is going away only to be rebuilt — a rotation, a theme change. The scan
     * is still cancelled, but it is not the owner leaving, so the rebuilt screen should not force a
     * fresh scan past the coordinator's throttle the way a genuine return to the app does.
     */
    @Volatile
    private var rebuilding = false

    /** A scan the owner pulled down for; drives the refresh indicator, and nothing else does. */
    private val _pullRefreshing = MutableStateFlow(false)

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

    /**
     * "Has this app ever scanned?", from the database rather than from this process's memory — so
     * the first-run copy is only ever shown on a genuine first run, not after a restart.
     */
    private val neverScanned: Flow<Boolean> = scrapeDao.observeRecent(1).map { it.isEmpty() }

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
        neverScanned,
        _pullRefreshing,
    ) { snapshot, now, firstEver, pullRefreshing ->
        buildState(snapshot, now, firstEver, pullRefreshing)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Loading)

    private fun buildState(
        snapshot: Snapshot,
        now: Instant,
        firstEver: Boolean,
        pullRefreshing: Boolean,
    ): HomeUiState {
        val scanning = snapshot.scanState is ScanState.Scanning
        val built = HomeStateBuilder.build(snapshot.rows, snapshot.settings, now, newSince)
        val newCount = HomeStateBuilder.newReportCount(snapshot.rows, newSince)
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
            firstEver = firstEver,
            emptyKind = EmptyStateBuilder.kind(
                visibleItems = built.items.size,
                totalReports = built.totalReports,
                scanning = scanning,
                lastStatus = snapshot.lastSummary?.status,
            ),
            pullRefreshing = pullRefreshing,
            now = now,
        )
    }

    /**
     * The scan the screen runs when it is opened. Suspends, deliberately: the caller runs it inside
     * `repeatOnLifecycle(STARTED)` so that leaving the app cancels the coroutine and, with it, the
     * WebView — which is what the design promises. Running it in [viewModelScope] would let a scan
     * carry on with the screen gone.
     */
    suspend fun scanOnOpen(host: WebViewHost) = runScan(host, force = forceNextOpen)

    /**
     * Pull to refresh: always forced, because the owner asked for it just now, and the only scan
     * that turns the refresh indicator on. The flag is cleared in `finally`, so a scan cancelled by
     * the owner leaving takes the indicator with it.
     */
    suspend fun refresh(host: WebViewHost) {
        _pullRefreshing.value = true
        try {
            runScan(host, force = true)
        } finally {
            _pullRefreshing.value = false
        }
    }

    /**
     * Called as the screen stops. [changingConfiguration] tells the difference between the owner
     * leaving — after which the next open should scan however recently the last one ran — and a
     * rotation, after which forcing a second scan seconds later would be pure waste.
     */
    fun onStopping(changingConfiguration: Boolean) {
        rebuilding = changingConfiguration
    }

    private suspend fun runScan(host: WebViewHost, force: Boolean) {
        forceNextOpen = false
        try {
            coordinator.scan(ScanTrigger.FOREGROUND, host, force)
        } catch (cancellation: CancellationException) {
            forceNextOpen = !rebuilding
            throw cancellation
        } finally {
            rebuilding = false
        }
    }

    /** A summary tile: show only this type, or, if it is already the only one, show them all. */
    fun soloType(type: ReportType) {
        viewModelScope.launch {
            settingsStore.update { it.copy(hiddenTypes = TypeFilter.solo(it.hiddenTypes, type)) }
        }
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
                scrapeDao = container.scrapeDao,
                settingsStore = container.settings,
                coordinator = container.scanCoordinator,
            ) as T
        }
    }
}
