package io.legado.app.help.sync

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubPositionMapperTest {

    private val bodies = mapOf(
        "chap1.xhtml" to """
            <html><body>
              <div>
                <p>第一段内容。</p>
                <p>第二段内容。</p>
                <p>第三段<em>内容</em>。</p>
              </div>
            </body></html>
        """.trimIndent(),
        "chap2.xhtml" to """
            <html><body>
              <h1>第二章</h1>
              <ul><li>列表内容。</li></ul>
            </body></html>
        """.trimIndent(),
    )

    private val chapters = listOf(
        chapter(0, "chap1.xhtml"),
        chapter(1, "chap2.xhtml"),
    )

    private val contents = mapOf(
        "chap1.xhtml" to "<div><p>第一段内容。</p><p>第二段内容。</p></div>",
        "chap2.xhtml" to "<h1>第二章</h1><ul><li>列表内容。</li></ul>",
    )

    private val totalBytes = bodies.values.sumOf { it.toByteArray().size.toLong() }

    @Test
    fun `generates paragraph pointer in first spine item`() {
        val mapper = mapper()
        val remote = mapper.toRemote(chapters[0], 7, "第二段内容。")
        assertNotNull(remote)
        assertEquals(
            "/body/DocFragment[1]/body/div[1]/p[2]/text()[1].0",
            remote?.progress,
        )
        assertEquals("epub:0:/div[1]/p[2]", remote?.paragraphKey)
    }

    @Test
    fun `generates pointer in second spine item`() {
        val mapper = mapper()
        val remote = mapper.toRemote(chapters[1], 5, "列表内容。")
        assertEquals(
            "/body/DocFragment[2]/body/ul[1]/li[1]/text()[1].0",
            remote?.progress,
        )
    }

    @Test
    fun `generates pointer from chapter position when page anchor is missing`() {
        val mapper = mapper()
        val remote = mapper.toRemote(chapters[0], 6, null)
        assertEquals(
            "/body/DocFragment[1]/body/div[1]/p[2]/text()[1].0",
            remote?.progress,
        )
    }

    @Test
    fun `generates pointer for anchor split across inline nodes`() {
        val mapper = mapper()
        val remote = mapper.toRemote(chapters[0], 7, "第三段内容。")
        assertEquals(
            "/body/DocFragment[1]/body/div[1]/p[3]/text()[1].0",
            remote?.progress,
        )
    }

    @Test
    fun `chapter start anchors heading and uses spine byte percentage`() {
        val mapper = mapper()
        val remote = mapper.toRemote(chapters[1], 0, "列表内容。")
        assertEquals(
            "/body/DocFragment[2]/body/h1[1]/text()[1].0",
            remote?.progress,
        )
        val expected = bodies.getValue("chap1.xhtml").toByteArray().size.toDouble() / totalBytes
        assertEquals(expected, remote?.percentage ?: -1.0, 1e-9)
    }

    @Test
    fun `chapter start pointer maps back to chapter start`() {
        val mapper = mapper()
        val percentage = bodies.getValue("chap1.xhtml").toByteArray().size.toDouble() / totalBytes
        val progress = mapper.fromRemote(
            "/body/DocFragment[2]/body/h1[1]/text()[1].0",
            percentage,
        )
        assertEquals(1, progress?.durChapterIndex)
        assertEquals(0, progress?.durChapterPos)
    }

    @Test
    fun `chooses matching chapter when one spine href is split into chapters`() {
        val splitChapters = listOf(
            chapter(0, "chap1.xhtml"),
            chapter(1, "chap1.xhtml"),
        )
        val splitContents = mapOf(
            0 to "<p>第二段内容。</p>",
            1 to "<p>第二段内容。</p>",
        )
        val bodyHtml = "<html><body><p>第二段内容。</p></body></html>"
        val spine = object : EpubSpineSource {
            override fun hrefs(): List<String> = listOf("chap1.xhtml")

            override fun body(href: String): Element? = Jsoup.parse(bodyHtml).body()

            override fun byteLength(href: String): Long = bodyHtml.toByteArray().size.toLong()
        }
        val mapper = EpubPositionMapper(
            book = book(),
            spine = spine,
            chapters = { splitChapters },
            chapterContent = { chapter -> splitContents[chapter.index] },
        )
        val progress = mapper.fromRemote("/body/DocFragment[1]/body/p[1]", 0.9)
        assertEquals(1, progress?.durChapterIndex)
    }

    @Test
    fun `resolves pointer to chapter character position`() {
        val mapper = mapper()
        val progress = mapper.fromRemote("/body/DocFragment[1]/body/div/p[2]", 0.4)
        assertEquals(0, progress?.durChapterIndex)
        assertEquals(7, progress?.durChapterPos)
    }

    @Test
    fun `resolves unindexed single spine pointer`() {
        val mapper = mapper()
        val progress = mapper.fromRemote("/body/DocFragment/body/div/p[1]", 0.1)
        assertEquals(0, progress?.durChapterIndex)
        assertEquals(0, progress?.durChapterPos)
    }

    @Test
    fun `falls back to deepest resolvable element when step is missing`() {
        val mapper = mapper()
        val progress = mapper.fromRemote("/body/DocFragment[1]/body/div/p[99]", 0.1)
        assertEquals(0, progress?.durChapterIndex)
        assertEquals(0, progress?.durChapterPos)
    }

    @Test
    fun `missing anchor returns null instead of chapter start`() {
        val mapper = mapper(chapterContent = { null })
        assertNull(mapper.toRemote(chapters[0], 7, "不存在的段落"))
    }

    @Test
    fun `malformed pointer falls back to percentage`() {
        val mapper = mapper()
        val progress = mapper.fromRemote("bad-pointer", 0.9)
        assertEquals(1, progress?.durChapterIndex)
    }

    @Test
    fun `percentage fallback clamps to available chapters`() {
        val mapper = mapper()
        val progress = mapper.fromPercentage(1.0)
        assertEquals(1, progress?.durChapterIndex)
        assertTrue((progress?.durChapterPos ?: -1) >= 0)
    }

    private fun mapper(
        chapterContent: (BookChapter) -> String? = { chapter ->
            contents[chapter.url.substringBeforeLast('#')]
        },
    ): EpubPositionMapper {
        val spine = object : EpubSpineSource {
            override fun hrefs(): List<String> = bodies.keys.toList()

            override fun body(href: String): Element? =
                bodies[href]?.let { Jsoup.parse(it).body() }

            override fun byteLength(href: String): Long =
                bodies[href]?.toByteArray()?.size?.toLong() ?: 0L
        }
        return EpubPositionMapper(
            book = book(),
            spine = spine,
            chapters = { chapters },
            chapterContent = chapterContent,
        )
    }

    private fun book(): Book = Book(
        bookUrl = "book",
        origin = io.legado.app.constant.BookType.localTag,
        originName = "book.epub",
        name = "book",
        author = "author",
    )

    private fun chapter(index: Int, href: String): BookChapter {
        return BookChapter(
            url = href,
            title = "chapter$index",
            bookUrl = "book",
            index = index,
        )
    }
}
