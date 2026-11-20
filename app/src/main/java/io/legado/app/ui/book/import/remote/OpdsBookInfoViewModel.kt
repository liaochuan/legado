package io.legado.app.ui.book.import.remote

import android.app.Application
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.exception.NoStackTraceException
import io.legado.app.lib.webdav.Authorization
import io.legado.app.model.opds.OpdsUrl
import io.legado.app.model.remote.OpdsBookImporter
import io.legado.app.model.remote.RemoteBook
import io.legado.app.model.remote.RemoteBookOpds
import io.legado.app.utils.toastOnUi
import java.nio.charset.StandardCharsets

class OpdsBookInfoViewModel(application: Application) : BaseViewModel(application) {

    fun download(
        serverId: Long,
        remoteBook: RemoteBook,
        format: String?,
        onResult: (Boolean) -> Unit
    ) {
        execute {
            val server = appDb.serverDao.get(serverId)
                ?: throw NoStackTraceException("服务器不存在")
            val config = server.getOpdsConfig()
                ?: throw NoStackTraceException("OPDS 配置无效")
            val manager = RemoteBookOpds(
                OpdsUrl.normalize(config.url),
                Authorization(config.username, config.password, StandardCharsets.UTF_8)
            )
            OpdsBookImporter.import(manager, remoteBook, format)
        }.onSuccess {
            onResult(true)
        }.onError {
            context.toastOnUi(it.localizedMessage ?: context.getString(R.string.download_failed))
            onResult(false)
        }
    }
}
