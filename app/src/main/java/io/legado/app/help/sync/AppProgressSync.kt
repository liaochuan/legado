package io.legado.app.help.sync

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.okHttpClient
import java.util.concurrent.TimeUnit

object AppProgressSync {

    private val kosyncHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

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

    /**
     * 在用户更换封面后立即把远程封面地址推送到 crosspoint-sync。
     * 只有 customCoverUrl/coverUrl/变量里存在 HTTP(S) 地址时才发请求。
     */
    suspend fun updateCover(book: Book) {
        if (!AppConfig.syncBookProgress || !AppConfig.progressSyncKosync) return
        if (!isKosyncEligible(book)) return
        val config = AppConfig.kosyncConfig
        if (!config.isConfigured()) return
        val coverUrl = KosyncCoverUrlProvider.coverUrl(book) ?: return
        val document = KosyncDocumentId.compute(book) ?: return
        runCatching {
            KosyncClient(
                baseUrl = config.serverUrl,
                username = config.username,
                authKey = config.authKey,
                client = kosyncHttpClient,
            ).putDocumentInfo(document, coverUrl)
        }.onFailure {
            AppLog.put("更新 KOReader 封面失败\n${it.localizedMessage}", it)
        }
    }

    private fun coordinator(): ProgressSyncCoordinator {
        val kosyncConfig = AppConfig.kosyncConfig
        val kosync = if (AppConfig.progressSyncKosync && kosyncConfig.isConfigured()) {
            KosyncProgressProvider(
                client = KosyncClient(
                    baseUrl = kosyncConfig.serverUrl,
                    username = kosyncConfig.username,
                    authKey = kosyncConfig.authKey,
                    client = kosyncHttpClient,
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
