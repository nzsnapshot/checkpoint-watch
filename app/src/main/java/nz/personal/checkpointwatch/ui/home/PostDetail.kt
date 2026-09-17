package nz.personal.checkpointwatch.ui.home

import nz.personal.checkpointwatch.ui.ReportUi
import java.util.Locale

/** Lines the card has already said in its own words, in the post's own vocabulary. */
private val TIME_PREFIX = Regex("^time\\s*:")
private val FROM_PREFIX = Regex("^from\\s*:")

/** Leading emoji, bullets and punctuation a post decorates its header with. */
private val LEADING_DECORATION = Regex("^[^\\p{L}\\p{N}]+")
private val TRAILING_DECORATION = Regex("[^\\p{L}\\p{N}]+$")
private val WHITESPACE = Regex("\\s+")

/**
 * Whether opening a card would actually show the owner anything new.
 *
 * A card already shows the details, the reported time and the source, so for the ordinary
 * single-report post the "full post" is the card again with emoji on it. It earns its place when
 * the post carried something the card does not: a second report, or a line the parser did not keep.
 *
 * Pure, and deliberately conservative — a line has to be *recognisably* one the card already shows
 * to be discounted, so the failure mode is showing the post unnecessarily rather than hiding
 * something the owner wanted.
 */
object PostDetail {

    fun postAddsDetail(report: ReportUi): Boolean {
        val shown = buildSet {
            report.details.lines().forEach { line -> normalise(line).takeIf { it.isNotEmpty() }?.let(::add) }
            report.source?.let { normalise(it).takeIf(String::isNotEmpty)?.let(::add) }
        }
        val road = report.road?.let(::normalise)?.takeIf { it.isNotEmpty() }
        val suburb = report.suburb?.let(::normalise)?.takeIf { it.isNotEmpty() }

        return report.postText.lines().any { raw ->
            val line = normalise(raw)
            when {
                line.isEmpty() -> false
                line in shown -> false
                TIME_PREFIX.containsMatchIn(line) -> false
                FROM_PREFIX.containsMatchIn(line) -> false
                // The header line naming this report: it is the card's own title and label.
                road != null && line.contains(road) -> false
                road == null && suburb != null && line.contains(suburb) -> false
                else -> true
            }
        }
    }

    /** Case, spacing and decoration are not information; two lines that differ only in those match. */
    private fun normalise(line: String): String = line
        .lowercase(Locale.ENGLISH)
        .replace(LEADING_DECORATION, "")
        .replace(TRAILING_DECORATION, "")
        .replace(WHITESPACE, " ")
        .trim()
}
