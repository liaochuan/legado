package io.legado.app.model.opds

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder

object OpdsUrl {

    /**
     * 用户可填 host、host:port、带路径的地址，也可直接填 /opds。
     * 统一补成以 /opds 结尾的入口。
     */
    fun normalize(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { "OPDS 地址不能为空" }
        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        val url = withScheme.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("无效的 OPDS 地址")
        val path = url.encodedPath
        if (path == "/opds" || path.endsWith("/opds")) return withScheme
        return "$withScheme/opds"
    }

    /**
     * 相对链接以当前 feed 的最终地址为 base 解析。
     */
    fun resolve(base: String, href: String): String {
        val baseUrl = base.toHttpUrlOrNull() ?: return href
        return baseUrl.resolve(href)?.toString() ?: href
    }

    fun search(root: String, key: String): String {
        val encoded = URLEncoder.encode(key, "UTF-8").replace("+", "%20")
        return "${normalize(root)}/search/$encoded"
    }
}
