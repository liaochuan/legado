package io.legado.app.model.opds

data class OpdsFeed(
    val title: String?,
    val nextUrl: String?,
    val entries: List<OpdsEntry>
)

data class OpdsEntry(
    val title: String,
    val id: String?,
    val updated: Long,
    val author: String?,
    val categories: List<String>,
    val navigationUrl: String?,
    val coverUrl: String?,
    val acquisitionLinks: List<OpdsLink>,
    val summary: String? = null,
)

data class OpdsLink(
    val href: String,
    val mimeType: String?,
    val length: Long?,
    val label: String?
)
