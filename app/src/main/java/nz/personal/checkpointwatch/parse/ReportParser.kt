package nz.personal.checkpointwatch.parse

import nz.personal.checkpointwatch.model.ParsedReport
import nz.personal.checkpointwatch.model.ReportType
import java.time.Instant

/**
 * Turns the raw text of a single Facebook post into zero or more [ParsedReport]s. A post
 * usually contains one report ("🛑 CHECKPOINT – Road, SUBURB" followed by details and a
 * "Time:" line) but can list several, one after another, separated by header lines.
 *
 * Text that appears before the first header (or a post with no header at all) is not
 * discarded: it is folded into the first report's details, so nothing the page posted is lost.
 */
object ReportParser {
    // Leading emoji/symbols, an all-caps label, a dash-like separator, then the location.
    private val HEADER = Regex("""^[^\p{L}]*(\p{Lu}[\p{Lu} /&'-]*?\p{Lu})\s+[–—-]\s+(.+)$""")
    private val TIME = Regex("""^time\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val FROM = Regex("""^from\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val TRAILING_NOTE = Regex("""\s*\([^)]*\)\s*$""")

    private class Draft(val label: String, val location: String) {
        val details = mutableListOf<String>()
        var time: String? = null
        var source: String? = null
    }

    fun parse(text: String, createdAt: Instant): List<ParsedReport> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()

        val drafts = mutableListOf<Draft>()
        val preamble = mutableListOf<String>()
        for (line in lines) {
            val header = HEADER.find(line)
            if (header != null) {
                val draft = Draft(header.groupValues[1].trim(), header.groupValues[2].trim())
                if (drafts.isEmpty()) draft.details += preamble
                drafts += draft
                continue
            }
            val draft = drafts.lastOrNull()
            if (draft == null) {
                preamble += line
                continue
            }
            val timeMatch = TIME.find(line)
            val fromMatch = FROM.find(line)
            if (timeMatch != null) {
                draft.time = timeMatch.groupValues[1].replace(TRAILING_NOTE, "").trim()
            } else if (fromMatch != null) {
                draft.source = fromMatch.groupValues[1].trim()
            } else {
                draft.details += line
            }
        }

        if (drafts.isEmpty()) {
            return listOf(
                ParsedReport(
                    indexInPost = 0,
                    type = ReportType.OTHER,
                    typeLabel = "",
                    road = null,
                    suburb = null,
                    details = lines.joinToString("\n"),
                    reportedTimeText = null,
                    reportedAt = null,
                    source = null,
                ),
            )
        }

        return drafts.mapIndexed { index, draft ->
            val location = draft.location.replace(TRAILING_NOTE, "")
            val commaIndex = location.lastIndexOf(',')
            val tail = if (commaIndex >= 0) location.substring(commaIndex + 1).trim() else ""
            val tailIsSuburb = tail.any { it.isLetter() } && tail == tail.uppercase()
            val road = (if (tailIsSuburb) location.substring(0, commaIndex) else location).trim().ifEmpty { null }
            val suburb = if (tailIsSuburb) tail else null
            ParsedReport(
                indexInPost = index,
                type = typeOf(draft.label),
                typeLabel = draft.label,
                road = road,
                suburb = suburb,
                details = draft.details.joinToString("\n"),
                reportedTimeText = draft.time,
                reportedAt = draft.time?.let { ReportedTimeResolver.resolve(it, createdAt) },
                source = draft.source,
            )
        }
    }

    private fun typeOf(label: String): ReportType = when {
        "CHECKPOINT" in label -> ReportType.CHECKPOINT
        "POLICE" in label -> ReportType.POLICE_PRESENCE
        "CRASH" in label -> ReportType.CRASH
        "CAMERA" in label -> ReportType.SPEED_CAMERA
        else -> ReportType.OTHER
    }
}
