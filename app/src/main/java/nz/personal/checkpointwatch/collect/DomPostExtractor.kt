package nz.personal.checkpointwatch.collect

import nz.personal.checkpointwatch.Constants
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * Builds [RawPost]s from posts scraped directly off the rendered DOM, used when the JSON feed
 * extraction finds nothing. Ages are approximate (Facebook only ever shows a rounded age like
 * "22m"), so [RawPost.createdAtApprox] is always true here.
 */
object DomPostExtractor {

    private val whitespaceRun = Regex("""\s+""")
    private val ageWithUnit = Regex("""(\d+)\s*([a-zA-Z])""")

    fun extract(posts: List<DomPost>, scanTime: Instant): List<RawPost> =
        posts.mapNotNull { post ->
            if (post.text.isBlank()) return@mapNotNull null
            val hash = textHash(post.text)
            RawPost(
                postId = "dom:${hash.take(16)}",
                createdAt = scanTime.minus(Duration.ofMinutes(parseAgeMinutes(post.age))),
                createdAtApprox = true,
                text = post.text,
                url = post.link ?: Constants.PAGE_URL,
            )
        }

    /** SHA-1 hex of [text] with whitespace runs collapsed, trimmed, and lower-cased. */
    fun textHash(text: String): String {
        val normalized = text.replace(whitespaceRun, " ").trim().lowercase()
        val digest = MessageDigest.getInstance("SHA-1").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Minutes implied by an age string like "22m", "3 hrs", "2 days"; unknown formats -> 0. */
    private fun parseAgeMinutes(age: String): Long {
        if (age.trim().equals("Just now", ignoreCase = true)) return 0
        val match = ageWithUnit.find(age) ?: return 0
        val amount = match.groupValues[1].toLongOrNull() ?: return 0
        return when (match.groupValues[2].lowercase()) {
            "m" -> amount
            "h" -> amount * 60
            "d" -> amount * 60 * 24
            else -> 0
        }
    }
}
