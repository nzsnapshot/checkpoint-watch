package nz.personal.checkpointwatch.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import nz.personal.checkpointwatch.R
import nz.personal.checkpointwatch.model.ReportType
import nz.personal.checkpointwatch.ui.CwIcons
import nz.personal.checkpointwatch.ui.TimeFormat
import nz.personal.checkpointwatch.ui.typeStyle
import java.time.Instant

/** The four types that get a tile. `OTHER` is deliberately not one — it is a catch-all, not news. */
private val TILE_TYPES = listOf(
    ReportType.CHECKPOINT,
    ReportType.POLICE_PRESENCE,
    ReportType.CRASH,
    ReportType.SPEED_CAMERA,
)

/** Below this the four tiles stop fitting side by side and fold into two rows of two. */
private val NARROW_WIDTH = 360.dp

/** Above this font scale the labels wrap badly in a row of four, so they fold as well. */
private const val LARGE_FONT_SCALE = 1.3f

/**
 * "What's happening now", above everything else: how many of each type were reported in the last
 * two hours, and how fresh the freshest of them is.
 *
 * Each tile is also a shortcut — tapping one narrows the list to that type, tapping it again puts
 * everything back — so the answer to "just show me the checkpoints" is one tap from the top of
 * the screen.
 */
@Composable
fun SummaryHeader(
    summary: Summary,
    now: Instant,
    hiddenTypes: Set<ReportType>,
    onSoloType: (ReportType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontScale = LocalDensity.current.fontScale
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.summary_window),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )

        BoxWithConstraints {
            val stacked = maxWidth < NARROW_WIDTH || fontScale >= LARGE_FONT_SCALE
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TileRow(TILE_TYPES.take(2), summary, hiddenTypes, onSoloType)
                    TileRow(TILE_TYPES.drop(2), summary, hiddenTypes, onSoloType)
                }
            } else {
                TileRow(TILE_TYPES, summary, hiddenTypes, onSoloType)
            }
        }

        FreshestLine(
            freshest = summary.freshest,
            now = now,
            modifier = Modifier.padding(top = 12.dp, start = 4.dp),
        )
    }
}

@Composable
private fun TileRow(
    types: List<ReportType>,
    summary: Summary,
    hiddenTypes: Set<ReportType>,
    onSoloType: (ReportType) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        types.forEach { type ->
            SummaryTile(
                type = type,
                count = summary.counts[type] ?: 0,
                solo = TypeFilter.isSolo(hiddenTypes, type),
                onClick = { onSoloType(type) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SummaryTile(
    type: ReportType,
    count: Int,
    solo: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = typeStyle(type)
    val scheme = MaterialTheme.colorScheme
    val muted = count == 0
    val countColour = if (muted) scheme.onSurfaceVariant else scheme.onSurface
    val description = stringResource(
        if (solo) R.string.cd_summary_tile_solo else R.string.cd_summary_tile,
        style.label,
        pluralStringResource(R.plurals.report_count, count, count),
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (solo) style.container else scheme.surfaceContainerLow)
            .border(
                width = 1.dp,
                color = if (solo) style.color else Color.Transparent,
                shape = RoundedCornerShape(16.dp),
            )
            .selectable(selected = solo, role = Role.Tab, onClick = onClick)
            // One spoken sentence per tile: the icon, the number and the label separately would be
            // read as three fragments, and the number alone tells the owner nothing.
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(
            modifier = Modifier
                .clearAndSetSemantics { }
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(style.container),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = style.icon,
                    contentDescription = null,
                    tint = if (muted) style.color.copy(alpha = 0.6f) else style.color,
                    modifier = Modifier.size(17.dp),
                )
            }
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = countColour,
            )
            Text(
                text = style.label,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FreshestLine(freshest: Instant?, now: Instant, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val text = if (freshest == null) {
        stringResource(R.string.summary_quiet)
    } else {
        stringResource(R.string.summary_freshest, remember(freshest, now) { TimeFormat.ago(freshest, now) })
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = CwIcons.Clock,
            contentDescription = null,
            tint = LocalContentColor.current.copy(alpha = 0.7f),
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
}
