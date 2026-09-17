package nz.personal.checkpointwatch.data

import android.content.ContentValues
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The migration, run against a real version-1 database.
 *
 * Version 1 is on the owner's phone as of 1.0.2 and there is no export, no backup and no cloud
 * copy of it: if a migration is wrong, the history is simply gone. So this is not a test of the
 * SQL as text — it creates a version-1 file, puts a row in it that looks like a real post, runs
 * the migration, and reads the row back out.
 *
 * It runs on the JVM under Robolectric, using the same Roborazzi/Robolectric setup the screenshot
 * tests already need, with the exported schemas handed to the test as assets (see the `sourceSets`
 * block in `app/build.gradle.kts`). No device and no emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `1 to 2 keeps every post and leaves the new columns empty`() {
        helper.createDatabase(DB_NAME, 1).use { db ->
            db.insert("posts", android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, v1Post())
        }

        val migrated = helper.runMigrationsAndValidate(DB_NAME, 2, true, MIGRATION_1_2)

        migrated.query("SELECT * FROM posts").use { cursor ->
            assertTrue("the post did not survive the migration", cursor.moveToFirst())
            assertEquals(1, cursor.count)
            assertEquals("1614134890501393", cursor.getString(cursor.getColumnIndexOrThrow("postId")))
            assertEquals(
                "🛑 CHECKPOINT – Lincoln Road, HENDERSON\nAfter the off-ramp",
                cursor.getString(cursor.getColumnIndexOrThrow("text")),
            )
            assertEquals(1789646506000L, cursor.getLong(cursor.getColumnIndexOrThrow("createdAt")))
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("gapBefore")))
            // Nothing collected before version 2 has a photo recorded, and the next scan that sees
            // this post again is what fills it in.
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("imageUrl")))
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("imagePath")))
        }
        migrated.close()
    }

    @Test
    fun `1 to 2 leaves an empty database usable`() {
        helper.createDatabase(DB_NAME, 1).close()

        val migrated = helper.runMigrationsAndValidate(DB_NAME, 2, true, MIGRATION_1_2)

        migrated.query("SELECT * FROM posts").use { cursor ->
            assertFalse(cursor.moveToFirst())
        }
        // The other four tables are untouched by this migration and must still be there.
        listOf("reports", "post_revisions", "scrapes", "scrape_sightings").forEach { table ->
            migrated.query("SELECT COUNT(*) FROM $table").use { cursor ->
                assertTrue("missing table $table", cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
        migrated.close()
    }

    @Test
    fun `the new columns accept a photo once a scan finds one`() {
        helper.createDatabase(DB_NAME, 1).use { db ->
            db.insert("posts", android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, v1Post())
        }
        val migrated = helper.runMigrationsAndValidate(DB_NAME, 2, true, MIGRATION_1_2)

        migrated.execSQL(
            "UPDATE posts SET imageUrl = ?, imagePath = ? WHERE postId = ?",
            arrayOf(
                "https://scontent.test.fbcdn.net/photo.jpg",
                "/data/user/0/nz.personal.checkpointwatch/files/images/abc.jpg",
                "1614134890501393",
            ),
        )

        migrated.query("SELECT imageUrl, imagePath FROM posts").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("https://scontent.test.fbcdn.net/photo.jpg", cursor.getString(0))
            assertEquals("/data/user/0/nz.personal.checkpointwatch/files/images/abc.jpg", cursor.getString(1))
        }
        migrated.close()
    }

    @Test
    fun `the migrated database is the one the app would have built from scratch`() {
        // `runMigrationsAndValidate` compares the migrated schema against the exported 2.json —
        // column by column, index by index — and throws if they differ, so every test above is
        // already making this claim. This one says it out loud, and checks the version stamp that
        // decides whether Room will try to migrate again on the next launch.
        helper.createDatabase(DB_NAME, 1).close()

        val migrated = helper.runMigrationsAndValidate(DB_NAME, 2, true, MIGRATION_1_2)

        assertEquals(2, migrated.version)
        migrated.close()
    }

    private fun v1Post() = ContentValues().apply {
        put("postId", "1614134890501393")
        put("url", "https://www.facebook.com/CheckpointNZ/posts/1614134890501393")
        put("text", "🛑 CHECKPOINT – Lincoln Road, HENDERSON\nAfter the off-ramp")
        put("textHash", "0123456789abcdef0123456789abcdef01234567")
        put("createdAt", 1789646506000L)
        put("createdAtApprox", 0)
        put("firstSeenAt", 1789646600000L)
        put("lastSeenAt", 1789646600000L)
        putNull("editedAt")
        put("gapBefore", 1)
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
