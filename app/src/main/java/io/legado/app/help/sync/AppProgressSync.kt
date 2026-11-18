package io.legado.app.help.sync

import io.legado.app.constant.AppConst
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.okHttpClient

object AppProgressSync {

    fun isKosyncEligible(book: Book): Boolean {
        return book.isLocal && (book.isLocalTxt || book.isEpub)
    }

    suspend fun syncOnOpen(
        book: Book,
        plus: Boolean,
        onApplyRemote: ((BookProgress) -> Unit)?,
        onPromptRemote: ((BookProgress) -> Unit)?,
        onMessage: (String) -> Unit,
        anchorText: String? = null,
        onUploaded: (() -> Unit)? = null,
    ) {
        if (!AppConfig.syncBookProgress) return
        coordinator().syncOnOpen(
            book = book,
            plus = plus,
            onApplyRemote = onApplyRemote,
            onPromptRemote = onPromptRemote,
            onMessage = onMessage,
            anchorText = anchorText,
            onUploaded = onUploaded,
        )
    }

    suspend fun syncOnClose(
        book: Book,
        onMessage: (String) -> Unit = {},
        anchorText: String? = null,
    ) {
        if (!AppConfig.syncBookProgress) return
        coordinator().syncOnClose(book, onMessage, anchorText)
    }

    suspend fun push(
        book: Book,
        onMessage: (String) -> Unit = {},
        onUploaded: (() -> Unit)? = null,
        anchorText: String? = null,
    ) {
        if (!AppConfig.syncBookProgress) return
        coordinator().push(book, onMessage, onUploaded, anchorText)
    }

    suspend fun syncAllOnStartup(books: List<Book>) {
        if (!AppConfig.syncBookProgress) return
        if (!AppConfig.progressSyncKosync || !AppConfig.kosyncBatchOnStartup) return
        coordinator().syncAllOnStartup(books)
    }

    private fun coordinator(): ProgressSyncCoordinator {
        val kosyncConfig = AppConfig.kosyncConfig
        val kosync = if (AppConfig.progressSyncKosync && kosyncConfig.isConfigured()) {
            KosyncProgressProvider(
                client = KosyncClient(
                    baseUrl = kosyncConfig.serverUrl,
                    username = kosyncConfig.username,
                    authKey = kosyncConfig.authKey,
                    client = okHttpClient,
                ),
                deviceName = kosyncConfig.deviceName,
                deviceId = AppConst.androidId,
            )
        } else {
            null
        }
        val webDav = if (AppConfig.progressSyncWebDav && AppWebDav.isOk) {
            WebDavProgressProvider()
        } else {
            null
        }
        return ProgressSyncCoordinator(
            webDav = webDav,
            kosync = kosync,
            isKosyncEligible = ::isKosyncEligible,
        )
    }
}
