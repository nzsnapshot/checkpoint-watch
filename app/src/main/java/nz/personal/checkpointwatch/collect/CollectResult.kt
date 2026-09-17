package nz.personal.checkpointwatch.collect

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Why a scan stopped collecting. */
enum class EndReason {
    /** Facebook showed the second dialog, the one with no close button. */
    LOGIN_WALL,

    /** The feed stopped growing, or the collector ran out of scroll rounds. */
    NO_MORE_POSTS,

    /**
     * The login dialog was closed and nothing came of it: no feed response, no second post and no
     * sign-in wall either. Measured on the owner's phone, and it is what Facebook does to a
     * logged-out visitor arriving from a VPN. The scan ends early so the page widget can have the
     * rest of the budget.
     */
    NO_FEED,

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
 * scraped-from-the-page fallback used only when the JSON yields nothing; [pluginPosts] is what
 * Facebook's Page Plugin rendered on the second pass, which only runs when the first was starved.
 *
 * [graphqlBodies] counts only the feed responses among [jsonChunks] — not the JSON blocks the
 * initial HTML already carried. The difference is the point: on a VPN the blocks arrive and the
 * feed never does, and "zero feed responses" is what tells a starved scan from a quiet one.
 *
 * [diagnostics] is the human-readable account of how the scan went — what the WebView was, what
 * the page did, why it stopped — for the owner to copy out of the app when a scan is puzzling.
 * It holds no personal data and nothing the app depends on: it exists to be read by a person.
 */
data class CollectResult(
    val jsonChunks: List<String>,
    val domPosts: List<DomPost>,
    val end: EndReason,
    val diagnostics: String? = null,
    val pluginPosts: List<PluginPost> = emptyList(),
    val graphqlBodies: Int = 0,
)

/**
 * A single message pushed from `collector.js` through the `cwBridge` web message listener.
 *
 * The page is untrusted input: [decode] never throws and returns `null` for anything it does not
 * recognise, so a Facebook change (or a hostile script on the page) can only ever cost us data,
 * never crash a scan.
 */
sealed interface CollectorMessage {

    /**
     * A raw JSON response body that mentions `post_id`.
     *
     * [fromFeed] separates a `/api/graphql` response from a `<script type="application/json">`
     * block the initial HTML already carried. Both parse the same way, so only the collector's own
     * accounting cares — but it cares a great deal: no feed responses at all is the signature of
     * the VPN case, and what sends the scan to the page widget.
     */
    data class JsonChunk(val body: String, val fromFeed: Boolean = false) : CollectorMessage

    /** The fallback posts scraped off the rendered page, sent once just before [End]. */
    data class Dom(val posts: List<DomPost>) : CollectorMessage

    /** The posts Facebook's Page Plugin rendered, sent once by `plugin_collector.js`. */
    data class Plugin(val posts: List<PluginPost>) : CollectorMessage

    /** The collector script has finished; no further messages are expected. */
    data class End(val reason: EndReason) : CollectorMessage

    /**
     * The script's own account of the scan: a JSON string, sent once just before [End] (or from
     * its last-chance dump). Never parsed here — it is evidence for a person to read, and the
     * less this app pretends to understand it, the more of it survives a Facebook change.
     */
    data class Diag(val body: String) : CollectorMessage

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
                "json" -> root.stringOrNull("body")?.let {
                    JsonChunk(it, fromFeed = root.stringOrNull("src") == "graphql")
                }
                "dom" -> (root["posts"] as? JsonArray)?.let { Dom(it.toDomPosts()) }
                "plugin" -> (root["posts"] as? JsonArray)?.let { Plugin(it.toPluginPosts()) }
                "end" -> End(endReason(root.stringOrNull("reason")))
                "diag" -> root.stringOrNull("body")?.let(::Diag)
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

        /** A plugin post is only a post if it has both a time and something written on it. */
        private fun JsonArray.toPluginPosts(): List<PluginPost> = mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val utime = obj.longOrNull("utime") ?: return@mapNotNull null
            val text = obj.stringOrNull("text")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PluginPost(
                utime = utime,
                link = obj.stringOrNull("link"),
                text = text,
                image = obj.stringOrNull("image"),
            )
        }

        private fun JsonObject.stringOrNull(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** A number, and only a number: a quoted "1789677637" is markup we did not expect. */
        private fun JsonObject.longOrNull(key: String): Long? =
            (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    }
}
