package io.legado.app.help.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

class KosyncDocumentIdTest {

    @Test
    fun `partialMd5 samples koReader offsets and stops past eof`() {
        val file = tempFile(5000)
        try {
            val expected = md5Of(
                file.readBytes(0, 1024),
                file.readBytes(1024, 1024),
                file.readBytes(4096, 904),
            )
            assertEquals(expected, file.readPartialMd5())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `partialMd5 reads only first sample for one kilobyte file`() {
        val file = tempFile(1024)
        try {
            val expected = md5Of(file.readBytes(0, 1024))
            assertEquals(expected, file.readPartialMd5())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `partialMd5 reads the partial tail at 1024 offset`() {
        val file = tempFile(1025)
        try {
            val expected = md5Of(
                file.readBytes(0, 1024),
                file.readBytes(1024, 1),
            )
            assertEquals(expected, file.readPartialMd5())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `partialMd5 returns md5 of empty content for empty file`() {
        val file = tempFile(0)
        try {
            assertEquals(md5Of(), file.readPartialMd5())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `compute returns null for missing local file`() {
        val book = io.legado.app.data.entities.Book(
            bookUrl = "/definitely/missing/book.txt",
            origin = io.legado.app.constant.BookType.localTag,
            originName = "book.txt",
            name = "book",
            author = "author",
        )
        assertNull(KosyncDocumentId.compute(book))
    }

    private fun tempFile(size: Int): File {
        val file = File.createTempFile("kosync-doc-", ".bin")
        val bytes = ByteArray(size) { (it * 31 + 7).toByte() }
        file.writeBytes(bytes)
        return file
    }

    private fun File.readBytes(offset: Int, length: Int): ByteArray {
        val result = ByteArray(length)
        RandomAccessFile(this, "r").use { raf ->
            raf.seek(offset.toLong())
            raf.readFully(result)
        }
        return result
    }

    private fun File.readPartialMd5(): String {
        RandomAccessFile(this, "r").use { raf ->
            return KosyncDocumentId.partialMd5(raf.channel, raf.length())
        }
    }

    private fun md5Of(vararg chunks: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        chunks.forEach(digest::update)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
