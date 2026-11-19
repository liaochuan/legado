package io.legado.app.help.sync

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.help.book.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class ProgressSyncCoordinator(
    private val webDav: ProgressSyncProvider?,
    private val kosync: ProgressSyncProvider?,
    private val isKosyncEligible: (Book) -> Boolean,
    private val saveBook: (Book) -> Unit = { it.update() },
) {

    suspend fun syncOnOpen(
        book: Book,
        plus: Boolean,
        onApplyRemote: ((BookProgress) -> Unit)?,
        onPromptRemote: ((BookProgress) -> Unit)?,
        onMessage: (String) -> Unit,
        anchorText: String? = null,
        onUploaded: (() -> Unit)? = null,
    ) {
        if (kosync != null && isKosyncEligible(book)) {
            try {
                syncKosync(book, plus, onApplyRemote, onPromptRemote, onMessage, anchorText, onUploaded)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage(
                    if (webDav != null) {
                        "KOReader 拉取失败，已回退 WebDAV"
                    } else {
                        "KOReader 拉取失败"
                    }
                )
                if (webDav != null) {
                    syncWebDav(book, plus, onApplyRemote, onPromptRemote, onMessage, onUploaded)
                }
                return
            }
        }
        if (webDav != null) {
            syncWebDav(book, plus, onApplyRemote, onPromptRemote, onMessage, onUploaded)
        }
    }

    suspend fun syncOnClose(
        book: Book,
        onMessage: (String) -> Unit = {},
        anchorText: String? = null,
    ) {
        pushLocal(book, BookProgress(book), anchorText, onMessage, null)
    }

    suspend fun push(
        book: Book,
        onMessage: (String) -> Unit = {},
        onUploaded: (() -> Unit)? = null,
        anchorText: String? = null,
    ) {
        pushLocal(book, BookProgress(book), anchorText, onMessage, onUploaded)
    }

    suspend fun syncAllOnStartup(books: List<Book>) {
        val kosync = kosync ?: return
        if (books.isEmpty()) return
        val remotes = try {
            kosync.pullAll(books)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        for (book in books) {
            val remote = remotes[book.bookUrl] ?: continue
            val progress = remote.bookProgress ?: continue
            if (remote.timestamp <= book.kosyncTime) continue
            book.durChapterIndex = progress.durChapterIndex
            book.durChapterPos = progress.durChapterPos
            book.durChapterTitle = progress.durChapterTitle
            book.durChapterTime = System.currentTimeMillis()
            book.kosyncTime = remote.timestamp
            saveBook(book)
        }
    }

    private suspend fun syncKosync(
        book: Book,
        plus: Boolean,
        onApplyRemote: ((BookProgress) -> Unit)?,
        onPromptRemote: ((BookProgress) -> Unit)?,
        onMessage: (String) -> Unit,
        anchorText: String?,
        onUploaded: (() -> Unit)?,
    ) {
        val kosync = kosync ?: return
        val local = BookProgress(book)
        val remote = kosync.pull(book)
        if (remote == null || remote.bookProgress == null) {
            pushLocal(book, local, anchorText, onMessage, onUploaded)
            return
        }
        val localKey = kosync.paragraphKey(book, local)
        if (remote.timestamp > book.kosyncTime) {
            if (PositionEquality.same(local, localKey, remote)) {
                book.kosyncTime = remote.timestamp
                saveBook(book)
            } else {
                val shouldPrompt = plus &&
                        PositionEquality.remoteIsBehind(local, localKey, remote) &&
                        onPromptRemote != null
                if (shouldPrompt) {
                    onPromptRemote.invoke(remote.bookProgress)
                } else if (onApplyRemote != null) {
                    onApplyRemote.invoke(remote.bookProgress)
                    webDav?.push(book, remote.bookProgress, null)
                    book.kosyncTime = remote.timestamp
                    saveBook(book)
                } else if (PositionEquality.remoteIsBehind(local, localKey, remote)) {
                    pushLocal(book, local, anchorText, onMessage, onUploaded)
                }
            }
            return
        }
        if (!PositionEquality.same(local, localKey, remote)) {
            pushLocal(book, local, anchorText, onMessage, onUploaded)
        }
    }

    private suspend fun syncWebDav(
        book: Book,
        plus: Boolean,
        onApplyRemote: ((BookProgress) -> Unit)?,
        onPromptRemote: ((BookProgress) -> Unit)?,
        onMessage: (String) -> Unit,
        onUploaded: (() -> Unit)?,
    ) {
        val webDav = webDav ?: return
        val local = BookProgress(book)
        val remote = try {
            webDav.pull(book)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onMessage("WebDAV 拉取失败")
            return
        }
        val progress = remote?.bookProgress
        if (progress == null) {
            if (pushWebDav(book, local, onMessage)) onUploaded?.invoke()
            return
        }
        val remoteAhead = progress.durChapterIndex > local.durChapterIndex ||
                (progress.durChapterIndex == local.durChapterIndex &&
                        progress.durChapterPos > local.durChapterPos)
        if (remoteAhead) {
            onApplyRemote?.invoke(progress)
        } else if (progress.durChapterIndex < local.durChapterIndex ||
            (progress.durChapterIndex == local.durChapterIndex &&
                    progress.durChapterPos < local.durChapterPos)
        ) {
            if (plus && onPromptRemote != null) {
                onPromptRemote.invoke(progress)
            } else {
                if (pushWebDav(book, local, onMessage)) onUploaded?.invoke()
            }
        }
    }

    private suspend fun pushLocal(
        book: Book,
        local: BookProgress,
        anchorText: String?,
        onMessage: (String) -> Unit,
        onUploaded: (() -> Unit)?,
    ) {
        val outcomes = coroutineScope {
            val jobs = ArrayList<kotlinx.coroutines.Deferred<PushOutcome>>()
            if (kosync != null) {
                jobs.add(async { pushKosyncOutcome(book, local, anchorText) })
            }
            if (webDav != null) {
                jobs.add(async { pushWebDavOutcome(book, local) })
            }
            jobs.map { it.await() }
        }
        val errors = outcomes.mapNotNull { it.error }
        if (errors.isNotEmpty()) {
            onMessage(errors.joinToString("；"))
        }
        if (outcomes.any { it.success }) {
            onUploaded?.invoke()
        }
    }

    private suspend fun pushKosyncOutcome(
        book: Book,
        progress: BookProgress,
        anchorText: String?,
    ): PushOutcome {
        val kosync = kosync ?: return PushOutcome(false)
        return try {
            val timestamp = kosync.push(book, progress, anchorText)
            if (timestamp != null) {
                book.kosyncTime = timestamp
                saveBook(book)
            }
            PushOutcome(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PushOutcome(false, "KOReader 上传失败")
        }
    }

    private suspend fun pushWebDav(
        book: Book,
        progress: BookProgress,
        onMessage: (String) -> Unit,
    ): Boolean {
        val outcome = pushWebDavOutcome(book, progress)
        outcome.error?.let(onMessage)
        return outcome.success
    }

    private suspend fun pushWebDavOutcome(
        book: Book,
        progress: BookProgress,
    ): PushOutcome {
        val webDav = webDav ?: return PushOutcome(false)
        return try {
            webDav.push(book, progress, null)
            PushOutcome(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PushOutcome(false, "WebDAV 上传失败")
        }
    }

    private data class PushOutcome(
        val success: Boolean,
        val error: String? = null,
    )
}
