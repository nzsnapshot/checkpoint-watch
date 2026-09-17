package nz.personal.checkpointwatch

import java.time.ZoneId

object Constants {
    const val PAGE_URL: String = "https://www.facebook.com/CheckpointNZ"

    /**
     * The page's display name, exactly as Facebook renders it above every post. Only the DOM
     * fallback needs it, to recognise that line as chrome rather than as something someone wrote.
     */
    const val PAGE_NAME: String = "Checkpoint Watch Auckland"

    const val DESKTOP_UA: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    val NZ: ZoneId = ZoneId.of("Pacific/Auckland")

    fun postUrl(id: String): String = "$PAGE_URL/posts/$id"
}
