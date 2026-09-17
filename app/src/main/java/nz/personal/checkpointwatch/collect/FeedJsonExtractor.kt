package nz.personal.checkpointwatch.collect

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import nz.personal.checkpointwatch.Constants
import java.time.Instant

/**
 * Pulls [RawPost]s out of the raw JSON responses Facebook's page sends to the hidden WebView
 * (both the initial `data-sjs` script block and the streamed GraphQL follow-up chunks).
 */
object FeedJsonExtractor {

    /**
     * Recursion cap for [walk], [findLongInSubtree] and [findMessageText]. Real Facebook
     * payloads nest about 25 levels deep; 200 leaves generous headroom while guaranteeing we
     * never stack-overflow walking a deeply nested or maliciously crafted document.
     */
    private const val MAX_DEPTH = 200

    private val json = Json { ignoreUnknownKeys = true }
    private val forLoopPrefix = Regex("""^\s*for\s*\(;;\);\s*""")
    private val scriptBlockPattern = Regex(
        """<script\s+type="application/json"[^>]*>(.*?)</script>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    /** Newest-first, deduplicated posts found across every chunk. */
    fun extract(chunks: List<String>): List<RawPost> {
        val found = mutableListOf<RawPost>()
        for (chunk in chunks) {
            val stripped = stripForLoopPrefix(chunk)
            val whole = tryParse(stripped)
            if (whole != null) {
                walk(whole, found)
                continue
            }
            for (line in stripped.split('\n')) {
                val trimmedLine = line.trim()
                if (trimmedLine.isEmpty()) continue
                val element = tryParse(stripForLoopPrefix(trimmedLine)) ?: continue
                walk(element, found)
            }
        }
        return dedupeKeepingLongestText(found).sortedByDescending { it.createdAt }
    }

    /** Bodies of `<script type="application/json">` tags that mention `post_id`. */
    fun jsonBlocksFromHtml(html: String): List<String> =
        scriptBlockPattern.findAll(html)
            .map { it.groupValues[1] }
            .filter { it.contains("post_id") }
            .toList()

    private fun stripForLoopPrefix(chunk: String): String = chunk.replaceFirst(forLoopPrefix, "")

    private fun tryParse(text: String): JsonElement? =
        try {
            json.parseToJsonElement(text)
        } catch (_: StackOverflowError) {
            // kotlinx's parser is itself recursive; a pathologically deep document can overflow
            // the stack while parsing, before we ever get a JsonElement to walk. Treat that the
            // same as any other malformed input: skip this chunk/line.
            null
        } catch (_: Exception) {
            null
        }

    /**
     * One row per post id, keeping the fullest text — and any photo, from whichever copy carried
     * one. Facebook repeats a story several times inside one payload and the attachment does not
     * hang off every copy, so the longest-text copy is not always the one with the photo on it.
     */
    private fun dedupeKeepingLongestText(posts: List<RawPost>): List<RawPost> =
        posts.groupBy { it.postId }
            .values
            .map { group ->
                val best = group.maxBy { it.text.length }
                best.imageUrl?.let { return@map best }
                best.copy(imageUrl = group.firstNotNullOfOrNull { it.imageUrl })
            }

    /**
     * Walks the parsed tree looking for objects with a non-blank string `post_id`. When such an
     * object also has a `creation_time` and a `message.text` findable in its subtree, emits a
     * post and does not descend further into it (avoiding the many nested duplicates Facebook
     * embeds). Otherwise keeps descending. Stops descending past [MAX_DEPTH] so a deeply nested
     * or adversarial document can never overflow the stack.
     */
    private fun walk(element: JsonElement, out: MutableList<RawPost>, depth: Int = 0) {
        if (depth > MAX_DEPTH) return
        when (element) {
            is JsonObject -> {
                val postId = element.stringField("post_id")
                if (postId != null) {
                    val creationTime = findCreationTime(element, depth)
                    val text = findMessageText(element, depth)
                    if (creationTime != null && !text.isNullOrBlank()) {
                        out.add(
                            RawPost(
                                postId = postId,
                                createdAt = Instant.ofEpochSecond(creationTime),
                                createdAtApprox = false,
                                text = text,
                                url = Constants.postUrl(postId),
                                imageUrl = findPhotoUri(element, depth),
                            ),
                        )
                        return
                    }
                }
                element.values.forEach { walk(it, out, depth + 1) }
            }
            is JsonArray -> element.forEach { walk(it, out, depth + 1) }
            else -> Unit
        }
    }

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    /** `creation_time` directly on [element], else the first one found anywhere in its subtree. */
    private fun findCreationTime(element: JsonObject, depth: Int): Long? =
        (element["creation_time"] as? JsonPrimitive)?.longOrNull
            ?: findLongInSubtree(element, "creation_time", depth)

    private fun findLongInSubtree(element: JsonElement, key: String, depth: Int): Long? {
        if (depth > MAX_DEPTH) return null
        when (element) {
            is JsonObject -> {
                for ((k, v) in element) {
                    if (k == key) {
                        val direct = (v as? JsonPrimitive)?.longOrNull
                        if (direct != null) return direct
                    }
                    val nested = findLongInSubtree(v, key, depth + 1)
                    if (nested != null) return nested
                }
            }
            is JsonArray -> {
                for (item in element) {
                    val nested = findLongInSubtree(item, key, depth + 1)
                    if (nested != null) return nested
                }
            }
            else -> Unit
        }
        return null
    }

    /**
     * The post's photo, if it has one: the first `photo_image.uri` anywhere in the story's subtree.
     *
     * Facebook puts it at `attachments[].styles.attachment.media.photo_image {uri,width,height}`,
     * but the story is repeated several times over inside one payload (a message container, a
     * feedback container, a context layout) and the attachment hangs off more than one of them, so
     * this searches the subtree rather than walking a fixed path — the same reason
     * [findMessageText] does.
     *
     * A post carries at most one photo as far as this app is concerned: the card shows one image,
     * and the first is the one Facebook shows first. Anything that is not plainly https on one of
     * Facebook's own content hosts is dropped rather than kept ([MediaUrl]): this address is fetched
     * later, unattended, from a background worker.
     */
    private fun findPhotoUri(element: JsonElement, depth: Int): String? {
        if (depth > MAX_DEPTH) return null
        when (element) {
            is JsonObject -> {
                for ((key, value) in element) {
                    if (key == "photo_image" && value is JsonObject) {
                        val uri = (value["uri"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        val valid = MediaUrl.validate(uri)
                        if (valid != null) return valid
                    }
                    val nested = findPhotoUri(value, depth + 1)
                    if (nested != null) return nested
                }
            }
            is JsonArray -> {
                for (item in element) {
                    val nested = findPhotoUri(item, depth + 1)
                    if (nested != null) return nested
                }
            }
            else -> Unit
        }
        return null
    }

    /** First `message.text` found by pre-order, key-order DFS anywhere in [element]'s subtree. */
    private fun findMessageText(element: JsonElement, depth: Int): String? {
        if (depth > MAX_DEPTH) return null
        when (element) {
            is JsonObject -> {
                for ((key, value) in element) {
                    if (key == "message" && value is JsonObject) {
                        val text = (value["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        if (text != null) return text
                    }
                    val nested = findMessageText(value, depth + 1)
                    if (nested != null) return nested
                }
            }
            is JsonArray -> {
                for (item in element) {
                    val nested = findMessageText(item, depth + 1)
                    if (nested != null) return nested
                }
            }
            else -> Unit
        }
        return null
    }
}
