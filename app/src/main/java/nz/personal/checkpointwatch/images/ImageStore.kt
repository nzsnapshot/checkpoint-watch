package nz.personal.checkpointwatch.images

import kotlinx.coroutines.CancellationException
import nz.personal.checkpointwatch.collect.MediaUrl
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/** Facebook's photo addresses are signed and expire within days; an older post's is already dead. */
private val DOWNLOAD_WINDOW: Duration = Duration.ofDays(3)

/** A scan is a background job on a phone. A dozen small photos is a fair share of one. */
private const val MAX_PER_SYNC = 12

/** A file younger than this may be a download whose post has not been told about it yet. */
private val ORPHAN_GRACE: Duration = Duration.ofHours(1)

private const val EXTENSION = ".img"
private const val PARTIAL = ".part"

/** A post that has a photo somewhere and no copy of it yet. */
data class PendingImage(val postId: String, val imageUrl: String)

/** What [ImageStore] needs of the database; `RoomImageIndex` is the real one. */
interface ImageIndex {
    /** Posts created since [sinceMs] with an address and no file, newest first. */
    suspend fun pending(sinceMs: Long, limit: Int): List<PendingImage>

    suspend fun setImagePath(postId: String, path: String)

    /** Every file name a post still points at. */
    suspend fun allImagePaths(): List<String>
}

/** Fetches one photo into [to]. `false` for any failure; `HttpImageDownloader` is the real one. */
fun interface ImageDownloader {
    suspend fun download(url: String, to: File): Boolean
}

/**
 * Keeps a copy of each post's photo under the app's own files.
 *
 * The address Facebook gives is signed and stops working within days, so the app downloads the
 * photo once, soon after it first sees the post, and from then on only ever shows its own copy.
 * What is stored against the post is a bare file name — generated here from a hash, never from
 * anything the feed said — and the UI resolves it against [dir] with [fileFor].
 *
 * [sync] runs after a scan and is housekeeping, not part of it: it never throws (cancellation
 * aside), a photo that fails today is simply still pending tomorrow, and files no post points at
 * any more — their post aged out, or was a placeholder that never bridged — are swept on the way
 * out.
 */
class ImageStore(
    private val dir: File,
    private val index: ImageIndex,
    private val downloader: ImageDownloader,
    private val clock: () -> Instant = Instant::now,
) {

    fun fileFor(path: String): File = File(dir, File(path).name)

    /** @return how many photos were downloaded. */
    suspend fun sync(): Int =
        try {
            val now = clock()
            val fetched = index.pending(now.minus(DOWNLOAD_WINDOW).toEpochMilli(), MAX_PER_SYNC)
                .count { fetch(it) }
            sweepOrphans(now)
            fetched
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            0
        }

    private suspend fun fetch(image: PendingImage): Boolean {
        val url = MediaUrl.validate(image.imageUrl) ?: return false
        dir.mkdirs()
        val name = fileNameFor(image.postId)
        val partial = File(dir, name + PARTIAL)
        try {
            if (!downloader.download(url, partial) || partial.length() == 0L) return false
            val target = File(dir, name)
            if (!partial.renameTo(target)) return false
            index.setImagePath(image.postId, name)
            return true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return false
        } finally {
            partial.delete()
        }
    }

    private suspend fun sweepOrphans(now: Instant) {
        val files = dir.listFiles() ?: return
        val spokenFor = index.allImagePaths().mapTo(mutableSetOf()) { File(it).name }
        val cutoff = now.minus(ORPHAN_GRACE).toEpochMilli()
        for (file in files) {
            if (file.name !in spokenFor && file.lastModified() < cutoff) file.delete()
        }
    }

    private fun fileNameFor(postId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(postId.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) } + EXTENSION
    }
}
