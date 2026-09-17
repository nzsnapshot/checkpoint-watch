package nz.personal.checkpointwatch.ui.preview

import nz.personal.checkpointwatch.collect.EndReason
import nz.personal.checkpointwatch.data.CollectorKind
import nz.personal.checkpointwatch.data.ScanTrigger
import nz.personal.checkpointwatch.data.ScrapeStatus
import nz.personal.checkpointwatch.model.ReportType
import nz.personal.checkpointwatch.settings.Settings
import nz.personal.checkpointwatch.ui.AreaMention
import nz.personal.checkpointwatch.ui.ReportUi
import nz.personal.checkpointwatch.ui.TimeFormat
import nz.personal.checkpointwatch.ui.home.BannerKind
import nz.personal.checkpointwatch.ui.home.BannerUi
import nz.personal.checkpointwatch.ui.home.EmptyStateBuilder
import nz.personal.checkpointwatch.ui.home.HomeUiState
import nz.personal.checkpointwatch.ui.home.ListItem
import nz.personal.checkpointwatch.ui.home.Summary
import nz.personal.checkpointwatch.ui.settings.ScanHistoryRow
import nz.personal.checkpointwatch.ui.settings.SettingsUiState
import java.time.Instant

/**
 * One evening of real Auckland reports, frozen at 9 pm.
 *
 * This lives in the main source set on purpose: the previews in this package and the JVM
 * screenshot tests render the very same composables from the very same data, so a preview that
 * looks right is the thing the test locks in.
 */
object SampleData {

    /** 2026-09-18, 9:00 pm in Auckland. Every time below is relative to this. */
    val now: Instant = Instant.parse("2026-09-18T09:00:00Z")

    private fun minutesAgo(minutes: Long): Instant = now.minusSeconds(minutes * 60)

    private fun report(
        id: Long,
        postId: String,
        type: ReportType,
        road: String?,
        suburb: String?,
        details: String,
        minutesAgo: Long,
        postText: String,
        typeLabel: String = type.name,
        source: String? = null,
        isNew: Boolean = false,
        gapAfter: Boolean = false,
        atApprox: Boolean = false,
        alsoInArea: List<AreaMention> = emptyList(),
    ): ReportUi {
        val at = minutesAgo(minutesAgo)
        return ReportUi(
            id = id,
            postId = postId,
            type = type,
            typeLabel = typeLabel,
            road = road,
            suburb = suburb,
            details = details,
            at = at,
            atApprox = atApprox,
            reportedTimeText = TimeFormat.clock(at),
            source = source,
            postText = postText,
            postUrl = "https://www.facebook.com/CheckpointNZ/posts/$postId",
            freshness = TimeFormat.freshness(at, now),
            gapAfter = gapAfter,
            alsoInArea = alsoInArea,
            isNew = isNew,
        )
    }

    val lincolnRoad: ReportUi = report(
        id = 1,
        postId = "1001",
        type = ReportType.CHECKPOINT,
        road = "Lincoln Road",
        suburb = "HENDERSON",
        details = "After the off-ramp coming from the motorway. Both lanes, everyone being stopped.",
        minutesAgo = 5,
        isNew = true,
        postText = "🛑 CHECKPOINT – Lincoln Road, HENDERSON\n" +
            "After the off-ramp coming from the motorway. Both lanes, everyone being stopped.\n" +
            "Time: 8:55PM",
    )

    val greenLaneWest: ReportUi = report(
        id = 2,
        postId = "1002",
        type = ReportType.POLICE_PRESENCE,
        road = "Green Lane West",
        suburb = "GREENLANE",
        details = "Two patrol cars parked up near the hospital entrance.",
        minutesAgo = 20,
        isNew = true,
        source = "WhatsApp subscriber",
        postText = "🚔 HEAVY POLICE PRESENCE – Green Lane West, GREENLANE\n" +
            "Two patrol cars parked up near the hospital entrance.\n" +
            "Time: 8:40PM\nFrom: WhatsApp subscriber",
    )

    /** One post, two reports: the crash and the checkpoint that turned up next to it. */
    private const val PAKURANGA_POST = "🚗 CRASH – Pakuranga Road, PAKURANGA\n" +
        "Two cars, left lane blocked heading east. Emergency services on scene.\n" +
        "Time: 8:20PM\n\n" +
        "🛑 CHECKPOINT – Pakuranga Road, PAKURANGA\n" +
        "Just past the Ti Rakau Drive turn-off.\n" +
        "Time: 8:20PM"

    val pakurangaCrash: ReportUi = report(
        id = 3,
        postId = "1003",
        type = ReportType.CRASH,
        road = "Pakuranga Road",
        suburb = "PAKURANGA",
        details = "Two cars, left lane blocked heading east. Emergency services on scene.",
        minutesAgo = 40,
        postText = PAKURANGA_POST,
        alsoInArea = listOf(AreaMention(ReportType.CHECKPOINT, minutesAgo(40))),
    )

    val pakurangaCheckpoint: ReportUi = report(
        id = 4,
        postId = "1003",
        type = ReportType.CHECKPOINT,
        road = "Pakuranga Road",
        suburb = "PAKURANGA",
        details = "Just past the Ti Rakau Drive turn-off.",
        minutesAgo = 40,
        postText = PAKURANGA_POST,
        alsoInArea = listOf(AreaMention(ReportType.CRASH, minutesAgo(40))),
    )

    val hibiscusCamera: ReportUi = report(
        id = 5,
        postId = "1005",
        type = ReportType.SPEED_CAMERA,
        road = "Hibiscus Coast Highway",
        suburb = "SILVERDALE",
        details = "Grey van on the shoulder just before the Park and Ride.",
        minutesAgo = 70,
        postText = "📷 SPEED CAMERA – Hibiscus Coast Highway, SILVERDALE\n" +
            "Grey van on the shoulder just before the Park and Ride.\n" +
            "Time: 7:50PM",
    )

    /** Old enough to be dimmed, and the last post before the history this app could not see. */
    val robertonRoad: ReportUi = report(
        id = 6,
        postId = "1006",
        type = ReportType.POLICE_PRESENCE,
        road = "Roberton Road",
        suburb = "AVONDALE",
        details = "Several cars at the shops.",
        minutesAgo = 150,
        gapAfter = true,
        atApprox = true,
        postText = "🚔 HEAVY POLICE PRESENCE – Roberton Road, AVONDALE\nSeveral cars at the shops.",
    )

    /** A road with no suburb at all. */
    val trigRoad: ReportUi = report(
        id = 7,
        postId = "1007",
        type = ReportType.CHECKPOINT,
        road = "Trig Road",
        suburb = null,
        details = "Both directions.",
        minutesAgo = 450,
        postText = "🛑 CHECKPOINT – Trig Road\nBoth directions.\nTime: 1:30PM",
    )

    /** A post the parser could make no header out of: nothing is lost, it is simply an update. */
    val headerless: ReportUi = report(
        id = 8,
        postId = "1008",
        type = ReportType.OTHER,
        typeLabel = "",
        road = null,
        suburb = null,
        details = "Roadworks on the Southern Motorway tonight between Ellerslie and Greenlane, " +
            "one lane open until 5am.",
        minutesAgo = 480,
        postText = "Roadworks on the Southern Motorway tonight between Ellerslie and Greenlane, " +
            "one lane open until 5am.",
    )

    val reports: List<ReportUi> = listOf(
        lincolnRoad,
        greenLaneWest,
        pakurangaCrash,
        pakurangaCheckpoint,
        hibiscusCamera,
        robertonRoad,
        trigRoad,
        headerless,
    )

    val items: List<ListItem> = buildList {
        add(ListItem.DayHeader("Today"))
        reports.forEach { report ->
            add(ListItem.Report(report))
            if (report.gapAfter) add(ListItem.Gap(report.postId))
        }
    }

    val suburbs: List<String> = listOf("AVONDALE", "GREENLANE", "HENDERSON", "PAKURANGA", "SILVERDALE")

    private val summary = Summary(
        counts = mapOf(
            ReportType.CHECKPOINT to 2,
            ReportType.POLICE_PRESENCE to 1,
            ReportType.CRASH to 1,
            ReportType.SPEED_CAMERA to 1,
        ),
        freshest = minutesAgo(5),
    )

    private fun state(
        items: List<ListItem>,
        summary: Summary,
        banner: BannerUi,
        scanning: Boolean = false,
        settings: Settings = Settings(),
        lastChecked: Instant? = minutesAgo(3),
        totalReports: Int = reports.size,
        firstEver: Boolean = false,
        lastStatus: ScrapeStatus? = ScrapeStatus.OK,
        pullRefreshing: Boolean = false,
    ) = HomeUiState(
        loading = false,
        items = items,
        summary = summary,
        suburbs = suburbs,
        settings = settings,
        scanning = scanning,
        banner = banner,
        lastChecked = lastChecked,
        totalReports = totalReports,
        firstEver = firstEver,
        emptyKind = EmptyStateBuilder.kind(
            visibleItems = items.size,
            totalReports = totalReports,
            scanning = scanning,
            lastStatus = lastStatus,
        ),
        pullRefreshing = pullRefreshing,
        now = now,
    )

    /** The normal evening: a full list, a finished scan that found two new reports. */
    val home: HomeUiState = state(items, summary, BannerUi.Message("Found 2 new reports", BannerKind.FOUND))

    /**
     * The one post that carried two reports, on its own. Opening either card is the case where the
     * original post genuinely says more than the card does, so the expanded section shows it.
     */
    val multiReport: HomeUiState = state(
        items = listOf(
            ListItem.DayHeader("Today"),
            ListItem.Report(pakurangaCrash),
            ListItem.Report(pakurangaCheckpoint),
        ),
        summary = Summary(mapOf(ReportType.CRASH to 1, ReportType.CHECKPOINT to 1), minutesAgo(40)),
        banner = BannerUi.Message("No new reports", BannerKind.NONE_NEW),
        totalReports = 2,
    )

    /** Mid-scan, with saved reports already on screen. */
    val scanning: HomeUiState = state(items, summary, BannerUi.Message("Finding checkpoints…", BannerKind.SCANNING), scanning = true)

    /** Mid-scan because the owner pulled the list down: the one case with a refresh indicator. */
    val pullRefreshing: HomeUiState = state(
        items = items,
        summary = summary,
        banner = BannerUi.Message("Finding checkpoints…", BannerKind.SCANNING),
        scanning = true,
        pullRefreshing = true,
    )

    /** Nothing reported in the last two hours; the list below still has history in it. */
    val quiet: HomeUiState = state(
        items = listOf(ListItem.DayHeader("Today"), ListItem.Report(trigRoad), ListItem.Report(headerless)),
        summary = Summary(emptyMap(), null),
        banner = BannerUi.Message("No new reports", BannerKind.NONE_NEW),
        totalReports = 2,
    )

    /** The very first run, still looking. */
    val firstRun: HomeUiState = state(
        items = emptyList(),
        summary = Summary(emptyMap(), null),
        banner = BannerUi.Message("Finding checkpoints for the first time…", BannerKind.SCANNING),
        scanning = true,
        lastChecked = null,
        totalReports = 0,
        firstEver = true,
        lastStatus = null,
    )

    /** First run, but the phone could not reach Facebook. */
    val offline: HomeUiState = state(
        items = emptyList(),
        summary = Summary(emptyMap(), null),
        banner = BannerUi.Message("Couldn't reach Facebook · showing saved reports", BannerKind.FAILED),
        totalReports = 0,
        firstEver = false,
        lastStatus = ScrapeStatus.FAILED_NETWORK,
    )

    /** The page was reached and served nothing. Not the same thing as being offline. */
    val noPosts: HomeUiState = state(
        items = emptyList(),
        summary = Summary(emptyMap(), null),
        banner = BannerUi.Message("Facebook returned no posts · showing saved reports", BannerKind.FAILED),
        totalReports = 0,
        firstEver = false,
        lastStatus = ScrapeStatus.FAILED_NO_DATA,
    )

    /** A scan that worked perfectly and found nothing worth keeping. */
    val nothingYet: HomeUiState = state(
        items = emptyList(),
        summary = Summary(emptyMap(), null),
        banner = BannerUi.Message("No new reports", BannerKind.NONE_NEW),
        totalReports = 0,
        firstEver = false,
        lastStatus = ScrapeStatus.OK,
    )

    /** Filters that nothing matches, with reports saved behind them. */
    val filteredOut: HomeUiState = state(
        items = emptyList(),
        summary = summary,
        banner = BannerUi.Message("No new reports", BannerKind.NONE_NEW),
        settings = Settings(
            hiddenTypes = ReportType.entries.toSet() - ReportType.SPEED_CAMERA,
            suburbFilter = "HENDERSON",
        ),
    )

    val history: List<ScanHistoryRow> = listOf(
        ScanHistoryRow(1, minutesAgo(3), ScanTrigger.FOREGROUND, CollectorKind.WEBVIEW, ScrapeStatus.OK, 3, 10, EndReason.LOGIN_WALL),
        ScanHistoryRow(2, minutesAgo(33), ScanTrigger.BACKGROUND, CollectorKind.WEBVIEW, ScrapeStatus.OK, 1, 10, EndReason.NO_MORE_POSTS),
        ScanHistoryRow(3, minutesAgo(63), ScanTrigger.BACKGROUND, CollectorKind.HTTP, ScrapeStatus.OK_WITH_GAP, 1, 1, EndReason.TIMEOUT),
        ScanHistoryRow(4, minutesAgo(93), ScanTrigger.BACKGROUND, CollectorKind.NONE, ScrapeStatus.FAILED_NETWORK, 0, 0, EndReason.NETWORK_ERROR),
        ScanHistoryRow(5, minutesAgo(140), ScanTrigger.FOREGROUND, CollectorKind.WEBVIEW_DOM, ScrapeStatus.CANCELLED, 0, 4, EndReason.CANCELLED),
    )

    val settings: SettingsUiState = SettingsUiState(
        settings = Settings(backgroundMinutes = 30, notify = true, watchedSuburbs = setOf("HENDERSON", "PAKURANGA")),
        suburbs = suburbs,
        history = history,
        version = "1.0",
    )

    /** Background updates off, notifications off: the defaults, and every hint switched on. */
    val settingsQuiet: SettingsUiState = SettingsUiState(
        settings = Settings(),
        suburbs = suburbs,
        history = emptyList(),
        version = "1.0",
    )

    /** The owner asked for notifications and the system refused. */
    val settingsBlocked: SettingsUiState = settings.copy(notificationsBlocked = true)
}
