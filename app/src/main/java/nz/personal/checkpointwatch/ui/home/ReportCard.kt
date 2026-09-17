package nz.personal.checkpointwatch.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import nz.personal.checkpointwatch.R
import nz.personal.checkpointwatch.ui.AreaMention
import nz.personal.checkpointwatch.ui.CwIcons
import nz.personal.checkpointwatch.ui.Freshness
import nz.personal.checkpointwatch.ui.ReportUi
import nz.personal.checkpointwatch.ui.TimeFormat
import nz.personal.checkpointwatch.ui.typeLabel
import nz.personal.checkpointwatch.ui.typeStyle
import java.time.Instant
import java.util.Locale

/** The coloured edge that carries the type through the whole list. */
private val RAIL_WIDTH = 4.dp

/** Details longer than this are cut until the card is opened. */
private const val COLLAPSED_DETAIL_LINES = 3

/**
 * Older reports recede rather than disappear: they are still true, just less useful.
 *
 * The fade is applied to the card's *body* only. The header — the coloured rail, the type badge,
 * the road name, the OLD tag — and the full post text once the card is opened stay at full
 * strength, because dimming those on top of their already-muted colour would put them under the
 * 4.5:1 the rest of the app holds to. The measured worst case with this split is 4.72:1
 * (`onSurface` at 60 % over the light "also in the area" panel).
 */
private fun Freshness.bodyAlpha(): Float = when (this) {
    Freshness.FRESH -> 1f
    Freshness.OLDER -> 0.8f
    Freshness.OLD -> 0.6f
}

/**
 * One report, as the list shows it.
 *
 * The road name is the hero; the type and suburb sit above it as a small tracked label, and
 * everything else — when it was reported, what else is happening nearby — is quieter. Tapping
 * opens the original post's full text and the way out to Facebook.
 *
 * To TalkBack the whole card is a single sentence ("Checkpoint, Lincoln Road, Henderson, reported
 * 2 hours ago. After the off-ramp…"), with opening and closing offered as a custom action rather
 * than as a separate control to hunt for.
 */
@Composable
fun ReportCard(
    report: ReportUi,
    now: Instant,
    onOpenPost: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(report.id) { mutableStateOf(false) }
    val style = typeStyle(report.type)
    val scheme = MaterialTheme.colorScheme
    val label = typeLabel(report.type, report.typeLabel)
    val bodyAlpha = report.freshness.bodyAlpha()
    // Faded text needs a stronger colour underneath it to land in the same place.
    val bodyColour = if (report.freshness == Freshness.FRESH) scheme.onSurfaceVariant else scheme.onSurface

    val ago = remember(report.at, now) { TimeFormat.ago(report.at, now) }
    val clock = remember(report.at) { TimeFormat.clock(report.at) }
    val meta = stringResource(
        if (report.atApprox) R.string.card_reported_about else R.string.card_reported,
        clock,
        ago,
    )
    val description = cardSentence(report, label, ago, expanded)
    val expandLabel = stringResource(R.string.card_expand)
    val collapseLabel = stringResource(R.string.card_collapse)
    val stateText = stringResource(
        if (expanded) R.string.card_state_expanded else R.string.card_state_collapsed,
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.surface)
            .drawBehind {
                // The start edge, not the left one: in an RTL layout the rail belongs on the right.
                val rail = RAIL_WIDTH.toPx()
                val x = if (layoutDirection == LayoutDirection.Rtl) size.width - rail else 0f
                drawRect(
                    color = style.color,
                    topLeft = Offset(x, 0f),
                    size = Size(rail, size.height),
                )
            }
            .clickable(role = Role.Button) { expanded = !expanded }
            .semantics(mergeDescendants = true) {
                contentDescription = description
                stateDescription = stateText
                customActions = listOf(
                    CustomAccessibilityAction(if (expanded) collapseLabel else expandLabel) {
                        expanded = !expanded
                        true
                    },
                )
            },
    ) {
        Column(
            modifier = Modifier
                .padding(start = RAIL_WIDTH + 12.dp, top = 14.dp, end = 14.dp, bottom = 12.dp)
                .animateContentSize(
                    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Header(report = report, label = label, expanded = expanded)

            Column(
                modifier = Modifier.alpha(bodyAlpha),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (report.details.isNotBlank()) {
                    Text(
                        text = report.details,
                        style = MaterialTheme.typography.bodyMedium,
                        color = bodyColour,
                        maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_DETAIL_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = bodyColour,
                )

                if (report.alsoInArea.isNotEmpty()) {
                    AlsoInArea(
                        where = report.suburb ?: report.road.orEmpty(),
                        mentions = report.alsoInArea,
                        now = now,
                        colour = bodyColour,
                    )
                }
            }

            // Opened deliberately, so shown at full strength however old the report is.
            if (expanded) {
                ExpandedDetail(report = report, onOpenPost = onOpenPost)
            }
        }
    }
}

@Composable
private fun Header(report: ReportUi, label: String, expanded: Boolean) {
    val style = typeStyle(report.type)
    val scheme = MaterialTheme.colorScheme
    val title = report.road ?: report.suburb ?: label
    val overlineSuburb = report.suburb?.takeIf { report.road != null }
    val chevronTurn by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")

    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(style.container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = style.icon,
                contentDescription = null,
                tint = style.color,
                modifier = Modifier.size(21.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = remember(label) { label.uppercase(Locale.ENGLISH) },
                    style = MaterialTheme.typography.labelSmall,
                    color = style.color,
                )
                if (overlineSuburb != null) {
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                    Text(
                        text = overlineSuburb,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false),
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                // Past six hours the road name greys off rather than fades, so it keeps its
                // contrast while still reading as history.
                color = if (report.freshness == Freshness.OLD) scheme.onSurfaceVariant else scheme.onSurface,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (report.isNew) {
                Pill(
                    text = stringResource(R.string.card_new),
                    background = scheme.primary,
                    foreground = scheme.onPrimary,
                )
            } else if (report.freshness == Freshness.OLD) {
                Pill(
                    text = stringResource(R.string.card_old),
                    background = scheme.surfaceContainerHigh,
                    foreground = scheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = CwIcons.ChevronDown,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(chevronTurn),
            )
        }
    }
}

@Composable
private fun Pill(text: String, background: Color, foreground: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = foreground,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(background)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** The whole card as one sentence, for a screen reader: type, where, when, then what was said. */
@Composable
private fun cardSentence(report: ReportUi, label: String, ago: String, expanded: Boolean): String {
    val reported = stringResource(R.string.a11y_reported, ago)
    val newSuffix = stringResource(R.string.a11y_new)
    val oldSuffix = stringResource(R.string.a11y_old)
    val fullPost = stringResource(R.string.card_full_post)
    val source = report.source?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.card_source, it) }
    return remember(report, label, ago, expanded) {
        val where = listOfNotNull(label, report.road, report.suburb).joinToString(", ")
        buildString {
            append(where)
            append(", ")
            append(reported)
            append(". ")
            if (report.details.isNotBlank()) append(report.details).append(". ")
            if (report.isNew) append(newSuffix).append(' ')
            if (report.freshness == Freshness.OLD) append(oldSuffix).append(' ')
            // What the card shows when open has to be in the sentence too, or opening it is silent
            // to a screen reader: the merged node is the only thing TalkBack reads here.
            if (expanded) {
                append(fullPost).append(". ").append(report.postText).append(". ")
                if (source != null) append(source).append('.')
            }
        }.trim()
    }
}
