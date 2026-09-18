package nz.personal.checkpointwatch.collect

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.personal.checkpointwatch.Constants
import nz.personal.checkpointwatch.scan.RelayResult
import nz.personal.checkpointwatch.scan.RelaySource
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import javax.net.ssl.HttpsURLConnection

private const val TIMEOUT_MS = 10_000

/** The real feed is some tens of kilobytes. Anything near this is not it. */
private const val MAX_BODY_BYTES = 2 * 1024 * 1024

/**
 * Fetches the home collector's `feed.json` from the repository's `data` branch.
 *
 * One plain HTTPS GET to GitHub's raw file host, which works from a VPN because it is not Facebook.
 * It sends nothing about the phone: no cookies, no identifier, and a query string that is only the
 * current minute, there to get past a cache. Redirects are not followed — the address is fixed and
 * never redirects, so one that does is not ours to chase.
 *
 * Never throws except on cancellation. The relay is a shortcut, so every way of failing is one of
 * two answers the coordinator already knows what to do with.
 */
class RelayFeedFetcher(private val clock: () -> Instant = Instant::now) : RelaySource {

    override suspend fun fetch(): RelayResult = withContext(Dispatchers.IO) {
        try {
            resultFor(get(urlFor(clock())))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            RelayResult.Unreachable
        }
    }

    private fun get(url: String): String? {
        val connection = URL(url).openConnection() as? HttpsURLConnection ?: return null
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            return readBounded(connection.inputStream, MAX_BODY_BYTES)
        } finally {
            connection.disconnect()
        }
    }

    companion object {

        /** Whole minutes, so a second scan inside the same minute can still be answered from a cache. */
        fun urlFor(now: Instant): String = "${Constants.RELAY_FEED_URL}?t=${now.epochSecond / 60}"

        fun resultFor(body: String?): RelayResult {
            if (body == null) return RelayResult.Unreachable
            return RelayFeedParser.parse(body)?.let(RelayResult::Loaded) ?: RelayResult.Invalid
        }

        /** The whole body as UTF-8, or `null` if it runs past [limit] bytes. */
        fun readBounded(stream: InputStream, limit: Int): String? = stream.use { input ->
            val buffer = ByteArray(16 * 1024)
            val out = ByteArrayOutputStream(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (out.size() + read > limit) return null
                out.write(buffer, 0, read)
            }
            out.toString(Charsets.UTF_8.name())
        }
    }
}
