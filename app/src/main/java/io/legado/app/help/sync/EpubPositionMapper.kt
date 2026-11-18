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
}

class EpubFileSpineSource(private val book: Book) : EpubSpineSource {
    private val epubFile by lazy { EpubFile(book) }

    override fun hrefs(): List<String> = epubFile.getSpineHrefs()

    override fun body(href: String): Element? = epubFile.getSpineBody(href)
}

/**
 * 把 Legado 的 EPUB 章节位置映射为 KOReader 的块级 XPointer。
 *
 * 上传方向用当前页首行文本作为锚点，找到最近的块级元素；
 * 拉取方向解析 XPointer，失败时逐级回退到最深可解析元素。
 */
class EpubPositionMapper(
    private val book: Book,
    private val spine: EpubSpineSource,
    private val chapters: () -> List<BookChapter>,
    private val chapterContent: (BookChapter) -> String? = { BookHelp.getContent(book, it) },
) : PositionMapper {

    override fun toRemote(progress: BookProgress, anchorText: String?): RemotePosition? {
        val chapter = chapters().getOrNull(progress.durChapterIndex) ?: return null
        return toRemote(chapter, progress.durChapterPos, anchorText)
    }

    fun toRemote(
        chapter: BookChapter,
        chapterPos: Int,
        anchorText: String?,
    ): RemotePosition? {
        val href = chapterHref(chapter)
        val spineIndex = spine.hrefs().indexOf(href)
        if (spineIndex < 0) return null
        val body = spine.body(href) ?: return null
        val anchors = anchorCandidates(chapter, chapterPos, anchorText)
        if (anchors.isEmpty()) return null
        var block: Element? = null
        for (anchor in anchors) {
            block = findBlock(body, anchor)
            if (block != null) break
        }
        val target = block ?: return null
        val path = pathFromBody(body, target) ?: return null
        val pointer = buildPointer(spineIndex, path)
        val total = chapters().size.coerceAtLeast(1)
        val percentage = ((chapter.index + chapterPos.coerceAtLeast(0) / 10000.0) / total)
            .coerceIn(0.0, 1.0)
        val key = if (path.isEmpty()) "epub:$spineIndex:" else "epub:$spineIndex:/$path"
        return RemotePosition(pointer, percentage, key)
    }

    private fun anchorCandidates(
        chapter: BookChapter,
        chapterPos: Int,
        anchorText: String?,
    ): List<String> {
        val result = LinkedHashSet<String>()
        normalize(anchorText.orEmpty()).take(ANCHOR_MAX_LENGTH)
            .takeIf { it.isNotEmpty() }?.let(result::add)
        val content = chapterContent(chapter) ?: return result.toList()
        val plain = Jsoup.parse(content).text()
        if (plain.isEmpty()) return result.toList()
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
        val href = spine.hrefs().getOrNull((pointer.fragmentIndex ?: 1) - 1)
            ?: return fromPercentage(percentage)
        val body = spine.body(href) ?: return fromPercentage(percentage)
        val node = resolve(body, pointer) ?: return fromPercentage(percentage)
        val anchor = normalize(nodeText(node)).take(ANCHOR_MAX_LENGTH)
        if (anchor.isEmpty()) return fromPercentage(percentage)
        val allChapters = chapters()
        val splitChapters = allChapters.filter { chapterHref(it) == href }
        if (splitChapters.isEmpty()) return fromPercentage(percentage)
        var best: BookProgress? = null
        var bestScore = Double.MAX_VALUE
        for (chapter in splitChapters) {
            val content = chapterContent(chapter) ?: continue
            val plainText = Jsoup.parse(content).text()
            if (plainText.isEmpty()) continue
            var searchFrom = 0
            while (searchFrom < plainText.length) {
                val position = plainText.indexOf(anchor, searchFrom)
                if (position < 0) break
                val globalPercentage =
                    (chapter.index + position.toDouble() / plainText.length) /
                        allChapters.size.coerceAtLeast(1)
                val score = abs(globalPercentage - percentage)
                if (score < bestScore) {
                    bestScore = score
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
        val list = chapters()
        if (list.isEmpty()) return null
        val index = (percentage.coerceIn(0.0, 1.0) * list.size)
            .toInt()
            .coerceIn(0, list.lastIndex)
        val chapter = list[index]
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
            parts.add(if (siblings.size > 1) "$name[${index + 1}]" else name)
            current = parent
        }
        if (current !== body) return null
        return parts.asReversed().joinToString("/") { it }
    }

    private fun buildPointer(spineIndex: Int, path: String): String {
        val prefix = "/body/DocFragment[${spineIndex + 1}]/body"
        return if (path.isEmpty()) prefix else "$prefix/$path"
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
