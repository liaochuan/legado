package io.legado.app.help.sync

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.help.book.BookHelp
import io.legado.app.model.localBook.EpubFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import kotlin.math.abs

interface EpubSpineSource {
    fun hrefs(): List<String>
    fun body(href: String): Element?

    /** spine 文档的原始字节数，用于按 crengine 的字节模型计算 percentage。 */
    fun byteLength(href: String): Long
}

class EpubFileSpineSource(private val book: Book) : EpubSpineSource {
    private val epubFile by lazy { EpubFile(book) }

    override fun hrefs(): List<String> = epubFile.getSpineHrefs()

    override fun body(href: String): Element? = epubFile.getSpineBody(href)

    override fun byteLength(href: String): Long = epubFile.getSpineByteLength(href)
}

/**
 * 把 Legado 的 EPUB 章节位置映射为 KOReader / CrossPoint 的块级 XPointer。
 *
 * percentage 使用 crengine 的模型：spine 文档累计字节 / spine 总字节。
 * XPointer 输出 crengine 风格（每级显式下标 + text()[1].offset），
 * 章节开头固定锚定标题或第一个文本块。
 */
class EpubPositionMapper(
    private val book: Book,
    private val spine: EpubSpineSource,
    private val chapters: () -> List<BookChapter>,
    private val chapterContent: (BookChapter) -> String? = { BookHelp.getContent(book, it) },
) : PositionMapper {

    private val allChapters by lazy { chapters() }
    private val hrefs by lazy { spine.hrefs() }
    private val docBytes by lazy { hrefs.map { spine.byteLength(it) } }
    private val cumulativeBytes by lazy {
        val result = LongArray(docBytes.size + 1)
        for (index in docBytes.indices) {
            result[index + 1] = result[index] + docBytes[index]
        }
        result
    }
    private val totalBytes: Long by lazy { cumulativeBytes.lastOrNull() ?: 0L }
    private val docPlainTextCache = HashMap<String, String>()
    private val chapterPlainTextCache = HashMap<String, String>()

    override fun toRemote(progress: BookProgress, anchorText: String?): RemotePosition? {
        val chapter = allChapters.getOrNull(progress.durChapterIndex) ?: return null
        return toRemote(chapter, progress.durChapterPos, anchorText)
    }

    fun toRemote(chapter: BookChapter, chapterPos: Int, anchorText: String?): RemotePosition? {
        val href = chapterHref(chapter)
        val spineIndex = hrefs.indexOf(href)
        if (spineIndex < 0) return null
        val body = spine.body(href) ?: return null
        val target = if (chapterPos <= 0) {
            firstBlock(body)
        } else {
            findBlockByAnchors(body, chapter, chapterPos, anchorText)
        } ?: return null
        val path = pathFromBody(body, target) ?: return null
        val pointer = buildPointer(spineIndex, path, target)
        val percentage = percentageFor(chapter, chapterPos) ?: 0.0
        val key = if (path.isEmpty()) "epub:$spineIndex:" else "epub:$spineIndex:/$path"
        return RemotePosition(pointer, percentage, key)
    }

    private fun findBlockByAnchors(
        body: Element,
        chapter: BookChapter,
        chapterPos: Int,
        anchorText: String?,
    ): Element? {
        for (anchor in anchorCandidates(chapter, chapterPos, anchorText)) {
            findBlock(body, anchor)?.let { return it }
        }
        return null
    }

    private fun anchorCandidates(
        chapter: BookChapter,
        chapterPos: Int,
        anchorText: String?,
    ): List<String> {
        val result = LinkedHashSet<String>()
        normalize(anchorText.orEmpty()).take(ANCHOR_MAX_LENGTH)
            .takeIf { it.isNotEmpty() }?.let(result::add)
        val plain = plainText(chapter) ?: return result.toList()
        val start = chapterPos.coerceIn(0, plain.length)
        for (delta in ANCHOR_OFFSETS) {
            val from = (start + delta).coerceIn(0, plain.length)
            if (from >= plain.length) continue
            val text = normalize(plain.substring(from, (from + ANCHOR_MAX_LENGTH).coerceAtMost(plain.length)))
            if (text.isNotEmpty()) result.add(text)
        }
        return result.toList()
    }

    override fun fromRemote(xpointer: String, percentage: Double): BookProgress? {
        val pointer = XPointerParser.parse(xpointer) ?: return fromPercentage(percentage)
        val href = hrefs.getOrNull((pointer.fragmentIndex ?: 1) - 1)
            ?: return fromPercentage(percentage)
        val body = spine.body(href) ?: return fromPercentage(percentage)
        val node = resolve(body, pointer) ?: return fromPercentage(percentage)
        val anchor = normalize(nodeText(node)).take(ANCHOR_MAX_LENGTH)
        if (anchor.isEmpty()) return fromPercentage(percentage)
        val splitChapters = allChapters.filter { chapterHref(it) == href }
        if (splitChapters.isEmpty()) return fromPercentage(percentage)
        var best: BookProgress? = null
        var bestScore = Double.MAX_VALUE
        var bestIndexScore = Double.MAX_VALUE
        for (chapter in splitChapters) {
            val plain = plainText(chapter) ?: continue
            var searchFrom = 0
            while (searchFrom < plain.length) {
                val position = plain.indexOf(anchor, searchFrom)
                if (position < 0) break
                val candidatePercentage = percentageFor(chapter, position) ?: break
                val score = abs(candidatePercentage - percentage)
                // 同一 href 被拆成多章、百分比相同时，用章节序号的粗估做兜底
                val indexScore = abs(chapter.index - percentage * allChapters.size)
                if (score < bestScore - 1e-9 ||
                    (abs(score - bestScore) <= 1e-9 && indexScore < bestIndexScore)
                ) {
                    bestScore = score
                    bestIndexScore = indexScore
                    best = BookProgress(
                        name = book.name,
                        author = book.author,
                        durChapterIndex = chapter.index,
                        durChapterPos = position,
                        durChapterTime = System.currentTimeMillis(),
                        durChapterTitle = chapter.title,
                    )
                }
                searchFrom = position + anchor.length.coerceAtLeast(1)
            }
        }
        return best ?: fromPercentage(percentage)
    }

    override fun fromPercentage(percentage: Double): BookProgress? {
        if (allChapters.isEmpty()) return null
        if (totalBytes <= 0) {
            val index = (percentage.coerceIn(0.0, 1.0) * allChapters.size)
                .toInt()
                .coerceIn(0, allChapters.lastIndex)
            val chapter = allChapters[index]
            return BookProgress(
                name = book.name,
                author = book.author,
                durChapterIndex = chapter.index,
                durChapterPos = 0,
                durChapterTime = System.currentTimeMillis(),
                durChapterTitle = chapter.title,
            )
        }
        val target = percentage.coerceIn(0.0, 1.0) * totalBytes
        val docIndex = cumulativeBytes.indexOfLast { it <= target }
            .coerceIn(0, hrefs.lastIndex)
        val href = hrefs.getOrNull(docIndex) ?: return null
        val parts = allChapters.filter { chapterHref(it) == href }
        if (parts.isEmpty()) return null
        val docStart = cumulativeBytes[docIndex]
        val docSize = (docBytes.getOrNull(docIndex) ?: 0L).coerceAtLeast(1L)
        val fraction = ((target - docStart) / docSize).coerceIn(0.0, 1.0)
        val docLength = docText(href).length.coerceAtLeast(1)
        val charPosition = (fraction * docLength).toInt().coerceIn(0, docLength)
        val chapter = parts.lastOrNull { chapterOffsetInDoc(it, href, docLength) <= charPosition }
            ?: parts.first()
        // 只有百分比、没有可解析的指针时，落到该章开头，避免粗略比例把位置带到章中间。
        return BookProgress(
            name = book.name,
            author = book.author,
            durChapterIndex = chapter.index,
            durChapterPos = 0,
            durChapterTime = System.currentTimeMillis(),
            durChapterTitle = chapter.title,
        )
    }

    override fun paragraphKey(progress: BookProgress): String? = null

    private fun percentageFor(chapter: BookChapter, chapterPos: Int): Double? {
        val href = chapterHref(chapter)
        val spineIndex = hrefs.indexOf(href)
        if (spineIndex < 0 || totalBytes <= 0) return null
        val docLength = docText(href).length.coerceAtLeast(1)
        val chapterStart = chapterOffsetInDoc(chapter, href, docLength)
        val offset = (chapterStart + chapterPos.coerceAtLeast(0)).coerceIn(0, docLength)
        val fraction = offset.toDouble() / docLength
        val absolute = cumulativeBytes[spineIndex] + fraction * (docBytes.getOrNull(spineIndex) ?: 0L)
        return (absolute / totalBytes).coerceIn(0.0, 1.0)
    }

    private fun chapterOffsetInDoc(chapter: BookChapter, href: String, docLength: Int): Int {
        val parts = allChapters.filter { chapterHref(it) == href }
        if (parts.size <= 1) return 0
        val plain = plainText(chapter) ?: return 0
        val anchor = normalize(plain).take(ANCHOR_MAX_LENGTH)
        if (anchor.isEmpty()) return 0
        return docText(href).indexOf(anchor).coerceIn(0, docLength)
    }

    private fun docText(href: String): String {
        return docPlainTextCache.getOrPut(href) {
            normalize(spine.body(href)?.text().orEmpty())
        }
    }

    private fun plainText(chapter: BookChapter): String? {
        val text = chapterPlainTextCache.getOrPut(chapter.url) {
            normalize(chapterContent(chapter)?.let { Jsoup.parse(it).text() }.orEmpty())
        }
        return text.takeIf { it.isNotEmpty() }
    }

    private fun chapterHref(chapter: BookChapter): String =
        chapter.url.substringBeforeLast('#')

    private fun findBlock(body: Element, anchor: String): Element? {
        val elements = body.getAllElements()
        for (element in elements) {
            if (element === body) continue
            for (textNode in element.textNodes()) {
                if (normalize(textNode.text()).contains(anchor)) {
                    return nearestBlock(body, textNode)
                }
            }
            if (element.textNodes().isNotEmpty() &&
                element.children().none { isBlock(it.tagName()) } &&
                normalize(element.text()).contains(anchor)
            ) {
                return if (isBlock(element.tagName())) element else nearestBlock(body, element)
            }
        }
        return null
    }

    /** 章节开头用标题或第一个含文本的块，保证落点和 KOReader 的 `.../h1[1]/text()[1].0` 一致。 */
    private fun firstBlock(body: Element): Element? {
        body.selectFirst("h1,h2,h3,h4,h5,h6")
            ?.takeIf { normalize(it.text()).isNotEmpty() }
            ?.let { return it }
        for (element in body.getAllElements()) {
            if (element === body) continue
            val textNode = element.textNodes().firstOrNull {
                normalize(it.text()).isNotEmpty()
            } ?: continue
            return nearestBlock(body, textNode)
        }
        return null
    }

    private fun nearestBlock(body: Element, node: Node): Element? {
        var current: Element? = node.parent() as? Element
        while (current != null && current !== body) {
            if (isBlock(current.tagName())) return current
            current = current.parent() as? Element
        }
        return null
    }

    private fun pathFromBody(body: Element, target: Element): String? {
        if (target === body) return ""
        val parts = ArrayList<String>()
        var current: Element? = target
        while (current != null && current !== body) {
            val parent = current.parent() as? Element ?: return null
            val name = current.tagName().lowercase()
            val siblings = parent.children().filter { it.tagName().equals(name, true) }
            val index = siblings.indexOf(current)
            if (index < 0) return null
            parts.add("$name[${index + 1}]")
            current = parent
        }
        if (current !== body) return null
        return parts.asReversed().joinToString("/") { it }
    }

    private fun buildPointer(spineIndex: Int, path: String, target: Element): String {
        val prefix = "/body/DocFragment[${spineIndex + 1}]/body"
        if (path.isEmpty()) return prefix
        // crengine 风格：能定位到文本节点时带上 text()[1].offset
        return if (target.textNodes().isNotEmpty()) {
            "$prefix/$path/text()[1].0"
        } else {
            "$prefix/$path"
        }
    }

    private fun resolve(body: Element, pointer: XPointer): Node? {
        var current: Node = body
        for (step in pointer.steps) {
            val element = current as? Element ?: break
            current = when (step) {
                is XPointerStep.Element -> {
                    val children = element.children()
                        .filter { it.tagName().equals(step.name, true) }
                    children.getOrNull((step.index ?: 1) - 1) ?: break
                }

                is XPointerStep.Text -> {
                    val texts = element.textNodes()
                    texts.getOrNull((step.index ?: 1) - 1) ?: break
                }
            }
        }
        return current
    }

    private fun nodeText(node: Node): String {
        return when (node) {
            is Element -> node.text()
            is TextNode -> node.text()
            else -> ""
        }
    }

    private fun normalize(text: String): String =
        text.replace(WHITESPACE, " ").trim()

    private fun isBlock(tag: String): Boolean = tag.lowercase() in BLOCK_TAGS

    private companion object {
        const val ANCHOR_MAX_LENGTH = 40
        val ANCHOR_OFFSETS = listOf(0, -40, 40, -80, 80)
        val WHITESPACE = Regex("\\s+")
        val BLOCK_TAGS = setOf(
            "p", "div", "h1", "h2", "h3", "h4", "h5", "h6",
            "li", "blockquote", "section", "article", "pre", "td", "th",
        )
    }
}
