package nz.personal.checkpointwatch.scan

import nz.personal.checkpointwatch.collect.CollectResult
import nz.personal.checkpointwatch.collect.DomPostExtractor
import nz.personal.checkpointwatch.collect.FeedJsonExtractor
import nz.personal.checkpointwatch.collect.PluginPostExtractor
import nz.personal.checkpointwatch.collect.RawPost
import nz.personal.checkpointwatch.data.CollectorKind
import java.time.Instant

/**
 * Decides which of a scan's sources actually produced posts: the JSON Facebook's page fetched,
 * else the posts scraped off the rendered page, else the plain HTTPS GET, else nothing.
 *
 * The page widget is the exception to "else". A starved scan has *both* the single post the page
 * embedded in its HTML and the five the widget rendered, and one of those five is that same post,
 * so the two are joined rather than ranked: take the JSON posts, add the widget's posts that are
 * not already among them, and the scan is worth what it actually found. Everything else stays a
 * preference chain, because everywhere else the sources really are alternatives to each other.
 *
 * Pure and ordered, so the choice can be tested without a WebView or a network. [httpChunks] is
 * a lambda because the fetch behind it is expensive: it is only called when the WebView sources
 * came back empty.
 */
object ScanPipeline {

    /** How a post with no real id is marked; see [DomPostExtractor]. */
    private const val DOM_ID_PREFIX = "dom:"

    /**
     * @param result what the WebView scan captured, or `null` when there was no WebView scan
     *   (or when the caller already knows it yielded nothing and is only offering HTTP chunks).
     * @param scanTime the instant DOM ages ("22m") are measured back from.
     */
    fun choose(
        result: CollectResult?,
        httpChunks: () -> List<String>,
        scanTime: Instant,
    ): Pair<List<RawPost>, CollectorKind> {
        if (result != null) {
            val fromJson = FeedJsonExtractor.extract(result.jsonChunks)
            val fromPlugin = PluginPostExtractor.extract(result.pluginPosts)
            if (fromPlugin.isNotEmpty()) return join(fromJson, fromPlugin)

            if (fromJson.isNotEmpty()) return fromJson to CollectorKind.WEBVIEW

            val fromDom = DomPostExtractor.extract(result.domPosts, scanTime)
            if (fromDom.isNotEmpty()) return fromDom to CollectorKind.WEBVIEW_DOM
        }

        val fromHttp = FeedJsonExtractor.extract(httpChunks())
        if (fromHttp.isNotEmpty()) return fromHttp to CollectorKind.HTTP

        return emptyList<RawPost>() to CollectorKind.NONE
    }

    /**
     * The scan's posts, plus the home collector's that are not already among them.
     *
     * Used when the relay was too old to stand in for a scan but still knows about posts this
     * phone, rationed to one or five, will never be shown. The same sameness as [join] — the id
     * where both have a real one, otherwise the words — and the same rule about which copy wins:
     * the one with the real post id, carrying across a photo only the other one saw.
     *
     * The label stays the scan's own, for the reason [join] gives. Only a scan that found nothing
     * at all is credited to the relay, because then the relay is all there is.
     */
    fun withRelay(
        scan: Pair<List<RawPost>, CollectorKind>,
        relayPosts: List<RawPost>,
    ): Pair<List<RawPost>, CollectorKind> {
        if (relayPosts.isEmpty()) return scan
        val (scanPosts, scanKind) = scan
        if (scanPosts.isEmpty()) return relayPosts.sortedByDescending { it.createdAt } to CollectorKind.RELAY

        val relayById = relayPosts.associateBy { it.postId }
        val relayByHash = relayPosts.associateBy { DomPostExtractor.textHash(it.text) }
        val merged = scanPosts.map { post ->
            val twin = relayById[post.postId] ?: relayByHash[DomPostExtractor.textHash(post.text)]
            when {
                twin == null -> post
                post.postId.startsWith(DOM_ID_PREFIX) -> twin.copy(imageUrl = twin.imageUrl ?: post.imageUrl)
                else -> post.copy(imageUrl = post.imageUrl ?: twin.imageUrl)
            }
        }
        val ids = merged.mapTo(mutableSetOf()) { it.postId }
        val hashes = merged.mapTo(mutableSetOf()) { DomPostExtractor.textHash(it.text) }
        val added = relayPosts.filterNot { it.postId in ids || DomPostExtractor.textHash(it.text) in hashes }
        return (merged + added).distinctBy { it.postId }.sortedByDescending { it.createdAt } to scanKind
    }

    /**
     * The JSON posts, plus the widget posts that are not already among them.
     *
     * Sameness is the text hash, which is the identity the whole app already bridges on: a widget
     * post has only a synthetic `dom:` id, so a shared post appears twice under two different ids
     * and only its wording says they are one post. The numeric copy wins — it carries the real
     * post id — but a photo only the widget saw is carried across to it rather than dropped with
     * the copy that found it.
     *
     * The label follows what the widget actually contributed: crediting the page with four posts
     * it never produced would make the scan log useless for the one question it exists to answer.
     */
    private fun join(
        fromJson: List<RawPost>,
        fromPlugin: List<RawPost>,
    ): Pair<List<RawPost>, CollectorKind> {
        val byHash = fromPlugin.associateBy { DomPostExtractor.textHash(it.text) }
        val merged = fromJson.map { post ->
            val twin = byHash[DomPostExtractor.textHash(post.text)]
            if (post.imageUrl == null && twin?.imageUrl != null) post.copy(imageUrl = twin.imageUrl) else post
        }
        val jsonHashes = fromJson.mapTo(mutableSetOf()) { DomPostExtractor.textHash(it.text) }
        val added = fromPlugin.filterNot { DomPostExtractor.textHash(it.text) in jsonHashes }
        val kind = if (added.isEmpty() && merged.isNotEmpty()) CollectorKind.WEBVIEW else CollectorKind.PLUGIN
        return (merged + added).sortedByDescending { it.createdAt } to kind
    }
}
