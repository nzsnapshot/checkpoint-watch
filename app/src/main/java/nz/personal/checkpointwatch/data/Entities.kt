package nz.personal.checkpointwatch.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single Checkpoint NZ post as stored in the database. Times are epoch milliseconds.
 * A [postId] starting with `"dom:"` marks a post recorded from the DOM fallback before its
 * real (numeric) id was known; see [nz.personal.checkpointwatch.data.ScrapeRecorder] for how
 * those rows are bridged onto the real post once it appears.
 */
@Entity(tableName = "posts", indices = [Index("createdAt"), Index("textHash")])
data class PostEntity(
    @PrimaryKey val postId: String,
    val url: String,
    val text: String,
    val textHash: String,
    val createdAt: Long,
    val createdAtApprox: Boolean,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val editedAt: Long?,
    /** True when the scan that first recorded this post found a gap in history before it. */
    val gapBefore: Boolean,
    /**
     * Where the post's photo lives on Facebook's content hosts, if it had one. Signed and
     * expiring, so it is a lead rather than an address worth keeping: it is what `ImageStore`
     * downloads from, once, and it is never what the UI loads.
     */
    val imageUrl: String? = null,
    /**
     * The downloaded copy, under the app's own files. Null until the download succeeds, and once
     * set it is never replaced by a later sighting of the same post — the file on disk outlives
     * the URL it came from.
     */
    val imagePath: String? = null,
)

/** One report parsed out of a post; a post can yield more than one, ordered by [indexInPost]. */
@Entity(
    tableName = "reports",
    foreignKeys = [
        ForeignKey(
            entity = PostEntity::class,
            parentColumns = ["postId"],
            childColumns = ["postId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("postId"), Index("suburb"), Index("type")],
)
data class ReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val postId: String,
    val indexInPost: Int,
    /** [nz.personal.checkpointwatch.model.ReportType] enum name. */
    val type: String,
    val typeLabel: String,
    val road: String?,
    val suburb: String?,
    val details: String,
    val reportedTimeText: String?,
    val reportedAt: Long?,
    val source: String?,
)

/** The previous text of a post, kept when a rescan finds it was edited. */
@Entity(
    tableName = "post_revisions",
    foreignKeys = [
        ForeignKey(
            entity = PostEntity::class,
            parentColumns = ["postId"],
            childColumns = ["postId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("postId")],
)
data class PostRevisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val postId: String,
    val text: String,
    val replacedAt: Long,
)

/** A single run of the scraper, recorded whether it succeeded or failed. */
@Entity(tableName = "scrapes")
data class ScrapeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val finishedAt: Long,
    /** [ScrapeStatus] enum name. */
    val status: String,
    val endReason: String,
    /** [ScanTrigger] enum name. */
    val trigger: String,
    /** [CollectorKind] enum name. */
    val collector: String,
    val postsSeen: Int,
    val postsNew: Int,
    val postsUpdated: Int,
)

/**
 * Records that a given post was present in a given scan, for auditing/history.
 *
 * Both parents cascade: dropping an old scrape (retention) takes its sightings with it, and so
 * does dropping a post — including the `dom:` placeholder row the recorder replaces once the real
 * numeric id turns up, whose sightings are not worth carrying across the bridge.
 */
@Entity(
    tableName = "scrape_sightings",
    primaryKeys = ["scrapeId", "postId"],
    foreignKeys = [
        ForeignKey(
            entity = ScrapeEntity::class,
            parentColumns = ["id"],
            childColumns = ["scrapeId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PostEntity::class,
            parentColumns = ["postId"],
            childColumns = ["postId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("scrapeId"), Index("postId")],
)
data class SightingEntity(
    val scrapeId: Long,
    val postId: String,
)

/** A [ReportEntity] joined with the fields of its parent post, for display. */
data class ReportRow(
    val id: Long,
    val postId: String,
    val indexInPost: Int,
    val type: String,
    val typeLabel: String,
    val road: String?,
    val suburb: String?,
    val details: String,
    val reportedTimeText: String?,
    val reportedAt: Long?,
    val source: String?,
    val postCreatedAt: Long,
    val postCreatedAtApprox: Boolean,
    val postUrl: String,
    val postText: String,
    val gapBefore: Boolean,
    val firstSeenAt: Long,
    /** The downloaded photo, if this post had one and it arrived. */
    val imagePath: String? = null,
)

/** A post whose photo has not been downloaded yet; see [PostDao.pendingImages]. */
data class PendingImageRow(
    val postId: String,
    val imageUrl: String,
)
