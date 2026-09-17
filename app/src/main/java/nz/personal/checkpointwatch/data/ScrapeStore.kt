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

    suspend fun insertScrape(s: ScrapeEntity): Long
    suspend fun insertSighting(s: SightingEntity)
}
