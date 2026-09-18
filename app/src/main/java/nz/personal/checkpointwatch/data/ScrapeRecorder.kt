package nz.personal.checkpointwatch.data

import nz.personal.checkpointwatch.collect.DomPostExtractor
import nz.personal.checkpointwatch.collect.RawPost
import nz.personal.checkpointwatch.model.ParsedReport
import nz.personal.checkpointwatch.parse.ReportParser
import java.time.Duration
import java.time.Instant

private val HASH_MATCH_WINDOW: Duration = Duration.ofHours(48)
private const val DOM_ID_PREFIX = "dom:"

/**
 * Merges a scan's freshly collected [RawPost]s into the database and records a `scrapes` row
 * for the run, whether it succeeded or failed. One call to [record] is one transaction.
 *
 * Matching: a post is matched to an existing row first by [RawPost.postId], then (to bridge the
 * DOM fallback's synthetic `dom:` ids onto the real numeric id once the JSON feed catches up) by
 * text hash within 48 hours of its `createdAt`. See the class's test file and the design spec's
 * "ScrapeRecorder (merge logic)" section for the exact rules.
 *
 * The incoming list is deduped first ([dedupeIncoming]) so that a post appearing twice in the
 * same scan — whether under the exact same id, or as both a `dom:` placeholder and its real
 * numeric id — is written at most once and never "matches" itself: a match only ever means a
 * match against a row that existed before this scan started, which is what the gap rule and the
 * new/updated counts depend on.
 */
class ScrapeRecorder(private val store: ScrapeStore) {

    suspend fun record(
        posts: List<RawPost>,
        startedAt: Instant,
        finishedAt: Instant,
        trigger: ScanTrigger,
        collector: CollectorKind,
        endReason: String,
        failure: ScrapeStatus? = null,
    ): ScrapeOutcome = store.inTransaction {
        val finishedAtMs = finishedAt.toEpochMilli()
        val distinctPosts = dedupeIncoming(posts)

        // The scrape row goes in before anything that references it (a sighting's foreign key),
        // carrying provisional counts; the merge below is what learns the real ones.
        val provisional = scrapeRow(
            startedAt = startedAt,
            finishedAt = finishedAt,
            status = failure ?: ScrapeStatus.FAILED_NO_DATA,
            endReason = endReason,
            trigger = trigger,
            collector = collector,
            seen = 0,
            new = 0,
            updated = 0,
        )
        val scrapeId = store.insertScrape(provisional)

        if (distinctPosts.isEmpty()) {
            val status = failure ?: ScrapeStatus.FAILED_NO_DATA
            RetentionPolicy.sweep(store, finishedAt)
            return@inTransaction ScrapeOutcome(scrapeId, status, seen = 0, new = 0, updated = 0, newReports = emptyList())
        }

        val hadPosts = store.postCount() > 0
        val merge = mergePosts(distinctPosts, finishedAtMs)

        val gapApplies = hadPosts && merge.matches == 0
        if (gapApplies) {
            val oldest = merge.oldestNewPost
            if (oldest != null) store.updatePost(oldest.copy(gapBefore = true))
        }

        val mergeStatus = if (gapApplies) ScrapeStatus.OK_WITH_GAP else ScrapeStatus.OK
        val status = failure ?: mergeStatus

        store.updateScrape(
            provisional.copy(
                id = scrapeId,
                status = status.name,
                postsSeen = distinctPosts.size,
                postsNew = merge.newCount,
                postsUpdated = merge.updatedCount,
            ),
        )
        merge.sightingPostIds.forEach { store.insertSighting(SightingEntity(scrapeId, it)) }

        RetentionPolicy.sweep(store, finishedAt)

        ScrapeOutcome(
            scrapeId = scrapeId,
            status = status,
            seen = distinctPosts.size,
            new = merge.newCount,
            updated = merge.updatedCount,
            newReports = merge.newReports,
        )
    }

    /**
     * Collapses posts that would otherwise resolve to the same stored row within this one scan,
     * so [mergePosts] never sees (and never has to match against) two entries for the same
     * underlying post:
     *  1. Exact `postId` duplicates are collapsed, keeping the one with the longest text.
     *  2. A remaining `dom:` post sharing a text hash with a numeric post in the same batch is
     *     dropped in favour of the numeric one (the DOM fallback's placeholder for a post the
     *     JSON feed also captured this scan). Two `dom:` posts can never collide here without
     *     already sharing an id, since a `dom:` id is derived from its hash. Two *numeric* posts
     *     that merely happen to share text are always kept as distinct posts — the page can
     *     legitimately repost identical wording under a new id.
     */
    private fun dedupeIncoming(posts: List<RawPost>): List<RawPost> {
        val byId = LinkedHashMap<String, RawPost>()
        for (post in posts) {
            val current = byId[post.postId]
            if (current == null || post.text.length > current.text.length) byId[post.postId] = post
        }

        val numericHashes = byId.values
            .filterNot { it.postId.startsWith(DOM_ID_PREFIX) }
            .mapTo(mutableSetOf()) { DomPostExtractor.textHash(it.text) }

        return byId.values.filterNot { post ->
            post.postId.startsWith(DOM_ID_PREFIX) && DomPostExtractor.textHash(post.text) in numericHashes
        }
    }

    /** Accumulated effect of merging one scan's posts, before the scrape row's id is known. */
    private class MergeResult {
        var newCount = 0
        var updatedCount = 0
        var matches = 0
        val newReports = mutableListOf<NewReport>()
        val sightingPostIds = mutableListOf<String>()
        var oldestNewPost: PostEntity? = null
        var oldestNewPostCreatedAt: Instant? = null

        /**
         * Rows this scan matched that carry a gap flag, as (the id they now live under, the time
         * of the incoming post that matched them). Candidates for [healGaps].
         */
        val gapCandidates = mutableListOf<Pair<String, Instant>>()
    }

    private suspend fun mergePosts(posts: List<RawPost>, finishedAtMs: Long): MergeResult {
        val result = MergeResult()
        for (post in posts.sortedByDescending { it.createdAt }) {
            val byId = store.findPost(post.postId)
            val existing = byId ?: run {
                val hash = DomPostExtractor.textHash(post.text)
                val fromMs = post.createdAt.minus(HASH_MATCH_WINDOW).toEpochMilli()
                val toMs = post.createdAt.plus(HASH_MATCH_WINDOW).toEpochMilli()
                val candidate = store.findByTextHash(hash, fromMs, toMs)
                // A numeric post may only hash-match a dom: placeholder (the DB row it should
                // bridge onto). A numeric post that merely shares text with a different existing
                // numeric post is not a match at all — that's a genuinely new, distinct post. A
                // dom: post, however, may hash-match any existing row (dom: or numeric).
                val incomingIsDom = post.postId.startsWith(DOM_ID_PREFIX)
                if (candidate != null && !incomingIsDom && !candidate.postId.startsWith(DOM_ID_PREFIX)) null else candidate
            }

            if (existing == null) {
                val entity = insertNewPost(post, finishedAtMs)
                result.newCount++
                result.sightingPostIds += post.postId
                result.newReports += parseAndInsertReports(post, entity.postId)
                    .map { it.toNewReport(post.createdAt) }
                val oldestSoFar = result.oldestNewPostCreatedAt
                if (oldestSoFar == null || post.createdAt.isBefore(oldestSoFar)) {
                    result.oldestNewPost = entity
                    result.oldestNewPostCreatedAt = post.createdAt
                }
                continue
            }

            result.matches++
            if (existing.gapBefore) {
                // Where the row ends up: a dom: placeholder is about to become the numeric post.
                val landsUnder = if (byId == null && existing.postId.startsWith(DOM_ID_PREFIX) &&
                    !post.postId.startsWith(DOM_ID_PREFIX)
                ) {
                    post.postId
                } else {
                    existing.postId
                }
                result.gapCandidates += landsUnder to post.createdAt
            }

            if (byId != null) {
                // Direct id match: same post seen again, possibly edited.
                if (existing.text == post.text) {
                    store.updatePost(existing.copy(lastSeenAt = finishedAtMs, imageUrl = keptImageUrl(existing, post)))
                } else {
                    store.insertRevision(PostRevisionEntity(postId = existing.postId, text = existing.text, replacedAt = finishedAtMs))
                    store.updatePost(
                        existing.copy(
                            url = post.url,
                            text = post.text,
                            textHash = DomPostExtractor.textHash(post.text),
                            lastSeenAt = finishedAtMs,
                            editedAt = finishedAtMs,
                            imageUrl = keptImageUrl(existing, post),
                        ),
                    )
                    parseAndInsertReports(post, existing.postId)
                    result.updatedCount++
                }
                result.sightingPostIds += existing.postId
            } else if (existing.postId.startsWith(DOM_ID_PREFIX) && !post.postId.startsWith(DOM_ID_PREFIX)) {
                // A dom: placeholder row is superseded by the real (numeric) post id.
                store.deletePost(existing.postId)
                val bridged = PostEntity(
                    postId = post.postId,
                    url = post.url,
                    text = post.text,
                    textHash = DomPostExtractor.textHash(post.text),
                    createdAt = post.createdAt.toEpochMilli(),
                    createdAtApprox = post.createdAtApprox,
                    firstSeenAt = existing.firstSeenAt,
                    lastSeenAt = finishedAtMs,
                    editedAt = null,
                    gapBefore = existing.gapBefore,
                    imageUrl = keptImageUrl(existing, post),
                    // The photo was downloaded against the placeholder row and the file is still
                    // there; it crosses the bridge with the post. Left behind it would be a file
                    // nothing points at, which the orphan sweep would quietly delete.
                    imagePath = existing.imagePath,
                )
                store.insertPost(bridged)
                parseAndInsertReports(post, post.postId)
                result.sightingPostIds += post.postId
                result.updatedCount++
            } else {
                // Same content seen again under a different id (e.g. DOM fallback re-finding a
                // post we already have from the JSON feed): just note it was seen — and learn
                // where its photo is, if this sighting is the one that carried it.
                store.updatePost(existing.copy(lastSeenAt = finishedAtMs, imageUrl = keptImageUrl(existing, post)))
                result.sightingPostIds += existing.postId
            }
        }
        healGaps(posts, result)
        return result
    }

    /**
     * Takes the gap flag off any post this scan has just reached past.
     *
     * A scan is a contiguous newest-first slice of the feed: whatever it returns, it returns with
     * nothing missing in between. So if a post that was flagged "history before this is unknown"
     * turns up in this scan *and* this scan also contains a post older than it, the older posts on
     * the far side of the supposed gap are exactly what we are now looking at — the flag was only
     * ever true of the scan that set it (a one-post HTTP fallback, a scan cancelled early), and
     * leaving it on would keep an "Earlier posts unavailable" divider on screen for good.
     *
     * Setting a gap is unchanged: zero overlap with existing data still means a gap. Only the
     * healing is new, and the two can never happen in the same scan — healing needs a match, and
     * the gap rule only fires when there were none.
     */
    private suspend fun healGaps(posts: List<RawPost>, result: MergeResult) {
        if (result.gapCandidates.isEmpty()) return
        val oldestInScan = posts.minOfOrNull { it.createdAt } ?: return
        for ((postId, matchedAt) in result.gapCandidates) {
            if (!oldestInScan.isBefore(matchedAt)) continue
            val row = store.findPost(postId) ?: continue
            if (row.gapBefore) store.updatePost(row.copy(gapBefore = false))
        }
    }

    /**
     * Where this post's photo lives: left alone once the photo is on disk, kept current until then.
     *
     * Facebook re-signs these URLs, so a later scan's "new" URL is the same photo with a different
     * signature. Once `ImageStore` has its copy that is a reason to ignore it — nothing about the
     * picture has changed. Before then it is a reason to take it: the signature is what expires,
     * and a post first seen while the phone could not download would otherwise hold a dead address
     * and turn away every live one. A sighting with no photo never takes an address away.
     */
    private fun keptImageUrl(existing: PostEntity, post: RawPost): String? =
        if (existing.imagePath != null) existing.imageUrl else post.imageUrl ?: existing.imageUrl

    private suspend fun insertNewPost(post: RawPost, finishedAtMs: Long): PostEntity {
        val entity = PostEntity(
            postId = post.postId,
            url = post.url,
            text = post.text,
            textHash = DomPostExtractor.textHash(post.text),
            createdAt = post.createdAt.toEpochMilli(),
            createdAtApprox = post.createdAtApprox,
            firstSeenAt = finishedAtMs,
            lastSeenAt = finishedAtMs,
            editedAt = null,
            gapBefore = false,
            imageUrl = post.imageUrl,
        )
        store.insertPost(entity)
        return entity
    }

    private suspend fun parseAndInsertReports(post: RawPost, postId: String): List<ParsedReport> {
        val parsed = ReportParser.parse(post.text, post.createdAt)
        store.replaceReports(postId, parsed.map { it.toEntity(postId) })
        return parsed
    }

    private fun scrapeRow(
        startedAt: Instant,
        finishedAt: Instant,
        status: ScrapeStatus,
        endReason: String,
        trigger: ScanTrigger,
        collector: CollectorKind,
        seen: Int,
        new: Int,
        updated: Int,
    ): ScrapeEntity = ScrapeEntity(
        startedAt = startedAt.toEpochMilli(),
        finishedAt = finishedAt.toEpochMilli(),
        status = status.name,
        endReason = endReason,
        trigger = trigger.name,
        collector = collector.name,
        postsSeen = seen,
        postsNew = new,
        postsUpdated = updated,
    )

    private fun ParsedReport.toEntity(postId: String) = ReportEntity(
        postId = postId,
        indexInPost = indexInPost,
        type = type.name,
        typeLabel = typeLabel,
        road = road,
        suburb = suburb,
        details = details,
        reportedTimeText = reportedTimeText,
        reportedAt = reportedAt?.toEpochMilli(),
        source = source,
    )

    private fun ParsedReport.toNewReport(postCreatedAt: Instant) = NewReport(
        type = type,
        typeLabel = typeLabel,
        road = road,
        suburb = suburb,
        at = reportedAt ?: postCreatedAt,
    )
}
