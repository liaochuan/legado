package io.legado.app.help.sync

import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KosyncCoverUrlProviderTest {

    @Test
    fun `prefers custom remote cover over local cover`() {
        val book = Book(
            bookUrl = "book",
            originName = "book.epub",
            name = "book",
            author = "author",
            coverUrl = "/data/user/0/covers/cover.jpg",
            customCoverUrl = "https://calibre.example.com/cover/1.jpg",
        )
        assertEquals("https://calibre.example.com/cover/1.jpg", KosyncCoverUrlProvider.coverUrl(book))
    }

    @Test
    fun `falls back to remote book cover`() {
        val book = Book(
            bookUrl = "book",
            originName = "book.epub",
            name = "book",
            author = "author",
            coverUrl = "https://example.com/cover.jpg",
        )
        assertEquals("https://example.com/cover.jpg", KosyncCoverUrlProvider.coverUrl(book))
    }

    @Test
    fun `supports future kosync cover variable`() {
        val book = Book(
            bookUrl = "book",
            originName = "book.epub",
            name = "book",
            author = "author",
            variable = """{"kosyncCoverUrl":"https://calibre.example.com/cover/2.jpg"}""",
        )
        assertEquals("https://calibre.example.com/cover/2.jpg", KosyncCoverUrlProvider.coverUrl(book))
    }

    @Test
    fun `ignores local cover paths`() {
        val book = Book(
            bookUrl = "book",
            originName = "book.epub",
            name = "book",
            author = "author",
            coverUrl = "/data/user/0/covers/cover.jpg",
            customCoverUrl = "content://media/external/images/1",
        )
        assertNull(KosyncCoverUrlProvider.coverUrl(book))
    }
}
