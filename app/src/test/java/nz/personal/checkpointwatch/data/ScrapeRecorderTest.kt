package nz.personal.checkpointwatch.data

import kotlinx.coroutines.test.runTest
import nz.personal.checkpointwatch.collect.RawPost
import nz.personal.checkpointwatch.model.ReportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    ) = RawPost(postId = id, createdAt = createdAt, createdAtApprox = approx, text = text, url = url)

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
}
