package io.legado.app.help.sync

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import kotlin.math.abs

data class RemoteProgress(
    val bookProgress: BookProgress?,
    val timestamp: Long,
    val percentage: Double?,
    val paragraphKey: String? = null,
    val device: String? = null,
    val deviceId: String? = null,
    val rawProgress: String? = null,
)

interface ProgressSyncProvider {
    suspend fun pull(book: Book): RemoteProgress?

    suspend fun push(
        book: Book,
        progress: BookProgress,
        anchorText: String? = null,
        overrideCloud: Boolean = false,
    ): Long?

    suspend fun pullAll(books: List<Book>): Map<String, RemoteProgress> = emptyMap()

    fun paragraphKey(book: Book, progress: BookProgress): String? = null
}

object PositionEquality {

    private const val POSITION_TOLERANCE = 200

    fun same(local: BookProgress, localKey: String?, remote: RemoteProgress): Boolean {
        val remoteProgress = remote.bookProgress ?: return false
        if (local.durChapterIndex != remoteProgress.durChapterIndex) return false
        if (localKey != null && remote.paragraphKey != null) {
            return localKey == remote.paragraphKey
        }
        return abs(local.durChapterPos - remoteProgress.durChapterPos) <= POSITION_TOLERANCE
    }

    fun remoteIsBehind(local: BookProgress, localKey: String?, remote: RemoteProgress): Boolean {
        val remoteProgress = remote.bookProgress ?: return false
        if (remoteProgress.durChapterIndex < local.durChapterIndex) return true
        if (remoteProgress.durChapterIndex > local.durChapterIndex) return false
        if (localKey != null && remote.paragraphKey != null && localKey == remote.paragraphKey) {
            return false
        }
        return remoteProgress.durChapterPos + POSITION_TOLERANCE < local.durChapterPos
    }

    /**
     * 云端位置是否领先本地。判断依据是阅读位置而不是服务器时间戳：
     * 旧版本可能已经把 `kosyncTime` 推到了云端时间戳之后，只有位置才能反映真实前后关系。
     */
    fun remoteIsAhead(local: BookProgress, localKey: String?, remote: RemoteProgress): Boolean {
        val remoteProgress = remote.bookProgress ?: return false
        if (remoteProgress.durChapterIndex > local.durChapterIndex) return true
        if (remoteProgress.durChapterIndex < local.durChapterIndex) return false
        if (localKey != null && remote.paragraphKey != null && localKey == remote.paragraphKey) {
            return false
        }
        return remoteProgress.durChapterPos > local.durChapterPos + POSITION_TOLERANCE
    }
}
