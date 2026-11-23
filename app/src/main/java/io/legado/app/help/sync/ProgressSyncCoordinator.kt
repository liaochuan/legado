package io.legado.app.help.sync

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.help.book.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

class ProgressSyncCoordinator(
    private val webDav: ProgressSyncProvider?,
    private val kosync: ProgressSyncProvider?,
    private val isKosyncEligible: (Book) -> Boolean,
    private val saveBook: (Book) -> Unit = { it.update() },
    private val uiContext: CoroutineContext = EmptyCoroutineContext,
) {

    suspend fun syncOnOpen(
        book: Book,
        plus: Boolean,
        onApplyRemote: ((BookProgress) -> Unit)?,
        onPromptRemote: ((BookProgress) -> Unit)?,
        onMessage: (String) -> Unit,
        anchorText: String? = null,
        onUploaded: (() -> Unit)? = null,
        localProgress: BookProgress? = null,
    ) {
        val local = localProgress ?: BookProgress(book)
        if (kosync != null && isKosyncEligible(book)) {
            try {
                syncKosync(book, local, plus, onApplyRemote, onPromptRemote, onMessage, anchorText, onUploaded)
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
                    syncWebDav(book, local, plus, onApplyRemote, onPromptRemote, onMessage, onUploaded)
                }
                return
            }
        }
        if (webDav != null) {
            syncWebDav(book, local, plus, onApplyRemote, onPromptRemote, onMessage, onUploaded)
        }
    }

    suspend fun syncOnClose(
        book: Book,
        onMessage: (String) -> Unit = {},
        anchorText: String? = null,
        localProgress: BookProgress? = null,
    ) {
        pushLocal(
            book,
            localProgress ?: BookProgress(book),
            anchorText,
            onMessage,
            null,
            overrideCloud = false,
        )
    }

    suspend fun push(
        book: Book,
        onMessage: (String) -> Unit = {},
        onUploaded: (() -> Unit)? = null,
        anchorText: String? = null,
        overrideCloud: Boolean = false,
        localProgress: BookProgress? = null,
    ) {
        pushLocal(
            book,
            localProgress ?: BookProgress(book),
            anchorText,
            onMessage,
            onUploaded,
            overrideCloud = overrideCloud,
        )
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
        local: BookProgress,
        plus: Boolean,
        onApplyRemote: ((BookProgress) -> Unit)?,
        onPromptRemote: ((BookProgress) -> Unit)?,
        onMessage: (String) -> Unit,
        anchorText: String?,
        onUploaded: (() -> Unit)?,
    ) {
        val kosync = kosync ?: return
        val remote = kosync.pull(book)
        if (remote == null || remote.bookProgress == null) {
            pushLocal(book, local, anchorText, onMessage, onUploaded, overrideCloud = false)
            return
        }
        val localKey = kosync.paragraphKey(book, local)
        if (PositionEquality.same(local, localKey, remote)) {
            if (remote.timestamp > book.kosyncTime) {
                book.kosyncTime = remote.timestamp
                saveBook(book)
            }
            // 手动同步（带上传回调）且位置相同：给出反馈，避免只剩「正在同步」提示。
            if (onUploaded != null) onMessage("已是最新进度")
            return
        }
        if (PositionEquality.remoteIsAhead(local, localKey, remote)) {
            // 云端位置更靠前：先让调用方提示是否跳转，确认后才应用。
            if (onApplyRemote != null) {
                notifyProgress(onApplyRemote, remote.bookProgress)
                pushWebDavOutcome(book, remote.bookProgress).error?.let(onMessage)
                book.kosyncTime = maxOf(book.kosyncTime, remote.timestamp)
                saveBook(book)
            }
            return
        }
        val shouldPrompt = plus &&
                PositionEquality.remoteIsBehind(local, localKey, remote) &&
                onPromptRemote != null
        if (shouldPrompt) {
            notifyProgress(onPromptRemote, remote.bookProgress)
        } else {
            pushLocal(book, local, anchorText, onMessage, onUploaded, overrideCloud = false)
        }
    }

    private suspend fun syncWebDav(
        book: Book,
        local: BookProgress,
        plus: Boolean,
        onApplyRemote: ((BookProgress) -> Unit)?,
        onPromptRemote: ((BookProgress) -> Unit)?,
        onMessage: (String) -> Unit,
        onUploaded: (() -> Unit)?,
    ) {
        val webDav = webDav ?: return
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
            notifyProgress(onApplyRemote, progress)
        } else if (progress.durChapterIndex < local.durChapterIndex ||
            (progress.durChapterIndex == local.durChapterIndex &&
                    progress.durChapterPos < local.durChapterPos)
        ) {
            if (plus && onPromptRemote != null) {
                notifyProgress(onPromptRemote, progress)
            } else {
                if (pushWebDav(book, local, onMessage)) onUploaded?.invoke()
            }
        }
    }

    /**
     * 应用/确认云端进度属于界面操作（弹窗、跳转、Toast），必须回到主线程执行。
     */
    private suspend fun notifyProgress(
        callback: ((BookProgress) -> Unit)?,
        progress: BookProgress,
    ) {
        if (callback == null) return
        withContext(uiContext) { callback(progress) }
    }

    private suspend fun pushLocal(
        book: Book,
        local: BookProgress,
        anchorText: String?,
        onMessage: (String) -> Unit,
        onUploaded: (() -> Unit)?,
        overrideCloud: Boolean,
    ) {
        val outcomes = coroutineScope {
            val jobs = ArrayList<kotlinx.coroutines.Deferred<PushOutcome>>()
            if (kosync != null) {
                jobs.add(async { pushKosyncOutcome(book, local, anchorText, overrideCloud) })
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
        overrideCloud: Boolean,
    ): PushOutcome {
        val kosync = kosync ?: return PushOutcome(false)
        return try {
            val timestamp = kosync.push(book, progress, anchorText, overrideCloud)
            if (timestamp != null) {
                book.kosyncTime = timestamp
                saveBook(book)
            }
            // 云端更靠前时普通上传会被跳过，返回 null，不能报告上传成功。
            PushOutcome(timestamp != null)
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
