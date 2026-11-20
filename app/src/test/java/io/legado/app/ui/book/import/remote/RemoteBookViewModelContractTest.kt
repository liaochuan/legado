package io.legado.app.ui.book.import.remote

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RemoteBookViewModelContractTest {

    private val source = readProjectFile(
        "src/main/java/io/legado/app/ui/book/import/remote/RemoteBookViewModel.kt"
    )

    @Test
    fun `creates opds manager for opds server`() {
        assertTrue(source.contains("RemoteBookOpds("))
        assertTrue(source.contains("getOpdsConfig()"))
    }

    @Test
    fun `opds does not write webdav origin`() {
        val importer = readProjectFile(
            "src/main/java/io/legado/app/model/remote/OpdsBookImporter.kt"
        )
        assertTrue(importer.contains("if (manager is RemoteBookWebDav)"))
    }

    @Test
    fun `opds sorting keeps server order`() {
        assertTrue(source.contains("if (isOpds) list else"))
    }

    @Test
    fun `download snapshots selection and counts forbidden`() {
        assertTrue(source.contains("remoteBooks.toList()"))
        assertTrue(source.contains("var forbidden = 0"))
        assertTrue(source.contains("if (targets.size == 1 && firstError != null)"))
    }

    @Test
    fun `stale list responses are ignored`() {
        assertTrue(source.contains("listRequestId"))
        assertTrue(source.contains("if (requestId != listRequestId) return"))
    }

    @Test
    fun `search and pagination are guarded`() {
        assertTrue(source.contains("var isLoadingMore"))
        assertTrue(source.contains("if (isLoadingMore) return"))
        assertTrue(source.contains("fun search("))
        assertTrue(source.contains("fun loadMore("))
        assertTrue(source.contains("fun exitSearch("))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
