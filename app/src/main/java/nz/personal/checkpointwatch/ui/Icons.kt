package nz.personal.checkpointwatch.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.unit.dp

/**
 * The app's icon set, drawn here rather than pulled in from `material-icons-extended`.
 *
 * That artifact is several thousand vectors for the dozen this app shows, and none of its glyphs
 * are the stop sign or speed camera the report types actually need. These are all one weight, one
 * 24 dp grid and one rounded-stroke language, so a chip, a card rail and a summary tile look like
 * they came from the same hand.
 *
 * Every path is stroked in black; `Icon` tints it, so the source colour never shows.
 */
object CwIcons {

    /** Report type: a checkpoint — the octagon of a stop sign with its bar. */
    val Checkpoint: ImageVector by lazy {
        icon("Checkpoint") {
            moveTo(8.6f, 2.8f); lineTo(15.4f, 2.8f); lineTo(21.2f, 8.6f); lineTo(21.2f, 15.4f)
            lineTo(15.4f, 21.2f); lineTo(8.6f, 21.2f); lineTo(2.8f, 15.4f); lineTo(2.8f, 8.6f)
            close()
            moveTo(7.8f, 12f); lineTo(16.2f, 12f)
        }
    }

    /** Report type: police presence — a shield. */
    val Shield: ImageVector by lazy {
        icon("Shield") {
            moveTo(12f, 2.8f); lineTo(20f, 5.9f); lineTo(20f, 11.6f)
            curveTo(20f, 16.6f, 16.6f, 20f, 12f, 21.2f)
            curveTo(7.4f, 20f, 4f, 16.6f, 4f, 11.6f)
            lineTo(4f, 5.9f)
            close()
        }
    }

    /** Report type: a crash — the standard warning triangle. */
    val Warning: ImageVector by lazy {
        icon("Warning") {
            moveTo(12f, 3.2f); lineTo(21.8f, 20.4f); lineTo(2.2f, 20.4f); close()
            moveTo(12f, 9.6f); lineTo(12f, 14.2f)
            moveTo(12f, 17.2f); lineTo(12.01f, 17.2f)
        }
    }

    /** Report type: a speed camera. */
    val Camera: ImageVector by lazy {
        icon("Camera") {
            moveTo(4.2f, 8.2f); lineTo(7.6f, 8.2f); lineTo(8.8f, 5.8f); lineTo(14.4f, 5.8f)
            lineTo(15.6f, 8.2f); lineTo(19.8f, 8.2f); lineTo(19.8f, 19f); lineTo(4.2f, 19f)
            close()
            circle(12f, 13.4f, 3.2f)
        }
    }

    /** Report type: anything the parser could not name. */
    val Info: ImageVector by lazy {
        icon("Info") {
            circle(12f, 12f, 9.2f)
            moveTo(12f, 11.2f); lineTo(12f, 16.6f)
            moveTo(12f, 7.7f); lineTo(12.01f, 7.7f)
        }
    }

    /** Opens the settings screen. */
    val Settings: ImageVector by lazy {
        icon("Settings") {
            moveTo(9.91f, 2.94f); lineTo(14.09f, 2.94f); lineTo(14.18f, 5.67f); lineTo(14.94f, 5.98f)
            lineTo(16.93f, 4.11f); lineTo(19.89f, 7.07f); lineTo(18.02f, 9.06f); lineTo(18.33f, 9.82f)
            lineTo(21.06f, 9.91f); lineTo(21.06f, 14.09f); lineTo(18.33f, 14.18f); lineTo(18.02f, 14.94f)
            lineTo(19.89f, 16.93f); lineTo(16.93f, 19.89f); lineTo(14.94f, 18.02f); lineTo(14.18f, 18.33f)
            lineTo(14.09f, 21.06f); lineTo(9.91f, 21.06f); lineTo(9.82f, 18.33f); lineTo(9.06f, 18.02f)
            lineTo(7.07f, 19.89f); lineTo(4.11f, 16.93f); lineTo(5.98f, 14.94f); lineTo(5.67f, 14.18f)
            lineTo(2.94f, 14.09f); lineTo(2.94f, 9.91f); lineTo(5.67f, 9.82f); lineTo(5.98f, 9.06f)
            lineTo(4.11f, 7.07f); lineTo(7.07f, 4.11f); lineTo(9.06f, 5.98f); lineTo(9.82f, 5.67f)
            close()
            circle(12f, 12f, 3.2f)
        }
    }

    /** Back, on the settings screen's top bar. */
    val ArrowBack: ImageVector by lazy {
        icon("ArrowBack") {
            moveTo(20f, 12f); lineTo(4.4f, 12f)
            moveTo(10.6f, 5.8f); lineTo(4.4f, 12f); lineTo(10.6f, 18.2f)
        }
    }

    /** Search, in the watched-suburbs sheet. */
    val Search: ImageVector by lazy {
        icon("Search") {
            circle(10.6f, 10.6f, 6.6f)
            moveTo(15.4f, 15.4f); lineTo(20.4f, 20.4f)
        }
    }

    /** Clears a filter or dismisses a sheet. */
    val Close: ImageVector by lazy {
        icon("Close") {
            moveTo(5.8f, 5.8f); lineTo(18.2f, 18.2f)
            moveTo(18.2f, 5.8f); lineTo(5.8f, 18.2f)
        }
    }

    /** A chosen item in a multi-select list. */
    val Check: ImageVector by lazy {
        icon("Check") {
            moveTo(4.6f, 12.4f); lineTo(9.6f, 17.4f); lineTo(19.4f, 6.6f)
        }
    }

    /** The expand affordance on a report card; rotated 180° when it is open. */
    val ChevronDown: ImageVector by lazy {
        icon("ChevronDown") {
            moveTo(5.8f, 9.2f); lineTo(12f, 15.4f); lineTo(18.2f, 9.2f)
        }
    }

    /** Leaves the app: "Open on Facebook". */
    val OpenInNew: ImageVector by lazy {
        icon("OpenInNew") {
            moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f)
            moveTo(20f, 4f); lineTo(11.4f, 12.6f)
            moveTo(18.4f, 13.6f); lineTo(18.4f, 19.4f); lineTo(4.6f, 19.4f); lineTo(4.6f, 5.6f)
            lineTo(10.4f, 5.6f)
        }
    }

    /** The offline empty state: a cloud, struck through. */
    val CloudOff: ImageVector by lazy {
        icon("CloudOff") {
            moveTo(8.2f, 18.6f); lineTo(17.4f, 18.6f)
            curveTo(19.8f, 18.6f, 21.6f, 16.8f, 21.6f, 14.5f)
            curveTo(21.6f, 12.3f, 19.9f, 10.5f, 17.7f, 10.4f)
            curveTo(17f, 7.6f, 14.5f, 5.5f, 11.5f, 5.5f)
            curveTo(10.2f, 5.5f, 9f, 5.9f, 8f, 6.5f)
            moveTo(6.3f, 8.1f)
            curveTo(4.5f, 9.3f, 3.4f, 11.3f, 3.4f, 13.6f)
            curveTo(3.4f, 16.4f, 5.5f, 18.6f, 8.2f, 18.6f)
            moveTo(3.2f, 3.2f); lineTo(20.8f, 20.8f)
        }
    }

    /** The first-run empty state: a beacon sweeping for reports. */
    val Radar: ImageVector by lazy {
        icon("Radar") {
            circle(12f, 12f, 9.2f)
            circle(12f, 12f, 5.2f)
            moveTo(12f, 12f); lineTo(12.01f, 12f)
        }
    }

    /** Nothing matches the current filters. */
    val Funnel: ImageVector by lazy {
        icon("Funnel") {
            moveTo(3.4f, 4.8f); lineTo(20.6f, 4.8f); lineTo(14f, 12.6f); lineTo(14f, 19.8f)
            lineTo(10f, 17.6f); lineTo(10f, 12.6f)
            close()
        }
    }

    /** The suburb filter and the watched-suburbs setting. */
    val Place: ImageVector by lazy {
        icon("Place") {
            moveTo(12f, 21.4f)
            curveTo(16.7f, 16.2f, 19f, 12.7f, 19f, 10.1f)
            curveTo(19f, 6.2f, 15.9f, 3.1f, 12f, 3.1f)
            curveTo(8.1f, 3.1f, 5f, 6.2f, 5f, 10.1f)
            curveTo(5f, 12.7f, 7.3f, 16.2f, 12f, 21.4f)
            close()
            circle(12f, 10f, 2.6f)
        }
    }

    /** The notifications section of the settings screen. */
    val Bell: ImageVector by lazy {
        icon("Bell") {
            moveTo(12f, 2.6f); lineTo(12f, 5f)
            moveTo(5.4f, 18f); lineTo(18.6f, 18f)
            curveTo(17.4f, 16.8f, 17f, 15.8f, 17f, 14f)
            lineTo(17f, 11f)
            curveTo(17f, 7.7f, 14.8f, 5f, 12f, 5f)
            curveTo(9.2f, 5f, 7f, 7.7f, 7f, 11f)
            lineTo(7f, 14f)
            curveTo(7f, 15.8f, 6.6f, 16.8f, 5.4f, 18f)
            close()
            moveTo(9.6f, 19.2f)
            curveTo(9.6f, 20.5f, 10.7f, 21.5f, 12f, 21.5f)
            curveTo(13.3f, 21.5f, 14.4f, 20.5f, 14.4f, 19.2f)
        }
    }

    /** Background updates: how often, and the quiet state's "nothing recent". */
    val Clock: ImageVector by lazy {
        icon("Clock") {
            circle(12f, 12f, 9.2f)
            moveTo(12f, 6.6f); lineTo(12f, 12.4f); lineTo(16.2f, 14.6f)
        }
    }
}

/** 24 dp grid, 1.8 px stroke, round caps and joins: the whole set in one line weight. */
private fun icon(name: String, path: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathData(path),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ).build()

/** Four cubic segments, the usual 0.5523 circle constant. */
private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    val k = r * 0.5523f
    moveTo(cx, cy - r)
    curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
    curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
    curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
    curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
    close()
}
