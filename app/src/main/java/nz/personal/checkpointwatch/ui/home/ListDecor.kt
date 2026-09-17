package nz.personal.checkpointwatch.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nz.personal.checkpointwatch.R
import nz.personal.checkpointwatch.ui.CwIcons

/**
 * The day a run of reports belongs to, pinned to the top of the list while that day is on screen.
 * It carries its own opaque background because a sticky header draws over the cards behind it.
 */
@Composable
fun DayHeader(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            // CARD_GAP above this is 8; together with 16 that is the 24 a new day gets.
            .padding(top = 16.dp, bottom = 4.dp, start = 4.dp),
    )
}

/**
 * The honest marker for history this app could never have: Facebook serves ten posts to a
 * logged-out visitor, so anything posted between two visits that is older than those ten is gone.
 * A dashed line rather than a solid one, because the timeline itself is broken here.
 */
@Composable
fun GapDivider(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DashedLine(colour = scheme.outline)
        Text(
            text = stringResource(R.string.gap_title),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.gap_body),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DashedLine(colour: Color, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp),
    ) {
        drawLine(
            color = colour,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = size.height,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())),
        )
    }
}

/**
 * Every empty list is a designed state, not a blank screen: an icon, a plain sentence about why it
 * is empty, and — when there is something to do about it — the one button that does it.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    tint: Color,
    container: Color,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .minimumInteractiveComponentSize(),
            ) {
                Text(actionLabel)
            }
        }
    }
}

/**
 * The empty state for [kind]. Which one that is was decided by [EmptyStateBuilder] from what the
 * last scan actually did, so this only has to say it — there is no guessing left here.
 */
@Composable
fun HomeEmptyState(
    kind: EmptyKind,
    onClearFilters: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    when (kind) {
        EmptyKind.NONE -> Unit

        EmptyKind.FILTERED -> EmptyState(
            icon = CwIcons.Funnel,
            tint = scheme.primary,
            container = scheme.primary.copy(alpha = 0.14f),
            title = stringResource(R.string.empty_filtered_title),
            body = stringResource(R.string.empty_filtered_body),
            actionLabel = stringResource(R.string.filter_clear),
            onAction = onClearFilters,
            modifier = modifier,
        )

        EmptyKind.SEARCHING -> EmptyState(
            icon = CwIcons.Radar,
            tint = scheme.primary,
            container = scheme.primary.copy(alpha = 0.14f),
            title = stringResource(R.string.empty_first_title),
            body = stringResource(R.string.empty_first_body),
            modifier = modifier,
        )

        EmptyKind.NO_REPORTS_YET -> EmptyState(
            icon = CwIcons.Clock,
            tint = scheme.onSurfaceVariant,
            container = scheme.surfaceContainerHigh,
            title = stringResource(R.string.empty_none_yet_title),
            body = stringResource(R.string.empty_none_yet_body),
            actionLabel = stringResource(R.string.empty_none_yet_action),
            onAction = onRetry,
            modifier = modifier,
        )

        EmptyKind.OFFLINE -> EmptyState(
            icon = CwIcons.CloudOff,
            tint = scheme.onSurfaceVariant,
            container = scheme.surfaceContainerHigh,
            title = stringResource(R.string.empty_offline_title),
            body = stringResource(R.string.empty_offline_body),
            actionLabel = stringResource(R.string.empty_offline_action),
            onAction = onRetry,
            modifier = modifier,
        )

        EmptyKind.NO_POSTS -> EmptyState(
            icon = CwIcons.Info,
            tint = scheme.onSurfaceVariant,
            container = scheme.surfaceContainerHigh,
            title = stringResource(R.string.empty_no_posts_title),
            body = stringResource(R.string.empty_no_posts_body),
            actionLabel = stringResource(R.string.empty_offline_action),
            onAction = onRetry,
            modifier = modifier,
        )
    }
}
