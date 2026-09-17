package nz.personal.checkpointwatch.collect

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.personal.checkpointwatch.Constants
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

private const val FACEBOOK_HOST = "www.facebook.com"
private const val TIMEOUT_MS = 15_000
private const val MAX_BODY_BYTES = 5 * 1024 * 1024
private const val MAX_REDIRECTS = 5

private val REDIRECT_CODES = setOf(
    HttpURLConnection.HTTP_MOVED_PERM,
    HttpURLConnection.HTTP_MOVED_TEMP,
    HttpURLConnection.HTTP_SEE_OTHER,
    307,
    308,
)

/**
 * The last-resort collector: a plain HTTPS GET of the page with a desktop user agent, which the
 * 2026-09-18 spike showed returns the newest post embedded as JSON in the HTML. Used when the
 * WebView scan comes back with nothing (most likely a throttled background run), so a background
 * update always captures at least the latest post.
 *
 * Never throws except on cancellation: any failure is "no chunks", which the caller records as a
 * failed scan.
 */
class HttpLatestFetcher {

    /** JSON blocks from the page HTML, ready for [FeedJsonExtractor.extract]. */
    suspend fun fetchChunks(): List<String> = withContext(Dispatchers.IO) {
        try {
            val html = get(Constants.PAGE_URL)
            if (html == null) emptyList() else FeedJsonExtractor.jsonBlocksFromHtml(html)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Follows redirects by hand so they can be kept inside `www.facebook.com` over HTTPS: an
     * off-site redirect (or a downgrade to HTTP) is a refusal, not something to chase.
     */
    private fun get(startUrl: String): String? {
        var url = startUrl
        repeat(MAX_REDIRECTS) {
            val connection = open(url) ?: return null
            val redirect = try {
                val status = connection.responseCode
                when {
                    status in 200..299 -> return connection.inputStream.readAtMost(MAX_BODY_BYTES)
                    status in REDIRECT_CODES -> connection.getHeaderField("Location")
                        ?.let { location -> runCatching { URL(URL(url), location).toString() }.getOrNull() }
                    // 4xx/5xx: Facebook is refusing us and the body holds nothing we can use.
                    else -> null
                }
            } finally {
                connection.disconnect()
            }
            if (redirect == null || !isFacebookHttps(redirect)) return null
            url = redirect
        }
        return null
    }

    private fun open(url: String): HttpsURLConnection? {
        if (!isFacebookHttps(url)) return null
        val connection = URL(url).openConnection() as? HttpsURLConnection ?: return null
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.useCaches = false
        // The same headers a logged-out desktop browser sends when it opens the page. No
        // Accept-Encoding: the platform adds and transparently undoes gzip when it wants to, and
        // anything we asked for ourselves we would have to decode ourselves.
        connection.setRequestProperty("User-Agent", Constants.DESKTOP_UA)
        connection.setRequestProperty(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )
        connection.setRequestProperty("Accept-Language", "en-NZ,en;q=0.9")
        connection.setRequestProperty("Sec-Fetch-Mode", "navigate")
        connection.setRequestProperty("Sec-Fetch-Site", "none")
        connection.setRequestProperty("Sec-Fetch-Dest", "document")
        return connection
    }

    private fun isFacebookHttps(url: String): Boolean {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return false
        return parsed.protocol.equals("https", ignoreCase = true) &&
            parsed.host.equals(FACEBOOK_HOST, ignoreCase = true)
    }

    /** Reads at most [limit] bytes as UTF-8; a page far bigger than that is not the page we want. */
    private fun InputStream.readAtMost(limit: Int): String = use { input ->
        val buffer = ByteArray(16 * 1024)
        val out = ByteArrayOutputStream(64 * 1024)
        while (out.size() < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
        out.toString(Charsets.UTF_8.name())
    }
}
