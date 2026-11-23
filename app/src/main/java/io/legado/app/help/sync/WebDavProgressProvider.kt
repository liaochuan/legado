package io.legado.app.help.sync

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.help.AppWebDav

class WebDavProgressProvider : ProgressSyncProvider {

    override suspend fun pull(book: Book): RemoteProgress? {
        val progress = AppWebDav.getBookProgress(book) ?: return null
        return RemoteProgress(
            bookProgress = progress,
            timestamp = 0L,
            percentage = null,
        )
    }

    override suspend fun push(
        book: Book,
        progress: BookProgress,
        anchorText: String?,
        overrideCloud: Boolean,
    ): Long? {
        AppWebDav.uploadBookProgressOrThrow(progress)
        book.syncTime = System.currentTimeMillis()
        return null
    }
}
