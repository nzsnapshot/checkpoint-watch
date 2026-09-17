package nz.personal.checkpointwatch.data

import androidx.room.withTransaction

/** [ScrapeStore] backed by the real Room [AppDatabase]. */
class RoomScrapeStore(private val db: AppDatabase) : ScrapeStore {

    override suspend fun <T> inTransaction(block: suspend () -> T): T = db.withTransaction { block() }

    override suspend fun postCount(): Int = db.postDao().count()

    override suspend fun findPost(postId: String): PostEntity? = db.postDao().findById(postId)

    override suspend fun findByTextHash(hash: String, fromMs: Long, toMs: Long): PostEntity? =
        db.postDao().findByTextHash(hash, fromMs, toMs)

    override suspend fun insertPost(p: PostEntity) = db.postDao().insert(p)

    override suspend fun updatePost(p: PostEntity) = db.postDao().update(p)

    override suspend fun deletePost(postId: String) = db.postDao().deleteById(postId)

    override suspend fun insertRevision(r: PostRevisionEntity) = db.revisionDao().insert(r)

    override suspend fun replaceReports(postId: String, reports: List<ReportEntity>) {
        db.reportDao().deleteForPost(postId)
        if (reports.isNotEmpty()) db.reportDao().insertAll(reports)
    }

    override suspend fun insertScrape(s: ScrapeEntity): Long = db.scrapeDao().insert(s)

    override suspend fun insertSighting(s: SightingEntity) = db.scrapeDao().insertSighting(s)
}
