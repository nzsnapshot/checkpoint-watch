package nz.personal.checkpointwatch.collect

import nz.personal.checkpointwatch.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PluginPostExtractorTest {

    private fun post(
        utime: Long = 1789677637,
        link: String? = "https://www.facebook.com/CheckpointNZ/posts/pfbid02abc",
        text: String = "🛑 CHECKPOINT – Cavendish Drive, MANUKAU\nSetting up under the bridge\nTime: 8:38AM",
        image: String? = null,
    ) = PluginPost(utime = utime, link = link, text = text, image = image)

    @Test
    fun extract_utimeIsTheExactPostTime() {
        // The widget hands over unix seconds, so unlike the DOM fallback nothing here is a guess.
        val posts = PluginPostExtractor.extract(listOf(post(utime = 1789677637)))

        assertEquals(Instant.ofEpochSecond(1789677637), posts.single().createdAt)
        assertFalse(posts.single().createdAtApprox)
    }

    @Test
    fun extract_idIsTheDomPlaceholder_soTheRecorderBridgesItOntoTheRealPost() {
        // The widget never gives a numeric post_id, so a plugin post lands under exactly the same
        // synthetic id the DOM fallback uses — which is what lets ScrapeRecorder replace it with
        // the real post the moment the JSON feed ever produces one with the same text.
        val text = "🛑 CHECKPOINT – Trig Road\nBoth directions."
        val fromPlugin = PluginPostExtractor.extract(listOf(post(text = text))).single()
        val fromDom = DomPostExtractor.extract(
            listOf(DomPost(text = text, age = "5m", link = null)),
            Instant.ofEpochSecond(1789677637),
        ).single()

        assertEquals(fromDom.postId, fromPlugin.postId)
        assertTrue(fromPlugin.postId.startsWith("dom:"))
        assertEquals("dom:" + DomPostExtractor.textHash(text).take(16), fromPlugin.postId)
    }

    @Test
    fun extract_textIsCleanedTheSameWayTheDomFallbackCleansIt() {
        // Same rules, one implementation: a plugin post and a DOM post of the same article have to
        // hash identically or they will never bridge onto each other.
        val raw = "Checkpoint Watch Auckland\n22m\n🛑 CHECKPOINT – Trig Road\nBoth directions.\nLike\nComment\nShare"

        val posts = PluginPostExtractor.extract(listOf(post(text = raw)))

        assertEquals("🛑 CHECKPOINT – Trig Road\nBoth directions.", posts.single().text)
        assertEquals(DomPostExtractor.cleanText(raw), posts.single().text)
    }

    @Test
    fun extract_blankAndChromeOnlyPostsAreDropped() {
        val posts = PluginPostExtractor.extract(
            listOf(
                post(text = "   "),
                post(text = "Like\nComment\nShare"),
                post(text = "🛑 CHECKPOINT – Trig Road"),
            ),
        )

        assertEquals(listOf("🛑 CHECKPOINT – Trig Road"), posts.map { it.text })
    }

    @Test
    fun extract_keepsAValidPermalink() {
        val posts = PluginPostExtractor.extract(
            listOf(post(link = "https://www.facebook.com/CheckpointNZ/posts/pfbid02abc")),
        )

        assertEquals("https://www.facebook.com/CheckpointNZ/posts/pfbid02abc", posts.single().url)
    }

    @Test
    fun extract_reelPermalinksAreKeptToo() {
        val posts = PluginPostExtractor.extract(listOf(post(link = "https://www.facebook.com/reel/123456")))

        assertEquals("https://www.facebook.com/reel/123456", posts.single().url)
    }

    @Test
    fun extract_anUntrustworthyLinkFallsBackToThePage() {
        // The widget's markup is untrusted input; "Open on Facebook" must never leave Facebook.
        listOf(
            null,
            "",
            "http://www.facebook.com/CheckpointNZ/posts/1",
            "https://www.facebook.com.evil.example/CheckpointNZ",
            "javascript:alert(1)",
            "not a url at all",
        ).forEach { link ->
            val posts = PluginPostExtractor.extract(listOf(post(link = link)))
            assertEquals("link was $link", Constants.PAGE_URL, posts.single().url)
        }
    }

    @Test
    fun extract_keepsAPhotoOnFacebooksOwnContentHosts() {
        val uri = "https://scontent.fakl1-4.fna.fbcdn.net/v/t39.99422-6/814926055_n.png?_nc_sig=abc"

        val posts = PluginPostExtractor.extract(listOf(post(image = uri)))

        assertEquals(uri, posts.single().imageUrl)
    }

    @Test
    fun extract_refusesAnImageFromAnywhereElse() {
        listOf(
            null,
            "",
            "http://scontent.test.fbcdn.net/photo.jpg",
            "https://fbcdn.net.evil.example/photo.jpg",
            "https://example.com/photo.jpg",
            "data:image/png;base64,AAAA",
            "//scontent.test.fbcdn.net/photo.jpg",
        ).forEach { image ->
            val posts = PluginPostExtractor.extract(listOf(post(image = image)))
            assertNull("image was $image", posts.single().imageUrl)
        }
    }

    @Test
    fun extract_returnsNewestFirst() {
        val posts = PluginPostExtractor.extract(
            listOf(
                post(utime = 1789600000, text = "older"),
                post(utime = 1789677637, text = "newer"),
            ),
        )

        assertEquals(listOf("newer", "older"), posts.map { it.text })
    }

    @Test
    fun extract_nothingAtAll_isNothing() {
        assertTrue(PluginPostExtractor.extract(emptyList()).isEmpty())
    }
}
