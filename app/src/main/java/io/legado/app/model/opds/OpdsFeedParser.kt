package io.legado.app.model.opds

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.time.OffsetDateTime

object OpdsFeedParser {

    private const val ACQUISITION = "http://opds-spec.org/acquisition"
    private const val IMAGE = "http://opds-spec.org/image"
    private const val IMAGE_THUMBNAIL = "http://opds-spec.org/image/thumbnail"

    fun parse(xml: String, baseUrl: String): OpdsFeed {
        val doc = Jsoup.parse(xml, baseUrl, Parser.xmlParser())
        val feed = doc.selectFirst("feed")
            ?: throw OpdsException(OpdsError.NOT_OPDS, "返回内容不是 OPDS 目录")
        val nextUrl = feed.children()
            .firstOrNull { it.tagName() == "link" && it.attr("rel") == "next" }
            ?.absUrl("href")
            ?.takeIf { it.isNotBlank() }
        val entries = feed.select("entry").mapNotNull(::parseEntry)
        return OpdsFeed(
            title = feed.selectFirst("title")?.text()?.trim()?.takeIf { it.isNotEmpty() },
            nextUrl = nextUrl,
            entries = entries
        )
    }

    private fun parseEntry(entry: Element): OpdsEntry? {
        val title = entry.selectFirst("title")?.text()?.trim().orEmpty()
        if (title.isEmpty()) return null
        val links = entry.select("link")
        val acquisitionLinks = links
            .filter { it.attr("rel").startsWith(ACQUISITION) }
            .mapNotNull { link ->
                val href = link.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                OpdsLink(
                    href = href,
                    mimeType = link.attr("type").takeIf { it.isNotBlank() },
                    length = link.attr("length").toLongOrNull(),
                    label = link.attr("title").takeIf { it.isNotBlank() }
                )
            }
        val navigationUrl = if (acquisitionLinks.isNotEmpty()) {
            null
        } else {
            links.firstOrNull { it.attr("rel") == "subsection" }?.absUrl("href")?.takeIf { it.isNotBlank() }
                ?: links.firstOrNull {
                    it.attr("type").contains("atom+xml") &&
                        it.attr("rel") != "self" &&
                        it.attr("rel") != "start"
                }?.absUrl("href")?.takeIf { it.isNotBlank() }
        }
        val coverUrl = links.firstOrNull { it.attr("rel") == IMAGE_THUMBNAIL }
            ?.absUrl("href")?.takeIf { it.isNotBlank() }
            ?: links.firstOrNull { it.attr("rel") == IMAGE }
                ?.absUrl("href")?.takeIf { it.isNotBlank() }
        val summary = entry.selectFirst("content")?.let { content ->
            val paragraphs = content.select("p")
            val text = if (paragraphs.isNotEmpty()) {
                paragraphs.joinToString("\n") { htmlToText(it.html()) }
            } else {
                htmlToText(content.html())
            }
            text.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("TAGS:", ignoreCase = true) }
                .joinToString("\n") { "　　$it" }
                .takeIf { it.isNotEmpty() }
        }
        return OpdsEntry(
            title = title,
            id = entry.selectFirst("id")?.text()?.takeIf { it.isNotBlank() },
            updated = parseDate(entry.selectFirst("updated")?.text()),
            author = entry.selectFirst("author > name")?.text()?.takeIf { it.isNotBlank() },
            categories = entry.select("category").mapNotNull {
                it.attr("label").ifBlank { it.attr("term") }.takeIf(String::isNotBlank)
            },
            navigationUrl = navigationUrl,
            coverUrl = coverUrl,
            acquisitionLinks = acquisitionLinks,
            summary = summary,
        )
    }

    /**
     * calibre-web 的简介是「HTML 里再转义一层 HTML」，需要解析两次才能拿到纯文本。
     */
    private fun htmlToText(html: String): String {
        // 第一遍解码 calibre-web 的实体，得到形如 "<p>第一段</p> <p>第二段</p>" 的文本；
        // 此时换行已被 Jsoup 压成空格，只能靠 </p> 恢复段落边界。
        val once = Jsoup.parse(html).text()
        return once
            .replace(Regex("(?i)</p\\s*>"), "\n")
            .replace(Regex("(?i)<p[^>]*>"), "")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .trim()
    }

    private fun parseDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        return runCatching {
            OffsetDateTime.parse(text).toInstant().toEpochMilli()
        }.getOrDefault(0L)
    }
}
