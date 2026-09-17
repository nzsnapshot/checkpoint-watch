package nz.personal.checkpointwatch

import java.time.ZoneId

object Constants {
    const val PAGE_URL: String = "https://www.facebook.com/CheckpointNZ"

    /**
     * The page's display name, exactly as Facebook renders it above every post. Only the DOM
     * fallback needs it, to recognise that line as chrome rather than as something someone wrote.
     */
    const val PAGE_NAME: String = "Checkpoint Watch Auckland"

    /**
     * Facebook's own embeddable Page Plugin for the same page: the fallback the collector loads
     * when the feed refuses to paginate, which on a VPN is every time.
     *
     * Unlike the page itself the widget renders logged out with no dialog at all, and even while
     * the document is hidden — but it is capped at the five newest posts and does not grow on
     * scroll, so it is a floor under a starved scan rather than a replacement for one.
     *
     * The parameters are the ones verified live: the timeline tab, no cover photo, no facepile, a
     * 500 px column, and a height tall enough that the widget never paginates internally.
     */
    const val PLUGIN_URL: String =
        "https://www.facebook.com/plugins/page.php" +
            "?href=https%3A%2F%2Fwww.facebook.com%2FCheckpointNZ" +
            "&tabs=timeline&width=500&height=5000&small_header=true" +
            "&hide_cover=true&show_facepile=false"

    const val DESKTOP_UA: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    val NZ: ZoneId = ZoneId.of("Pacific/Auckland")

    fun postUrl(id: String): String = "$PAGE_URL/posts/$id"
}
