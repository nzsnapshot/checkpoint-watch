package nz.personal.checkpointwatch.images

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class ImageStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val now: Instant = Instant.parse("2026-09-19T02:00:00Z")
    private val index = FakeIndex()
    private val downloader = FakeDownloader()

    private val dir: File by lazy { File(temp.root, "post_images") }

    private fun store() = ImageStore(dir = dir, index = index, downloader = downloader, clock = { now })

    private fun photo(name: String) = "https://scontent.fakl1-3.fna.fbcdn.net/v/$name.jpg?sig=1"

    @Test
    fun `a post's photo is downloaded once and the post is told where it is`() = runTest {
        index.pending += PendingImage("1615051343743081", photo("a"))

        val fetched = store().sync()

        assertEquals(1, fetched)
        val name = index.paths.getValue("1615051343743081")
        assertEquals("bytes of ${photo("a")}", File(dir, name).readText())
        assertEquals(listOf(photo("a")), downloader.requested)
    }

    @Test
    fun `the file name is safe whatever the post id looks like`() = runTest {
        index.pending += PendingImage("dom:0123456789abcdef", photo("a"))
        index.pending += PendingImage("../../databases/checkpoint.db", photo("b"))

        store().sync()

        for (name in index.paths.values) {
            assertTrue(name, Regex("^[0-9a-f]{32}\\.img$").matches(name))
            assertEquals(dir.canonicalFile, File(dir, name).canonicalFile.parentFile)
        }
        assertEquals(2, index.paths.values.toSet().size)
    }

    @Test
    fun `a download that fails leaves nothing behind and is simply tried again next time`() = runTest {
        index.pending += PendingImage("1", photo("gone"))
        downloader.failing += photo("gone")

        val fetched = store().sync()

        assertEquals(0, fetched)
        assertNull(index.paths["1"])
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `one bad photo does not stop the others`() = runTest {
        index.pending += PendingImage("1", photo("explodes"))
        index.pending += PendingImage("2", photo("fine"))
        downloader.throwing += photo("explodes")

        val fetched = store().sync()

        assertEquals(1, fetched)
        assertNull(index.paths["1"])
        assertTrue(index.paths.containsKey("2"))
    }

    @Test
    fun `an address that is not one of facebook's photo hosts is never requested`() = runTest {
        // The URL was checked when it was read, but the database is not the last line of defence:
        // this is the code that actually opens the connection.
        index.pending += PendingImage("1", "https://fbcdn.net.evil.example/a.jpg")
        index.pending += PendingImage("2", "http://scontent.fakl1-3.fna.fbcdn.net/a.jpg")

        store().sync()

        assertTrue(downloader.requested.isEmpty())
        assertTrue(index.paths.isEmpty())
    }

    @Test
    fun `only recent posts are asked for, and only a few at a time`() = runTest {
        store().sync()

        // Signed addresses expire within days, so an old post's photo is already out of reach.
        assertEquals(now.minusSeconds(3 * 24 * 3600).toEpochMilli(), index.askedSince)
        assertEquals(12, index.askedLimit)
    }

    @Test
    fun `a file no post points at any more is swept, once it is old enough not to be a download in flight`() = runTest {
        dir.mkdirs()
        val kept = File(dir, "kept.img").apply { writeText("x"); setLastModified(now.minusSeconds(7200).toEpochMilli()) }
        val orphan = File(dir, "orphan.img").apply { writeText("x"); setLastModified(now.minusSeconds(7200).toEpochMilli()) }
        val young = File(dir, "young.img").apply { writeText("x"); setLastModified(now.minusSeconds(60).toEpochMilli()) }
        index.paths["9"] = "kept.img"

        store().sync()

        assertTrue(kept.exists())
        assertFalse(orphan.exists())
        assertTrue(young.exists())
    }

    @Test
    fun `a broken index is a sync that did nothing, never a crash`() = runTest {
        index.failure = IllegalStateException("database is closed")

        assertEquals(0, store().sync())
    }

    // --- fakes -----------------------------------------------------------------------------

    private class FakeIndex : ImageIndex {
        val pending = mutableListOf<PendingImage>()
        val paths = mutableMapOf<String, String>()
        var askedSince: Long? = null
        var askedLimit: Int? = null
        var failure: Exception? = null

        override suspend fun pending(sinceMs: Long, limit: Int): List<PendingImage> {
            failure?.let { throw it }
            askedSince = sinceMs
            askedLimit = limit
            return pending.take(limit)
        }

        override suspend fun setImagePath(postId: String, path: String) {
            paths[postId] = path
        }

        override suspend fun allImagePaths(): List<String> = paths.values.toList()
    }

    private class FakeDownloader : ImageDownloader {
        val requested = mutableListOf<String>()
        val failing = mutableSetOf<String>()
        val throwing = mutableSetOf<String>()

        override suspend fun download(url: String, to: File): Boolean {
            requested += url
            if (url in throwing) throw IllegalStateException("boom")
            if (url in failing) {
                to.writeText("half a pho")
                return false
            }
            to.writeText("bytes of $url")
            return true
        }
    }
}
