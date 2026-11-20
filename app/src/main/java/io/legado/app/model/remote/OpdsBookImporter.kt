package io.legado.app.model.remote

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.installPersistentCover
import io.legado.app.model.analyzeRule.CustomUrl
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.externalFiles
import splitties.init.appCtx
import java.io.File

/**
 * 下载远程书籍并导入书架，供列表批量下载和详情页共用。
 */
object OpdsBookImporter {

    suspend fun import(
        manager: RemoteBookManager,
        remoteBook: RemoteBook,
        forcedFormat: String? = null,
    ) {
        val target = (manager as? RemoteBookOpds)?.targetForFormat(remoteBook, forcedFormat)
            ?: remoteBook
        val downloadBookUri = manager.downloadRemoteBook(target)
        LocalBook.importFiles(downloadBookUri).forEach { book ->
            if (manager is RemoteBookOpds) {
                applyOpdsMetadata(book, remoteBook, manager)
            }
            if (manager is RemoteBookWebDav) {
                book.origin = BookType.webDavTag + CustomUrl(remoteBook.path)
                    .putAttribute("serverID", manager.serverID)
                    .toString()
                book.save()
            }
        }
        target.isOnBookShelf = true
        remoteBook.isOnBookShelf = true
    }

    private suspend fun applyOpdsMetadata(
        book: Book,
        remoteBook: RemoteBook,
        manager: RemoteBookOpds,
    ) {
        remoteBook.filename.takeIf { it.isNotBlank() }?.let { book.name = it }
        remoteBook.author?.takeIf { it.isNotBlank() }?.let { book.author = it }
        remoteBook.intro?.takeIf { it.isNotBlank() }?.let { book.intro = it }
        remoteBook.categories.takeIf { it.isNotEmpty() }?.let {
            book.kind = it.joinToString(",")
        }
        val coverUrl = remoteBook.coverUrl
        if (!coverUrl.isNullOrBlank()) {
            val persisted = persistCover(book, coverUrl, manager)
            // 只有匿名可访问的封面地址才能给 KOReader 使用
            if (persisted && manager.isCoverPublic(coverUrl)) {
                book.putVariable("kosyncCoverUrl", coverUrl)
            }
        }
        runCatching { appDb.bookDao.update(book) }.onFailure {
            AppLog.put("OPDS 元数据写入失败: ${book.name}\n${it.localizedMessage}", it)
        }
    }

    private suspend fun persistCover(
        book: Book,
        coverUrl: String,
        manager: RemoteBookOpds,
    ): Boolean {
        val temp = File.createTempFile("opds-cover-", ".tmp", appCtx.cacheDir)
        return try {
            val stream = manager.downloadCover(coverUrl) ?: return false
            stream.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            val persistent = installPersistentCover(temp, File(appCtx.externalFiles, "covers"))
            book.persistedCoverUrl = persistent.absolutePath
            true
        } catch (e: Exception) {
            AppLog.put("OPDS 封面保存失败: ${book.name}\n${e.localizedMessage}", e)
            false
        } finally {
            temp.delete()
        }
    }
}
