package nz.personal.checkpointwatch

import android.content.Context
import androidx.room.Room
import nz.personal.checkpointwatch.collect.CollectResult
import nz.personal.checkpointwatch.collect.FeedCollector
import nz.personal.checkpointwatch.collect.HttpLatestFetcher
import nz.personal.checkpointwatch.collect.WebViewHost
import nz.personal.checkpointwatch.data.AppDatabase
import nz.personal.checkpointwatch.data.ReportDao
import nz.personal.checkpointwatch.data.RoomScrapeStore
import nz.personal.checkpointwatch.data.ScrapeDao
import nz.personal.checkpointwatch.data.ScrapeRecorder
import nz.personal.checkpointwatch.notify.Notifier
import nz.personal.checkpointwatch.scan.PostCollector
import nz.personal.checkpointwatch.scan.ScanCoordinator
import nz.personal.checkpointwatch.settings.SettingsStore

private const val DATABASE_NAME = "checkpointwatch.db"

/**
 * The app's single graph of long-lived objects, built by hand: this app has one screen, one
 * database and one scan, which a dependency-injection library would only wrap in ceremony.
 *
 * Everything is lazy, so opening the database, spinning up WebView machinery and reading
 * settings all happen on first use rather than on the startup path.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME).build()
    }

    /** The list and the scrape log read straight from these; there is no repository in between. */
    val reportDao: ReportDao get() = database.reportDao()
    val scrapeDao: ScrapeDao get() = database.scrapeDao()

    val settings: SettingsStore by lazy { SettingsStore(appContext) }

    val notifier: Notifier by lazy { Notifier(appContext) }

    private val feedCollector: FeedCollector by lazy { FeedCollector(appContext) }

    private val httpLatestFetcher: HttpLatestFetcher by lazy { HttpLatestFetcher() }

    val scanCoordinator: ScanCoordinator by lazy {
        ScanCoordinator(
            collector = feedCollector.asPostCollector(),
            httpFetcher = { httpLatestFetcher.fetchChunks() },
            recorder = ScrapeRecorder(RoomScrapeStore(database)),
            lastFinishedAt = { database.scrapeDao().lastFinishedAt() },
        )
    }
}

/** [FeedCollector] is the real [PostCollector]; it just predates the interface. */
private fun FeedCollector.asPostCollector(): PostCollector = object : PostCollector {
    override suspend fun collect(host: WebViewHost): CollectResult = this@asPostCollector.collect(host)
    override fun snapshot(): CollectResult = this@asPostCollector.snapshot()
}
