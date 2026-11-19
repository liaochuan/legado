package io.legado.app.model.localBook

/**
 * 本地书籍已解析出的封面文件优先于书源网络封面。
 *
 * 网络封面可能因 URL 失效、鉴权过期或第三方清理缓存而无法加载，
 * 本地文件则可以长期复用；本地文件不存在时才回退到网络封面。
 */
internal fun resolveLocalBookCover(
    localCoverPath: String,
    localCoverExists: Boolean,
    fallbackCoverUrl: String?,
): String? = if (localCoverExists) localCoverPath else fallbackCoverUrl
