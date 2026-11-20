package io.legado.app.model.remote

import io.legado.app.lib.webdav.Authorization
import io.legado.app.model.opds.OpdsClient
import io.legado.app.model.opds.OpdsEntry
import io.legado.app.model.opds.OpdsFeed
import io.legado.app.model.opds.OpdsLink
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteBookOpdsTest {

    private fun manager(
        feed: OpdsFeed,
        shelf: (String) -> Boolean = { false }
    ): RemoteBookOpds {
        val client = object : OpdsClient(OkHttpClient()) {
            override suspend fun getFeed(url: String, authorization: String): OpdsFeed = feed
        }
        return RemoteBookOpds(
            rootUrl = "https://host/opds",
            authorization = Authorization("u", "p"),
            client = client,
            isOnShelf = shelf,
        )
    }

    private fun navEntry() = OpdsEntry(
        title = "Categories",
        id = "/opds/category",
        updated = 0L,
        author = null,
        categories = emptyList(),
        navigationUrl = "https://host/opds/category",
        coverUrl = null,
        acquisitionLinks = emptyList(),
    )

    private fun bookEntry() = OpdsEntry(
        title = "示例 书名",
        id = "urn:uuid:1111",
        updated = 100L,
        author = "示例作者",
        categories = listOf("分类A"),
        navigationUrl = null,
        coverUrl = "https://host/opds/cover/1",
        summary = "示例简介",
        acquisitionLinks = listOf(
            OpdsLink("https://host/opds/download/1/epub/", "application/epub+zip", 1000L, "EPUB"),
            OpdsLink("https://host/opds/download/1/txt/", "text/plain", 2000L, "TXT"),
        ),
    )

    @Test
    fun `maps navigation entry to directory`() = runBlocking {
        val book = manager(OpdsFeed("root", null, listOf(navEntry())))
            .getRemoteBookList("https://host/opds").first()
        assertTrue(book.isDir)
        assertEquals("https://host/opds/category", book.path)
    }

    @Test
    fun `maps book entry to file with all formats`() = runBlocking {
        val book = manager(OpdsFeed("root", null, listOf(bookEntry())))
            .getRemoteBookList("https://host/opds").first()
        assertEquals("示例 书名", book.filename)
        assertEquals("EPUB", book.contentType)
        assertEquals(2, book.links.size)
        assertEquals("示例作者", book.author)
        assertEquals("示例简介", book.intro)
        assertEquals("https://host/opds/download/1/epub/", book.path)
        assertFalse(book.isDir)
    }

    @Test
    fun `marks on shelf when a candidate name matches`() = runBlocking {
        val book = manager(OpdsFeed("root", null, listOf(bookEntry()))) {
            it == "示例 书名.txt"
        }.getRemoteBookList("https://host/opds").first()
        assertTrue(book.isOnBookShelf)
    }

    @Test
    fun `missing format falls back to preferred`() = runBlocking {
        val opds = manager(OpdsFeed("root", null, listOf(bookEntry())))
        val book = opds.getRemoteBookList("https://host/opds").first()
        val target = opds.targetForFormat(book, "PDF")
        assertEquals(book.path, target.path)
    }

    @Test
    fun `records matched local file name`() = runBlocking {
        val book = manager(OpdsFeed("root", null, listOf(bookEntry()))) {
            it == "示例 书名.txt"
        }.getRemoteBookList("https://host/opds").first()
        assertEquals("示例 书名.txt", book.localFileName)
    }

    @Test
    fun `metadata survives intent json round trip`() {
        val book = RemoteBook(
            filename = "书名",
            path = "https://host/download/1",
            size = 1,
            lastModify = 0,
            contentType = "EPUB",
            author = "作者",
            intro = "简介",
            categories = listOf("分类"),
        )
        val restored = GSON
            .fromJsonObject<RemoteBook>(GSON.toJson(book))
            .getOrNull()
        assertEquals("书名", restored?.filename)
        assertEquals("作者", restored?.author)
        assertEquals("简介", restored?.intro)
        assertEquals(listOf("分类"), restored?.categories)
    }

    @Test
    fun `next page returns entries from next url`() = runBlocking {
        val page1 = OpdsFeed("root", "https://host/opds/new?offset=60", listOf(bookEntry()))
        val page2Entry = bookEntry().copy(title = "第二页书名")
        val page2 = OpdsFeed("root", null, listOf(page2Entry))
        val client = object : OpdsClient(OkHttpClient()) {
            override suspend fun getFeed(url: String, authorization: String): OpdsFeed =
                if (url.contains("offset=60")) page2 else page1
        }
        val opds = RemoteBookOpds(
            rootUrl = "https://host/opds",
            authorization = Authorization("u", "p"),
            client = client,
            isOnShelf = { false },
        )
        opds.getRemoteBookList("https://host/opds/new")
        assertEquals("第二页书名", opds.loadNextPage("https://host/opds/new").first().filename)
    }
}
