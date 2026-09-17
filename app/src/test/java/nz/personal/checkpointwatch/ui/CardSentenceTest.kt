package nz.personal.checkpointwatch.ui

import nz.personal.checkpointwatch.model.ReportType
import nz.personal.checkpointwatch.ui.home.CardSentence
import nz.personal.checkpointwatch.ui.home.CardWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * A report card is one merged node, so this sentence is the *whole* of what TalkBack says about it.
 * Anything drawn on the card and missing from here is invisible to a screen reader.
 */
class CardSentenceTest {

    private val words = CardWords(
        label = "Checkpoint",
        reported = "reported 2 hours ago",
        isNew = "New in the latest check.",
        isOld = "This report is more than six hours old.",
        fullPost = "Full post",
        source = "From WhatsApp subscriber",
    )

    private fun report(
        road: String? = "Lincoln Road",
        suburb: String? = "HENDERSON",
        details: String = "After the off-ramp",
        source: String? = "WhatsApp subscriber",
        postText: String = "CHECKPOINT – Lincoln Road, HENDERSON\nAfter the off-ramp\nTime: 11:55PM",
        isNew: Boolean = false,
        freshness: Freshness = Freshness.FRESH,
    ) = ReportUi(
        id = 1,
        postId = "1",
        type = ReportType.CHECKPOINT,
        typeLabel = "CHECKPOINT",
        road = road,
        suburb = suburb,
        details = details,
        at = Instant.parse("2026-09-18T09:00:00Z"),
        atApprox = false,
        reportedTimeText = "11:55PM",
        source = source,
        postText = postText,
        postUrl = "https://www.facebook.com/CheckpointNZ/posts/1",
        freshness = freshness,
        gapAfter = false,
        isNew = isNew,
    )

    @Test
    fun `a collapsed card reads as type, place, when, then what was said`() {
        val sentence = CardSentence.build(report(), words, expanded = false, showPost = false)

        assertEquals("Checkpoint, Lincoln Road, HENDERSON, reported 2 hours ago. After the off-ramp.", sentence)
    }

    @Test
    fun `a card with no place at all still names its type`() {
        val sentence = CardSentence.build(
            report(road = null, suburb = null, details = "Roadworks tonight"),
            words,
            expanded = false,
            showPost = false,
        )

        assertEquals("Checkpoint, reported 2 hours ago. Roadworks tonight.", sentence)
    }

    @Test
    fun `a road with no suburb reads without a dangling comma`() {
        val sentence = CardSentence.build(report(suburb = null), words, expanded = false, showPost = false)

        assertEquals("Checkpoint, Lincoln Road, reported 2 hours ago. After the off-ramp.", sentence)
    }

    @Test
    fun `new and old are said out loud, because the pill and the fade are not`() {
        assertTrue(
            CardSentence.build(report(isNew = true), words, expanded = false, showPost = false)
                .contains(words.isNew),
        )
        assertTrue(
            CardSentence.build(report(freshness = Freshness.OLD), words, expanded = false, showPost = false)
                .contains(words.isOld),
        )
        assertFalse(
            CardSentence.build(report(freshness = Freshness.OLDER), words, expanded = false, showPost = false)
                .contains(words.isOld),
        )
    }

    // --- what opening the card adds ---------------------------------------------------------

    @Test
    fun `the source is announced whenever the card is open, post text or not`() {
        val sentence = CardSentence.build(report(), words, expanded = true, showPost = false)

        assertTrue(sentence.contains("From WhatsApp subscriber"))
        assertFalse(sentence.contains("Full post"))
    }

    @Test
    fun `the post text is announced only when the card is actually showing it`() {
        val withPost = CardSentence.build(report(), words, expanded = true, showPost = true)
        assertTrue(withPost.contains("Full post"))
        assertTrue(withPost.contains("Time: 11:55PM"))
        // …and the source is still there alongside it.
        assertTrue(withPost.contains("From WhatsApp subscriber"))
    }

    @Test
    fun `a closed card announces neither, however much it would have to show`() {
        val sentence = CardSentence.build(report(), words, expanded = false, showPost = true)

        assertFalse(sentence.contains("Full post"))
        assertFalse(sentence.contains("From WhatsApp subscriber"))
    }

    @Test
    fun `a post with no source simply has none to announce`() {
        val sentence = CardSentence.build(
            report(source = null),
            words.copy(source = null),
            expanded = true,
            showPost = false,
        )

        assertFalse(sentence.contains("From"))
        assertTrue(sentence.endsWith("."))
    }

    @Test
    fun `blank details do not leave a stray full stop`() {
        val sentence = CardSentence.build(report(details = ""), words, expanded = false, showPost = false)

        assertEquals("Checkpoint, Lincoln Road, HENDERSON, reported 2 hours ago.", sentence)
    }
}
