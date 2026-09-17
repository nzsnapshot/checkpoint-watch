package nz.personal.checkpointwatch.collect

import java.time.Instant

/**
 * A single Checkpoint NZ post, normalised from the JSON feed, the DOM fallback or the page widget.
 *
 * [imageUrl] is the post's photo on Facebook's content hosts — a signed, expiring URL, which is
 * why the app downloads a copy rather than pointing at it (see `ImageStore`). `null` means the
 * post had no photo, or had one this app would not trust.
 */
data class RawPost(
    val postId: String,
    val createdAt: Instant,
    val createdAtApprox: Boolean,
    val text: String,
    val url: String,
    val imageUrl: String? = null,
)

/** A post as scraped straight from the rendered page, before normalisation. */
data class DomPost(
    val text: String,
    val age: String,
    val link: String?,
)

/**
 * A post as read from Facebook's Page Plugin, before normalisation.
 *
 * [utime] is `abbr[data-utime]`: unix seconds, exact, which is the one thing the widget gives that
 * the DOM fallback never can.
 */
data class PluginPost(
    val utime: Long,
    val link: String?,
    val text: String,
    val image: String?,
)
