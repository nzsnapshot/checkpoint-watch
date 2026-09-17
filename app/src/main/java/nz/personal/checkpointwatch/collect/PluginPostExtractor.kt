package nz.personal.checkpointwatch.collect

import nz.personal.checkpointwatch.Constants
import java.time.Instant

/**
 * Builds [RawPost]s from the posts Facebook's Page Plugin rendered, used when the feed itself
 * refused to paginate — which on the owner's VPN is every scan.
 *
 * Two things make this neither the JSON feed nor the DOM fallback:
 *
 *  - **The time is exact.** The widget puts unix seconds in `abbr[data-utime]`, so unlike the DOM
 *    fallback (which can only work backwards from "22m") nothing here is rounded, and
 *    [RawPost.createdAtApprox] is false.
 *  - **The id is not.** The widget never exposes a numeric `post_id`, so a post lands under the
 *    same synthetic `dom:<hash>` id the DOM fallback uses. That is deliberate, and it is the whole
 *    reason nothing downstream needed changing: `ScrapeRecorder` already knows that a `dom:` row
 *    is a placeholder, already bridges it onto the real numeric post the first time the JSON feed
 *    produces one with the same text hash, and already collapses a `dom:`/numeric pair inside one
 *    scan. A plugin post inherits every one of those rules by having the same shape of id.
 *
 * The text is cleaned by [DomPostExtractor.cleanText] and hashed by [DomPostExtractor.textHash] —
 * the same functions, not the same rules written twice. If the two ever cleaned differently, the
 * same post read through the widget and through the page would hash differently, and the bridge
 * above would never close.
 */
object PluginPostExtractor {

    /** Newest first. Posts that clean to nothing — a photo with no caption — are dropped. */
    fun extract(posts: List<PluginPost>): List<RawPost> =
        posts.mapNotNull { post ->
            val text = DomPostExtractor.cleanText(post.text)
            if (text.isBlank()) return@mapNotNull null
            RawPost(
                postId = "dom:${DomPostExtractor.textHash(text).take(16)}",
                createdAt = Instant.ofEpochSecond(post.utime),
                createdAtApprox = false,
                text = text,
                url = DomPostExtractor.safeLink(post.link) ?: Constants.PAGE_URL,
                imageUrl = MediaUrl.validate(post.image),
            )
        }.sortedByDescending { it.createdAt }
}
