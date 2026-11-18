package io.legado.app.help.sync

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TxtPositionMapperTest {

    private val text = buildString {
        append("第一章 开始\n")
        append("第一段内容。\n")
        append("\n")
        append("第二段内容。\n")
        append("\n")
        append("第二章 标题\n")
        append("第二章内容。\n")
    }

    @Test
    fun `line mode maps chapter position to global line pointer`() {
        val mapper = mapper(text, "UTF-8", TxtParagraphMode.LINE)
        val remote = mapper.toRemote(progress(1, 0))
        assertNotNull(remote)
        assertEquals("/body/DocFragment/body/p[4]/text().0", remote?.progress)
        assertEquals("txt:4", remote?.paragraphKey)
    }

    @Test
    fun `line mode round trips pointer to chapter position`() {
        val mapper = mapper(text, "UTF-8", TxtParagraphMode.LINE)
        val result = mapper.fromRemote("/body/DocFragment/body/p[4]/text().0", 0.8)
        assertEquals(1, result?.durChapterIndex)
        assertEquals(0, result?.durChapterPos)
    }

    @Test
    fun `empty line mode groups wrapped lines into one paragraph`() {
        val mapper = mapper(text, "UTF-8", TxtParagraphMode.EMPTY_LINE)
        val remote = mapper.toRemote(progress(1, 0))
        assertEquals("/body/DocFragment/body/p[3]/text().0", remote?.progress)
        val result = mapper.fromRemote("/body/DocFragment/body/p[3]/text().0", 0.8)
        assertEquals(1, result?.durChapterIndex)
        assertEquals(0, result?.durChapterPos)
    }

    @Test
    fun `gbk bytes and character offsets round trip`() {
        val mapper = mapper(text, "GBK", TxtParagraphMode.LINE)
        val remote = mapper.toRemote(progress(1, 3))
        assertNotNull(remote)
        val result = mapper.fromRemote(requireNotNull(remote).progress, remote.percentage)
        assertEquals(1, result?.durChapterIndex)
        assertEquals(3, result?.durChapterPos)
    }

    @Test
    fun `crlf keeps line count based on newline bytes`() {
        val crlf = text.replace("\n", "\r\n")
        val mapper = mapper(crlf, "UTF-8", TxtParagraphMode.LINE)
        val remote = mapper.toRemote(progress(1, 0))
        assertEquals("/body/DocFragment/body/p[4]/text().0", remote?.progress)
    }

    @Test
    fun `percentage fallback selects a later chapter`() {
        val mapper = mapper(text, "UTF-8", TxtParagraphMode.LINE)
        val result = mapper.fromPercentage(0.9)
        assertEquals(1, result?.durChapterIndex)
    }

    @Test
    fun `malformed pointer falls back to percentage`() {
        val mapper = mapper(text, "UTF-8", TxtParagraphMode.LINE)
        val result = mapper.fromRemote("not-a-pointer", 0.9)
        assertEquals(1, result?.durChapterIndex)
    }

    @Test
    fun `missing chapter bounds returns null`() {
        val mapper = mapper(text, "UTF-8", TxtParagraphMode.LINE)
        val broken = progress(0, 0).copy(durChapterIndex = 99)
        assertNull(mapper.toRemote(broken))
    }

    private fun mapper(text: String, charsetName: String, mode: TxtParagraphMode): TxtPositionMapper {
        val bytes = text.toByteArray(charset(charsetName))
        val secondStart = text.indexOf("第二章").let {
            text.substring(0, it).toByteArray(charset(charsetName)).size.toLong()
        }
        val chapters = listOf(
            BookChapter(
                url = "c1",
                title = "第一章",
                bookUrl = "book",
                index = 0,
                start = 0,
                end = secondStart,
            ),
            BookChapter(
                url = "c2",
                title = "第二章",
                bookUrl = "book",
                index = 1,
                start = secondStart,
                end = bytes.size.toLong(),
            ),
        )
        val book = Book(
            bookUrl = "book",
            origin = io.legado.app.constant.BookType.localTag,
            originName = "book.txt",
            name = "book",
            author = "author",
            charset = charsetName,
            type = io.legado.app.constant.BookType.local,
        )
        return TxtPositionMapper(
            book = book,
            source = MemoryTxtSource(bytes),
            chapters = { chapters },
            mode = mode,
        )
    }

    private fun progress(chapter: Int, pos: Int): BookProgress {
        return BookProgress(
            name = "book",
            author = "author",
            durChapterIndex = chapter,
            durChapterPos = pos,
            durChapterTime = 0,
            durChapterTitle = null,
        )
    }

    private fun charset(name: String) = java.nio.charset.Charset.forName(name)

    private class MemoryTxtSource(private val bytes: ByteArray) : TxtSource {
        override val size: Long = bytes.size.toLong()

        override fun read(start: Long, length: Int): ByteArray {
            val from = start.toInt().coerceIn(0, bytes.size)
            val to = (from + length).coerceIn(from, bytes.size)
            return bytes.copyOfRange(from, to)
        }
    }
}
