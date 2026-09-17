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
        } catch (_: Exception) {
            null
        }

    private fun dedupeKeepingLongestText(posts: List<RawPost>): List<RawPost> =
        posts.groupBy { it.postId }
            .values
            .map { group -> group.maxBy { it.text.length } }

    /**
     * Walks the parsed tree looking for objects with a string `post_id`. When such an object
     * also has a `creation_time` and a `message.text` findable in its subtree, emits a post and
     * does not descend further into it (avoiding the many nested duplicates Facebook embeds).
     * Otherwise keeps descending.
     */
    private fun walk(element: JsonElement, out: MutableList<RawPost>) {
        when (element) {
            is JsonObject -> {
                val postId = element.stringField("post_id")
                if (postId != null) {
                    val creationTime = findCreationTime(element)
                    val text = findMessageText(element)
                    if (creationTime != null && !text.isNullOrBlank()) {
                        out.add(
                            RawPost(
                                postId = postId,
                                createdAt = Instant.ofEpochSecond(creationTime),
                                createdAtApprox = false,
                                text = text,
                                url = Constants.postUrl(postId),
                            ),
                        )
                        return
                    }
                }
                element.values.forEach { walk(it, out) }
            }
            is JsonArray -> element.forEach { walk(it, out) }
            else -> Unit
        }
    }

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** `creation_time` directly on [element], else the first one found anywhere in its subtree. */
    private fun findCreationTime(element: JsonObject): Long? =
        (element["creation_time"] as? JsonPrimitive)?.longOrNull ?: findLongInSubtree(element, "creation_time")

    private fun findLongInSubtree(element: JsonElement, key: String): Long? {
        when (element) {
            is JsonObject -> {
                for ((k, v) in element) {
                    if (k == key) {
                        val direct = (v as? JsonPrimitive)?.longOrNull
                        if (direct != null) return direct
                    }
                    val nested = findLongInSubtree(v, key)
                    if (nested != null) return nested
                }
            }
            is JsonArray -> {
                for (item in element) {
                    val nested = findLongInSubtree(item, key)
                    if (nested != null) return nested
                }
            }
            else -> Unit
        }
        return null
    }

    /** First `message.text` found by pre-order, key-order DFS anywhere in [element]'s subtree. */
    private fun findMessageText(element: JsonElement): String? {
        when (element) {
            is JsonObject -> {
                for ((key, value) in element) {
                    if (key == "message" && value is JsonObject) {
                        val text = (value["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        if (text != null) return text
                    }
                    val nested = findMessageText(value)
                    if (nested != null) return nested
                }
            }
            is JsonArray -> {
                for (item in element) {
                    val nested = findMessageText(item)
                    if (nested != null) return nested
                }
            }
            else -> Unit
        }
        return null
    }
}
