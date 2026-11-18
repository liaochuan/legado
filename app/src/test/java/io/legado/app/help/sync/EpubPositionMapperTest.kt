package io.legado.app.help.sync

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun `generates paragraph pointer in first spine item`() {
        val mapper = mapper()
        val remote = mapper.toRemote(chapters[0], 0, "第二段内容。")
        assertNotNull(remote)
        assertEquals("/body/DocFragment[1]/body/div/p[2]", remote?.progress)
        assertEquals("epub:0:/div/p[2]", remote?.paragraphKey)
    }

    @Test
    fun `generates pointer in second spine item`() {
        val mapper = mapper()
        val remote = mapper.toRemote(chapters[1], 0, "列表内容。")
        assertEquals("/body/DocFragment[2]/body/ul/li", remote?.progress)
    }

    @Test
    fun `generates pointer from chapter position when page anchor is missing`() {
        val mapper = mapper()
        val remote = mapper.toRemote(chapters[0], 6, null)
        assertEquals("/body/DocFragment[1]/body/div/p[2]", remote?.progress)
    }

    @Test
    fun `generates pointer for anchor split across inline nodes`() {
        val mapper = mapper()
        val remote = mapper.toRemote(chapters[0], 0, "第三段内容。")
        assertEquals("/body/DocFragment[1]/body/div/p[3]", remote?.progress)
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
        val spine = object : EpubSpineSource {
            override fun hrefs(): List<String> = listOf("chap1.xhtml")
            override fun body(href: String): Element? =
                Jsoup.parse("<html><body><p>第二段内容。</p></body></html>").body()
        }
        val mapper = EpubPositionMapper(
            book = Book(
                bookUrl = "book",
                origin = io.legado.app.constant.BookType.localTag,
                originName = "book.epub",
                name = "book",
                author = "author",
            ),
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
        val mapper = mapper()
        assertNull(mapper.toRemote(chapters[0], 0, "不存在的段落"))
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
    }

    private fun mapper(): EpubPositionMapper {
        val spine = object : EpubSpineSource {
            override fun hrefs(): List<String> = bodies.keys.toList()
            override fun body(href: String): Element? =
                bodies[href]?.let { Jsoup.parse(it).body() }
        }
        val book = Book(
            bookUrl = "book",
            origin = io.legado.app.constant.BookType.localTag,
            originName = "book.epub",
            name = "book",
            author = "author",
        )
        return EpubPositionMapper(
            book = book,
            spine = spine,
            chapters = { chapters },
            chapterContent = { chapter -> contents[chapter.url.substringBeforeLast('#')] },
        )
    }

    private fun chapter(index: Int, href: String): BookChapter {
        return BookChapter(
            url = href,
            title = "chapter$index",
            bookUrl = "book",
            index = index,
        )
    }
}
