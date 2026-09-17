package nz.personal.checkpointwatch.data

/**
 * Persistence seam used by [ScrapeRecorder], so its merge logic can be unit-tested against an
 * in-memory fake instead of a real Room database. [RoomScrapeStore] is the production
 * implementation.
 */
interface ScrapeStore {
    suspend fun <T> inTransaction(block: suspend () -> T): T

    suspend fun postCount(): Int
    suspend fun findPost(postId: String): PostEntity?
    suspend fun findByTextHash(hash: String, fromMs: Long, toMs: Long): PostEntity?
    suspend fun insertPost(p: PostEntity)
    suspend fun updatePost(p: PostEntity)
    suspend fun deletePost(postId: String)

    suspend fun insertRevision(r: PostRevisionEntity)

    suspend fun replaceReports(postId: String, reports: List<ReportEntity>)

    /**
     * The scrape row is inserted first, before any sighting can reference it, and rewritten at the
     * end of the transaction once the merge knows its status and counts.
     */
    suspend fun insertScrape(s: ScrapeEntity): Long
    suspend fun updateScrape(s: ScrapeEntity)
    suspend fun insertSighting(s: SightingEntity)

    /** Retention: drops scrape rows (and, by cascade, their sightings). Returns rows removed. */
    suspend fun deleteScrapesStartedBefore(cutoffMs: Long): Int

    /**
     * Retention: drops posts last seen *and* created before the cutoff, with their reports,
     * revisions and sightings. Returns rows removed.
     */
    suspend fun deleteStalePosts(cutoffMs: Long): Int
}
