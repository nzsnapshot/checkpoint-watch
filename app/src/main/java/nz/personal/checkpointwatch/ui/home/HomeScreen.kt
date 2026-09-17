package nz.personal.checkpointwatch.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
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
import kotlin.math.roundToInt

/** Wide screens get a reading column rather than a 1200 px line of text. */
private val MAX_CONTENT_WIDTH = 640.dp

private val GUTTER = 16.dp

/**
 * Vertical rhythm, on a 4 dp grid. A day heading adds 16 of its own on top of [CARD_GAP], which is
 * the 24 a new day gets; see `DayHeader`.
 */
private val CARD_GAP = 8.dp
private val SECTION_GAP = 16.dp

/** Room the subtitle needs in the expanded bar, and how fast it gets out of the way. */
private val SUBTITLE_HEADROOM = 24.dp
private const val SUBTITLE_FADE_RATE = 2.5f

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
            // The filters live up here with the app bar rather than in the list. A LazyColumn pins
            // only its most recent sticky header, so as a list item the filter row was unpinned by
            // the first day heading and scrolled away; the day headings stay sticky in the list.
            // Read through derivedStateOf: overlappedFraction changes every scroll frame, and
            // reading it directly here would recompose the whole bar — chips included — with it.
            val scrolled by remember(scrollBehavior) {
                derivedStateOf { scrollBehavior.state.overlappedFraction > 0.01f }
            }
            val barColour by animateColorAsState(
                targetValue = if (scrolled) scheme.surfaceContainer else scheme.background,
                label = "top-bar-colour",
            )
            Column(modifier = Modifier.background(barColour)) {
                LargeTopAppBar(
                    title = { HomeTitle(collapsedFraction = scrollBehavior.state.collapsedFraction) },
                    expandedHeight = TopAppBarDefaults.LargeAppBarExpandedHeight + SUBTITLE_HEADROOM,
                    // The Column above paints the container, so the bar itself must not, or the two
                    // would cross-fade against each other as the list scrolls under them.
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
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
                // Inset and capped exactly like the list below it, so on a tablet or in landscape
                // the chips line up with the cards instead of running the full width — and never
                // sit under a cutout or a side navigation bar.
                PageWidth(
                    Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                    ),
                ) {
                    FilterBar(
                        hiddenTypes = state.settings.hiddenTypes,
                        suburbs = state.suburbs,
                        suburbFilter = state.settings.suburbFilter,
                        onToggleType = callbacks.onToggleType,
                        onSetSuburb = callbacks.onSetSuburb,
                    )
                }
                // A hairline, and only once something is actually underneath it.
                HorizontalDivider(
                    color = if (scrolled) scheme.outlineVariant else Color.Transparent,
                )
            }
        },
    ) { innerPadding ->
        val direction = LocalLayoutDirection.current
        PullToRefreshBox(
            // Only a scan the owner pulled down for. An automatic scan on open, or one the
            // background worker started, is reported by the banner's sweep — a spinner nobody asked
            // for dropping in over the list is exactly what the design rules out.
            isRefreshing = state.pullRefreshing,
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

/**
 * The app's name, with a quiet line under it saying what it is actually looking at. The subtitle
 * fades *and* gives its height back as the bar collapses, so the collapsed bar is a single
 * correctly-centred line rather than one line floating above an invisible second.
 */
@Composable
private fun HomeTitle(collapsedFraction: Float) {
    val visible = ((1f - collapsedFraction) * SUBTITLE_FADE_RATE).coerceIn(0f, 1f)
    Column {
        Text(stringResource(R.string.home_title))
        Text(
            text = stringResource(R.string.home_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier
                .graphicsLayer { alpha = visible }
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val height = (placeable.height * visible).roundToInt()
                    layout(placeable.width, height) { placeable.place(0, 0) }
                },
        )
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
        contentPadding = PaddingValues(top = SECTION_GAP, bottom = bottomInset + 32.dp),
        verticalArrangement = Arrangement.spacedBy(CARD_GAP),
    ) {
        item(key = "summary", contentType = "summary") {
            PageWidth(Modifier.animateItem()) {
                SummaryHeader(
                    summary = state.summary,
                    now = state.now,
                    hiddenTypes = state.settings.hiddenTypes,
                    loading = state.loading,
                    onSoloType = callbacks.onSoloType,
                )
            }
        }

        item(key = "banner", contentType = "banner") {
            // CARD_GAP + this = SECTION_GAP.
            PageWidth(Modifier.animateItem().padding(top = SECTION_GAP - CARD_GAP)) {
                StatusBanner(
                    banner = state.banner,
                    scanning = state.scanning,
                    lastChecked = state.lastChecked,
                    now = state.now,
                )
            }
        }

        if (state.emptyKind != EmptyKind.NONE && !state.loading) {
            item(key = "empty", contentType = "empty") {
                PageWidth(Modifier.animateItem()) {
                    HomeEmptyState(
                        kind = state.emptyKind,
                        onClearFilters = callbacks.onClearFilters,
                        onRetry = callbacks.onRefresh,
                    )
                }
            }
        }

        // Emitted one by one rather than through items(), because a day header has to be a
        // stickyHeader: scrolling through yesterday should never leave you wondering which day
        // you are looking at.
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
    // Saved, not just remembered: coming back from Settings or rotating rebuilds this composable,
    // and a plain remember would let the same scan's result buzz a second time.
    var lastConfirmed by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(state.scanning, state.lastChecked, newCount) {
        val checked = state.lastChecked?.toEpochMilli() ?: return@LaunchedEffect
        if (!state.scanning && checked != lastConfirmed && newCount > 0) {
            lastConfirmed = checked
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }
}
