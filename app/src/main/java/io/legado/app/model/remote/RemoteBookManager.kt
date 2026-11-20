package io.legado.app.model.remote

import android.net.Uri
import io.legado.app.data.entities.Book

abstract class RemoteBookManager {

    /**
     * 获取书籍列表
     */
    @Throws(Exception::class)
    abstract suspend fun getRemoteBookList(path: String): MutableList<RemoteBook>

    /**
     * 根据书籍地址获取书籍信息
     */
    @Throws(Exception::class)
    abstract suspend fun getRemoteBook(path: String): RemoteBook?

    /**
     * @return Uri：下载到本地的路径
     */
    @Throws(Exception::class)
    abstract suspend fun downloadRemoteBook(remoteBook: RemoteBook): Uri

    /**
     * 上传书籍
     */
    @Throws(Exception::class)
    abstract suspend fun upload(book: Book)

    /**
     * 删除书籍
     */
    @Throws(Exception::class)
    abstract suspend fun delete(remoteBookUrl: String)

    /**
     * 是否支持服务端搜索
     */
    open val supportsSearch: Boolean
        get() = false

    /**
     * 服务端搜索
     */
    @Throws(Exception::class)
    open suspend fun search(key: String): MutableList<RemoteBook> = mutableListOf()

    /**
     * 加载当前目录的下一页
     */
    @Throws(Exception::class)
    open suspend fun loadNextPage(path: String): MutableList<RemoteBook> = mutableListOf()

}