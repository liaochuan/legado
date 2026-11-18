package io.legado.app.help.sync

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.help.book.isLocalTxt

class KosyncProgressProvider(
    private val client: KosyncClient,
    private val deviceName: String,
    private val deviceId: String,
    private val mapperFactory: (Book) -> PositionMapper? = PositionMapperFactory::create,
    private val documentId: (Book) -> String? = KosyncDocumentId::compute,
) : ProgressSyncProvider {

    private val mappers = HashMap<String, PositionMapper>()

    private fun mapperFor(book: Book): PositionMapper? {
        mappers[book.bookUrl]?.let { return it }
        return mapperFactory(book)?.also { mappers[book.bookUrl] = it }
    }

    override suspend fun pull(book: Book): RemoteProgress? {
        val document = documentId(book) ?: throw KosyncException("无法计算文档 ID")
        val dto = client.getProgress(document) ?: return null
        val mapper = mapperFor(book) ?: throw KosyncException("不支持的书类型")
        val mapped = dto.progress?.let { mapper.fromRemote(it, dto.percentage ?: 0.0) }
            ?: dto.percentage?.let { mapper.fromPercentage(it) }
            ?: throw KosyncException("无法解析远端位置")
        return RemoteProgress(
            bookProgress = mapped,
            timestamp = dto.timestamp ?: 0L,
            percentage = dto.percentage,
            paragraphKey = remoteParagraphKey(book, dto.progress),
            device = dto.device,
            rawProgress = dto.progress,
        )
    }

    override suspend fun push(
        book: Book,
        progress: BookProgress,
        anchorText: String?,
    ): Long? {
        val document = documentId(book) ?: throw KosyncException("无法计算文档 ID")
        val mapper = mapperFor(book) ?: throw KosyncException("不支持的书类型")
        val remote = mapper.toRemote(progress, anchorText)
            ?: throw KosyncException("无法生成 XPointer")
        return client.putProgress(
            document = document,
            progress = remote.progress,
            percentage = remote.percentage,
            device = deviceName,
            deviceId = deviceId,
        )
    }

    override suspend fun pullAll(books: List<Book>): Map<String, RemoteProgress> {
        if (books.isEmpty()) return emptyMap()
        val capabilityKey = client.capabilityKey()
        if (!KosyncCapabilities.isSupported(capabilityKey)) {
            val supported = runCatching { client.supportsExtendedApi() }.getOrDefault(false)
            KosyncCapabilities.setSupported(capabilityKey, supported)
            if (!supported) return emptyMap()
        }
        val items = runCatching { client.listProgress() }.getOrDefault(emptyList())
        if (items.isEmpty()) return emptyMap()
        val byDocument = items.mapNotNull { item ->
            item.document?.let { it to item }
        }.toMap()
        val result = HashMap<String, RemoteProgress>()
        for (book in books) {
            val document = documentId(book) ?: continue
            val item = byDocument[document] ?: continue
            val mapper = mapperFor(book) ?: continue
            val mapped = item.progress?.let {
                mapper.fromRemote(it, item.percentage ?: 0.0)
            } ?: item.percentage?.let {
                mapper.fromPercentage(it)
            } ?: continue
            result[book.bookUrl] = RemoteProgress(
                bookProgress = mapped,
                timestamp = item.timestamp ?: 0L,
                percentage = item.percentage,
                paragraphKey = remoteParagraphKey(book, item.progress),
                deviceId = item.deviceId,
                rawProgress = item.progress,
            )
        }
        return result
    }

    override fun paragraphKey(book: Book, progress: BookProgress): String? {
        return mapperFor(book)?.paragraphKey(progress)
    }

    private fun remoteParagraphKey(book: Book, progress: String?): String? {
        if (!book.isLocalTxt || progress.isNullOrBlank()) return null
        val pointer = XPointerParser.parse(progress) ?: return null
        val paragraph = pointer.steps.filterIsInstance<XPointerStep.Element>()
            .lastOrNull { it.name == "p" } ?: return null
        return paragraph.index?.let { "txt:$it" }
    }
}
