package nz.personal.checkpointwatch.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nz.personal.checkpointwatch.R
import nz.personal.checkpointwatch.model.ReportType
import nz.personal.checkpointwatch.ui.CwIcons
import java.time.Instant

/** Wide screens get a reading column rather than a 1200 px line of text. */
private val MAX_CONTENT_WIDTH = 640.dp

private val GUTTER = 16.dp

/**
 * Everything the home screen can be asked to do. One immutable holder rather than eight separate
 * lambda parameters, so recomposition sees a single stable object.
 *
 * [onOpenPost] returns false when the phone has nothing that can open a web link, which is the one
 * failure the screen has to say something about.
 */
@Immutable
data class HomeCallbacks(
    val onRefresh: () -> Unit,
    val onToggleType: (ReportType) -> Unit,
    val onSoloType: (ReportType) -> Unit,
    val onSetSuburb: (String?) -> Unit,
    val onClearFilters: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenPost: (String) -> Boolean,
)

/**
 * The home screen, with no ViewModel in it: everything it shows arrives in [state] and everything
 * it does leaves through [callbacks]. That is what lets previews and screenshot tests render every
 * state of this screen — first run, offline, filtered out, mid-scan — without a database.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    state: HomeUiState,
    callbacks: HomeCallbacks,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val openFailed = stringResource(R.string.card_open_failed)

    ConfirmOnNewReports(state, haptics)

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = scheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = scheme.background,
                    scrolledContainerColor = scheme.surfaceContainer,
                    titleContentColor = scheme.onSurface,
                    actionIconContentColor = scheme.onSurfaceVariant,
                ),
                actions = {
                    IconButton(onClick = callbacks.onOpenSettings) {
                        Icon(CwIcons.Settings, contentDescription = stringResource(R.string.cd_settings))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        val direction = LocalLayoutDirection.current
        PullToRefreshBox(
            isRefreshing = state.scanning,
            onRefresh = {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                callbacks.onRefresh()
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    start = innerPadding.calculateStartPadding(direction),
                    end = innerPadding.calculateEndPadding(direction),
                ),
        ) {
            ReportList(
                state = state,
                callbacks = callbacks,
                listState = listState,
                bottomInset = innerPadding.calculateBottomPadding(),
                onOpenPost = { url ->
                    if (!callbacks.onOpenPost(url)) {
                        scope.launch { snackbarHostState.showSnackbar(openFailed) }
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportList(
    state: HomeUiState,
    callbacks: HomeCallbacks,
    listState: LazyListState,
    bottomInset: Dp,
    onOpenPost: (String) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = bottomInset + 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "summary", contentType = "summary") {
            PageWidth(Modifier.animateItem()) {
                SummaryHeader(
                    summary = state.summary,
                    now = state.now,
                    hiddenTypes = state.settings.hiddenTypes,
                    onSoloType = callbacks.onSoloType,
                )
            }
        }

        item(key = "banner", contentType = "banner") {
            PageWidth(Modifier.animateItem()) {
                StatusBanner(
                    banner = state.banner,
                    scanning = state.scanning,
                    lastChecked = state.lastChecked,
                    now = state.now,
                )
            }
        }

        stickyHeader(key = "filters", contentType = "filters") {
            PageWidth(Modifier.background(MaterialTheme.colorScheme.background)) {
                FilterBar(
                    hiddenTypes = state.settings.hiddenTypes,
                    suburbs = state.suburbs,
                    suburbFilter = state.settings.suburbFilter,
                    onToggleType = callbacks.onToggleType,
                    onSetSuburb = callbacks.onSetSuburb,
                )
            }
        }

        if (state.items.isEmpty() && !state.loading) {
            item(key = "empty", contentType = "empty") {
                PageWidth(Modifier.animateItem()) {
                    HomeEmptyState(
                        totalReports = state.totalReports,
                        scanning = state.scanning || state.firstEver,
                        onClearFilters = callbacks.onClearFilters,
                        onRetry = callbacks.onRefresh,
                    )
                }
            }
        }

        // Emitted one by one rather than through items(), because a day header has to be a
        // stickyHeader: scrolling through yesterday should never leave you wondering which day
        // you are looking at. Compose pins the most recent sticky header only, so a day heading
        // takes over from the filter row as soon as you are into the list proper.
        state.items.forEach { item ->
            when (item) {
                is ListItem.DayHeader -> stickyHeader(key = itemKey(item), contentType = "day") {
                    PageWidth(Modifier.background(MaterialTheme.colorScheme.background)) {
                        DayHeader(item.label)
                    }
                }

                is ListItem.Gap -> item(key = itemKey(item), contentType = "gap") {
                    PageWidth(Modifier.animateItem()) { GapDivider() }
                }

                is ListItem.Report -> item(key = itemKey(item), contentType = "report") {
                    PageWidth(Modifier.animateItem()) {
                        ReportCard(report = item.report, now = state.now, onOpenPost = onOpenPost)
                    }
                }
            }
        }
    }
}

/** Stable across scans, so a new report slides in rather than the whole list redrawing. */
private fun itemKey(item: ListItem): Any = when (item) {
    is ListItem.DayHeader -> "day-${item.label}"
    is ListItem.Gap -> "gap-${item.afterPostId}"
    is ListItem.Report -> "report-${item.report.id}"
}

/**
 * The reading column: full width up to [MAX_CONTENT_WIDTH], then centred. The outer box stays full
 * width so a sticky header's background still covers the gutters as cards scroll under it.
 */
@Composable
private fun PageWidth(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(
            modifier = Modifier
                .widthIn(max = MAX_CONTENT_WIDTH + GUTTER * 2)
                .fillMaxWidth()
                .padding(horizontal = GUTTER),
        ) {
            content()
        }
    }
}

/**
 * One light tick when a finished scan actually brought something back. Nothing at all when it
 * found nothing, which is most of the time — a buzz that means "no change" is just noise.
 */
@Composable
private fun ConfirmOnNewReports(state: HomeUiState, haptics: HapticFeedback) {
    val newCount = remember(state.items) {
        state.items.count { it is ListItem.Report && it.report.isNew }
    }
    var lastConfirmed by remember { mutableStateOf<Instant?>(null) }
    LaunchedEffect(state.scanning, state.lastChecked, newCount) {
        val checked = state.lastChecked
        if (!state.scanning && checked != null && checked != lastConfirmed && newCount > 0) {
            lastConfirmed = checked
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }
}
