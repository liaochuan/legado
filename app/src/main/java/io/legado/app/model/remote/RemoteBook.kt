package io.legado.app.model.remote

import androidx.annotation.Keep
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.model.localBook.LocalBook

@Keep
data class RemoteBook(
    val filename: String,
    val path: String,
    val size: Long,
    val lastModify: Long,
    var contentType: String = "folder",
    var isOnBookShelf: Boolean = false,
    val links: List<RemoteBookLink> = emptyList(),
    val coverUrl: String? = null,
    val coverHeaders: Map<String, String> = emptyMap(),
    val author: String? = null,
    val localFileName: String? = null,
    val intro: String? = null,
    val categories: List<String> = emptyList(),
) {

    val isDir get() = contentType == "folder"

    constructor(webDavFile: WebDavFile) : this(
        webDavFile.displayName,
        webDavFile.path,
        webDavFile.size,
        webDavFile.lastModify
    ) {
        if (!webDavFile.isDir) {
            contentType = webDavFile.displayName.substringAfterLast(".")
            isOnBookShelf = LocalBook.isOnBookShelf(webDavFile.displayName)
        }
    }

}

data class RemoteBookLink(
    val href: String,
    val mimeType: String?,
    val length: Long?,
    val label: String? = null,
    val extension: String? = null,
)
