package nz.personal.checkpointwatch

import org.junit.Assert.assertEquals
import org.junit.Test

class ConstantsTest {

    @Test
    fun pageUrl_isCheckpointNzFacebookPage() {
        assertEquals("https://www.facebook.com/CheckpointNZ", Constants.PAGE_URL)
    }

    @Test
    fun desktopUa_isChromeOnWindows() {
        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            Constants.DESKTOP_UA,
        )
    }

    @Test
    fun nz_isPacificAuckland() {
        assertEquals("Pacific/Auckland", Constants.NZ.id)
    }

    @Test
    fun postUrl_appendsPostsSegment() {
        assertEquals(
            "https://www.facebook.com/CheckpointNZ/posts/12345",
            Constants.postUrl("12345"),
        )
    }
}
