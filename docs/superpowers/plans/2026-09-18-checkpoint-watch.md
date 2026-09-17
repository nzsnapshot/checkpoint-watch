# Checkpoint Watch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A sideloadable GrapheneOS/Android app that collects road reports from the public Facebook page `CheckpointNZ` with a hidden WebView, stores them in Room, and presents them in a premium Compose UI, with optional background updates and notifications.

**Architecture:** A hidden WebView runs `collector.js`, which forwards Facebook's own JSON responses to Kotlin. Pure-Kotlin units (`FeedJsonExtractor`, `ReportParser`, `ReportedTimeResolver`, `ScrapeRecorder`, `NotificationPlanner`, `AreaLinker`, `TimeFormat`) do all interpretation and are JVM unit-tested against fixtures captured from the live page on 2026-09-18. `ScanCoordinator` serialises scans for both the foreground UI and the WorkManager `ScanWorker`.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Room + KSP, kotlinx.serialization, coroutines, AndroidX WebKit, WorkManager, DataStore. Gradle wrapper 9.3.1, AGP 9.3.2 (built-in Kotlin), JDK 17, compileSdk 36.

**Spec:** `docs/superpowers/specs/2026-09-18-checkpoint-watch-design.md`

## Global Constraints

- Application ID and namespace `nz.personal.checkpointwatch`; label "Checkpoint Watch".
- `minSdk` 29, `compileSdk`/`targetSdk` 36. Single module `app`. `java.time` everywhere (no desugaring needed at minSdk 29).
- No Google Play services, Firebase, analytics, or any network host other than `www.facebook.com`.
- Manifest permissions: `INTERNET`, `POST_NOTIFICATIONS`. WorkManager merges its own.
- Time zone for all user-facing times and the time resolver: `Pacific/Auckland`.
- Page URL constant: `https://www.facebook.com/CheckpointNZ`. Post URL: `https://www.facebook.com/CheckpointNZ/posts/<postId>`.
- Desktop UA constant: `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36`.
- JS→Kotlin only via `WebViewCompat.addWebMessageListener` limited to `https://www.facebook.com`. Never `addJavascriptInterface`.
- Copy: NZ English, calm, never "active"/"confirmed".
- Library versions (latest stable on 2026-09-18): compose-bom 2026.09.00, room 2.8.5, work 2.11.2, webkit 1.17.0, datastore 1.2.1, lifecycle 2.11.0, activity-compose 1.13.0, navigation-compose 2.10.1, core-ktx 1.19.0, kotlin 2.4.20, ksp 2.3.12, serialization-json 1.11.0, coroutines-test 1.11.0, junit 4.13.2. If a library's AAR metadata demands compileSdk > 36, step that library down one minor version rather than raising compileSdk (platform 37 is not installed).
- Every commit message ends with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Verification command for every task: `./gradlew testDebugUnitTest` (plus `assembleDebug` where Android code changed).

## File map

```
settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml, local.properties (untracked)
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/assets/collector.js
app/src/main/res/...                       icons, strings, themes
app/src/main/java/nz/personal/checkpointwatch/
  App.kt, AppContainer.kt, Constants.kt
  model/ReportType.kt
  parse/ReportParser.kt, ReportedTimeResolver.kt
  collect/RawPost.kt, FeedJsonExtractor.kt, DomPostExtractor.kt, CollectResult.kt,
          FeedCollector.kt, WebViewHost.kt, HttpLatestFetcher.kt
  data/Entities.kt, Daos.kt, AppDatabase.kt, ScrapeStore.kt, RoomScrapeStore.kt, ScrapeRecorder.kt
  scan/ScanCoordinator.kt, ScanWorker.kt, BackgroundScheduler.kt
  notify/NotificationPlanner.kt, Notifier.kt
  settings/SettingsStore.kt
  ui/MainActivity.kt, TypeStyle.kt, TimeFormat.kt, AreaLinker.kt
  ui/theme/Color.kt, Type.kt, Theme.kt
  ui/home/HomeViewModel.kt, HomeScreen.kt, SummaryHeader.kt, StatusBanner.kt, FilterBar.kt, ReportCard.kt, ListDecor.kt
  ui/settings/SettingsViewModel.kt, SettingsScreen.kt
app/src/test/java/nz/personal/checkpointwatch/...   one test class per pure unit
app/src/test/resources/fixtures/graphql_1.txt, graphql_2.txt, graphql_3.txt, initial_block.json, page_min.html
```

---

### Task 1: Project scaffold that builds

**Files:** all Gradle files, manifest, `App.kt`, `Constants.kt`, a placeholder-free `MainActivity` showing the app name, launcher icon resources, `.gitignore` (exists).

**Produces:** `./gradlew assembleDebug` yields `app/build/outputs/apk/debug/app-debug.apk`; `Constants.PAGE_URL`, `Constants.DESKTOP_UA`, `Constants.NZ: ZoneId`, `Constants.postUrl(id)`.

- [ ] Step 1: Bootstrap the wrapper from the cached distribution: `~/.gradle/wrapper/dists/gradle-9.3.1-bin/*/gradle-9.3.1/bin/gradle wrapper --gradle-version 9.3.1` in an empty settings project.
- [ ] Step 2: Write `gradle/libs.versions.toml` with the versions in Global Constraints; plugins: `com.android.application` (AGP 9.3.2, built-in Kotlin — do **not** apply `org.jetbrains.kotlin.android`), `org.jetbrains.kotlin.plugin.compose`, `org.jetbrains.kotlin.plugin.serialization`, `com.google.devtools.ksp`.
- [ ] Step 3: `app/build.gradle.kts`: namespace/appId, SDK levels, `buildFeatures { compose = true }`, `testOptions.unitTests.isReturnDefaultValues = true`, release build type `isMinifyEnabled = true` with default proguard + debug signing (personal sideload), dependencies per File map, `ksp(room-compiler)`, room schema dir `app/schemas`.
- [ ] Step 4: `local.properties` with `sdk.dir=~/Library/Android/sdk`.
- [ ] Step 5: Manifest: permissions, `App`, single exported `MainActivity` (`launchMode=singleTop`, `windowSoftInputMode=adjustResize`), `android:enableOnBackInvokedCallback="true"`, `android:allowBackup="false"`, `android:usesCleartextTraffic="false"`.
- [ ] Step 6: Adaptive icon: `ic_launcher_background` (#0B1220), foreground vector = amber (#FFB020) beacon (filled circle r=10 at centre with two concentric arcs) inside the 66 dp safe zone, plus `monochrome` layer.
- [ ] Step 7: Run `./gradlew assembleDebug testDebugUnitTest`. Expected: BUILD SUCCESSFUL. Resolve version incompatibilities here per Global Constraints.
- [ ] Step 8: Commit `chore: scaffold Android project`.

### Task 2: Report model, parser and time resolver

**Files:** `model/ReportType.kt`, `parse/ReportParser.kt`, `parse/ReportedTimeResolver.kt`; tests `parse/ReportParserTest.kt`, `parse/ReportedTimeResolverTest.kt`.

**Produces:**

```kotlin
enum class ReportType { CHECKPOINT, POLICE_PRESENCE, CRASH, SPEED_CAMERA, OTHER }

data class ParsedReport(
    val indexInPost: Int, val type: ReportType, val typeLabel: String,
    val road: String?, val suburb: String?, val details: String,
    val reportedTimeText: String?, val reportedAt: Instant?, val source: String?,
)
object ReportParser { fun parse(text: String, createdAt: Instant): List<ParsedReport> }
object ReportedTimeResolver { fun resolve(timeText: String, createdAt: Instant, zone: ZoneId = Constants.NZ): Instant? }
```

- [ ] Step 1: Write failing tests.

```kotlin
class ReportedTimeResolverTest {
    private fun nz(s: String) = ZonedDateTime.parse(s).toInstant()
    @Test fun sameEvening() = assertEquals(nz("2026-09-17T23:55:00+12:00"),
        ReportedTimeResolver.resolve("11:55PM", Instant.ofEpochSecond(1789646182)))
    @Test fun justAfterMidnightStaysToday() = assertEquals(nz("2026-09-18T00:00:00+12:00"),
        ReportedTimeResolver.resolve("12:00AM", Instant.ofEpochSecond(1789646506)))
    @Test fun lateReportCrossesMidnightBackwards() = assertEquals(nz("2026-09-17T23:50:00+12:00"),
        ReportedTimeResolver.resolve("11:50PM", nz("2026-09-18T00:05:00+12:00")))
    @Test fun slightlyAheadOfPostIsAllowed() = assertEquals(nz("2026-09-18T00:10:00+12:00"),
        ReportedTimeResolver.resolve("12:10AM", nz("2026-09-18T00:01:00+12:00")))
    @Test fun trailingNoteIgnored() = assertEquals(nz("2026-09-17T21:30:00+12:00"),
        ReportedTimeResolver.resolve("9:30PM (Pictured) ", Instant.ofEpochSecond(1789637686)))
    @Test fun dotSeparatorAndSpaces() = assertEquals(nz("2026-09-17T20:14:00+12:00"),
        ReportedTimeResolver.resolve("8.14 pm", Instant.ofEpochSecond(1789633047)))
    @Test fun hourOnly() = assertEquals(nz("2026-09-17T21:00:00+12:00"),
        ReportedTimeResolver.resolve("9pm", Instant.ofEpochSecond(1789637686)))
    @Test fun daylightSavingGapDoesNotThrow() =   // 2026-09-27 02:30 does not exist in NZ
        assertNotNull(ReportedTimeResolver.resolve("2:30AM", nz("2026-09-27T03:20:00+13:00")))
    @Test fun garbage() = assertNull(ReportedTimeResolver.resolve("soon", Instant.now()))
    @Test fun invalidHour() = assertNull(ReportedTimeResolver.resolve("25:00PM", Instant.now()))
}
```

```kotlin
class ReportParserTest {
    private val t = Instant.ofEpochSecond(1789637686)
    @Test fun simpleCheckpoint() {
        val r = ReportParser.parse("🛑 CHECKPOINT – Lincoln Road, HENDERSON\nAfter the off-ramp coming from the motorway\nTime: 11:55PM", Instant.ofEpochSecond(1789646182)).single()
        assertEquals(ReportType.CHECKPOINT, r.type); assertEquals("Lincoln Road", r.road); assertEquals("HENDERSON", r.suburb)
        assertEquals("After the off-ramp coming from the motorway", r.details); assertEquals("11:55PM", r.reportedTimeText)
        assertNotNull(r.reportedAt); assertNull(r.source)
    }
    @Test fun policePresence() { val r = ReportParser.parse("⚠️ HEAVY POLICE PRESENCE – Roberton Road, AVONDALE\nMassive police presence reported\nTime: 12:00AM", t).single()
        assertEquals(ReportType.POLICE_PRESENCE, r.type); assertEquals("HEAVY POLICE PRESENCE", r.typeLabel); assertEquals("AVONDALE", r.suburb) }
    @Test fun crash() = assertEquals(ReportType.CRASH, ReportParser.parse("⚠️ CRASH – Pakuranga Road, PAKURANGA\nOutside St Kentigern College.\nTime: 8:15PM", t).single().type)
    @Test fun speedCameraWithTwoEmoji() { val r = ReportParser.parse("📷⚠️ SPEED CAMERA – Hibiscus Coast Highway\nSpeed camera van\nTime: 8:14PM", t).single()
        assertEquals(ReportType.SPEED_CAMERA, r.type); assertEquals("Hibiscus Coast Highway", r.road); assertNull(r.suburb) }
    @Test fun noSuburb() { val r = ReportParser.parse("🛑 CHECKPOINT – Trig Road\nAt the top\nTime: 9:11PM", t).single(); assertEquals("Trig Road", r.road); assertNull(r.suburb) }
    @Test fun multiWordSuburbAndCommaInRoad() { val r = ReportParser.parse("🛑 CHECKPOINT – Auckland International Airport, MANGERE\nNear the pick-up", t).single()
        assertEquals("Auckland International Airport", r.road); assertEquals("MANGERE", r.suburb); assertNull(r.reportedTimeText) }
    @Test fun multiReportPost() {
        val rs = ReportParser.parse("🛑 CHECKPOINT – Grafton On-Ramp\nTime: 9:30PM (Pictured) \n\n🛑 CHECKPOINT – Stancombe Road, FLAT BUSH\nNear the temple\nTime: 9:30PM", t)
        assertEquals(2, rs.size); assertEquals(listOf(0, 1), rs.map { it.indexInPost })
        assertEquals("Grafton On-Ramp", rs[0].road); assertEquals("", rs[0].details)
        assertEquals("FLAT BUSH", rs[1].suburb); assertEquals("Near the temple", rs[1].details)
    }
    @Test fun sourceLine() = assertEquals("WhatsApp subscriber", ReportParser.parse("🛑 CHECKPOINT – Karaka Road, KARAKA\nBy BP\nTime: 9:59PM\n\nFrom: WhatsApp subscriber", t).single().source)
    @Test fun hyphenSeparator() = assertEquals("Queen Street", ReportParser.parse("CHECKPOINT - Queen Street, CBD", t).single().road)
    @Test fun unknownHeaderIsOther() { val r = ReportParser.parse("🚧 ROAD CLOSED – Dominion Road, MT EDEN\nUntil 5am", t).single(); assertEquals(ReportType.OTHER, r.type); assertEquals("ROAD CLOSED", r.typeLabel) }
    @Test fun headerlessTextKeptAsOther() { val r = ReportParser.parse("Win 3 months free rego! Subscribe now", t).single()
        assertEquals(ReportType.OTHER, r.type); assertEquals("Win 3 months free rego! Subscribe now", r.details); assertNull(r.road) }
    @Test fun preambleBeforeFirstHeaderGoesToFirstReportDetails() { val r = ReportParser.parse("UPDATE\n🛑 CHECKPOINT – Trig Road\nStill there", t).single(); assertEquals("UPDATE\nStill there", r.details) }
    @Test fun blankText() = assertTrue(ReportParser.parse("  \n ", t).isEmpty())
}
```

- [ ] Step 2: Run `./gradlew testDebugUnitTest --tests '*parse*'` → FAIL (unresolved references).
- [ ] Step 3: Implement.

```kotlin
object ReportedTimeResolver {
    private val RX = Regex("""(\d{1,2})(?:[:.](\d{2}))?\s*([AaPp])\.?\s*[Mm]""")
    fun resolve(timeText: String, createdAt: Instant, zone: ZoneId = Constants.NZ): Instant? {
        val m = RX.find(timeText) ?: return null
        val h12 = m.groupValues[1].toInt(); val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        if (h12 !in 1..12 || min !in 0..59) return null
        val pm = m.groupValues[3].equals("p", ignoreCase = true)
        val hour = (h12 % 12) + if (pm) 12 else 0
        val created = createdAt.atZone(zone)
        var candidate = ZonedDateTime.of(created.toLocalDate(), LocalTime.of(hour, min), zone)
        if (candidate.toInstant().isAfter(createdAt.plus(Duration.ofMinutes(15)))) candidate = candidate.minusDays(1)
        return candidate.toInstant()
    }
}
```

```kotlin
object ReportParser {
    private val HEADER = Regex("""^[^\p{L}]*(\p{Lu}[\p{Lu} /&'-]*?\p{Lu})\s+[–—-]\s+(.+)$""")
    private val TIME = Regex("""^time\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val FROM = Regex("""^from\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val TRAILING_NOTE = Regex("""\s*\([^)]*\)\s*$""")

    private class Draft(val label: String, val location: String) { val details = mutableListOf<String>(); var time: String? = null; var source: String? = null }

    fun parse(text: String, createdAt: Instant): List<ParsedReport> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        val drafts = mutableListOf<Draft>(); val preamble = mutableListOf<String>()
        for (line in lines) {
            val h = HEADER.find(line)
            if (h != null) { drafts += Draft(h.groupValues[1].trim(), h.groupValues[2].trim()).also { if (drafts.isEmpty()) it.details += preamble }; continue }
            val d = drafts.lastOrNull()
            if (d == null) { preamble += line; continue }
            TIME.find(line)?.let { d.time = it.groupValues[1].trim(); null } ?: FROM.find(line)?.let { d.source = it.groupValues[1].trim(); null } ?: run { d.details += line }
        }
        if (drafts.isEmpty()) return listOf(ParsedReport(0, ReportType.OTHER, "", null, null, lines.joinToString("\n"), null, null, null))
        return drafts.mapIndexed { i, d ->
            val loc = d.location.replace(TRAILING_NOTE, "")
            val comma = loc.lastIndexOf(',')
            val tail = if (comma >= 0) loc.substring(comma + 1).trim() else ""
            val isSuburb = tail.any { it.isLetter() } && tail == tail.uppercase()
            ParsedReport(i, typeOf(d.label), d.label,
                road = (if (isSuburb) loc.substring(0, comma) else loc).trim().ifEmpty { null },
                suburb = if (isSuburb) tail else null,
                details = d.details.joinToString("\n"), reportedTimeText = d.time,
                reportedAt = d.time?.let { ReportedTimeResolver.resolve(it, createdAt) }, source = d.source)
        }
    }
    private fun typeOf(label: String) = when {
        "CHECKPOINT" in label -> ReportType.CHECKPOINT
        "POLICE" in label -> ReportType.POLICE_PRESENCE
        "CRASH" in label -> ReportType.CRASH
        "CAMERA" in label -> ReportType.SPEED_CAMERA
        else -> ReportType.OTHER
    }
}
```

(The `TIME…?: FROM…?: run` chain must be written as a plain `when`/`if` ladder in the real file — clarity over cleverness: if TIME matches set time; else if FROM matches set source; else add to details. `reportedTimeText` keeps the raw value minus any trailing parenthetical.)

- [ ] Step 4: Run tests → PASS. Step 5: Commit `feat: report parser and reported-time resolver`.

### Task 3: Feed extraction (JSON, DOM fallback, HTML)

**Files:** `collect/RawPost.kt`, `collect/FeedJsonExtractor.kt`, `collect/DomPostExtractor.kt`; fixtures under `app/src/test/resources/fixtures/`; tests `collect/FeedJsonExtractorTest.kt`, `collect/DomPostExtractorTest.kt`.

**Produces:**

```kotlin
data class RawPost(val postId: String, val createdAt: Instant, val createdAtApprox: Boolean, val text: String, val url: String)
data class DomPost(val text: String, val age: String, val link: String?)
object FeedJsonExtractor {
    fun extract(chunks: List<String>): List<RawPost>          // newest first
    fun jsonBlocksFromHtml(html: String): List<String>         // <script type="application/json"> bodies containing "post_id"
}
object DomPostExtractor { fun extract(posts: List<DomPost>, scanTime: Instant): List<RawPost>; fun textHash(text: String): String }
```

Fixtures are the pruned real responses captured on 2026-09-18 (structure `data.node.timeline_list_feed_units.edges[].node{post_id,creation_time,comet_sections…message.text}` and streamed follow-up lines `data.node{…}`), three graphql files with three newline-separated JSON documents each, and `initial_block.json` (`require[[[{__bbox:{require:[[[{__bbox:{result:{data:{user:{timeline_list_feed_units…`). `page_min.html` wraps `initial_block.json` in `<script type="application/json" data-sjs>` next to an unrelated JSON script.

- [ ] Step 1: Tests: graphql_1 yields 3 posts with ids `1614130810501801, 1614083877173161, 1614056027175946` and creation times `1789646182, 1789642021, 1789639287`, text starting `🛑 CHECKPOINT – Lincoln Road`; all four fixtures together yield 10 unique posts sorted newest first with first id `1614134890501393`; duplicate chunks collapse; a malformed line between valid lines is skipped; chunk with `for (;;);` prefix parses; object with `post_id` but no message is dropped; `jsonBlocksFromHtml(page_min.html)` returns exactly 1 block and extracting it yields post `1614134890501393`; URL format. DOM: `"22m"`→22 min, `"2h"`, `"1d"`, `"Just now"`→0, unknown age→0 with approx flag; id is `dom:` + 16 hex and stable under whitespace changes; blank text dropped.
- [ ] Step 2: Run → FAIL.
- [ ] Step 3: Implement. JSON: `Json.parseToJsonElement`; for each chunk strip a leading `for (;;);`, try whole chunk, on failure try per line. Walk: at a `JsonObject` with string `post_id`, find `creation_time` (direct, else first in subtree) and first `message.text` in subtree (DFS, key order); if both present emit and do not descend; otherwise descend. Dedupe by id keeping longest text. Sort by `createdAt` desc. `textHash` = SHA-1 hex of text with all whitespace runs collapsed to one space, trimmed, lower-cased.
- [ ] Step 4: Run → PASS. Step 5: Commit `feat: feed extractors with live fixtures`.

### Task 4: Database and ScrapeRecorder

**Files:** `data/Entities.kt`, `data/Daos.kt`, `data/AppDatabase.kt`, `data/ScrapeStore.kt`, `data/RoomScrapeStore.kt`, `data/ScrapeRecorder.kt`; test `data/ScrapeRecorderTest.kt` with `FakeScrapeStore`.

**Produces:**

```kotlin
@Entity(tableName="posts") data class PostEntity(@PrimaryKey val postId: String, val url: String, val text: String, val textHash: String,
    val createdAt: Long, val createdAtApprox: Boolean, val firstSeenAt: Long, val lastSeenAt: Long, val editedAt: Long?, val gapBefore: Boolean)
@Entity(tableName="reports", FK→posts CASCADE, indices postId/suburb/type) data class ReportEntity(@PrimaryKey(autoGenerate=true) val id: Long = 0, val postId: String,
    val indexInPost: Int, val type: String, val typeLabel: String, val road: String?, val suburb: String?, val details: String,
    val reportedTimeText: String?, val reportedAt: Long?, val source: String?)
@Entity(tableName="post_revisions") data class PostRevisionEntity(id, postId, text, replacedAt)
@Entity(tableName="scrapes") data class ScrapeEntity(id, startedAt, finishedAt, status, endReason, trigger, collector, postsSeen, postsNew, postsUpdated)
@Entity(tableName="scrape_sightings", primaryKeys=[scrapeId, postId]) data class SightingEntity(scrapeId, postId)
data class ReportRow(/* report columns */ …, val postCreatedAt: Long, val postCreatedAtApprox: Boolean, val postUrl: String, val postText: String, val gapBefore: Boolean, val firstSeenAt: Long)

enum class ScrapeStatus { OK, OK_WITH_GAP, FAILED_NETWORK, FAILED_NO_DATA, CANCELLED }
enum class ScanTrigger { FOREGROUND, BACKGROUND }
enum class CollectorKind { WEBVIEW, WEBVIEW_DOM, HTTP, NONE }

interface ScrapeStore {
    suspend fun <T> inTransaction(block: suspend () -> T): T
    suspend fun postCount(): Int
    suspend fun findPost(postId: String): PostEntity?
    suspend fun findByTextHash(hash: String, fromMs: Long, toMs: Long): PostEntity?
    suspend fun insertPost(p: PostEntity); suspend fun updatePost(p: PostEntity); suspend fun deletePost(postId: String)
    suspend fun insertRevision(r: PostRevisionEntity)
    suspend fun replaceReports(postId: String, reports: List<ReportEntity>)
    suspend fun insertScrape(s: ScrapeEntity): Long
    suspend fun insertSighting(s: SightingEntity)
}
data class NewReport(val type: ReportType, val typeLabel: String, val road: String?, val suburb: String?, val at: Instant)
data class ScrapeOutcome(val scrapeId: Long, val status: ScrapeStatus, val seen: Int, val new: Int, val updated: Int, val newReports: List<NewReport>)
class ScrapeRecorder(private val store: ScrapeStore) {
    suspend fun record(posts: List<RawPost>, startedAt: Instant, finishedAt: Instant, trigger: ScanTrigger,
                       collector: CollectorKind, endReason: String, failure: ScrapeStatus? = null): ScrapeOutcome
}
```

DAO read side: `ReportDao.observeRows(limit=600): Flow<List<ReportRow>>` (join, `ORDER BY posts.createdAt DESC, reports.indexInPost ASC`), `observeSuburbs(): Flow<List<String>>`, `ScrapeDao.observeRecent(n): Flow<List<ScrapeEntity>>`, `ScrapeDao.lastFinishedAt(): Long?`.

Recorder rules (spec §ScrapeRecorder): one transaction; `hadPosts = postCount() > 0` before inserting; match by id then by hash within ±48 h; `dom:` row matched by a numeric-id post → delete dom row, insert real post keeping `firstSeenAt`, count as updated; text change → revision + re-parse + `editedAt`; gap rule sets `gapBefore` on this scan's oldest post when `hadPosts && matches == 0 && posts.isNotEmpty()`; empty `posts` → status `failure ?: FAILED_NO_DATA`, nothing else written but the scrape row; `failure == CANCELLED` with posts still records the posts and keeps status `CANCELLED`.

- [ ] Step 1: Tests with `FakeScrapeStore` (maps; `inTransaction` just runs the block): first scan → all new, status OK, no gap flag; rescan same posts → new=0, `lastSeenAt` advanced, sightings 2 per post; overlap of 1 post → OK, no gap; zero overlap with existing data → `OK_WITH_GAP` and only the oldest new post has `gapBefore`; edited text → revision stored, reports replaced, `updated=1`; multi-report post yields 2 `ReportEntity` and 2 `NewReport`; DOM then JSON of same text → single post with numeric id, `firstSeenAt` preserved; JSON then DOM of same text → no duplicate; empty list → FAILED_NO_DATA and store unchanged; empty list with `failure=FAILED_NETWORK` → that status; `NewReport.at` is `reportedAt ?: createdAt`.
- [ ] Step 2: FAIL. Step 3: Implement entities, DAOs, `AppDatabase` (version 1, `exportSchema = true`), `RoomScrapeStore` (uses `db.withTransaction`), recorder. Step 4: PASS + `assembleDebug` (KSP validates queries). Step 5: Commit `feat: Room schema and scrape recorder`.

### Task 5: Collector (collector.js, FeedCollector, hosts, HTTP fetcher)

**Files:** `assets/collector.js`, `collect/CollectResult.kt`, `collect/WebViewHost.kt`, `collect/FeedCollector.kt`, `collect/HttpLatestFetcher.kt`; test `collect/CollectorMessageTest.kt` (message decoding only).

**Produces:**

```kotlin
enum class EndReason { LOGIN_WALL, NO_MORE_POSTS, TIMEOUT, NETWORK_ERROR, BLOCKED, CANCELLED }
data class CollectResult(val jsonChunks: List<String>, val domPosts: List<DomPost>, val end: EndReason)
sealed interface CollectorMessage { data class JsonChunk(val body: String); data class Dom(val posts: List<DomPost>); data class End(val reason: EndReason)
    companion object { fun decode(raw: String): CollectorMessage? } }
interface WebViewHost { fun attach(webView: WebView); fun detach(webView: WebView) }     // main thread
class ActivityHost(activity: ComponentActivity) : WebViewHost     // adds at index 0 of android.R.id.content, 1280x2400 px, alpha 1, behind Compose
class HeadlessHost : WebViewHost                                   // measure/layout 1280x2400 + dispatchWindowVisibilityChanged(VISIBLE)
class FeedCollector(private val appContext: Context) { suspend fun collect(host: WebViewHost): CollectResult }
class HttpLatestFetcher { suspend fun fetchChunks(): List<String> }   // HttpsURLConnection, desktop UA, Accept-Language en-NZ, 15 s timeouts, → FeedJsonExtractor.jsonBlocksFromHtml
```

`collector.js` exactly as spec §collector.js, posting `JSON.stringify({t,…})` through the injected `cwBridge.postMessage`; guards against double injection with `window.__cwInstalled`; all work wrapped in try/catch; forwards bodies only when they contain `"post_id"`; caps each forwarded body at 3 MB.

`FeedCollector.collect`: `withContext(Dispatchers.Main.immediate)`; clears cookies + `WebStorage`; builds WebView with settings from spec; `addWebMessageListener("cwBridge", setOf("https://www.facebook.com"))`; `addDocumentStartJavaScript` when `WebViewFeature.DOCUMENT_START_SCRIPT` is supported else `evaluateJavascript` in `onPageStarted` and again in `onPageFinished`; `WebViewClient` blocks non-`PAGE_URL` main-frame navigations, maps main-frame `onReceivedError`/HTTP ≥ 400 to `NETWORK_ERROR`/`BLOCKED`, handles `onRenderProcessGone` (returns true, ends with `NETWORK_ERROR`); `WebChromeClient` denies permission requests, swallows JS dialogs; overall `withTimeoutOrNull(45_000)` → `TIMEOUT`; `finally` (NonCancellable) stops loading, detaches, destroys. On coroutine cancellation returns nothing (propagates) — coordinator records what it already received via a shared buffer exposed as `val received: StateFlow<Int>` and `fun snapshot(): CollectResult`.

- [ ] Step 1: Test `CollectorMessage.decode` for `json`, `dom`, `end`, unknown `t`, malformed JSON, unknown end reason → `NO_MORE_POSTS`.
- [ ] Step 2–4: FAIL → implement → PASS + `assembleDebug`. Step 5: Commit `feat: hidden WebView collector`.

### Task 6: Settings, ScanCoordinator, background worker, notifications

**Files:** `settings/SettingsStore.kt`, `scan/ScanCoordinator.kt`, `scan/ScanWorker.kt`, `scan/BackgroundScheduler.kt`, `notify/NotificationPlanner.kt`, `notify/Notifier.kt`, `AppContainer.kt`, `App.kt`; tests `notify/NotificationPlannerTest.kt`, `scan/ScanPipelineTest.kt`.

**Produces:**

```kotlin
data class Settings(val backgroundMinutes: Int = 0 /* 0,15,30,60,120 */, val notify: Boolean = false,
    val notifyTypes: Set<ReportType> = ReportType.entries.toSet() - ReportType.OTHER, val watchedSuburbs: Set<String> = emptySet(),
    val hiddenTypes: Set<ReportType> = emptySet(), val suburbFilter: String? = null)
class SettingsStore(context) { val settings: Flow<Settings>; suspend fun update(transform: (Settings) -> Settings) }

sealed interface ScanState { data object Idle; data object Scanning }
data class ScanSummary(val status: ScrapeStatus, val new: Int, val finishedAt: Instant, val trigger: ScanTrigger)
class ScanCoordinator(collector, httpFetcher, recorder, lastFinishedAt: suspend () -> Long?, clock) {
    val state: StateFlow<ScanState>; val lastSummary: StateFlow<ScanSummary?>
    suspend fun scan(trigger: ScanTrigger, host: WebViewHost, force: Boolean): ScrapeOutcome?   // null = skipped (busy or < 2 min since last unless force)
}
object ScanPipeline { fun choose(result: CollectResult?, httpChunks: () -> List<String>, scanTime: Instant): Pair<List<RawPost>, CollectorKind> }  // JSON → DOM → HTTP → NONE
object NotificationPlanner { data class Plan(val title: String, val lines: List<String>)
    fun plan(newReports: List<NewReport>, settings: Settings, now: Instant): Plan? }
object BackgroundScheduler { fun apply(context: Context, minutes: Int) }   // 0 cancels; else enqueueUniquePeriodicWork("cw-scan", UPDATE, CONNECTED)
```

Planner rules: null when `!settings.notify`; keep reports whose type ∈ `notifyTypes`, whose suburb ∈ `watchedSuburbs` when that set is non-empty, and `now − at ≤ 2 h`; line format `"<Type name> – <road>, <SUBURB>"` (omit missing parts; OTHER uses its label or "Report"); title = the single line, or `"<n> new reports"`; at most 6 lines then `"+N more"`.
Coordinator: `Mutex.tryLock`; cancellation → records snapshot with `failure = CANCELLED` inside `NonCancellable`; network failure with no posts → `FAILED_NETWORK`. `ScanWorker.doWork`: `scan(BACKGROUND, HeadlessHost(), force=false)`, then `Notifier.show(plan)`; always `Result.success()` (periodic work must not back off into silence). `Notifier`: channel `new_reports` (default importance), `BigTextStyle`/`InboxStyle`, content intent → `MainActivity`, no-op without `POST_NOTIFICATIONS`.

- [ ] Step 1: Planner tests (disabled → null; type filter; suburb filter; 2 h cut-off; single vs many titles; 8 reports → 6 lines + "+2 more"; missing suburb formatting). Pipeline tests (JSON wins; DOM used when JSON empty → `WEBVIEW_DOM`; HTTP used when both empty → `HTTP`; all empty → `NONE`; `httpChunks` not invoked when JSON has posts).
- [ ] Step 2–4: FAIL → implement → PASS + `assembleDebug`. Step 5: Commit `feat: scan coordinator, background updates, notifications`.

### Task 7: Presentation logic (pure)

**Files:** `ui/TimeFormat.kt`, `ui/AreaLinker.kt`, `ui/home/HomeViewModel.kt` (+ `HomeUiState`); tests `ui/TimeFormatTest.kt`, `ui/AreaLinkerTest.kt`, `ui/HomeStateBuilderTest.kt`.

**Produces:**

```kotlin
object TimeFormat { fun ago(at: Instant, now: Instant): String      // "just now", "12 min ago", "2 h ago", "yesterday", "3 days ago"
    fun clock(at: Instant): String                                   // "9:30 pm" NZ
    fun dayHeader(at: Instant, now: Instant): String }               // "Today", "Yesterday", "Tue 15 Sep"
enum class Freshness { FRESH, OLDER, OLD }                           // <2 h, 2–6 h, >6 h
data class ReportUi(id, postId, type, typeLabel, road, suburb, details, at: Instant, atApprox: Boolean, reportedTimeText, source, postText, postUrl,
    freshness, gapAfter: Boolean, alsoInArea: List<AreaMention>, isNew: Boolean)
data class AreaMention(val type: ReportType, val at: Instant)
object AreaLinker { fun link(reports: List<ReportUi>): Map<Long, List<AreaMention>> }   // same suburb (or same road, case-insensitive, when no suburb), other report, |Δt| ≤ 3 h, max 3, newest first
data class Summary(val counts: Map<ReportType, Int>, val freshest: Instant?)             // last 2 h
sealed interface ListItem { DayHeader(label); Report(ReportUi); Gap(afterPostId) }
data class HomeUiState(val loading: Boolean, val items: List<ListItem>, val summary: Summary, val suburbs: List<String>, val settings: Settings,
    val scanning: Boolean, val banner: BannerUi, val lastChecked: Instant?, val totalReports: Int)
object HomeStateBuilder { fun build(rows: List<ReportRow>, settings: Settings, now: Instant, newSince: Instant?): … }
```

Banner copy (exact): scanning → "Finding checkpoints…"; first-ever scan → "Finding checkpoints for the first time…"; OK new>0 → "Found N new report(s)"; OK new=0 → "No new reports"; OK_WITH_GAP → "Found N new report(s) · earlier posts unavailable"; FAILED_NETWORK → "Couldn't reach Facebook · showing saved reports"; FAILED_NO_DATA → "Facebook returned no posts · showing saved reports"; CANCELLED → none. "N new" counts reports, not posts.

- [ ] Steps: tests first (boundaries at 59 s/60 s, 59 min/60 min, 23 h/24 h, 2 h and 6 h freshness edges; day headers across NZ midnight; linker symmetric, excludes self and same-post siblings? — **includes** siblings only if different road; type filter hides types; suburb filter; gap item placed after the last report of a `gapBefore` post; summary counts only ≤ 2 h), FAIL → implement → PASS → commit `feat: presentation logic`.

### Task 8: Premium UI

**Files:** `ui/theme/*`, `ui/TypeStyle.kt`, `ui/MainActivity.kt`, `ui/home/*.kt`, `ui/settings/*.kt`, `res/values/strings.xml`, `res/values/themes.xml`.

Design tokens (dark / light): background `#0B1220` / `#F6F7FB`; surface `#121B2E` / `#FFFFFF`; surfaceContainer `#1A2540` / `#EEF1F8`; onSurface `#E8EDF7` / `#0B1220`; onSurfaceVariant `#93A1BC` / `#5B677D`; primary (amber) `#FFB020` / `#9A5B00`; onPrimary `#1A1200` / `#FFFFFF`; outline `#2A3655` / `#D5DBE8`. Type colours dark/light: checkpoint `#FF6B6F`/`#C8202D`, police `#5CADFF`/`#1560B5`, crash `#FFA24D`/`#B35300`, camera `#BE9CFA`/`#6B41C4`, other `#93A1BC`/`#5B677D`. No dynamic colour. Typography: system sans; road `titleMedium` SemiBold 17 sp; suburb `labelSmall` letter-spacing 1.2 sp; details `bodyMedium`; times use `fontFeatureSettings = "tnum"`.

Composition:
- `MainActivity`: `enableEdgeToEdge()`, installs `ActivityHost`, `NavHost` with `home` and `settings`, starts a foreground scan in `repeatOnLifecycle(STARTED)` (cancels on stop), predictive back via manifest flag.
- `HomeScreen`: `Scaffold` with large collapsing top bar ("Checkpoint Watch", settings icon), `PullToRefreshBox`, `LazyColumn` (keys = report id, `contentType`, `animateItem()`), width capped at 640 dp and centred. Order: `SummaryHeader`, `StatusBanner`, `FilterBar` (sticky), list items.
- `SummaryHeader`: "Last 2 hours" label; four stat tiles (icon, big tabular count, label) tinted by type colour at 14 % alpha; "Freshest report 12 min ago" line; when all zero: "Quiet right now" with muted style.
- `StatusBanner`: rounded container; while scanning, an indeterminate amber sweep (`rememberInfiniteTransition` gradient moving across a 3 dp track; static when animations are disabled via `Settings.Global.ANIMATOR_DURATION_SCALE == 0`) plus text; result text cross-fades (`AnimatedContent`); "Checked 3 min ago" trailing.
- `FilterBar`: horizontally scrolling `FilterChip`s per type with icon; suburb `AssistChip` opening a `ModalBottomSheet` searchable suburb list with "All areas".
- `ReportCard`: 4 dp coloured rail + tinted icon badge; suburb label; road title; details (max 3 lines collapsed); meta row "Reported 9:30 pm · 2 h ago" (prefix "about" when approximate); `NEW` amber pill for reports first seen in the latest scan; OLD tag and 60 % alpha for `OLD`, 80 % for `OLDER`; "Also in PAKURANGA: Crash · 40 min ago" row with small type icons; tap → spring expand to full post text, source, "Open on Facebook" text button (ACTION_VIEW). One merged semantics sentence: "Checkpoint, Lincoln Road, Henderson, reported 2 hours ago. After the off-ramp…".
- `ListDecor`: day headers (sticky), `GapDivider` (dashed line + "Earlier posts unavailable"), empty states (first run, filtered-out with "Clear filters" button, offline) each with a 56 dp tinted icon, title, body.
- Haptics: `HapticFeedbackType.Confirm`-equivalent on scan result with new reports and on refresh release.
- `SettingsScreen`: sections Background updates (segmented Off/15 m/30 m/1 h/2 h, explainer, "Battery settings" button → `ACTION_APPLICATION_DETAILS_SETTINGS`), Notifications (switch → runtime permission request; type chips; watched suburbs multi-select), Scan history (last 10: time, trigger, collector, new/seen, status), About (data source, "reports are user-submitted and unverified", version).
- Icons: `material-icons-extended` is heavy → draw the 6 needed icons as `ImageVector`s in `TypeStyle.kt`/`Icons.kt` or use `material-icons-core` where available.

- [ ] Steps: implement theme → TypeStyle → home components → settings → wire MainActivity; `./gradlew assembleDebug testDebugUnitTest lintDebug`; commit per component group.

### Task 9: Verification and hand-off

- [ ] `./gradlew clean testDebugUnitTest lintDebug assembleDebug assembleRelease` → all succeed; note APK paths and sizes.
- [ ] If a device/emulator is available: install, run the on-device checklist in the spec (scan finds ~10 posts, Facebook never visible, leaving cancels, second scan "No new reports", background run recorded with its collector). If none is available, state that plainly in the hand-off and list the checklist for the owner.
- [ ] Write `README.md`: build, install on GrapheneOS (`adb install`, or copy APK and open it), enabling background updates + battery "Unrestricted", how to patch `collector.js`/`FeedJsonExtractor` if Facebook changes, known limits (10 posts per visit, gaps).
- [ ] Commit `docs: README and verification notes`.
