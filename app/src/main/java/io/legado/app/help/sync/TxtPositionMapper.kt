package io.legado.app.help.sync

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getLocalUri
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.localBook.TextFile
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset

enum class TxtParagraphMode { LINE, EMPTY_LINE }

interface TxtSource {
    val size: Long
    fun read(start: Long, length: Int): ByteArray
}

class BookTxtSource(private val book: Book) : TxtSource {

    override val size: Long by lazy {
        val descriptorSize = runCatching {
            BookHelp.getBookPFD(book)?.use { it.statSize }
        }.getOrNull() ?: -1L
        if (descriptorSize >= 0) {
            descriptorSize
        } else {
            runCatching {
                val uri = book.getLocalUri()
                if (uri.scheme == "file") File(requireNotNull(uri.path)).length() else 0L
            }.getOrDefault(0L)
        }
    }

    override fun read(start: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        LocalBook.getBookInputStream(book).use { input ->
            skipFully(input, start)
            val result = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(result, offset, length - offset)
                if (count <= 0) break
                offset += count
            }
            return if (offset == length) result else result.copyOf(offset)
        }
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        val discard = ByteArray(8 * 1024)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val count = input.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
            if (count <= 0) return
            remaining -= count
        }
    }
}

/**
 * 把 Legado 的 TXT 章节位置映射为 KOReader 的段/行 XPointer。
 *
 * TXT 的 crengine 解析可能是“每行一段”或“空行分段”，因此保留两种模式；
 * 真实模式由 KOReader 的 progress 样本校准。
 */
class TxtPositionMapper(
    private val book: Book,
    private val source: TxtSource,
    private val chapters: () -> List<BookChapter>,
    private val mode: TxtParagraphMode = TxtParagraphMode.LINE,
) : PositionMapper {

    private val charset: Charset = book.fileCharset()
    private var allBytesCache: ByteArray? = null
    private var linesCache: List<TextLine>? = null

    override fun toRemote(progress: BookProgress, anchorText: String?): RemotePosition? {
        return toRemote(progress)
    }

    fun toRemote(progress: BookProgress): RemotePosition? {
        val absolute = absoluteByteOffset(progress) ?: return null
        val index = if (mode == TxtParagraphMode.LINE) {
            lineIndex(absolute)
        } else {
            paragraphIndex(absolute)
        }
        val start = if (mode == TxtParagraphMode.LINE) {
            lineStart(index)
        } else {
            paragraphStart(index)
        } ?: return null
        val column = columnCount(start, absolute)
        val pointer = "/body/DocFragment/body/p[$index]/text().$column"
        val percentage = if (source.size > 0) {
            (absolute.toDouble() / source.size).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        return RemotePosition(pointer, percentage, "txt:$index")
    }

    override fun fromRemote(xpointer: String, percentage: Double): BookProgress? {
        val pointer = XPointerParser.parse(xpointer) ?: return fromPercentage(percentage)
        val paragraph = pointer.steps.filterIsInstance<XPointerStep.Element>()
            .lastOrNull { it.name == "p" } ?: return fromPercentage(percentage)
        val index = paragraph.index ?: return fromPercentage(percentage)
        val column = pointer.point ?: 0
        val absolute = if (mode == TxtParagraphMode.LINE) {
            byteAtLine(index, column)
        } else {
            byteAtParagraph(index, column)
        } ?: return fromPercentage(percentage)
        return positionAt(absolute)
    }

    override fun fromPercentage(percentage: Double): BookProgress? {
        if (source.size <= 0) return null
        val absolute = (percentage.coerceIn(0.0, 1.0) * source.size)
            .toLong()
            .coerceIn(0L, (source.size - 1).coerceAtLeast(0L))
        return positionAt(absolute)
    }

    override fun paragraphKey(progress: BookProgress): String? {
        val absolute = absoluteByteOffset(progress) ?: return null
        val index = if (mode == TxtParagraphMode.LINE) {
            lineIndex(absolute)
        } else {
            paragraphIndex(absolute)
        }
        return "txt:$index"
    }

    private fun absoluteByteOffset(progress: BookProgress): Long? {
        val chapter = chapters().getOrNull(progress.durChapterIndex) ?: return null
        val start = chapter.start ?: return null
        val end = chapter.end ?: return null
        val text = chapterText(chapter) ?: return null
        if (end < start) return null
        val position = progress.durChapterPos.coerceIn(0, text.normalized.length)
        if (position <= text.replacedPrefixChars) return start
        val tail = text.normalized.substring(text.replacedPrefixChars, position)
        return start + text.leadingBytes + tail.toByteArray(charset).size
    }

    private fun positionAt(absolute: Long): BookProgress? {
        val chapterList = chapters()
        val chapter = chapterList.firstOrNull {
            val start = it.start ?: return@firstOrNull false
            val end = it.end ?: return@firstOrNull false
            absolute >= start && absolute < end
        } ?: chapterList.lastOrNull {
            val end = it.end ?: return@lastOrNull false
            absolute == end
        } ?: return null
        val text = chapterText(chapter) ?: return null
        val start = chapter.start ?: return null
        val relative = (absolute - start).toInt().coerceAtLeast(0)
        val position = when {
            relative <= 0 -> 0
            text.replacedPrefixChars > 0 && relative < text.leadingBytes -> 0
            else -> {
                val tail = text.normalized.substring(text.replacedPrefixChars)
                val chars = charCountForBytes(tail, relative - text.leadingBytes)
                text.replacedPrefixChars + chars
            }
        }.coerceIn(0, text.normalized.length)
        return BookProgress(
            name = book.name,
            author = book.author,
            durChapterIndex = chapter.index,
            durChapterPos = position,
            durChapterTime = System.currentTimeMillis(),
            durChapterTitle = chapter.title,
        )
    }

    private fun chapterText(chapter: BookChapter): ChapterText? {
        val start = chapter.start ?: return null
        val end = chapter.end ?: return null
        if (end < start) return null
        val length = (end - start).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val bytes = source.read(start, length)
        if (bytes.isEmpty()) return ChapterText("", "", 0, 0)
        val raw = String(bytes, charset)
        val match = TextFile.padRegex.find(raw)
        val leading = match?.value.orEmpty()
        return if (leading.isEmpty()) {
            ChapterText(raw = raw, normalized = raw, leadingBytes = 0, replacedPrefixChars = 0)
        } else {
            ChapterText(
                raw = raw,
                normalized = "　　" + raw.substring(leading.length),
                leadingBytes = leading.toByteArray(charset).size,
                replacedPrefixChars = 2,
            )
        }
    }

    private fun allBytes(): ByteArray {
        allBytesCache?.let { return it }
        val length = source.size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return source.read(0L, length).also { allBytesCache = it }
    }

    private fun lines(): List<TextLine> {
        linesCache?.let { return it }
        val bytes = allBytes()
        val result = ArrayList<TextLine>()
        var start = 0
        for (index in bytes.indices) {
            if (bytes[index] == '\n'.code.toByte()) {
                result.add(line(start, index))
                start = index + 1
            }
        }
        result.add(line(start, bytes.size))
        return result.also { linesCache = it }
    }

    private fun line(start: Int, end: Int): TextLine {
        val bytes = allBytes()
        val text = String(bytes, start, (end - start).coerceAtLeast(0), charset).trim()
        return TextLine(start.toLong(), end.toLong(), text.isEmpty())
    }

    private fun lineIndex(absolute: Long): Int {
        var count = 0
        for (line in lines()) {
            if (line.empty) continue
            count++
            if (absolute <= line.end) return count
        }
        return count.coerceAtLeast(1)
    }

    private fun lineStart(index: Int): Long? {
        return lineAt(index)?.start
    }

    private fun lineAt(index: Int): TextLine? {
        if (index <= 0) return null
        var count = 0
        for (line in lines()) {
            if (line.empty) continue
            count++
            if (count == index) return line
        }
        return null
    }

    private fun byteAtLine(index: Int, column: Int): Long? {
        val line = lineAt(index) ?: return null
        val text = String(allBytes(), line.start.toInt(), (line.end - line.start).toInt(), charset)
        val prefix = text.take(column.coerceAtLeast(0))
        return line.start + prefix.toByteArray(charset).size
    }

    private fun paragraphIndex(absolute: Long): Int {
        var group = 0
        var inGroup = false
        for (line in lines()) {
            if (line.empty) {
                inGroup = false
                continue
            }
            if (!inGroup) {
                group++
                inGroup = true
            }
            if (absolute <= line.end) return group
        }
        return group.coerceAtLeast(1)
    }

    private fun paragraphStart(index: Int): Long? {
        var group = 0
        var inGroup = false
        for (line in lines()) {
            if (line.empty) {
                inGroup = false
                continue
            }
            if (!inGroup) {
                group++
                inGroup = true
                if (group == index) return line.start
            }
        }
        return null
    }

    private fun paragraphEnd(index: Int): Long? {
        var group = 0
        var inGroup = false
        var end: Long? = null
        for (line in lines()) {
            if (line.empty) {
                inGroup = false
                if (group == index && end != null) return end
                continue
            }
            if (!inGroup) {
                group++
                inGroup = true
            }
            if (group == index) end = line.end
        }
        return if (group == index) end else null
    }

    private fun byteAtParagraph(index: Int, column: Int): Long? {
        val start = paragraphStart(index) ?: return null
        val end = paragraphEnd(index) ?: return null
        val text = String(allBytes(), start.toInt(), (end - start).toInt(), charset)
        val prefix = text.take(column.coerceAtLeast(0))
        return start + prefix.toByteArray(charset).size
    }

    private fun columnCount(start: Long, absolute: Long): Int {
        val bytes = allBytes()
        val from = start.coerceIn(0L, bytes.size.toLong()).toInt()
        val to = absolute.coerceIn(from.toLong(), bytes.size.toLong()).toInt()
        val text = String(bytes, from, to - from, charset)
        return text.codePointCount(0, text.length)
    }

    private fun charCountForBytes(text: String, byteLimit: Int): Int {
        if (byteLimit <= 0) return 0
        var bytes = 0
        var chars = 0
        while (chars < text.length) {
            val codePoint = text.codePointAt(chars)
            val charCount = Character.charCount(codePoint)
            val size = text.substring(chars, chars + charCount).toByteArray(charset).size
            if (bytes + size > byteLimit) break
            bytes += size
            chars += charCount
        }
        return chars
    }

    private data class ChapterText(
        val raw: String,
        val normalized: String,
        val leadingBytes: Int,
        val replacedPrefixChars: Int,
    )

    private data class TextLine(
        val start: Long,
        val end: Long,
        val empty: Boolean,
    )
}
