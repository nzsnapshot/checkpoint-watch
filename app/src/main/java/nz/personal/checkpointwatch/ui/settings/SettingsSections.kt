package nz.personal.checkpointwatch.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nz.personal.checkpointwatch.Constants
import nz.personal.checkpointwatch.R
import nz.personal.checkpointwatch.ui.CwIcons
import nz.personal.checkpointwatch.ui.TimeFormat

@Composable
internal fun HistorySection(state: SettingsUiState) {
    val scheme = MaterialTheme.colorScheme
    Section(title = stringResource(R.string.settings_history_header), icon = CwIcons.Radar) {
        Text(
            text = stringResource(R.string.settings_history_body),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        if (state.history.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        state.history.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = scheme.outlineVariant)
            ScanHistoryItem(row)
        }
    }
}

@Composable
internal fun ScanHistoryItem(row: ScanHistoryRow) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = TimeFormat.clock(row.startedAt),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )
            Text(
                text = stringResource(ScanHistoryUi.triggerLabel(row.trigger)),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            StatusChip(label = stringResource(ScanHistoryUi.statusLabel(row.status)))
        }
        Text(
            text = stringResource(R.string.settings_history_counts, row.new, row.seen) +
                " · " + stringResource(ScanHistoryUi.collectorLabel(row.collector)),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun StatusChip(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
internal fun AboutSection(state: SettingsUiState) {
    val scheme = MaterialTheme.colorScheme
    Section(title = stringResource(R.string.settings_about_header), icon = CwIcons.Info) {
        Text(
            text = stringResource(R.string.settings_about_source_label),
            style = MaterialTheme.typography.titleSmall,
            color = scheme.onSurface,
        )
        Text(
            text = stringResource(R.string.settings_about_source_name),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface,
        )
        Text(
            text = Constants.PAGE_URL,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.settings_about_disclaimer),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        if (state.version.isNotBlank()) {
            Text(
                text = stringResource(R.string.settings_about_version, state.version),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** A titled card. Grouping settings this way keeps each decision next to its own explanation. */
@Composable
internal fun Section(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = scheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
            }
        }
    }
}

/** A short, calm explanation of why something is not going to work as expected. */
@Composable
internal fun Notice(text: String, action: @Composable (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            action?.invoke()
        }
    }
}
