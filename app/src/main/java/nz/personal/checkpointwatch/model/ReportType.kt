package nz.personal.checkpointwatch.model

import java.time.Instant

/** Category of a road report extracted from a Facebook post. */
enum class ReportType { CHECKPOINT, POLICE_PRESENCE, CRASH, SPEED_CAMERA, OTHER }

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
