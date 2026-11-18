package io.legado.app.help.sync

import android.os.ParcelFileDescriptor
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.model.localBook.LocalBook
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * KOReader 的 document 标识：对文件做部分 MD5（Binary 匹配模式）。
 *
 * 采样偏移为 0, 1024, 4096, ..., 1073741824，每段最多 1024 字节；
 * 读取不到数据时停止。与 KOReader util.partialMD5 保持一致。
 */
object KosyncDocumentId {

    private const val SAMPLE_SIZE = 1024
    private val sampleOffsets = LongArray(12) { index ->
        if (index == 0) 0L else SAMPLE_SIZE.toLong() shl (2 * (index - 1))
    }

    private data class CacheEntry(
        val size: Long,
        val lastModified: Long,
        val hash: String,
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    fun partialMd5(channel: FileChannel, size: Long): String {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteBuffer.allocate(SAMPLE_SIZE)
        for (offset in sampleOffsets) {
            if (offset >= size) break
            buffer.clear()
            var read = 0
            while (buffer.hasRemaining()) {
                val count = channel.read(buffer, offset + read)
                if (count <= 0) break
                read += count
            }
            if (read <= 0) break
            digest.update(buffer.array(), 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun compute(book: Book): String? {
        if (!book.isLocal || !(book.isLocalTxt || book.isEpub)) return null
        return runCatching {
            val descriptor = BookHelp.getBookPFD(book) ?: return null
            val statSize = descriptor.statSize
            val lastModified = LocalBook.getLastModified(book).getOrDefault(0L)
            val cached = cache[book.bookUrl]
            if (cached != null && cached.size == statSize && cached.lastModified == lastModified) {
                descriptor.close()
                return cached.hash
            }
            val fileSize = if (statSize >= 0) statSize else 0L
            val hash = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                input.channel.use { channel ->
                    partialMd5(channel, if (fileSize > 0) fileSize else channel.size())
                }
            }
            cache[book.bookUrl] = CacheEntry(statSize, lastModified, hash)
            hash
        }.getOrNull()
    }

    fun clearCache() {
        cache.clear()
    }
}
