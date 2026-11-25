package io.legado.app.help.sync

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

class ProgressSyncCoordinatorTest {

    private val book = Book(
        bookUrl = "book",
        origin = io.legado.app.constant.BookType.localTag,
        originName = "book.txt",
        name = "book",
        author = "author",
    )

    @Test
    fun `non eligible book uses webdav only`() = runBlocking {
        val webDav = FakeProvider(
            pullResult = remote(1, 10, timestamp = 20),
        )
        val kosync = FakeProvider()
        val coordinator = coordinator(webDav = webDav, kosync = kosync, eligible = false)
        var applied: BookProgress? = null
        coordinator.syncOnOpen(book, plus = false, onApplyRemote = { applied = it }, onPromptRemote = {}, onMessage = {})
        assertEquals(1, webDav.pullCalls)
        assertEquals(0, kosync.pullCalls)
        assertEquals(1, applied?.durChapterIndex)
    }

    @Test
    fun `newer kosync progress is applied and webdav uploads`() = runBlocking {
        book.kosyncTime = 10
        val remote = remote(2, 0, timestamp = 20)
        val kosync = FakeProvider(pullResult = remote)
        val webDav = FakeProvider()
        val coordinator = coordinator(webDav = webDav, kosync = kosync, eligible = true)
        var applied: BookProgress? = null
        coordinator.syncOnOpen(book, plus = false, onApplyRemote = { applied = it }, onPromptRemote = {}, onMessage = {})
        assertEquals(2, applied?.durChapterIndex)
        assertEquals(20L, book.kosyncTime)
        assertEquals(1, webDav.pushCalls)
        assertEquals(0, webDav.pullCalls)
    }

    @Test
    fun `sync uses live local progress instead of stale book fields`() = runBlocking {
        book.kosyncTime = 10
        book.durChapterIndex = 2
        book.durChapterPos = 0
        val kosync = FakeProvider(pullResult = remote(2, 0, timestamp = 20))
        val coordinator = coordinator(webDav = null, kosync = kosync, eligible = true)
        var applied: BookProgress? = null
        coordinator.syncOnOpen(
            book,
            plus = false,
            onApplyRemote = { applied = it },
            onPromptRemote = {},
            onMessage = {},
            localProgress = progress(1, 0),
        )
        assertEquals(2, applied?.durChapterIndex)
    }

    @Test
    fun `push uses live local progress`() = runBlocking {
        book.durChapterIndex = 2
        book.durChapterPos = 0
        val kosync = FakeProvider()
        val coordinator = coordinator(webDav = null, kosync = kosync, eligible = true)
        coordinator.push(book, localProgress = progress(5, 7))
        assertEquals(5, kosync.lastPushed?.durChapterIndex)
        assertEquals(7, kosync.lastPushed?.durChapterPos)
    }

    @Test
    fun `cloud ahead prompts even when kosync timestamp is newer`() = runBlocking {
        // 旧版本可能已经把 kosyncTime 推到了云端时间戳之后，位置更靠前时仍要提示。
        book.kosyncTime = 100
        book.durChapterIndex = 1
        book.durChapterPos = 0
        val kosync = FakeProvider(pullResult = remote(3, 0, timestamp = 50))
        val webDav = FakeProvider()
        val coordinator = coordinator(webDav = webDav, kosync = kosync, eligible = true)
        var applied: BookProgress? = null
        coordinator.syncOnOpen(
            book,
            plus = false,
            onApplyRemote = { applied = it },
            onPromptRemote = {},
            onMessage = {},
        )
        assertEquals(3, applied?.durChapterIndex)
        assertEquals(0, kosync.pushCalls)
        assertEquals(100L, book.kosyncTime)
    }

    @Test
    fun `remote ahead callbacks run on ui context`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "legado-ui-test")
        }
        val uiDispatcher = executor.asCoroutineDispatcher()
        try {
            book.kosyncTime = 10
            val kosync = FakeProvider(pullResult = remote(2, 0, timestamp = 20))
            val coordinator = coordinator(
                webDav = FakeProvider(),
                kosync = kosync,
                eligible = true,
                uiContext = uiDispatcher,
            )
            val applyThread = AtomicReference<String>()
            coordinator.syncOnOpen(
                book,
                plus = false,
                onApplyRemote = { applyThread.set(Thread.currentThread().name) },
                onPromptRemote = {},
                onMessage = {},
            )
            assertEquals("legado-ui-test", applyThread.get()?.substringBefore(' '))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `webdav push failure after applying remote keeps kosync result`() = runBlocking {
        book.kosyncTime = 10
        val kosync = FakeProvider(pullResult = remote(2, 0, timestamp = 20))
        val webDav = FakeProvider(pushError = IllegalStateException("webdav down"))
        val coordinator = coordinator(webDav = webDav, kosync = kosync, eligible = true)
        val messages = mutableListOf<String>()
        var applied: BookProgress? = null
        coordinator.syncOnOpen(
            book,
            plus = false,
            onApplyRemote = { applied = it },
            onPromptRemote = {},
            onMessage = { messages.add(it) },
        )
        assertEquals(2, applied?.durChapterIndex)
        assertEquals(20L, book.kosyncTime)
        assertEquals(listOf("WebDAV 上传失败"), messages)
    }

    @Test
    fun `remote newer without apply callback keeps timestamp and does not upload`() = runBlocking {
        book.kosyncTime = 10
        val webDav = FakeProvider()
        val kosync = FakeProvider(pullResult = remote(2, 0, timestamp = 20))
        val coordinator = coordinator(webDav = webDav, kosync = kosync, eligible = true)
        coordinator.syncOnOpen(
            book,
            plus = false,
            onApplyRemote = null,
            onPromptRemote = null,
            onMessage = {},
        )
        assertEquals(10L, book.kosyncTime)
        assertEquals(0, webDav.pushCalls)
        assertEquals(0, kosync.pushCalls)
    }

    @Test
    fun `older kosync progress uploads local to both`() = runBlocking {
        book.kosyncTime = 30
        book.durChapterIndex = 3
        val kosync = FakeProvider(pullResult = remote(1, 0, timestamp = 20))
        val webDav = FakeProvider()
        val coordinator = coordinator(webDav = webDav, kosync = kosync, eligible = true)
        var uploaded = 0
        coordinator.syncOnOpen(
            book,
            plus = false,
            onApplyRemote = {},
            onPromptRemote = {},
            onMessage = {},
            onUploaded = { uploaded++ },
        )
        assertEquals(1, kosync.pushCalls)
        assertEquals(1, webDav.pushCalls)
        assertEquals(1, uploaded)
    }

    @Test
    fun `same position updates timestamp without upload`() = runBlocking {
        book.kosyncTime = 10
        book.durChapterIndex = 2
        book.durChapterPos = 0
        val kosync = FakeProvider(pullResult = remote(2, 0, timestamp = 20))
        val webDav = FakeProvider()
        val coordinator = coordinator(webDav = webDav, kosync = kosync, eligible = true)
        coordinator.syncOnOpen(book, plus = false, onApplyRemote = {}, onPromptRemote = {}, onMessage = {})
        assertEquals(0, kosync.pushCalls)
        assertEquals(0, webDav.pushCalls)
        assertEquals(20L, book.kosyncTime)
    }

    @Test
    fun `kosync failure falls back to webdav once`() = runBlocking {
        val kosync = FakeProvider(pullError = IllegalStateException("boom"))
        val webDav = FakeProvider(pullResult = remote(1, 0, timestamp = 0))
        val coordinator = coordinator(webDav = webDav, kosync = kosync, eligible = true)
        val messages = mutableListOf<String>()
        var applied: BookProgress? = null
        coordinator.syncOnOpen(
            book,
            plus = false,
            onApplyRemote = { applied = it },
            onPromptRemote = {},
            onMessage = { messages.add(it) },
        )
        assertEquals(1, webDav.pullCalls)
        assertEquals(1, applied?.durChapterIndex)
        assertTrue(messages.single().contains("KOReader"))
    }

    @Test
    fun `local ahead uploads instead of prompting`() = runBlocking {
        book.kosyncTime = 10
        book.durChapterIndex = 3
        val kosync = FakeProvider(pullResult = remote(1, 0, timestamp = 20))
        val webDav = FakeProvider()
        val coordinator = coordinator(webDav = webDav, kosync = kosync, eligible = true)
        var prompted: BookProgress? = null
        var applied: BookProgress? = null
        coordinator.syncOnOpen(
            book,
            plus = true,
            onApplyRemote = { applied = it },
            onPromptRemote = { prompted = it },
            onMessage = {},
        )
        assertEquals(null, prompted)
        assertEquals(null, applied)
        assertEquals(1, kosync.pushCalls)
        assertEquals(1, webDav.pushCalls)
    }

    @Test
    fun `webdav local ahead uploads instead of prompting`() = runBlocking {
        book.durChapterIndex = 3
        val webDav = FakeProvider(pullResult = remote(1, 0, timestamp = 0))
        val coordinator = coordinator(webDav = webDav, kosync = null, eligible = false)
        var prompted: BookProgress? = null
        coordinator.syncOnOpen(
            book,
            plus = true,
            onApplyRemote = {},
            onPromptRemote = { prompted = it },
            onMessage = {},
        )
        assertEquals(null, prompted)
        assertEquals(1, webDav.pushCalls)
    }

    @Test
    fun `non eligible book never calls kosync`() = runBlocking {
        val kosync = FakeProvider()
        val webDav = FakeProvider(pullResult = remote(1, 0, timestamp = 0))
        val coordinator = coordinator(webDav = webDav, kosync = kosync, eligible = false)
        coordinator.syncOnOpen(book, plus = false, onApplyRemote = {}, onPromptRemote = {}, onMessage = {})
        assertEquals(0, kosync.pullCalls)
    }

    @Test
    fun `push invokes uploaded callback`() = runBlocking {
        val kosync = FakeProvider()
        val coordinator = coordinator(webDav = FakeProvider(), kosync = kosync, eligible = true)
        var uploaded = 0
        coordinator.push(book, onUploaded = { uploaded++ })
        assertEquals(1, kosync.pushCalls)
        assertEquals(1, uploaded)
    }

    @Test
    fun `push passes override flag to kosync`() = runBlocking {
        val kosync = FakeProvider()
        val coordinator = coordinator(webDav = null, kosync = kosync, eligible = true)
        coordinator.push(book, overrideCloud = true)
        assertTrue(kosync.lastOverrideCloud)
    }

    @Test
    fun `skipped kosync push does not report upload success`() = runBlocking {
        val kosync = FakeProvider(pushResult = null)
        val coordinator = coordinator(webDav = null, kosync = kosync, eligible = true)
        var uploaded = 0
        coordinator.push(book, onUploaded = { uploaded++ })
        assertEquals(1, kosync.pushCalls)
        assertEquals(0, uploaded)
        assertEquals(0L, book.kosyncTime)
    }

    @Test
    fun `remote newer but behind without callbacks uploads local`() = runBlocking {
        book.kosyncTime = 10
        book.durChapterIndex = 3
        val webDav = FakeProvider()
        val kosync = FakeProvider(pullResult = remote(1, 0, timestamp = 20))
        val coordinator = coordinator(webDav = webDav, kosync = kosync, eligible = true)
        coordinator.syncOnOpen(
            book,
            plus = false,
            onApplyRemote = null,
            onPromptRemote = null,
            onMessage = {},
        )
        assertEquals(1, kosync.pushCalls)
        assertEquals(1, webDav.pushCalls)
    }

    @Test
    fun `position equality uses paragraph keys when available`() {
        val local = progress(2, 100)
        val remote = RemoteProgress(progress(2, 900), 20, 0.5, paragraphKey = "txt:9")
        assertFalse(PositionEquality.same(local, "txt:3", remote))
        assertTrue(PositionEquality.same(local, "txt:9", remote))
    }

    @Test
    fun `position equality falls back to chapter and offset tolerance`() {
        val local = progress(2, 100)
        val remote = RemoteProgress(progress(2, 150), 20, 0.5)
        assertTrue(PositionEquality.same(local, null, remote))
        assertFalse(PositionEquality.same(progress(1, 100), null, remote))
        assertTrue(PositionEquality.remoteIsBehind(progress(3, 1000), null, RemoteProgress(progress(2, 900), 20, 0.5)))
    }

    @Test
    fun `remote ahead is decided by position not timestamp`() {
        val local = progress(1, 0)
        assertTrue(PositionEquality.remoteIsAhead(local, null, RemoteProgress(progress(3, 0), 0, 0.9)))
        assertTrue(
            PositionEquality.remoteIsAhead(
                progress(2, 0),
                "txt:1",
                RemoteProgress(progress(2, 500), 0, 0.9, paragraphKey = "txt:2"),
            )
        )
        assertFalse(PositionEquality.remoteIsAhead(local, null, RemoteProgress(progress(1, 0), 999, 0.1)))
        assertFalse(PositionEquality.remoteIsAhead(progress(3, 0), null, RemoteProgress(progress(1, 0), 999, 0.1)))
    }

    @Test
    fun `capabilities cache is invalidated`() {
        KosyncCapabilities.clear()
        assertFalse(KosyncCapabilities.isSupported("http://a", "k"))
        KosyncCapabilities.setSupported("http://a", "k", true)
        assertTrue(KosyncCapabilities.isSupported("http://a", "k"))
        KosyncCapabilities.clear()
        assertFalse(KosyncCapabilities.isSupported("http://a", "k"))
    }

    private fun coordinator(
        webDav: FakeProvider?,
        kosync: FakeProvider?,
        eligible: Boolean,
        uiContext: CoroutineContext = EmptyCoroutineContext,
    ): ProgressSyncCoordinator {
        return ProgressSyncCoordinator(
            webDav = webDav,
            kosync = kosync,
            isKosyncEligible = { eligible },
            saveBook = {},
            uiContext = uiContext,
        )
    }

    private fun remote(chapter: Int, pos: Int, timestamp: Long): RemoteProgress {
        return RemoteProgress(
            bookProgress = progress(chapter, pos),
            timestamp = timestamp,
            percentage = 0.5,
        )
    }

    private fun progress(chapter: Int, pos: Int): BookProgress {
        return BookProgress(
            name = "book",
            author = "author",
            durChapterIndex = chapter,
            durChapterPos = pos,
            durChapterTime = 0,
            durChapterTitle = null,
        )
    }

    private class FakeProvider(
        var pullResult: RemoteProgress? = null,
        var pullError: Exception? = null,
        var pushError: Exception? = null,
        var pushResult: Long? = 100L,
    ) : ProgressSyncProvider {
        var pullCalls = 0
        var pushCalls = 0
        var lastPushed: BookProgress? = null
        var lastOverrideCloud = false

        override suspend fun pull(book: Book): RemoteProgress? {
            pullCalls++
            pullError?.let { throw it }
            return pullResult
        }

        override suspend fun push(
            book: Book,
            progress: BookProgress,
            anchorText: String?,
            overrideCloud: Boolean,
        ): Long? {
            pushCalls++
            lastOverrideCloud = overrideCloud
            pushError?.let { throw it }
            lastPushed = progress
            return pushResult
        }
    }
}
