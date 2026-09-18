package nz.personal.checkpointwatch.collect

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import nz.personal.checkpointwatch.Constants
import java.time.Instant

/** The only feed layout this app understands; see `docs/HANDOVER.md` for the contract. */
private const val FEED_VERSION = 1L
private const val FEED_PAGE = "CheckpointNZ"

/** The contract's own ceiling. A feed longer than this is not one the collector wrote. */
private const val MAX_POSTS = 150

private val NUMERIC_ID = Regex("^[0-9]{1,32}$")

/**
 * The home collector's `feed.json`, as far as the app trusts it.
 *
 * [lastFullScanAt] is `null` when the collector has never yet had a scan Facebook answered in full.
 */
data class RelayFeed(
    val generatedAt: Instant,
    val lastFullScanAt: Instant?,
    val outcome: String,
    val posts: List<RawPost>,
)

/**
 * Reads the home collector's feed.
 *
 * The feed is a public file fetched from the internet by a background worker with no one watching,
 * so it is treated exactly like Facebook's own JSON: nothing in it is believed until it has been
 * checked. A feed that is not version 1 of this page's feed is no feed at all (`null`) and the
 * phone scans for itself; inside a good feed, a post that fails a check is dropped on its own and
 * the rest are kept. The post's link is rebuilt from its id rather than read, and a photo is kept
 * only under the same rule every other source obeys ([MediaUrl]).
 *
 * Pure: no clock and no network, so freshness is the caller's question, not this one's.
 */
object RelayFeedParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): RelayFeed? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        if (root.long("version") != FEED_VERSION) return null
        if (root.string("page") != FEED_PAGE) return null
        val generatedAt = root.long("generatedAt")?.takeIf { it > 0 } ?: return null
        val outcome = (root["collector"] as? JsonObject)?.string("outcome") ?: return null
        val posts = runCatching { root["posts"]?.jsonArray }.getOrNull() ?: return null

        return RelayFeed(
            generatedAt = Instant.ofEpochSecond(generatedAt),
            lastFullScanAt = root.long("lastFullScanAt")?.takeIf { it > 0 }?.let(Instant::ofEpochSecond),
            outcome = outcome,
            posts = posts.asSequence()
                .take(MAX_POSTS)
                .mapNotNull(::post)
                .distinctBy { it.postId }
                .toList(),
        )
    }

    private fun post(element: JsonElement): RawPost? {
        val fields = element as? JsonObject ?: return null
        val id = fields.string("id")?.takeIf(NUMERIC_ID::matches) ?: return null
        val createdAt = fields.long("createdAt")?.takeIf { it > 0 } ?: return null
        val text = fields.string("text")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return RawPost(
            postId = id,
            createdAt = Instant.ofEpochSecond(createdAt),
            createdAtApprox = false,
            text = text,
            url = Constants.postUrl(id),
            imageUrl = MediaUrl.validate(fields.string("image")),
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** A number that arrived in quotes is refused: the collector never writes one that way. */
    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}
