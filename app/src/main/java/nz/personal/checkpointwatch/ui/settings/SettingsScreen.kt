package nz.personal.checkpointwatch.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import nz.personal.checkpointwatch.R
import nz.personal.checkpointwatch.model.ReportType
import nz.personal.checkpointwatch.scan.ALLOWED_BACKGROUND_MINUTES
import nz.personal.checkpointwatch.ui.CwIcons
import nz.personal.checkpointwatch.ui.TypeChip
import nz.personal.checkpointwatch.ui.theme.chipOutline

private val MAX_CONTENT_WIDTH = 640.dp

/** Beyond this font scale five segmented buttons stop fitting, so the choice becomes a list. */
private const val SEGMENTED_MAX_FONT_SCALE = 1.3f

/**
 * The width the segmented row needs, measured where it actually sits — inside the section card,
 * inside the screen gutters — not the width of the phone. An ordinary 411 dp phone leaves 347 dp
 * here, which is what the first attempt at this compared against 360 and lost.
 */
private val SEGMENTED_MIN_WIDTH = 296.dp

/** How much colour a selected segment carries — the same as a selected chip. */
private const val SELECTED_TINT_ALPHA = 0.16f

/** Everything the settings screen can be asked to do. */
@Immutable
data class SettingsCallbacks(
    val onBack: () -> Unit,
    val onInterval: (Int) -> Unit,
    val onNotifyChange: (Boolean) -> Unit,
    val onToggleNotifyType: (ReportType) -> Unit,
    val onToggleWatchedSuburb: (String) -> Unit,
    val onClearWatchedSuburbs: () -> Unit,
    val onBatterySettings: () -> Unit,
    val onNotificationSettings: () -> Unit,
)

/**
 * Settings, with no ViewModel in it, for the same reason as the home screen: every state here —
 * notifications refused, background updates off, an empty scan log — has to be renderable on its
 * own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    state: SettingsUiState,
    callbacks: SettingsCallbacks,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Scaffold(
        modifier = modifier,
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = callbacks.onBack) {
                        Icon(CwIcons.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = scheme.background,
                    titleContentColor = scheme.onSurface,
                    navigationIconContentColor = scheme.onSurfaceVariant,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = MAX_CONTENT_WIDTH)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                BackgroundSection(state, callbacks)
                NotificationsSection(state, callbacks)
                HistorySection(state)
                AboutSection(state)
            }
        }
    }
}

@Composable
private fun BackgroundSection(state: SettingsUiState, callbacks: SettingsCallbacks) {
    Section(title = stringResource(R.string.settings_background_header), icon = CwIcons.Clock) {
        Text(
            text = stringResource(R.string.settings_interval_label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        IntervalChoice(
            selected = state.settings.backgroundMinutes,
            onSelect = callbacks.onInterval,
        )
        Text(
            text = stringResource(R.string.settings_interval_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = callbacks.onBatterySettings,
            modifier = Modifier.minimumInteractiveComponentSize(),
        ) {
            Text(stringResource(R.string.settings_battery_button))
        }
    }
}

/**
 * Five choices, side by side while they fit and stacked as a radio list when they do not. The
 * stacked form is the accessible one anyway, so large text simply gets it earlier.
 */
@Composable
private fun IntervalChoice(selected: Int, onSelect: (Int) -> Unit) {
    BoxWithConstraints {
        val stacked = LocalDensity.current.fontScale >= SEGMENTED_MAX_FONT_SCALE ||
            maxWidth < SEGMENTED_MIN_WIDTH
        IntervalChoiceLayout(selected = selected, stacked = stacked, onSelect = onSelect)
    }
}

/**
 * "15 m" truncated to "15…" would be worse than useless, so nothing here is allowed to ellipsise:
 * when the labels stop fitting, the control changes shape instead.
 */
@Composable
private fun IntervalChoiceLayout(selected: Int, stacked: Boolean, onSelect: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    if (stacked) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ALLOWED_BACKGROUND_MINUTES.forEach { minutes ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = minutes == selected,
                            role = Role.RadioButton,
                            onClick = { onSelect(minutes) },
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(selected = minutes == selected, onClick = null)
                    Text(
                        // The stacked form has room to say it properly.
                        text = stringResource(IntervalUi.longLabel(minutes)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    } else {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ALLOWED_BACKGROUND_MINUTES.forEachIndexed { index, minutes ->
                SegmentedButton(
                    selected = minutes == selected,
                    onClick = { onSelect(minutes) },
                    modifier = Modifier.minimumInteractiveComponentSize(),
                    shape = SegmentedButtonDefaults.itemShape(index, ALLOWED_BACKGROUND_MINUTES.size),
                    icon = {},
                    // The chips' language, so selection looks the same everywhere: a quiet tinted
                    // fill and an ordinary label, rather than amber-on-brown.
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = scheme.primary.copy(alpha = SELECTED_TINT_ALPHA),
                        activeContentColor = scheme.onSurface,
                        activeBorderColor = scheme.chipOutline,
                        inactiveContainerColor = Color.Transparent,
                        inactiveContentColor = scheme.onSurfaceVariant,
                        inactiveBorderColor = scheme.chipOutline,
                    ),
                    label = {
                        Text(stringResource(IntervalUi.compactLabel(minutes)), maxLines = 1, softWrap = false)
                    },
                )
            }
        }
    }
}

@Composable
private fun NotificationsSection(state: SettingsUiState, callbacks: SettingsCallbacks) {
    val scheme = MaterialTheme.colorScheme
    val on = state.settings.notify
    var sheetOpen by rememberSaveable { mutableStateOf(false) }

    Section(title = stringResource(R.string.settings_notifications_header), icon = CwIcons.Bell) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_notify_switch),
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.settings_notify_supporting),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Switch(checked = on, onCheckedChange = callbacks.onNotifyChange)
        }

        if (state.notificationsBlocked) {
            Notice(text = stringResource(R.string.settings_notify_denied)) {
                TextButton(onClick = callbacks.onNotificationSettings) {
                    Text(stringResource(R.string.settings_notify_open_settings))
                }
            }
        } else if (on && state.settings.backgroundMinutes == 0) {
            Notice(text = stringResource(R.string.settings_notify_needs_background))
        }

        Text(
            text = stringResource(R.string.settings_notify_types),
            style = MaterialTheme.typography.titleSmall,
            color = if (on) scheme.onSurface else scheme.onSurfaceVariant,
        )
        TypeChips(
            chosen = state.settings.notifyTypes,
            enabled = on,
            onToggle = callbacks.onToggleNotifyType,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_watched_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (on) scheme.onSurface else scheme.onSurfaceVariant,
                )
                Text(
                    text = watchedSummary(state.settings.watchedSuburbs),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = { sheetOpen = true },
                enabled = on,
                modifier = Modifier.minimumInteractiveComponentSize(),
            ) {
                Text(stringResource(R.string.settings_watched_change))
            }
        }
    }

    if (sheetOpen) {
        WatchedAreasSheet(
            suburbs = state.suburbs,
            chosen = state.settings.watchedSuburbs,
            onToggle = callbacks.onToggleWatchedSuburb,
            onClearAll = callbacks.onClearWatchedSuburbs,
            onDismiss = { sheetOpen = false },
        )
    }
}

@Composable
private fun watchedSummary(watched: Set<String>): String =
    if (watched.isEmpty()) {
        stringResource(R.string.settings_watched_all)
    } else {
        pluralStringResource(R.plurals.area_count, watched.size, watched.size)
    }

@Composable
private fun TypeChips(chosen: Set<ReportType>, enabled: Boolean, onToggle: (ReportType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ReportType.entries.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { type ->
                    TypeChip(
                        type = type,
                        selected = enabled && type in chosen,
                        enabled = enabled,
                        onClick = { onToggle(type) },
                    )
                }
            }
        }
    }
}
