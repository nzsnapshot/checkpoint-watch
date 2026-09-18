package nz.personal.checkpointwatch.ui

import nz.personal.checkpointwatch.model.ReportType
import java.time.Duration
import java.time.Instant

/** How far apart two reports can be and still be considered "also in the area". */
private val AREA_WINDOW: Duration = Duration.ofHours(3)

/** Maximum number of other reports shown per card. */
private const val MAX_MENTIONS = 3

/** A report of any type mentioned as also happening near a given report. */
data class AreaMention(val type: ReportType, val at: Instant)

/**
 * One report as the UI renders it: a [nz.personal.checkpointwatch.data.ReportRow] plus everything
 * [nz.personal.checkpointwatch.ui.home.HomeStateBuilder] derives from it (resolved time, freshness,
 * nearby reports, newness).
 */
data class ReportUi(
    val id: Long,
    val postId: String,
    val type: ReportType,
    val typeLabel: String,
    val road: String?,
    val suburb: String?,
    val details: String,
    val at: Instant,
    val atApprox: Boolean,
    val reportedTimeText: String?,
    val source: String?,
    val postText: String,
    val postUrl: String,
    val freshness: Freshness,
    val gapAfter: Boolean,
    val alsoInArea: List<AreaMention> = emptyList(),
    val isNew: Boolean,
    /**
     * The file name of the post's downloaded photo, on the post's first card only — a post can be
     * several reports, and one picture repeated down the list would be noise.
     */
    val imagePath: String? = null,
)

/**
 * Finds other reports "in the same area" as each report: same suburb (case-insensitive), or, when
 * a report has no suburb, the same road (case-insensitive, non-blank) among other reports that
 * also have no suburb. Runs over every report passed in, so a type the owner has hidden from the
 * list can still be mentioned on a card that is shown.
 */
object AreaLinker {

    fun link(reports: List<ReportUi>): Map<Long, List<AreaMention>> =
        reports.associate { report -> report.id to mentionsFor(report, reports) }

    private fun mentionsFor(report: ReportUi, all: List<ReportUi>): List<AreaMention> =
        all.asSequence()
            .filter { it.id != report.id }
            .filter { sameArea(report, it) }
            .filter { withinWindow(report, it) }
            .sortedByDescending { it.at }
            .take(MAX_MENTIONS)
            .map { AreaMention(it.type, it.at) }
            .toList()

    private fun sameArea(a: ReportUi, b: ReportUi): Boolean {
        val suburbA = a.suburb.blankToNull()
        val suburbB = b.suburb.blankToNull()
        return if (suburbA != null) {
            suburbB != null && suburbA.equals(suburbB, ignoreCase = true)
        } else {
            if (suburbB != null) return false
            val roadA = a.road.blankToNull()
            val roadB = b.road.blankToNull()
            roadA != null && roadB != null && roadA.equals(roadB, ignoreCase = true)
        }
    }

    private fun withinWindow(a: ReportUi, b: ReportUi): Boolean =
        Duration.between(a.at, b.at).abs() <= AREA_WINDOW

    private fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
