package nz.personal.checkpointwatch.data

/**
 * In-memory [ScrapeStore] used only by tests, so [ScrapeRecorder]'s merge logic can be exercised
 * without Room. Mirrors the real schema's cascade-delete behaviour (deleting a post removes its
 * reports) closely enough for the recorder's own tests; it is not a substitute for testing
 * [RoomScrapeStore] itself.
 */
class FakeScrapeStore : ScrapeStore {
    val posts = mutableMapOf<String, PostEntity>()
    val reportsByPost = mutableMapOf<String, MutableList<ReportEntity>>()
    val revisions = mutableListOf<PostRevisionEntity>()
    val scrapes = mutableListOf<ScrapeEntity>()
    val sightings = mutableListOf<SightingEntity>()

    private var nextReportId = 1L
    private var nextRevisionId = 1L
    private var nextScrapeId = 1L

    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()

    override suspend fun postCount(): Int = posts.size

    override suspend fun findPost(postId: String): PostEntity? = posts[postId]

    override suspend fun findByTextHash(hash: String, fromMs: Long, toMs: Long): PostEntity? =
        posts.values.firstOrNull { it.textHash == hash && it.createdAt in fromMs..toMs }

    override suspend fun insertPost(p: PostEntity) {
        posts[p.postId] = p
    }

    override suspend fun updatePost(p: PostEntity) {
        posts[p.postId] = p
    }

    override suspend fun deletePost(postId: String) {
        posts.remove(postId)
        reportsByPost.remove(postId) // mirrors ON DELETE CASCADE
    }

    override suspend fun insertRevision(r: PostRevisionEntity) {
        revisions += r.copy(id = nextRevisionId++)
    }

    override suspend fun replaceReports(postId: String, reports: List<ReportEntity>) {
        reportsByPost[postId] = reports.map { it.copy(id = nextReportId++) }.toMutableList()
    }

    override suspend fun insertScrape(s: ScrapeEntity): Long {
        val id = nextScrapeId++
        scrapes += s.copy(id = id)
        return id
    }

    override suspend fun insertSighting(s: SightingEntity) {
        sightings += s
    }
}
