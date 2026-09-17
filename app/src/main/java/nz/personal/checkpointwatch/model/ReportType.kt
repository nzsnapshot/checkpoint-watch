package nz.personal.checkpointwatch.model

import java.time.Instant

/** Category of a road report extracted from a Facebook post. */
enum class ReportType { CHECKPOINT, POLICE_PRESENCE, CRASH, SPEED_CAMERA, OTHER }

/**
 * How a type is named to the owner, in notifications and on screen. [ReportType.OTHER] has no
 * name of its own, so it borrows the post's own header [label] ("ROAD WORKS" -> "Road Works")
 * and falls back to a neutral "Report" when the post had no header at all.
 */
fun ReportType.displayName(label: String? = null): String = when (this) {
    ReportType.CHECKPOINT -> "Checkpoint"
    ReportType.POLICE_PRESENCE -> "Police presence"
    ReportType.CRASH -> "Crash"
    ReportType.SPEED_CAMERA -> "Speed camera"
    ReportType.OTHER -> label?.takeIf { it.isNotBlank() }?.toTitleCase() ?: "Report"
}

private fun String.toTitleCase(): String = trim()
    .split(' ')
    .filter { it.isNotEmpty() }
    .joinToString(" ") { word ->
        word.lowercase().replaceFirstChar { it.uppercaseChar() }
    }

/**
 * A single report parsed out of one Facebook post. A post can contain more than one report
 * (e.g. several checkpoints listed one after another); [indexInPost] preserves their order.
 */
data class ParsedReport(
    val indexInPost: Int,
    val type: ReportType,
    val typeLabel: String,
    val road: String?,
    val suburb: String?,
    val details: String,
    val reportedTimeText: String?,
    val reportedAt: Instant?,
    val source: String?,
)
