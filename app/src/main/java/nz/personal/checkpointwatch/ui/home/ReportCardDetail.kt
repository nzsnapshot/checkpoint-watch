package nz.personal.checkpointwatch.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nz.personal.checkpointwatch.R
import nz.personal.checkpointwatch.ui.AreaMention
import nz.personal.checkpointwatch.ui.CwIcons
import nz.personal.checkpointwatch.ui.ReportUi
import nz.personal.checkpointwatch.ui.TimeFormat
import nz.personal.checkpointwatch.ui.typeStyle
import java.time.Instant

/**
 * The two pieces of a report card that are about something other than the report itself: what else
 * is happening near it, and what the original post actually said.
 *
 * Split out of `ReportCard.kt` to keep both files readable; they are only ever used from there.
 */

@Composable
internal fun AlsoInArea(where: String, mentions: List<AreaMention>, now: Instant, colour: Color) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceContainerLow)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.card_also_in, where),
            style = MaterialTheme.typography.labelSmall,
            color = colour,
        )
        mentions.forEach { mention ->
            val mentionStyle = typeStyle(mention.type)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = mentionStyle.icon,
                    contentDescription = null,
                    tint = mentionStyle.color,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "${mentionStyle.label} · ${TimeFormat.ago(mention.at, now)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colour,
                )
            }
        }
    }
}

@Composable
internal fun ExpandedDetail(report: ReportUi, showPost: Boolean, onOpenPost: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = scheme.outlineVariant)
        // Most posts are the card with emoji on them; the full text is only worth the space when
        // it carries something the card does not — a second report, or a line the parser dropped.
        if (showPost) {
            Text(
                text = stringResource(R.string.card_full_post),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            Text(
                text = report.postText,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )
        }
        report.source?.takeIf { it.isNotBlank() }?.let { source ->
            Text(
                text = stringResource(R.string.card_source, source),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = { onOpenPost(report.postUrl) },
            modifier = Modifier.minimumInteractiveComponentSize(),
            // Flush with the card's own text column; a button's default inset left it floating
            // a few dp to the right of everything above it.
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.card_open_facebook))
            Icon(
                imageVector = CwIcons.OpenInNew,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(16.dp),
            )
        }
    }
}
