package io.legado.app.model.localBook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalBookCoverTest {

    @Test
    fun `existing local cover wins over network cover`() {
        assertEquals(
            "/data/user/0/io.legado.app/files/covers/cover.jpg",
            resolveLocalBookCover(
                "/data/user/0/io.legado.app/files/covers/cover.jpg",
                localCoverExists = true,
                fallbackCoverUrl = "https://images.example/cover.jpg",
            ),
        )
    }

    @Test
    fun `network cover is kept when local cover is missing`() {
        assertEquals(
            "https://images.example/cover.jpg",
            resolveLocalBookCover(
                "/data/user/0/io.legado.app/files/covers/cover.jpg",
                localCoverExists = false,
                fallbackCoverUrl = "https://images.example/cover.jpg",
            ),
        )
    }

    @Test
    fun `missing local cover without fallback stays null`() {
        assertNull(
            resolveLocalBookCover(
                "/data/user/0/io.legado.app/files/covers/cover.jpg",
                localCoverExists = false,
                fallbackCoverUrl = null,
            ),
        )
    }

    @Test
    fun `local book parsing prefers the extracted local cover`() {
        val sources = listOf(
            "model/localBook/LocalBook.kt",
            "model/localBook/EpubFile.kt",
            "model/localBook/UmdFile.kt",
            "model/localBook/PdfFile.kt",
            "model/localBook/MobiFile.kt",
        ).map(::readAppSource)

        assertTrue(sources[0].contains("resolveLocalBookCover("))
        sources.drop(1).forEach { source ->
            assertTrue(source.contains("resolveLocalBookCover("))
            assertTrue(source.contains("LocalBook.getCoverPath(book)"))
        }
    }

    private fun readAppSource(path: String): String = sequenceOf(
        File("src/main/java/io/legado/app"),
        File("app/src/main/java/io/legado/app"),
    ).map { it.resolve(path) }
        .first(File::isFile)
        .readText()
}
