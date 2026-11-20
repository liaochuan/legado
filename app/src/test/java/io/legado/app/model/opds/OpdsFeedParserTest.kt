package io.legado.app.model.opds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpdsFeedParserTest {

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResource("opds/$name")!!.readText()

    @Test
    fun `root entries without rel are navigation`() {
        val feed = OpdsFeedParser.parse(resource("root.xml"), "https://host/opds")
        assertEquals(4, feed.entries.size)
        val categories = feed.entries.first { it.title == "Categories" }
        assertEquals("https://host/opds/category", categories.navigationUrl)
        assertTrue(categories.acquisitionLinks.isEmpty())
    }

    @Test
    fun `book entry maps author cover acquisition and next`() {
        val feed = OpdsFeedParser.parse(resource("books.xml"), "https://host/opds/new")
        assertEquals("https://host/opds/new?offset=60", feed.nextUrl)
        val book = feed.entries.first()
        assertEquals("示例作者", book.author)
        assertEquals("　　示例简介", book.summary)
        assertEquals("https://host/opds/cover/1", book.coverUrl)
        assertEquals(2, book.acquisitionLinks.size)
        assertEquals(
            "application/epub+zip",
            book.acquisitionLinks.first { it.label == "EPUB" }.mimeType
        )
        assertNull(book.navigationUrl)
        assertFalse(book.acquisitionLinks.first().href.startsWith("urn:uuid"))
    }

    @Test
    fun `content with tags and escaped html becomes readable summary`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>真实结构</title>
                <id>urn:uuid:5555</id>
                <updated>2026-01-01T00:00:00+00:00</updated>
                <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml">
            TAGS: 发现, 玄幻<br/>
                <p>&lt;p&gt;第一段简介&lt;/p&gt;
                &lt;p&gt;第二段简介&lt;/p&gt;</p>
            </div></content>
                <link rel="http://opds-spec.org/acquisition" href="/opds/download/5/txt/" type="text/plain"/>
              </entry>
            </feed>
        """.trimIndent()
        val summary = OpdsFeedParser.parse(xml, "https://host/opds")
            .entries.first().summary.orEmpty()
        assertTrue("summary=[$summary]", summary.contains("　　第一段简介"))
        assertTrue(summary.contains("　　第二段简介"))
        assertTrue(summary.contains("\n"))
        assertFalse(summary.contains("\n\n"))
        assertFalse(summary.contains("TAGS:"))
        assertFalse(summary.contains("<p>"))
    }

    @Test
    fun `empty feed is empty not error`() {
        assertTrue(
            OpdsFeedParser.parse(resource("empty.xml"), "https://host/opds").entries.isEmpty()
        )
    }

    @Test(expected = OpdsException::class)
    fun `html login page is not opds`() {
        OpdsFeedParser.parse("<html><body>login</body></html>", "https://host/opds")
    }
}
