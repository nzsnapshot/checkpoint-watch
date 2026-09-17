package nz.personal.checkpointwatch.collect

import nz.personal.checkpointwatch.Constants
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * Builds [RawPost]s from posts scraped directly off the rendered DOM, used when the JSON feed
 * extraction finds nothing. Ages are approximate (Facebook only ever shows a rounded age like
 * "22m"), so [RawPost.createdAtApprox] is always true here.
 *
 * The text arriving here is whatever the page rendered, so it is cleaned before it is stored or
 * hashed — see [cleanText].
 */
object DomPostExtractor {

    private val whitespaceRun = Regex("""\s+""")
    private val ageWithUnit = Regex("""(\d+)\s*([a-zA-Z]+)""")

    /** Lines Facebook draws under every post, which are not part of what was posted. */
    private val CHROME_LINES = setOf(
        "like", "comment", "share", "all reactions", "all reactions:", "see more", "see less",
    )

    /** Lines Facebook draws *above* every post: who posted, when, and how they are followed. */
    private val HEADER_CHROME_LINES = setOf(
        "online status indicator",
        "active",
        "follow",
        "following",
        "verified account",
        "shared with public",
        Constants.PAGE_NAME.lowercase(),
    )

    /** A separator Facebook puts between the page name and the age. */
    private val SEPARATOR_LINES = setOf("·", "•")

    /**
     * The whole line is a relative age: "22m", "3 hrs ago", "Just now". Deliberately the same
     * grammar [parseAgeMinutes] understands (and the same the collector script uses to find the
     * age link), so a line is only ever treated as a timestamp if it really is one — "2 lanes"
     * is not an age, and is not stripped.
     */
    private val AGE_LINE = Regex(
        """^(just now|\d+\s*(s|sec|secs|second|seconds|m|min|mins|minute|minutes|h|hr|hrs|hour|""" +
            """hours|d|day|days|w|wk|wks|week|weeks|y|yr|yrs|year|years)(\s+ago)?)$""",
        RegexOption.IGNORE_CASE,
    )

    /** A bare reaction or comment count: "8", "1.2K". */
    private val COUNT_LINE = Regex("""^\d+([.,]\d+)?[kKmM]?$""")

    /** A counted noun: "12 comments", "3 shares". */
    private val COUNTED_LINE =
        Regex("""^\d+([.,]\d+)?[kKmM]?\s+(comments?|shares?|reactions?|likes?)$""", RegexOption.IGNORE_CASE)

    fun extract(posts: List<DomPost>, scanTime: Instant): List<RawPost> =
        posts.mapNotNull { post ->
            val text = cleanText(post.text)
            if (text.isBlank()) return@mapNotNull null
            val hash = textHash(text)
            RawPost(
                postId = "dom:${hash.take(16)}",
                createdAt = scanTime.minus(Duration.ofMinutes(parseAgeMinutes(post.age))),
                createdAtApprox = true,
                text = text,
                url = safeLink(post.link) ?: Constants.PAGE_URL,
            )
        }

    /**
     * Strips the chrome out of an article's text, so what is left is the post itself.
     *
     * `innerText` of a `[role=article]` is the post wrapped in everything drawn around it — the
     * page's name and online status above, the relative age, then reaction counts and the
     * Like/Comment/Share row below. Those move between scans, so hashing them means the same post
     * reads as a new one every time (a duplicate row, and a notification for it), and the hash can
     * never bridge onto the same post's `message.text` from the JSON feed.
     *
     * Both cuts are made line by line, from the outside in, and stop at the first line that is not
     * recognisably chrome:
     *  - from the top: the online-status lines, the page's own name, the relative age, separators;
     *  - from the bottom: reaction counts, "See more", the Like/Comment/Share row.
     *
     * Nothing is dropped for being *before the post's first report header*. The author may write a
     * line or two before it — `ReportParser` deliberately keeps that as the first report's details
     * — and deleting it here would give the DOM copy of a post a different hash from the JSON copy,
     * which is exactly what stops the two ever bridging. A headerless post is cleaned the same way
     * as any other, which matters most of all for those: with no header to anchor on, the age line
     * is otherwise the thing that changes the post's identity every single scan.
     *
     * If the cuts would leave nothing at all, the original text is returned unchanged.
     */
    fun cleanText(text: String): String {
        val lines = text.lines().map { it.trimEnd() }
        var start = 0
        while (start < lines.size && isHeaderChrome(lines[start])) start++
        var end = lines.size
        while (end > start && isFooterChrome(lines[end - 1])) end--
        val kept = lines.subList(start, end).joinToString("\n").trim()
        return kept.ifEmpty { text.trim() }
    }

    /** Only an entire line that is exactly one of these: "Active checkpoint on…" is a post. */
    private fun isHeaderChrome(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed in SEPARATOR_LINES) return true
        return trimmed.lowercase() in HEADER_CHROME_LINES || AGE_LINE.matches(trimmed)
    }

    private fun isFooterChrome(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed in SEPARATOR_LINES) return true
        return trimmed.lowercase() in CHROME_LINES ||
            COUNT_LINE.matches(trimmed) ||
            COUNTED_LINE.matches(trimmed)
    }

    /**
     * Keeps a scraped permalink only if it is an https Facebook URL. The link comes out of the
     * page's own markup, so it is untrusted input: anything else (another host, plain http, a
     * `javascript:` URL, a string that is not a URL at all) is replaced by the page URL, which is
     * where "Open on Facebook" then takes the owner.
     */
    private fun safeLink(link: String?): String? {
        if (link.isNullOrBlank()) return null
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        if (!"https".equals(uri.scheme, ignoreCase = true)) return null
        val host = uri.host?.lowercase() ?: return null
        return if (host == "facebook.com" || host.endsWith(".facebook.com")) link else null
    }

    /** SHA-1 hex of [text] with whitespace runs collapsed, trimmed, and lower-cased. */
    fun textHash(text: String): String {
        val normalized = text.replace(whitespaceRun, " ").trim().lowercase()
        val digest = MessageDigest.getInstance("SHA-1").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Minutes implied by an age string like "22m", "45s", "3 hrs", "2 days", "1w", "2 yrs";
     * unknown formats -> 0, which dates the post to the scan and marks it approximate anyway.
     *
     * Facebook switches to an absolute date long before it would ever say "3 months", so no month
     * unit is modelled: an "m" is always minutes.
     */
    private fun parseAgeMinutes(age: String): Long {
        if (age.trim().equals("Just now", ignoreCase = true)) return 0
        val match = ageWithUnit.find(age) ?: return 0
        val amount = match.groupValues[1].toLongOrNull() ?: return 0
        val unit = match.groupValues[2].lowercase()
        return when {
            unit.startsWith("s") -> 0 // seconds: this minute
            unit.startsWith("m") -> amount
            unit.startsWith("h") -> amount * 60
            unit.startsWith("d") -> amount * 60 * 24
            unit.startsWith("w") -> amount * 60 * 24 * 7
            unit.startsWith("y") -> amount * 60 * 24 * 365
            else -> 0
        }
    }
}
