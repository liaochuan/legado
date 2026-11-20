package io.legado.app.model.remote

import android.net.Uri
import io.legado.app.data.entities.Book
import io.legado.app.lib.webdav.Authorization
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.opds.OpdsClient
import io.legado.app.model.opds.OpdsEntry
import io.legado.app.model.opds.OpdsError
import io.legado.app.model.opds.OpdsException
import io.legado.app.model.opds.OpdsLink
import io.legado.app.model.opds.OpdsUrl
import io.legado.app.utils.normalizeFileName
import java.net.URLEncoder

class RemoteBookOpds(
    val rootUrl: String,
    private val authorization: Authorization,
    private val client: OpdsClient = OpdsClient(),
    private val isOnShelf: (String) -> Boolean = { LocalBook.isOnBookShelf(it) },
) : RemoteBookManager() {

    private val authHeader: String get() = authorization.data
    private val nextUrls = java.util.concurrent.ConcurrentHashMap<String, String>()

    override suspend fun getRemoteBookList(path: String): MutableList<RemoteBook> {
        val feed = client.getFeed(path, authHeader)
        feed.nextUrl?.let { nextUrls[path] = it } ?: nextUrls.remove(path)
        return feed.entries.mapNotNull { it.toRemoteBook() }.toMutableList()
    }

    suspend fun downloadCover(coverUrl: String): java.io.InputStream? {
        return runCatching { client.download(coverUrl, authHeader).stream }.getOrNull()
    }

    suspend fun isCoverPublic(coverUrl: String): Boolean {
        return runCatching { client.isAccessibleWithoutAuth(coverUrl) }.getOrDefault(false)
    }

    override suspend fun getRemoteBook(path: String): RemoteBook? = null

    override suspend fun downloadRemoteBook(remoteBook: RemoteBook): Uri {
        val file = client.download(remoteBook.path, authHeader)
        val fileName = (file.fileName ?: fallbackFileName(remoteBook)).normalizeFileName()
        return file.stream.use { stream ->
            LocalBook.saveBookFile(stream, fileName)
        }
    }

    override suspend fun upload(book: Book) {
        throw UnsupportedOperationException("OPDS 不支持上传")
    }

    override suspend fun delete(remoteBookUrl: String) {
        throw UnsupportedOperationException("OPDS 不支持删除")
    }

    override val supportsSearch: Boolean
        get() = true

    override suspend fun search(key: String): MutableList<RemoteBook> {
        val url = OpdsUrl.search(rootUrl, key)
        val feed = try {
            client.getFeed(url, authHeader)
        } catch (e: OpdsException) {
            if (e.error != OpdsError.NOT_FOUND) throw e
            val fallback = "$rootUrl/search?query=${URLEncoder.encode(key, "UTF-8")}"
            client.getFeed(fallback, authHeader)
        }
        feed.nextUrl?.let { nextUrls[url] = it } ?: nextUrls.remove(url)
        return feed.entries.mapNotNull { it.toRemoteBook() }.toMutableList()
    }

    override suspend fun loadNextPage(path: String): MutableList<RemoteBook> {
        val next = nextUrls[path] ?: return mutableListOf()
        val feed = client.getFeed(next, authHeader)
        feed.nextUrl?.let { nextUrls[path] = it } ?: nextUrls.remove(path)
        return feed.entries.mapNotNull { it.toRemoteBook() }.toMutableList()
    }

    fun formats(remoteBook: RemoteBook): List<String> {
        if (remoteBook.links.isEmpty()) return listOf(remoteBook.contentType)
        return remoteBook.links
            .map { (it.extension ?: extensionOf(it.href, it.mimeType, it.label)).uppercase() }
            .distinct()
    }

    /**
     * 批量下载统一格式时，若该书没有该格式，回退到首选格式，避免整本失败。
     */
    fun targetForFormat(remoteBook: RemoteBook, format: String?): RemoteBook {
        if (format == null || remoteBook.links.isEmpty()) return remoteBook
        val link = linkForFormat(remoteBook, format) ?: return remoteBook
        return remoteBook.copy(
            path = link.href,
            contentType = (link.extension ?: format).uppercase()
        )
    }

    fun linkForFormat(remoteBook: RemoteBook, format: String): RemoteBookLink? {
        return remoteBook.links.firstOrNull { link ->
            link.extension?.equals(format, ignoreCase = true) == true ||
                link.label?.equals(format, ignoreCase = true) == true ||
                extensionOf(link.href, link.mimeType, link.label)
                    .equals(format, ignoreCase = true)
        }
    }

    private fun OpdsEntry.toRemoteBook(): RemoteBook? {
        navigationUrl?.let { url ->
            return RemoteBook(
                filename = title,
                path = url,
                size = 0,
                lastModify = updated,
                contentType = "folder",
            )
        }
        val preferred = pickPreferred(acquisitionLinks) ?: return null
        val extension = extensionOf(preferred.href, preferred.mimeType, preferred.label)
        val remoteLinks = acquisitionLinks.map {
            RemoteBookLink(
                it.href,
                it.mimeType,
                it.length,
                it.label,
                extensionOf(it.href, it.mimeType, it.label)
            )
        }
        val names = candidateNames(title, remoteLinks)
        val matchedName = names.firstOrNull(isOnShelf)
        return RemoteBook(
            filename = title,
            path = preferred.href,
            size = preferred.length ?: 0,
            lastModify = updated,
            contentType = extension.uppercase(),
            isOnBookShelf = matchedName != null,
            links = remoteLinks,
            coverUrl = coverUrl,
            coverHeaders = mapOf("Authorization" to authHeader),
            author = author,
            localFileName = matchedName,
            intro = summary,
            categories = categories,
        )
    }

    private fun pickPreferred(links: List<OpdsLink>): OpdsLink? {
        if (links.isEmpty()) return null
        return links.minByOrNull { link ->
            val index = FORMAT_PRIORITY.indexOf(link.mimeType)
            if (index < 0) FORMAT_PRIORITY.size else index
        }
    }

    private fun extensionOf(href: String, mimeType: String?, label: String?): String {
        val normalizedLabel = label?.lowercase()
        if (normalizedLabel != null && normalizedLabel in SUPPORTED_EXTENSIONS) {
            return normalizedLabel
        }
        return when (mimeType) {
            "application/epub+zip" -> "epub"
            "application/x-mobipocket-ebook" -> "mobi"
            "application/vnd.amazon.ebook" -> "azw3"
            "application/pdf" -> "pdf"
            "text/plain" -> "txt"
            else -> href.trimEnd('/').substringAfterLast('.', "bin").lowercase()
        }
    }

    private fun candidateNames(
        title: String,
        links: List<RemoteBookLink>
    ): List<String> {
        val names = linkedSetOf<String>()
        links.forEach { link ->
            val extension = extensionOf(link.href, link.mimeType, link.label)
            names.add("$title.$extension")
            names.add("$title.$extension".normalizeFileName())
            link.href.trimEnd('/')
                .substringAfterLast('/')
                .takeIf { it.contains('.') }
                ?.let(names::add)
        }
        return names.toList()
    }

    private fun fallbackFileName(remoteBook: RemoteBook): String {
        val extension = remoteBook.contentType.lowercase()
        return "${remoteBook.filename}.$extension".normalizeFileName()
    }

    companion object {
        private val FORMAT_PRIORITY = listOf(
            "application/epub+zip",
            "application/x-mobipocket-ebook",
            "application/vnd.amazon.ebook",
            "application/pdf",
            "text/plain",
        )
        private val SUPPORTED_EXTENSIONS = setOf(
            "epub", "mobi", "azw3", "azw", "pdf", "txt"
        )
    }
}
