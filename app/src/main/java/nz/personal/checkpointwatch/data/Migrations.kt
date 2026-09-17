package nz.personal.checkpointwatch.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema changes, one object per step, never a destructive fallback.
 *
 * The app is a personal log on one phone with no export, no backup and no cloud sync: a dropped
 * table is data nobody can get back. Version 1 is on the owner's phone as of 1.0.2, so from here
 * every schema change has to arrive as a migration that has been run against a real version 1
 * database in a test (`MigrationTest`) before it is allowed near a release.
 */

/**
 * 1 → 2: posts learn about their photo.
 *
 * Two nullable columns, no data touched. `imageUrl` is where the photo lives on Facebook's content
 * hosts (signed, expiring — a lead, not an address to keep) and `imagePath` is the local copy once
 * it has been downloaded. Every existing row gets NULL for both, which is exactly right: nothing
 * collected before this version has a photo recorded, and the next scan that sees one of those
 * posts again will fill it in.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE posts ADD COLUMN imageUrl TEXT")
        db.execSQL("ALTER TABLE posts ADD COLUMN imagePath TEXT")
    }
}

/** Every migration this version knows how to apply, in order, for the database builder. */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
