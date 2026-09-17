package nz.personal.checkpointwatch.ui.home

import androidx.compose.runtime.Immutable
import nz.personal.checkpointwatch.ui.Freshness
import nz.personal.checkpointwatch.ui.ReportUi

/**
 * The resolved wording a card needs, so the sentence itself can be pure and unit-tested rather than
 * only reachable through a composable.
 *
 * [source] is the whole line ("From WhatsApp subscriber"), already formatted, or null.
 */
@Immutable
data class CardWords(
    val label: String,
    val reported: String,
    val isNew: String,
    val isOld: String,
    val fullPost: String,
    val source: String?,
)

/**
 * A report card is one merged semantics node, so this sentence is the *whole* of what TalkBack says
 * about it: anything drawn on the card and missing from here does not exist to a screen reader.
 *
 * [expanded] and [showPost] are separate on purpose. Opening a card always reveals the source and
 * the link; it only reveals the original post's text when that text adds something
 * ([PostDetail.postAddsDetail]). Keying both on the same flag left the source silent on every card
 * whose post the screen had decided not to repeat.
 */
object CardSentence {

    fun build(report: ReportUi, words: CardWords, expanded: Boolean, showPost: Boolean): String {
        val where = listOfNotNull(words.label, report.road, report.suburb).joinToString(", ")
        return buildString {
            append(where)
            append(", ")
            append(words.reported)
            append(". ")
            if (report.details.isNotBlank()) append(report.details.trimEnd('.')).append(". ")
            if (report.isNew) append(words.isNew).append(' ')
            if (report.freshness == Freshness.OLD) append(words.isOld).append(' ')
            if (expanded) {
                if (showPost) {
                    append(words.fullPost).append(". ").append(report.postText.trimEnd('.')).append(". ")
                }
                words.source?.let { append(it.trimEnd('.')).append('.') }
            }
        }.trim()
    }
}
