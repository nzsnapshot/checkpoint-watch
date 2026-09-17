package nz.personal.checkpointwatch.data

/**
 * In-memory [ScrapeStore] used only by tests, so [ScrapeRecorder]'s merge logic can be exercised
 * without Room.
 *
 * It enforces the same foreign keys the real schema declares — a revision or a sighting may only
 * reference a row that exists, a sighting may only reference a scrape that exists — and mirrors
 * their `ON DELETE CASCADE` behaviour. That is the point of the checks: the recorder writes inside
 * one transaction with foreign keys on, so an insert in the wrong order is a crash on the phone,
 * and this fake is where that has to be caught. It is still not a substitute for testing
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
        check(!posts.containsKey(p.postId)) {
            "duplicate postId ${p.postId}: real Room would violate the posts primary key"
        }
        posts[p.postId] = p
    }

    override suspend fun updatePost(p: PostEntity) {
        posts[p.postId] = p
    }

    override suspend fun deletePost(postId: String) {
        posts.remove(postId)
        cascadeFromPost(postId)
    }

    override suspend fun insertRevision(r: PostRevisionEntity) {
        check(posts.containsKey(r.postId)) {
            "revision for unknown postId ${r.postId}: real Room would violate the " +
                "post_revisions -> posts foreign key"
        }
        revisions += r.copy(id = nextRevisionId++)
    }

    override suspend fun replaceReports(postId: String, reports: List<ReportEntity>) {
        check(posts.containsKey(postId)) {
            "reports for unknown postId $postId: real Room would violate the reports -> posts " +
                "foreign key"
        }
        reportsByPost[postId] = reports.map { it.copy(id = nextReportId++) }.toMutableList()
    }

    override suspend fun insertScrape(s: ScrapeEntity): Long {
        val id = nextScrapeId++
        scrapes += s.copy(id = id)
        return id
    }

    override suspend fun updateScrape(s: ScrapeEntity) {
        val index = scrapes.indexOfFirst { it.id == s.id }
        check(index >= 0) { "no scrape row with id ${s.id} to update" }
        scrapes[index] = s
    }

    override suspend fun insertSighting(s: SightingEntity) {
        check(scrapes.any { it.id == s.scrapeId }) {
            "sighting for unknown scrapeId ${s.scrapeId}: real Room would violate the " +
                "scrape_sightings -> scrapes foreign key"
        }
        check(posts.containsKey(s.postId)) {
            "sighting for unknown postId ${s.postId}: real Room would violate the " +
                "scrape_sightings -> posts foreign key"
        }
        check(sightings.none { it.scrapeId == s.scrapeId && it.postId == s.postId }) {
            "duplicate sighting (${s.scrapeId}, ${s.postId}): real Room would violate the " +
                "scrape_sightings primary key"
        }
        sightings += s
    }

    override suspend fun deleteScrapesStartedBefore(cutoffMs: Long): Int {
        val doomed = scrapes.filter { it.startedAt < cutoffMs }
        scrapes.removeAll(doomed)
        doomed.forEach { scrape -> sightings.removeAll { it.scrapeId == scrape.id } }
        return doomed.size
    }

    override suspend fun deleteStalePosts(cutoffMs: Long): Int {
        val doomed = posts.values.filter { it.lastSeenAt < cutoffMs && it.createdAt < cutoffMs }
        doomed.forEach {
            posts.remove(it.postId)
            cascadeFromPost(it.postId)
        }
        return doomed.size
    }

    /** Mirrors `ON DELETE CASCADE` on every child of a post. */
    private fun cascadeFromPost(postId: String) {
        reportsByPost.remove(postId)
        revisions.removeAll { it.postId == postId }
        sightings.removeAll { it.postId == postId }
    }
}
