package nz.personal.checkpointwatch.ui.home

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Turns the file name stored against a post into the file itself. Provided once, by the activity,
 * from `ImageStore`; absent in previews and screenshots, where a card simply has no photo.
 */
val LocalPhotoFiles = compositionLocalOf<((String) -> File)?> { null }

/**
 * A post's photo, read from the app's own copy and never from the network.
 *
 * Decoded off the main thread and sampled down to roughly [targetPx] on its long edge, so a list of
 * thumbnails does not hold a list of full-size bitmaps. Draws nothing at all until the picture is
 * ready, and nothing ever if the file is missing or is not a picture: [content] is only called
 * with a bitmap in hand, so the caller's spacing never makes room for something that is not there.
 */
@Composable
internal fun PostPhoto(
    path: String?,
    targetPx: Int,
    content: @Composable (ImageBitmap) -> Unit,
) {
    val files = LocalPhotoFiles.current
    if (path == null || files == null) return
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path, targetPx) {
        value = withContext(Dispatchers.IO) { decode(files(path), targetPx) }
    }
    bitmap?.let { content(it) }
}

@Composable
internal fun PhotoImage(bitmap: ImageBitmap, description: String?, scale: ContentScale, modifier: Modifier) {
    Image(bitmap = bitmap, contentDescription = description, contentScale = scale, modifier = modifier)
}

private fun decode(file: File, targetPx: Int): ImageBitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
    if (longEdge <= 0) {
        null
    } else {
        var sample = 1
        while (longEdge / (sample * 2) >= targetPx) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
    }
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}
