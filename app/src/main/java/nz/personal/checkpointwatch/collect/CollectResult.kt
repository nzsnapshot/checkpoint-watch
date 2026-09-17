package nz.personal.checkpointwatch.collect

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Why a scan stopped collecting. */
enum class EndReason {
    /** Facebook showed the second dialog, the one with no close button. */
    LOGIN_WALL,

    /** The feed stopped growing, or the collector ran out of scroll rounds. */
    NO_MORE_POSTS,

    /** The whole scan took longer than its budget; whatever arrived is kept. */
    TIMEOUT,

    /** The page could not be loaded, or the renderer died. */
    NETWORK_ERROR,

    /** Facebook redirected us to a login/checkpoint page, or answered with an HTTP error. */
    BLOCKED,

    /** The caller cancelled the scan (app stopped); whatever arrived is kept. */
    CANCELLED,
}

/**
 * Everything one scan captured. [jsonChunks] are raw response bodies (GraphQL responses and the
 * initial `<script type="application/json">` blocks) for [FeedJsonExtractor]; [domPosts] is the
 * scraped-from-the-page fallback used only when the JSON yields nothing.
 */
data class CollectResult(
    val jsonChunks: List<String>,
    val domPosts: List<DomPost>,
    val end: EndReason,
)

/**
 * A single message pushed from `collector.js` through the `cwBridge` web message listener.
 *
 * The page is untrusted input: [decode] never throws and returns `null` for anything it does not
 * recognise, so a Facebook change (or a hostile script on the page) can only ever cost us data,
 * never crash a scan.
 */
sealed interface CollectorMessage {

    /** A raw JSON response body that mentions `post_id`. */
    data class JsonChunk(val body: String) : CollectorMessage

    /** The fallback posts scraped off the rendered page, sent once just before [End]. */
    data class Dom(val posts: List<DomPost>) : CollectorMessage

    /** The collector script has finished; no further messages are expected. */
    data class End(val reason: EndReason) : CollectorMessage

    companion object {

        private val json = Json { ignoreUnknownKeys = true }

        /** Parses one `cwBridge.postMessage` payload, or returns `null` if it makes no sense. */
        fun decode(raw: String): CollectorMessage? {
            val root = try {
                json.parseToJsonElement(raw) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return null

            return when (root.stringOrNull("t")) {
                "json" -> root.stringOrNull("body")?.let(::JsonChunk)
                "dom" -> (root["posts"] as? JsonArray)?.let { Dom(it.toDomPosts()) }
                "end" -> End(endReason(root.stringOrNull("reason")))
                else -> null
            }
        }

        /** Unknown or missing reasons mean "the script stopped for a reason we don't model". */
        private fun endReason(name: String?): EndReason =
            EndReason.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: EndReason.NO_MORE_POSTS

        private fun JsonArray.toDomPosts(): List<DomPost> = mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val text = obj.stringOrNull("text")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            DomPost(
                text = text,
                age = obj.stringOrNull("age").orEmpty(),
                link = obj.stringOrNull("link"),
            )
        }

        private fun JsonObject.stringOrNull(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
