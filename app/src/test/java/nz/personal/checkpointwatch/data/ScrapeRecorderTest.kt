package nz.personal.checkpointwatch.data

import kotlinx.coroutines.test.runTest
import nz.personal.checkpointwatch.collect.RawPost
import nz.personal.checkpointwatch.model.ReportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class ScrapeRecorderTest {

    private val t0 = Instant.parse("2026-09-18T00:00:00Z")

    private fun post(
        id: String,
        text: String,
        createdAt: Instant = t0,
        approx: Boolean = false,
        url: String = "https://www.facebook.com/CheckpointNZ/posts/$id",
        imageUrl: String? = null,
    ) = RawPost(
        postId = id,
        createdAt = createdAt,
        createdAtApprox = approx,
        text = text,
        url = url,
        imageUrl = imageUrl,
    )

    private val checkpointText = "🛑 CHECKPOINT – Lincoln Road, HENDERSON\nAfter the off-ramp\nTime: 11:55PM"

    @Test
    fun firstScanRecordsEveryPostAsNewWithNoGap() = runTest {
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)

        val outcome = recorder.record(
            posts = listOf(post("1", checkpointText), post("2", "Win 3 months free rego! Subscribe now")),
            startedAt = t0,
            finishedAt = t0.plusSeconds(5),
            trigger = ScanTrigger.FOREGROUND,
            collector = CollectorKind.WEBVIEW,
            endReason = "no more posts",
        )

        assertEquals(ScrapeStatus.OK, outcome.status)
        assertEquals(2, outcome.seen)
        assertEquals(2, outcome.new)
        assertEquals(0, outcome.updated)
        assertEquals(2, store.posts.size)
        assertTrue(store.posts.values.none { it.gapBefore })
        assertEquals(1, store.scrapes.size)
        assertEquals(2, store.sightings.size)
    }

    @Test
    fun rescanOfIdenticalPostsRecordsNoNewAndAdvancesLastSeen() = runTest {
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)
        val posts = listOf(post("1", checkpointText), post("2", "Win 3 months free rego! Subscribe now"))

        recorder.record(posts, t0, t0.plusSeconds(5), ScanTrigger.FOREGROUND, CollectorKind.WEBVIEW, "done")
        val second = recorder.record(
            posts,
            t0.plusSeconds(60),
            t0.plusSeconds(65),
            ScanTrigger.BACKGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )

        assertEquals(ScrapeStatus.OK, second.status)
        assertEquals(0, second.new)
        assertEquals(0, second.updated)
        assertEquals(2, second.seen)
        assertEquals(t0.plusSeconds(65).toEpochMilli(), store.posts.getValue("1").lastSeenAt)
        assertEquals(2, store.sightings.count { it.postId == "1" })
        assertEquals(2, store.sightings.count { it.postId == "2" })
    }

    @Test
    fun overlapOfOnePostIsOkWithNoGap() = runTest {
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)

        recorder.record(
            listOf(post("1", checkpointText), post("2", "Win 3 months free rego! Subscribe now")),
            t0,
            t0.plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )
        val second = recorder.record(
            listOf(post("2", "Win 3 months free rego! Subscribe now"), post("3", "New post text here")),
            t0.plusSeconds(60),
            t0.plusSeconds(65),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )

        assertEquals(ScrapeStatus.OK, second.status)
        assertEquals(1, second.new)
        assertFalse(store.posts.getValue("3").gapBefore)
    }

    @Test
    fun zeroOverlapSetsGapOnlyOnOldestNewPost() = runTest {
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)

        recorder.record(
            listOf(post("1", checkpointText)),
            t0,
            t0.plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )

        val newer = t0.plus(2, ChronoUnit.HOURS)
        val older = t0.plus(1, ChronoUnit.HOURS)
        val second = recorder.record(
            listOf(
                post("10", "New post text A", createdAt = newer),
                post("11", "New post text B", createdAt = older),
            ),
            t0.plus(3, ChronoUnit.HOURS),
            t0.plus(3, ChronoUnit.HOURS).plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )

        assertEquals(ScrapeStatus.OK_WITH_GAP, second.status)
        assertEquals(2, second.new)
        assertTrue(store.posts.getValue("11").gapBefore)
        assertFalse(store.posts.getValue("10").gapBefore)
        assertFalse(store.posts.getValue("1").gapBefore)
    }

    // --- the gap flag heals -----------------------------------------------------------------
    //
    // A scan is a contiguous newest-first slice of the feed. So when a scan contains a post that
    // is flagged as having a gap before it, AND contains something older than that post, the scan
    // has just shown what was on the other side of the gap: the flag is no longer true and must
    // come off, or the "Earlier posts unavailable" divider stays on screen forever.

    @Test
    fun aLaterScanReachingPastAGapFlagClearsIt() = runTest {
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)

        // An old scan, then a single-post scan with no overlap at all: the gap rule fires.
        recorder.record(
            listOf(post("1", checkpointText)),
            t0,
            t0.plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )
        val lonely = t0.plus(5, ChronoUnit.HOURS)
        recorder.record(
            listOf(post("20", "Newest post, nothing else came back", createdAt = lonely)),
            lonely,
            lonely.plusSeconds(5),
            ScanTrigger.BACKGROUND,
            CollectorKind.HTTP,
            "done",
        )
        assertTrue(store.posts.getValue("20").gapBefore)

        // The next full scan sees that post again and the ones underneath it.
        val full = lonely.plus(1, ChronoUnit.HOURS)
        val posts = listOf(post("20", "Newest post, nothing else came back", createdAt = lonely)) +
            (19 downTo 12).map { n ->
                post("$n", "Filler post $n", createdAt = lonely.minus((20 - n).toLong(), ChronoUnit.MINUTES))
            }
        val third = recorder.record(posts, full, full.plusSeconds(5), ScanTrigger.FOREGROUND, CollectorKind.WEBVIEW, "done")

        assertEquals(ScrapeStatus.OK, third.status)
        assertTrue("no gap flag should survive", store.posts.values.none { it.gapBefore })
    }

    @Test
    fun aGapNoLaterScanEverReachesPastKeepsItsFlag() = runTest {
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)

        recorder.record(
            listOf(post("1", checkpointText)),
            t0,
            t0.plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )
        val newer = t0.plus(5, ChronoUnit.HOURS)
        val older = t0.plus(4, ChronoUnit.HOURS)
        val gapScan = listOf(
            post("20", "New post A", createdAt = newer),
            post("19", "New post B", createdAt = older),
        )
        recorder.record(gapScan, newer, newer.plusSeconds(5), ScanTrigger.FOREGROUND, CollectorKind.WEBVIEW, "done")
        assertTrue(store.posts.getValue("19").gapBefore)

        // Every later scan returns the same slice: nothing older than the flagged post is ever
        // shown, so nothing proves the gap was filled.
        val again = newer.plus(1, ChronoUnit.HOURS)
        recorder.record(gapScan, again, again.plusSeconds(5), ScanTrigger.FOREGROUND, CollectorKind.WEBVIEW, "done")

        assertTrue("a real gap must stay marked", store.posts.getValue("19").gapBefore)
    }

    @Test
    fun aGapOnADomPlaceholderIsClearedWhenTheBridgedScanReachesPastIt() = runTest {
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)

        recorder.record(
            listOf(post("1", "An older post from an earlier scan")),
            t0,
            t0.plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )
        // A DOM-fallback scan with no overlap: the placeholder row carries the gap flag.
        val domTime = t0.plus(5, ChronoUnit.HOURS)
        recorder.record(
            listOf(post("dom:abc123", checkpointText, createdAt = domTime, approx = true)),
            domTime,
            domTime.plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW_DOM,
            "done",
        )
        assertTrue(store.posts.getValue("dom:abc123").gapBefore)

        // The JSON feed comes back: the same post under its real id, plus an older neighbour.
        val full = domTime.plus(30, ChronoUnit.MINUTES)
        recorder.record(
            listOf(
                post("789", checkpointText, createdAt = domTime),
                post("788", "The post underneath it", createdAt = domTime.minus(20, ChronoUnit.MINUTES)),
            ),
            full,
            full.plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )

        assertNull(store.posts["dom:abc123"])
        assertFalse("the bridged row must not inherit a healed gap", store.posts.getValue("789").gapBefore)
        assertTrue(store.posts.values.none { it.gapBefore })
    }

    // --- retention --------------------------------------------------------------------------

    @Test
    fun recordingSweepsScrapesOlderThanTheRetentionWindow() = runTest {
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)

        val ancient = t0.minus(RetentionPolicy.SCRAPE_HISTORY).minusSeconds(60)
        recorder.record(
            listOf(post("1", checkpointText, createdAt = ancient)),
            ancient,
            ancient.plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )
        assertEquals(1, store.scrapes.size)
        assertEquals(1, store.sightings.size)

        recorder.record(
            listOf(post("2", "A post from today", createdAt = t0)),
            t0,
            t0.plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )

        assertEquals("the month-old scrape row is gone", 1, store.scrapes.size)
        assertEquals(t0.toEpochMilli(), store.scrapes.single().startedAt)
        assertEquals("its sighting went with it", listOf("2"), store.sightings.map { it.postId })
        assertTrue("but its post stayed", store.posts.containsKey("1"))
    }

    @Test
    fun recordingSweepsPostsOnlyWhenBothClocksAreOlderThanTheWindow() = runTest {
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)

        val ancient = t0.minus(RetentionPolicy.POST_HISTORY).minusSeconds(60)
        recorder.record(
            listOf(
                post("old", "A post nobody has seen for half a year", createdAt = ancient),
                post("oldButSeen", "An old post the page still shows", createdAt = ancient),
            ),
            ancient,
            ancient.plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )

        recorder.record(
            listOf(post("oldButSeen", "An old post the page still shows", createdAt = ancient)),
            t0,
            t0.plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )

        assertNull("created and last seen long ago: dropped", store.posts["old"])
        assertNotNull("seen again today: kept", store.posts["oldButSeen"])
        assertTrue("its reports went with it", store.reportsByPost["old"] == null)
    }

    @Test
    fun editedTextStoresRevisionReplacesReportsAndCountsAsUpdated() = runTest {
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)
        val originalText = "🛑 CHECKPOINT – Lincoln Road, HENDERSON\nJust set up\nTime: 11:55PM"
        val editedText = "🛑 CHECKPOINT – Lincoln Road, HENDERSON\nNow with more detail\nTime: 11:55PM"

        recorder.record(
            listOf(post("1", originalText)),
            t0,
            t0.plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )
        val second = recorder.record(
            listOf(post("1", editedText)),
            t0.plusSeconds(60),
            t0.plusSeconds(65),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )

        assertEquals(1, second.updated)
        assertEquals(0, second.new)
        assertEquals(1, store.revisions.size)
        assertEquals(originalText, store.revisions.single().text)
        assertEquals(t0.plusSeconds(65).toEpochMilli(), store.revisions.single().replacedAt)
        assertEquals(editedText, store.posts.getValue("1").text)
        assertEquals(t0.plusSeconds(65).toEpochMilli(), store.posts.getValue("1").editedAt)
        assertEquals("Now with more detail", store.reportsByPost.getValue("1").single().details)
    }

    @Test
    fun multiReportPostYieldsTwoReportEntitiesAndTwoNewReports() = runTest {
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)
        val text = "🛑 CHECKPOINT – Grafton On-Ramp\nTime: 9:30PM (Pictured) \n\n" +
            "🛑 CHECKPOINT – Stancombe Road, FLAT BUSH\nNear the temple\nTime: 9:30PM"

        val outcome = recorder.record(
            listOf(post("1", text)),
            t0,
            t0.plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )

        assertEquals(2, store.reportsByPost.getValue("1").size)
        assertEquals(2, outcome.newReports.size)
    }

    @Test
    fun domPostThenNumericPostOfSameTextMergesIntoSingleRowKeepingFirstSeenAt() = runTest {
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)

        recorder.record(
            listOf(post("dom:abc123", checkpointText, approx = true)),
            t0,
            t0.plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW_DOM,
            "done",
        )
        val firstSeen = store.posts.getValue("dom:abc123").firstSeenAt

        val second = recorder.record(
            listOf(post("789", checkpointText, createdAt = t0.plusSeconds(30))),
            t0.plusSeconds(60),
            t0.plusSeconds(65),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )

        assertEquals(1, store.posts.size)
        assertNull(store.posts["dom:abc123"])
        assertEquals("789", store.posts.keys.single())
        assertEquals(firstSeen, store.posts.getValue("789").firstSeenAt)
        assertEquals(1, second.updated)
        assertEquals(0, second.new)
    }

    @Test
    fun numericPostThenDomPostOfSameTextIsNotDuplicated() = runTest {
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)

        recorder.record(
            listOf(post("789", checkpointText)),
            t0,
            t0.plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )
        val second = recorder.record(
            listOf(post("dom:abc123", checkpointText, createdAt = t0.plusSeconds(30), approx = true)),
            t0.plusSeconds(60),
            t0.plusSeconds(65),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW_DOM,
            "done",
        )

        assertEquals(1, store.posts.size)
        assertEquals("789", store.posts.keys.single())
        assertEquals(t0.plusSeconds(65).toEpochMilli(), store.posts.getValue("789").lastSeenAt)
        assertEquals(0, second.new)
        assertEquals(0, second.updated)
    }

    @Test
    fun emptyPostListIsRecordedAsFailedNoDataAndLeavesStoreUntouched() = runTest {
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)

        val outcome = recorder.record(
            emptyList(),
            t0,
            t0.plusSeconds(5),
            ScanTrigger.BACKGROUND,
            CollectorKind.NONE,
            "no data",
        )

        assertEquals(ScrapeStatus.FAILED_NO_DATA, outcome.status)
        assertEquals(0, outcome.seen)
        assertTrue(store.posts.isEmpty())
        assertTrue(store.sightings.isEmpty())
        assertEquals(1, store.scrapes.size)
        assertEquals(ScrapeStatus.FAILED_NO_DATA.name, store.scrapes.single().status)
    }

    @Test
    fun emptyPostListWithExplicitFailureUsesThatStatus() = runTest {
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)

        val outcome = recorder.record(
            emptyList(),
            t0,
            t0.plusSeconds(5),
            ScanTrigger.BACKGROUND,
            CollectorKind.NONE,
            "network error",
            failure = ScrapeStatus.FAILED_NETWORK,
        )

        assertEquals(ScrapeStatus.FAILED_NETWORK, outcome.status)
    }

    @Test
    fun cancelledScanWithPostsStillRecordsThemButKeepsCancelledStatus() = runTest {
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)

        val outcome = recorder.record(
            listOf(post("1", checkpointText)),
            t0,
            t0.plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "cancelled",
            failure = ScrapeStatus.CANCELLED,
        )

        assertEquals(ScrapeStatus.CANCELLED, outcome.status)
        assertEquals(1, outcome.new)
        assertEquals(1, store.posts.size)
    }

    @Test
    fun duplicatePostIdInOneScanIsDedupedKeepingLongestText() = runTest {
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)

        val outcome = recorder.record(
            listOf(
                post("1", "short text"),
                post("1", "much longer text that should win the dedupe"),
            ),
            t0,
            t0.plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )

        assertEquals(1, outcome.new)
        assertEquals(1, outcome.seen)
        assertEquals(1, store.posts.size)
        assertEquals(1, store.sightings.count { it.postId == "1" })
        assertEquals("much longer text that should win the dedupe", store.posts.getValue("1").text)
    }

    @Test
    fun internalDuplicateDoesNotSuppressGapRuleOnGenuineZeroOverlap() = runTest {
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)

        recorder.record(
            listOf(post("1", checkpointText)),
            t0,
            t0.plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )

        val scanTime = t0.plus(2, ChronoUnit.HOURS)
        val duplicatedText = "New post text — appears twice in the same scan"
        val outcome = recorder.record(
            listOf(post("2", duplicatedText, createdAt = scanTime), post("2", duplicatedText, createdAt = scanTime)),
            scanTime,
            scanTime.plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )

        assertEquals(ScrapeStatus.OK_WITH_GAP, outcome.status)
        assertEquals(1, outcome.new)
        assertEquals(1, outcome.seen)
        assertEquals(1, store.sightings.count { it.postId == "2" })
        assertTrue(store.posts.getValue("2").gapBefore)
    }

    @Test
    fun domAndNumericOfSameTextInOneScanCollapseToSingleNumericRow() = runTest {
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)

        val outcome = recorder.record(
            listOf(
                post("dom:abc123", checkpointText, approx = true),
                post("789", checkpointText, createdAt = t0.plusSeconds(30)),
            ),
            t0,
            t0.plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )

        assertEquals(1, outcome.new)
        assertEquals(1, outcome.seen)
        assertEquals(1, store.posts.size)
        assertEquals("789", store.posts.keys.single())
        assertEquals(1, store.sightings.size)
    }

    @Test
    fun twoDistinctNumericPostsWithIdenticalTextInOneScanAreBothKept() = runTest {
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)

        val outcome = recorder.record(
            listOf(post("100", checkpointText), post("200", checkpointText)),
            t0,
            t0.plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )

        assertEquals(2, outcome.new)
        assertEquals(2, outcome.seen)
        assertEquals(2, store.posts.size)
        assertTrue(store.posts.keys.containsAll(listOf("100", "200")))
    }

    @Test
    fun laterNumericPostWithSameTextAsExistingNumericPostIsStoredAsNewNotMerged() = runTest {
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)

        recorder.record(
            listOf(post("A", checkpointText)),
            t0,
            t0.plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )
        val originalA = store.posts.getValue("A")

        val second = recorder.record(
            listOf(post("B", checkpointText, createdAt = t0.plusSeconds(30))),
            t0.plusSeconds(60),
            t0.plusSeconds(65),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )

        assertEquals(1, second.new)
        assertEquals(0, second.updated)
        assertEquals(2, store.posts.size)
        assertEquals(originalA, store.posts.getValue("A"))
    }

    @Test
    fun newReportTimeFallsBackToPostCreatedAtWhenNoReportedTime() = runTest {
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)
        val createdAt = t0.plusSeconds(120)

        val outcome = recorder.record(
            listOf(post("1", "🛑 CHECKPOINT – Trig Road\nAt the top", createdAt = createdAt)),
            createdAt,
            createdAt.plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )

        val report = outcome.newReports.single()
        assertEquals(ReportType.CHECKPOINT, report.type)
        assertEquals(createdAt, report.at)
    }

    // --- photos ---------------------------------------------------------------------------------
    //
    // A post's photo arrives as a signed, expiring URL, and the downloaded copy is written outside
    // this transaction by `ImageStore`. So the rule is one-way: the recorder may learn where a
    // photo lives, and may never forget where one already is.

    @Test
    fun aNewPostKeepsThePhotoItArrivedWith() = runTest {
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)

        recorder.record(
            listOf(post("1", checkpointText, imageUrl = "https://scontent.test.fbcdn.net/photo.jpg")),
            t0,
            t0.plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )

        assertEquals("https://scontent.test.fbcdn.net/photo.jpg", store.posts.getValue("1").imageUrl)
        assertNull(store.posts.getValue("1").imagePath)
    }

    @Test
    fun aLaterSightingFillsInAPhotoTheFirstScanDidNotSee() = runTest {
        // The initial HTML block often has no attachment on it while the feed's copy of the same
        // story does, so the second scan is where a photo turns up for a post already stored.
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)
        recorder.record(listOf(post("1", checkpointText)), t0, t0.plusSeconds(5), ScanTrigger.FOREGROUND, CollectorKind.WEBVIEW, "done")

        recorder.record(
            listOf(post("1", checkpointText, imageUrl = "https://scontent.test.fbcdn.net/photo.jpg")),
            t0.plusSeconds(600),
            t0.plusSeconds(605),
            ScanTrigger.BACKGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )

        assertEquals("https://scontent.test.fbcdn.net/photo.jpg", store.posts.getValue("1").imageUrl)
    }

    @Test
    fun aStoredPhotoIsNeverReplacedByALaterScansCopyOfTheSameUrl() = runTest {
        // Facebook re-signs these URLs, so the "new" one is the same photo with a different
        // signature. Taking it would invalidate nothing and re-download everything.
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)
        recorder.record(
            listOf(post("1", checkpointText, imageUrl = "https://scontent.test.fbcdn.net/photo.jpg?sig=first")),
            t0,
            t0.plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )
        // ImageStore has been and gone: the copy is on disk.
        store.posts["1"] = store.posts.getValue("1").copy(imagePath = "abc.img")

        recorder.record(
            listOf(post("1", checkpointText, imageUrl = "https://scontent.test.fbcdn.net/photo.jpg?sig=second")),
            t0.plusSeconds(600),
            t0.plusSeconds(605),
            ScanTrigger.BACKGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )

        assertEquals(
            "https://scontent.test.fbcdn.net/photo.jpg?sig=first",
            store.posts.getValue("1").imageUrl,
        )
    }

    @Test
    fun aPhotoNotYetDownloadedFollowsTheNewestAddress_becauseTheOldOneExpires() = runTest {
        // The signature that makes the second URL "the same photo" is also what makes the first
        // one stop working. A phone that was offline when the post first appeared would otherwise
        // hold a dead address for ever and ignore every live one it was later handed.
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)
        recorder.record(
            listOf(post("1", checkpointText, imageUrl = "https://scontent.test.fbcdn.net/photo.jpg?sig=first")),
            t0,
            t0.plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )

        recorder.record(
            listOf(post("1", checkpointText, imageUrl = "https://scontent.test.fbcdn.net/photo.jpg?sig=second")),
            t0.plusSeconds(600),
            t0.plusSeconds(605),
            ScanTrigger.BACKGROUND,
            CollectorKind.RELAY,
            "RELAY",
        )
        // ...and a later sighting with no photo at all takes nothing away.
        recorder.record(
            listOf(post("1", checkpointText)),
            t0.plusSeconds(1200),
            t0.plusSeconds(1205),
            ScanTrigger.BACKGROUND,
            CollectorKind.HTTP,
            "done",
        )

        assertEquals(
            "https://scontent.test.fbcdn.net/photo.jpg?sig=second",
            store.posts.getValue("1").imageUrl,
        )
    }

    @Test
    fun anEditedPostKeepsItsDownloadedPhoto() = runTest {
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)
        recorder.record(
            listOf(post("1", checkpointText, imageUrl = "https://scontent.test.fbcdn.net/photo.jpg")),
            t0,
            t0.plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )
        // Stand in for ImageStore, which writes this column after the scan's transaction closes.
        store.posts["1"] = store.posts.getValue("1").copy(imagePath = "/files/images/abc.jpg")

        recorder.record(
            listOf(post("1", "$checkpointText\nUPDATE: gone now", imageUrl = "https://scontent.test.fbcdn.net/photo.jpg")),
            t0.plusSeconds(600),
            t0.plusSeconds(605),
            ScanTrigger.BACKGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )

        assertEquals("/files/images/abc.jpg", store.posts.getValue("1").imagePath)
    }

    @Test
    fun aDownloadedPhotoCrossesTheDomToNumericBridge() = runTest {
        // The page widget stores a post under a dom: id and its photo is downloaded against that
        // row. When the JSON feed later produces the real numeric post, the file must come with
        // it: the row is replaced, and a file nothing points at is a file the sweep deletes.
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)
        recorder.record(
            listOf(post("dom:abc123", checkpointText, approx = true, imageUrl = "https://scontent.test.fbcdn.net/photo.jpg")),
            t0,
            t0.plusSeconds(5),
            ScanTrigger.FOREGROUND,
            CollectorKind.PLUGIN,
            "done",
        )
        store.posts["dom:abc123"] = store.posts.getValue("dom:abc123").copy(imagePath = "/files/images/abc.jpg")

        recorder.record(
            listOf(post("99", checkpointText)),
            t0.plusSeconds(600),
            t0.plusSeconds(605),
            ScanTrigger.BACKGROUND,
            CollectorKind.WEBVIEW,
            "done",
        )

        assertNull(store.posts["dom:abc123"])
        val bridged = store.posts.getValue("99")
        assertEquals("/files/images/abc.jpg", bridged.imagePath)
        assertEquals("https://scontent.test.fbcdn.net/photo.jpg", bridged.imageUrl)
    }

    @Test
    fun aPhotoFoundUnderADifferentIdIsKeptOnTheRowWeAlreadyHave() = runTest {
        // The same post seen again through another collector, which this time carried the photo.
        val store = FakeScrapeStore()
        val recorder = ScrapeRecorder(store)
        recorder.record(listOf(post("1", checkpointText)), t0, t0.plusSeconds(5), ScanTrigger.FOREGROUND, CollectorKind.WEBVIEW, "done")

        recorder.record(
            listOf(post("dom:abc123", checkpointText, approx = true, imageUrl = "https://scontent.test.fbcdn.net/photo.jpg")),
            t0.plusSeconds(600),
            t0.plusSeconds(605),
            ScanTrigger.FOREGROUND,
            CollectorKind.PLUGIN,
            "done",
        )

        assertNull(store.posts["dom:abc123"])
        assertEquals("https://scontent.test.fbcdn.net/photo.jpg", store.posts.getValue("1").imageUrl)
    }
}
