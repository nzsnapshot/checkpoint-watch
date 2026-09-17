package nz.personal.checkpointwatch.scan

import nz.personal.checkpointwatch.collect.CollectResult
import nz.personal.checkpointwatch.collect.DomPostExtractor
import nz.personal.checkpointwatch.collect.FeedJsonExtractor
import nz.personal.checkpointwatch.collect.RawPost
import nz.personal.checkpointwatch.data.CollectorKind
import java.time.Instant

/**
 * Decides which of a scan's sources actually produced posts: the JSON Facebook's page fetched,
 * else the posts scraped off the rendered page, else the plain HTTPS GET, else nothing.
 *
 * Pure and ordered, so the choice can be tested without a WebView or a network. [httpChunks] is
 * a lambda because the fetch behind it is expensive: it is only called when the WebView sources
 * came back empty.
 */
object ScanPipeline {

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
            if (fromJson.isNotEmpty()) return fromJson to CollectorKind.WEBVIEW

            val fromDom = DomPostExtractor.extract(result.domPosts, scanTime)
            if (fromDom.isNotEmpty()) return fromDom to CollectorKind.WEBVIEW_DOM
        }

        val fromHttp = FeedJsonExtractor.extract(httpChunks())
        if (fromHttp.isNotEmpty()) return fromHttp to CollectorKind.HTTP

        return emptyList<RawPost>() to CollectorKind.NONE
    }
}
