package io.legado.app.ui.book.import.remote

import android.app.Application
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.Server
import io.legado.app.model.opds.OpdsClient
import io.legado.app.model.opds.OpdsError
import io.legado.app.model.opds.OpdsException
import io.legado.app.model.opds.OpdsUrl
import io.legado.app.utils.toastOnUi
import okhttp3.Credentials
import java.nio.charset.StandardCharsets

class ServerConfigViewModel(application: Application) : BaseViewModel(application) {

    var mServer: Server? = null

    fun init(id: Long?, onSuccess: () -> Unit) {
        //mServer不为空可能是旋转屏幕界面重新创建,不用更新数据
        if (mServer != null) return
        execute {
            mServer = if (id != null) {
                appDb.serverDao.get(id)
            } else {
                Server()
            }
        }.onSuccess {
            onSuccess.invoke()
        }
    }

    fun save(server: Server, onSuccess: () -> Unit) {
        execute {
            mServer = server
            appDb.serverDao.insert(server)
        }.onSuccess {
            onSuccess.invoke()
        }.onError {
            context.toastOnUi("保存出错\n${it.localizedMessage}")
        }
    }

    fun testConnection(server: Server, onResult: (Boolean, String) -> Unit) {
        val config = server.getOpdsConfig()
        if (config == null) {
            onResult(false, context.getString(R.string.test_connection_only_opds))
            return
        }
        execute {
            val authorization = Credentials.basic(
                config.username,
                config.password,
                StandardCharsets.UTF_8
            )
            OpdsClient().getFeed(OpdsUrl.normalize(config.url), authorization)
        }.onSuccess {
            onResult(true, context.getString(R.string.connection_success, it.entries.size))
        }.onError {
            onResult(false, it.toUserMessage())
        }
    }

    private fun Throwable.toUserMessage(): String {
        return when ((this as? OpdsException)?.error) {
            OpdsError.UNAUTHORIZED -> context.getString(R.string.auth_failed)
            OpdsError.UNAUTHORIZED_REDIRECT -> context.getString(R.string.auth_failed_redirect)
            OpdsError.FORBIDDEN -> context.getString(R.string.download_forbidden)
            OpdsError.NOT_FOUND -> context.getString(R.string.not_opds)
            OpdsError.NOT_OPDS -> context.getString(R.string.not_opds)
            OpdsError.NETWORK -> context.getString(R.string.network_error)
            OpdsError.SERVER -> context.getString(R.string.server_error)
            null -> localizedMessage ?: context.getString(R.string.connection_failed)
        }
    }
}
