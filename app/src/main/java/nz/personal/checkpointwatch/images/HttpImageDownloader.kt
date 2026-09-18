package nz.personal.checkpointwatch.images

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.personal.checkpointwatch.collect.MediaUrl
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

private const val TIMEOUT_MS = 15_000

/** The photos the page posts are a few hundred kilobytes at most. */
private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024

/**
 * One plain HTTPS GET of a photo on Facebook's content hosts.
 *
 * The address is checked again here, at the moment of the request, by the same rule that admitted
 * it ([MediaUrl]); redirects are not followed, because a content host has no reason to send the
 * app anywhere else; and the answer has to say it is an image and stay under [MAX_IMAGE_BYTES], or
 * what was written is thrown away. No cookies and no identifying headers are sent.
 */
class HttpImageDownloader : ImageDownloader {

    override suspend fun download(url: String, to: File): Boolean = withContext(Dispatchers.IO) {
        val checked = MediaUrl.validate(url) ?: return@withContext false
        val connection = URL(checked).openConnection() as? HttpsURLConnection ?: return@withContext false
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("Accept", "image/*")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext false
            if (connection.contentType?.startsWith("image/", ignoreCase = true) != true) return@withContext false
            copyBounded(connection, to)
        } finally {
            connection.disconnect()
        }
    }

    private fun copyBounded(connection: HttpsURLConnection, to: File): Boolean {
        var total = 0L
        connection.inputStream.use { input ->
            to.outputStream().use { output ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_IMAGE_BYTES) return false
                    output.write(buffer, 0, read)
                }
            }
        }
        return total > 0
    }
}
