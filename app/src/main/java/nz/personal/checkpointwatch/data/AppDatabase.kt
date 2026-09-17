package nz.personal.checkpointwatch.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PostEntity::class,
        ReportEntity::class,
        PostRevisionEntity::class,
        ScrapeEntity::class,
        SightingEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun postDao(): PostDao
    abstract fun reportDao(): ReportDao
    abstract fun revisionDao(): RevisionDao
    abstract fun scrapeDao(): ScrapeDao
}
