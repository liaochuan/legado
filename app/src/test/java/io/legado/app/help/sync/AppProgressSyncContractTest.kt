package io.legado.app.help.sync

import io.legado.app.data.entities.Book
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppProgressSyncContractTest {

    @Test
    fun `read book routes progress through app progress sync`() {
        val source = File("src/main/java/io/legado/app/model/ReadBook.kt").readText()
        assertTrue(source.contains("AppProgressSync.syncOnOpen"))
        assertTrue(source.contains("AppProgressSync.push"))
        assertFalse(source.contains("AppWebDav.getBookProgress"))
        assertFalse(source.contains("AppWebDav.uploadBookProgress"))
    }

    @Test
    fun `startup filters kosync eligible books from webdav bulk`() {
        val source = File("src/main/java/io/legado/app/App.kt").readText()
        assertTrue(source.contains("AppProgressSync.syncAllOnStartup"))
        assertTrue(source.contains("downloadAllBookProgress"))
        assertTrue(source.contains("isKosyncEligible"))
    }

    @Test
    fun `eligibility is limited to local txt and epub`() {
        assertTrue(AppProgressSync.isKosyncEligible(localBook("book.txt")))
        assertTrue(AppProgressSync.isKosyncEligible(localBook("book.epub")))
        assertFalse(AppProgressSync.isKosyncEligible(localBook("book.pdf")))
        assertFalse(
            AppProgressSync.isKosyncEligible(
                Book(
                    bookUrl = "https://example.com/book",
                    origin = "source",
                    originName = "book.txt",
                    name = "book",
                    author = "author",
                )
            )
        )
    }

    private fun localBook(name: String): Book {
        return Book(
            bookUrl = "/tmp/$name",
            origin = io.legado.app.constant.BookType.localTag,
            originName = name,
            name = name,
            author = "author",
            type = io.legado.app.constant.BookType.local,
        )
    }
}
