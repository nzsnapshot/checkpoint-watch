package nz.personal.checkpointwatch.collect

import java.time.Instant

/** A single Checkpoint NZ post, normalised from either the JSON feed or the DOM fallback. */
data class RawPost(
    val postId: String,
    val createdAt: Instant,
    val createdAtApprox: Boolean,
    val text: String,
    val url: String,
)

/** A post as scraped straight from the rendered page, before normalisation. */
data class DomPost(
    val text: String,
    val age: String,
    val link: String?,
)
