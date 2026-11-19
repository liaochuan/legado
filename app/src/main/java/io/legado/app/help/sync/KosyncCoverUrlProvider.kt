package io.legado.app.help.sync

import io.legado.app.data.entities.Book

/**
 * 提供给 crosspoint-sync 的封面地址。
 *
 * 只接受 HTTP/HTTPS URL；本地文件路径一律视为“没有封面地址”，不发起请求。
 * 后续接入 calibre-web 时，把远程封面写入 customCoverUrl、coverUrl
 * 或 book.variableMap["kosyncCoverUrl"] 即可自动生效。
 */
object KosyncCoverUrlProvider {

    fun coverUrl(book: Book): String? {
        return sequenceOf(
            book.customCoverUrl,
            book.coverUrl,
            book.variableMap["kosyncCoverUrl"],
        ).firstOrNull { isHttpUrl(it) }
    }

    private fun isHttpUrl(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
    }
}
