package nz.personal.checkpointwatch.images

import nz.personal.checkpointwatch.data.PostDao

/** [ImageIndex] over the real database. */
class RoomImageIndex(private val posts: PostDao) : ImageIndex {

    override suspend fun pending(sinceMs: Long, limit: Int): List<PendingImage> =
        posts.pendingImages(sinceMs, limit).map { PendingImage(it.postId, it.imageUrl) }

    override suspend fun setImagePath(postId: String, path: String) = posts.setImagePath(postId, path)

    override suspend fun allImagePaths(): List<String> = posts.allImagePaths()
}
