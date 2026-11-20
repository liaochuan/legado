package io.legado.app.ui.book.import.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ServerConfigViewModelContractTest {

    private val source = readProjectFile(
        "src/main/java/io/legado/app/ui/book/import/remote/ServerConfigViewModel.kt"
    )

    @Test
    fun `saving a server never deletes the existing row first`() {
        assertFalse(source.contains("appDb.serverDao.delete"))
        assertTrue(source.contains("appDb.serverDao.insert(server)"))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
