package nz.personal.checkpointwatch.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PostDao {
    @Query("SELECT COUNT(*) FROM posts")
    suspend fun count(): Int

    @Query("SELECT * FROM posts WHERE postId = :postId")
    suspend fun findById(postId: String): PostEntity?

    @Query("SELECT * FROM posts WHERE textHash = :hash AND createdAt BETWEEN :fromMs AND :toMs LIMIT 1")
    suspend fun findByTextHash(hash: String, fromMs: Long, toMs: Long): PostEntity?

    @Insert
    suspend fun insert(post: PostEntity)

    @Update
    suspend fun update(post: PostEntity)

    @Query("DELETE FROM posts WHERE postId = :postId")
    suspend fun deleteById(postId: String)
}

@Dao
interface ReportDao {
    @Insert
    suspend fun insertAll(reports: List<ReportEntity>)

    @Query("DELETE FROM reports WHERE postId = :postId")
    suspend fun deleteForPost(postId: String)

    @Query(
        """
        SELECT
            reports.id AS id,
            reports.postId AS postId,
            reports.indexInPost AS indexInPost,
            reports.type AS type,
            reports.typeLabel AS typeLabel,
            reports.road AS road,
            reports.suburb AS suburb,
            reports.details AS details,
            reports.reportedTimeText AS reportedTimeText,
            reports.reportedAt AS reportedAt,
            reports.source AS source,
            posts.createdAt AS postCreatedAt,
            posts.createdAtApprox AS postCreatedAtApprox,
            posts.url AS postUrl,
            posts.text AS postText,
            posts.gapBefore AS gapBefore,
            posts.firstSeenAt AS firstSeenAt
        FROM reports
        JOIN posts ON posts.postId = reports.postId
        ORDER BY posts.createdAt DESC, reports.indexInPost ASC
        LIMIT :limit
        """,
    )
    fun observeRows(limit: Int = 600): Flow<List<ReportRow>>

    @Query("SELECT DISTINCT suburb FROM reports WHERE suburb IS NOT NULL ORDER BY suburb ASC")
    fun observeSuburbs(): Flow<List<String>>
}

@Dao
interface RevisionDao {
    @Insert
    suspend fun insert(revision: PostRevisionEntity)
}

@Dao
interface ScrapeDao {
    @Insert
    suspend fun insert(scrape: ScrapeEntity): Long

    @Insert
    suspend fun insertSighting(sighting: SightingEntity)

    @Query("SELECT * FROM scrapes ORDER BY startedAt DESC LIMIT :n")
    fun observeRecent(n: Int): Flow<List<ScrapeEntity>>

    @Query("SELECT MAX(finishedAt) FROM scrapes")
    suspend fun lastFinishedAt(): Long?
}
