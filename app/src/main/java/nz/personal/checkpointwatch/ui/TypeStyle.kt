package nz.personal.checkpointwatch.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import nz.personal.checkpointwatch.R
import nz.personal.checkpointwatch.model.ReportType
import nz.personal.checkpointwatch.model.displayName
import nz.personal.checkpointwatch.ui.theme.DarkCamera
import nz.personal.checkpointwatch.ui.theme.DarkCheckpoint
import nz.personal.checkpointwatch.ui.theme.DarkCrash
import nz.personal.checkpointwatch.ui.theme.DarkOther
import nz.personal.checkpointwatch.ui.theme.DarkPolice
import nz.personal.checkpointwatch.ui.theme.LightCamera
import nz.personal.checkpointwatch.ui.theme.LightCheckpoint
import nz.personal.checkpointwatch.ui.theme.LightCrash
import nz.personal.checkpointwatch.ui.theme.LightOther
import nz.personal.checkpointwatch.ui.theme.LightPolice

/** How much of the type colour a badge, tile or rail backdrop carries. */
private const val CONTAINER_ALPHA = 0.14f

/**
 * Everything one report type looks like, in one place: chips, card rails, summary tiles and
 * notification-type toggles all read from here, so a checkpoint is the same red octagon wherever
 * it appears.
 *
 * Colour is never the only signal — [icon] and [label] travel with [color] everywhere it is used.
 */
@Immutable
data class TypeStyle(
    val color: Color,
    /** [color] at low alpha, for a badge or tile backdrop; composited over whatever is behind it. */
    val container: Color,
    val icon: ImageVector,
    val label: String,
    /**
     * A one-line form for the summary tiles ("Police", not "Police presence"), which are four
     * across on a phone. These are the same category names the filter chips use, so the type
     * language stays one language; [label] is what a screen reader hears, so nothing is lost.
     */
    val shortLabel: String,
)

/** The style for [type], resolved against the current light/dark scheme. */
@Composable
fun typeStyle(type: ReportType): TypeStyle {
    val dark = MaterialTheme.colorScheme.surface.luminanceIsDark()
    val color = when (type) {
        ReportType.CHECKPOINT -> if (dark) DarkCheckpoint else LightCheckpoint
        ReportType.POLICE_PRESENCE -> if (dark) DarkPolice else LightPolice
        ReportType.CRASH -> if (dark) DarkCrash else LightCrash
        ReportType.SPEED_CAMERA -> if (dark) DarkCamera else LightCamera
        ReportType.OTHER -> if (dark) DarkOther else LightOther
    }
    val icon = when (type) {
        ReportType.CHECKPOINT -> CwIcons.Checkpoint
        ReportType.POLICE_PRESENCE -> CwIcons.Shield
        ReportType.CRASH -> CwIcons.Warning
        ReportType.SPEED_CAMERA -> CwIcons.Camera
        ReportType.OTHER -> CwIcons.Info
    }
    val label = stringResource(
        when (type) {
            ReportType.CHECKPOINT -> R.string.type_checkpoint
            ReportType.POLICE_PRESENCE -> R.string.type_police
            ReportType.CRASH -> R.string.type_crash
            ReportType.SPEED_CAMERA -> R.string.type_camera
            ReportType.OTHER -> R.string.type_other
        },
    )
    val shortLabel = stringResource(
        when (type) {
            ReportType.CHECKPOINT -> R.string.type_checkpoint_short
            ReportType.POLICE_PRESENCE -> R.string.type_police_short
            ReportType.CRASH -> R.string.type_crash_short
            ReportType.SPEED_CAMERA -> R.string.type_camera_short
            ReportType.OTHER -> R.string.type_other_short
        },
    )
    return remember(color, icon, label, shortLabel) {
        TypeStyle(
            color = color,
            container = color.copy(alpha = CONTAINER_ALPHA),
            icon = icon,
            label = label,
            shortLabel = shortLabel,
        )
    }
}

/**
 * What to call this particular report. [ReportType.OTHER] borrows the post's own header
 * ("ROAD WORKS" -> "Road Works"); a post with no header at all has nothing to borrow, so it is
 * simply an update.
 */
@Composable
fun typeLabel(type: ReportType, rawLabel: String): String {
    if (type != ReportType.OTHER) return typeStyle(type).label
    val fallback = stringResource(R.string.type_other)
    return remember(rawLabel, fallback) {
        rawLabel.takeIf { it.isNotBlank() }?.let { type.displayName(it) } ?: fallback
    }
}

/** The scheme's own surface tells us which half of the theme we are in, without a second flag. */
private fun Color.luminanceIsDark(): Boolean = (red * 0.2126f + green * 0.7152f + blue * 0.0722f) < 0.5f
