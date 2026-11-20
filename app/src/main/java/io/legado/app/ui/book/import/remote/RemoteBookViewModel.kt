package io.legado.app.ui.book.import.remote

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Server
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.webdav.Authorization
import io.legado.app.model.opds.OpdsError
import io.legado.app.model.opds.OpdsException
import io.legado.app.model.opds.OpdsUrl
import io.legado.app.model.remote.OpdsBookImporter
import io.legado.app.model.remote.RemoteBook
import io.legado.app.model.remote.RemoteBookManager
import io.legado.app.model.remote.RemoteBookOpds
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.nio.charset.StandardCharsets
import java.util.Collections

class RemoteBookViewModel(application: Application) : BaseViewModel(application) {
    private val FORMAT_ORDER = listOf("EPUB", "MOBI", "AZW3", "PDF", "TXT")

    var sortKey = RemoteBookSort.Default
    var sortAscending = false
    val dirList = arrayListOf<RemoteBook>()
    val permissionDenialLiveData = MutableLiveData<Int>()

    var dataCallback: DataCallback? = null

    val dataFlow = callbackFlow<List<RemoteBook>> {

        val list = Collections.synchronizedList(ArrayList<RemoteBook>())

        dataCallback = object : DataCallback {

            override fun setItems(remoteFiles: List<RemoteBook>) {
                list.clear()
                list.addAll(remoteFiles)
                trySend(list)
            }

            override fun addItems(remoteFiles: List<RemoteBook>) {
                list.addAll(remoteFiles)
                trySend(list)
            }

            override fun clear() {
                list.clear()
                trySend(emptyList())
            }

            override fun screen(key: String?) {
                if (key.isNullOrBlank()) {
                    trySend(list)
                } else {
                    trySend(
                        list.filter { it.filename.contains(key) }
                    )
                }
            }
        }

        awaitClose {
            dataCallback = null
        }
    }.map { list ->
        if (isOpds) list else if (sortAscending) when (sortKey) {
            RemoteBookSort.Name -> list.sortedWith(compareBy<RemoteBook> { !it.isDir }
                    then compareBy(AlphanumComparator) { it.filename })

            else -> list.sortedWith(compareBy({ !it.isDir }, { it.lastModify }))
        } else when (sortKey) {
            RemoteBookSort.Name -> list.sortedWith { o1, o2 ->
                val compare = -compareValues(o1.isDir, o2.isDir)
                if (compare == 0) {
                    return@sortedWith -AlphanumComparator.compare(o1.filename, o2.filename)
                }
                return@sortedWith compare
            }

            else -> list.sortedWith { o1, o2 ->
                val compare = -compareValues(o1.isDir, o2.isDir)
                if (compare == 0) {
                    return@sortedWith -compareValues(o1.lastModify, o2.lastModify)
                }
                return@sortedWith compare
            }
        }
    }.flowOn(Dispatchers.IO)

    private var manager: RemoteBookManager? = null
    var serverId: Long = AppConst.DEFAULT_WEBDAV_ID
        private set
    var isDefaultWebdav = false
    @Volatile
    var isOpds = false
        private set
    @Volatile
    var lastError: String? = null
        private set
    private var listRequestId = 0
    val errorLiveData = MutableLiveData<String>()
    val pendingFormatChoice = MutableLiveData<List<RemoteBook>>()
    var isSearching = false
        private set
    var searchKey: String? = null
        private set
    private var isLoadingMore = false
    private var currentFeedUrl: String? = null

    fun initData(onSuccess: () -> Unit) {
        execute {
            isDefaultWebdav = false
            isOpds = false
            val server = appDb.serverDao.get(AppConfig.remoteServerId)
            serverId = server?.id ?: AppConst.DEFAULT_WEBDAV_ID
            if (server?.type == Server.TYPE.OPDS) {
                val config = server.getOpdsConfig()
                    ?: throw NoStackTraceException("OPDS 配置无效")
                manager = RemoteBookOpds(
                    OpdsUrl.normalize(config.url),
                    Authorization(config.username, config.password, StandardCharsets.UTF_8)
                )
                isOpds = true
                return@execute
            }
            server?.getWebDavConfig()?.let {
                manager = RemoteBookWebDav(it.url, Authorization(it), server.id)
                return@execute
            }
            isDefaultWebdav = true
            manager = AppWebDav.defaultBookWebDav
                ?: throw NoStackTraceException("webDav没有配置")
        }.onError {
            context.toastOnUi("初始化服务器出错:${it.localizedMessage}")
        }.onSuccess {
            onSuccess.invoke()
        }
    }

    private fun Throwable.toUserMessage(): String {
        return when ((this as? OpdsException)?.error) {
            OpdsError.UNAUTHORIZED -> context.getString(R.string.auth_failed)
            OpdsError.UNAUTHORIZED_REDIRECT -> context.getString(R.string.auth_failed_redirect)
            OpdsError.FORBIDDEN -> context.getString(R.string.download_forbidden)
            OpdsError.NOT_FOUND -> context.getString(R.string.not_opds)
            OpdsError.NOT_OPDS -> context.getString(R.string.not_opds_response)
            OpdsError.NETWORK -> context.getString(R.string.network_error)
            OpdsError.SERVER -> context.getString(R.string.server_error)
            null -> context.getString(R.string.connection_failed)
        }
    }

    private fun Throwable.sanitizedMessage(): String? {
        return localizedMessage?.replace(Regex("//[^/@\\s]+@"), "//")
    }

    fun loadRemoteBookList(path: String?, loadCallback: (loading: Boolean) -> Unit) {
        val requestId = ++listRequestId
        executeLazy {
            val bookManager = manager
                ?: throw NoStackTraceException("没有配置服务器")
            val url = path ?: when (bookManager) {
                is RemoteBookWebDav -> bookManager.rootBookUrl
                is RemoteBookOpds -> bookManager.rootUrl
                else -> ""
            }
            currentFeedUrl = url
            bookManager.getRemoteBookList(url)
        }.onSuccess {
            if (requestId != listRequestId) return@onSuccess
            lastError = null
            errorLiveData.postValue("")
            dataCallback?.setItems(it)
        }.onError {
            if (requestId != listRequestId) return@onError
            val message = it.toUserMessage()
            lastError = message
            errorLiveData.postValue(message)
            dataCallback?.clear()
            AppLog.put("获取网络书库出错\n${it.sanitizedMessage()}", it)
        }.onStart {
            loadCallback.invoke(true)
        }.onFinally {
            loadCallback.invoke(false)
        }.start()
    }

    fun search(key: String) {
        if (!isOpds) return
        val bookManager = manager as? RemoteBookOpds ?: return
        val requestId = ++listRequestId
        isSearching = true
        searchKey = key
        currentFeedUrl = OpdsUrl.search(bookManager.rootUrl, key)
        execute {
            bookManager.search(key)
        }.onSuccess {
            if (requestId != listRequestId) return@onSuccess
            lastError = null
            errorLiveData.postValue("")
            dataCallback?.setItems(it)
        }.onError {
            if (requestId != listRequestId) return@onError
            val message = it.toUserMessage()
            lastError = message
            errorLiveData.postValue(message)
        }
    }

    fun exitSearch() {
        if (!isSearching) return
        isSearching = false
        searchKey = null
        loadRemoteBookList(dirList.lastOrNull()?.path) { }
    }

    fun clearSearchState() {
        isSearching = false
        searchKey = null
    }

    fun loadMore() {
        if (isLoadingMore) return
        if (!isOpds) return
        val bookManager = manager as? RemoteBookOpds ?: return
        val path = currentFeedUrl ?: return
        isLoadingMore = true
        execute {
            bookManager.loadNextPage(path)
        }.onSuccess {
            if (path != currentFeedUrl) return@onSuccess
            if (it.isNotEmpty()) dataCallback?.addItems(it)
        }.onError {
            AppLog.put("加载下一页失败\n${it.sanitizedMessage()}", it)
        }.onFinally {
            isLoadingMore = false
        }
    }

    fun requestFormatChoice(remoteBooks: Collection<RemoteBook>) {
        val multi = remoteBooks.filter { it.links.size > 1 }
        if (multi.isEmpty()) return
        pendingFormatChoice.value = multi
    }

    fun consumeFormatChoice() {
        pendingFormatChoice.value = emptyList()
    }

    fun formatOptions(remoteBooks: Collection<RemoteBook>): List<String> {
        val opds = manager as? RemoteBookOpds ?: return emptyList()
        val multi = remoteBooks.filter { it.links.size > 1 }
        if (multi.isEmpty()) return emptyList()
        val sets = multi.map { opds.formats(it).toSet() }
        val intersection = sets.reduce { acc, set -> acc intersect set }
        val options = intersection.ifEmpty { sets.flatten().toSet() }
        return options.sortedBy { format ->
            val index = FORMAT_ORDER.indexOf(format)
            if (index < 0) FORMAT_ORDER.size else index
        }
    }

    fun addToBookshelf(
        remoteBooks: HashSet<RemoteBook>,
        forcedFormat: String? = null,
        finally: () -> Unit
    ) {
        execute {
            val currentManager = manager
                ?: throw NoStackTraceException("没有配置服务器")
            val targets = remoteBooks.toList()
            var success = 0
            var failed = 0
            var forbidden = 0
            var firstError: Throwable? = null
            targets.forEach { remoteBook ->
                kotlin.runCatching {
                    downloadOne(currentManager, remoteBook, forcedFormat)
                }.onSuccess {
                    success++
                }.onFailure {
                    if ((it as? OpdsException)?.error == OpdsError.FORBIDDEN) {
                        forbidden++
                    } else {
                        failed++
                    }
                    if (firstError == null) firstError = it
                    AppLog.put("下载失败\n${remoteBook.filename}\n${it.sanitizedMessage()}", it)
                    if (it is SecurityException) {
                        permissionDenialLiveData.postValue(1)
                    }
                }
            }
            if (targets.size == 1 && firstError != null) {
                throw firstError!!
            }
            Triple(success, failed, forbidden)
        }.onSuccess { (success, failed, forbidden) ->
            if (remoteBooks.size > 1) {
                val base = context.getString(R.string.batch_download_result, success, failed)
                val message = if (forbidden > 0) {
                    base + "\n" + context.getString(R.string.forbidden_count, forbidden)
                } else {
                    base
                }
                context.toastOnUi(message)
            }
        }.onError {
            val detail = it.sanitizedMessage() ?: context.getString(R.string.download_failed)
            context.toastOnUi(context.getString(R.string.download_failed) + "\n" + detail)
        }.onFinally {
            finally.invoke()
        }
    }

    private suspend fun downloadOne(
        manager: RemoteBookManager,
        remoteBook: RemoteBook,
        forcedFormat: String?
    ) {
        OpdsBookImporter.import(manager, remoteBook, forcedFormat)
    }

    fun updateCallBackFlow(filterKey: String?) {
        dataCallback?.screen(filterKey)
    }

    interface DataCallback {

        fun setItems(remoteFiles: List<RemoteBook>)

        fun addItems(remoteFiles: List<RemoteBook>)

        fun clear()

        fun screen(key: String?)

    }
}