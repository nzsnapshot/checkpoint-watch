package nz.personal.checkpointwatch.collect

import java.net.URI

/**
 * The one rule for trusting a URL that claims to be a photo on Facebook's content hosts.
 *
 * Every image the app downloads comes out of untrusted input — a JSON feed Facebook streams, or
 * markup rendered inside the page widget — and the app fetches whatever it is given, from a
 * background worker, with no one watching. So the address is checked before it is kept rather
 * than at the moment of the request: https only, and a host that really is `*.fbcdn.net` rather
 * than something ending in those characters (`fbcdn.net.evil.example` is not a Facebook host).
 *
 * Pure, and shared by [FeedJsonExtractor] and [PluginPostExtractor] so the two can never drift.
 */
internal object MediaUrl {

    private const val CONTENT_HOST_SUFFIX = ".fbcdn.net"

    /** [url] if it is an https address on one of Facebook's content hosts, else `null`. */
    fun validate(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!"https".equals(uri.scheme, ignoreCase = true)) return null
        val host = uri.host?.lowercase() ?: return null
        return if (host.endsWith(CONTENT_HOST_SUFFIX)) url else null
    }
}
