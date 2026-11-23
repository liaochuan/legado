package io.legado.app.help.sync

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KosyncProgressProviderTest {

    private lateinit var server: TestHttpServer
    private lateinit var provider: KosyncProgressProvider
    private var lastPutBody: String? = null
    private var lastPutPath: String? = null
    private var lastDocumentInfoPath: String? = null
    private var lastDocumentInfoBody: String? = null
    private var cloudPercentage = 0.5
    private var localPercentage = 0.5
    private var cloudChapter = 2
    private var cloudPos = 3
    private var extendedPutCode = 200
    private val book = Book(
        bookUrl = "book",
        origin = io.legado.app.constant.BookType.localTag,
        originName = "book.txt",
        name = "book",
        author = "author",
        type = io.legado.app.constant.BookType.local,
    )

    @Before
    fun setUp() {
        KosyncCapabilities.clear()
        server = TestHttpServer()
        server.route("/syncs/progress") { request ->
            when (request.method) {
                "GET" -> TestHttpServer.Response(
                    200,
                    """{"document":"doc","progress":"/body/DocFragment[1]/body/p[9]","percentage":$cloudPercentage,"timestamp":10}""",
                )

                "PUT" -> {
                    lastPutBody = request.body
                    lastPutPath = request.path
                    TestHttpServer.Response(200, """{"document":"doc","timestamp":20}""")
                }
                else -> TestHttpServer.Response(405, "{}")
            }
        }
        server.route("/api/v1/progress") { request ->
            when (request.method) {
                "PUT" -> {
                    lastPutBody = request.body
                    lastPutPath = request.path
                    TestHttpServer.Response(extendedPutCode, """{"document":"doc","timestamp":30}""")
                }

                else -> TestHttpServer.Response(
                    200,
                    """{"items":[{"document":"doc","progress":"/body/DocFragment[1]/body/p[1]","percentage":0.5,"timestamp":10}],"next":null}""",
                )
            }
        }
        server.route("/api/v1/documents") { request ->
            lastDocumentInfoPath = request.path
            lastDocumentInfoBody = request.body
            TestHttpServer.Response(200, """{"document":"doc"}""")
        }
        provider = KosyncProgressProvider(
            client = KosyncClient(
                baseUrl = server.baseUrl,
                username = "u",
                authKey = "k",
                client = OkHttpClient(),
            ),
            deviceName = "Legado",
            deviceId = "device",
            mapperFactory = { FakeMapper(localPercentage, cloudChapter, cloudPos) },
            documentId = { "doc" },
        )
    }

    @After
    fun tearDown() {
        server.close()
        KosyncCapabilities.clear()
    }

    @Test
    fun `pull maps remote progress`() = runBlocking {
        val remote = provider.pull(book)
        assertEquals(10L, remote?.timestamp)
        assertEquals(2, remote?.bookProgress?.durChapterIndex)
        assertEquals(3, remote?.bookProgress?.durChapterPos)
    }

    @Test
    fun `push maps local progress and returns timestamp`() = runBlocking {
        val timestamp = provider.push(book, progress(2, 3))
        assertEquals(20L, timestamp)
        assertEquals("/syncs/progress", lastPutPath)
        val body = requireNotNull(lastPutBody)
        assertTrue(body.contains("\"metadata\""))
        assertTrue(body.contains("\"book.txt\""))
        assertTrue(body.contains("\"book\""))
        assertTrue(body.contains("\"author\""))
    }

    @Test
    fun `push skips when cloud progress is ahead`() = runBlocking {
        cloudChapter = 2
        cloudPos = 3
        assertNull(provider.push(book, progress(1, 0)))
        assertNull(lastPutPath)
    }

    @Test
    fun `push uploads local ahead even when percentage is lower than cloud`() = runBlocking {
        cloudChapter = 1
        cloudPos = 0
        cloudPercentage = 0.5
        localPercentage = 0.2
        val timestamp = provider.push(book, progress(3, 0))
        assertEquals(30L, timestamp)
        assertEquals("/api/v1/progress", lastPutPath)
    }

    @Test
    fun `override pushes through extended api`() = runBlocking {
        cloudPercentage = 0.9
        val timestamp = provider.push(book, progress(1, 0), overrideCloud = true)
        assertEquals(30L, timestamp)
        assertEquals("/api/v1/progress", lastPutPath)
    }

    @Test
    fun `override falls back to legacy put without extended api`() = runBlocking {
        cloudPercentage = 0.9
        extendedPutCode = 404
        val timestamp = provider.push(book, progress(1, 0), overrideCloud = true)
        assertEquals(20L, timestamp)
        assertEquals("/syncs/progress", lastPutPath)
    }

    @Test
    fun `push sends cover url when available`() = runBlocking {
        book.customCoverUrl = "https://example.com/cover.jpg"
        try {
            provider.push(book, progress(2, 3))
            assertEquals("/api/v1/documents/doc/info", lastDocumentInfoPath)
            assertTrue(requireNotNull(lastDocumentInfoBody).contains("https://example.com/cover.jpg"))
        } finally {
            book.customCoverUrl = null
        }
    }

    @Test
    fun `pullAll lists documents and maps matching books`() = runBlocking {
        val remotes = provider.pullAll(listOf(book))
        assertTrue(remotes.containsKey(book.bookUrl))
        assertEquals(2, remotes[book.bookUrl]?.bookProgress?.durChapterIndex)
    }

    private fun progress(chapter: Int, pos: Int) = BookProgress(
        name = "book",
        author = "author",
        durChapterIndex = chapter,
        durChapterPos = pos,
        durChapterTime = 0,
        durChapterTitle = null,
    )

    private class FakeMapper(
        private val localPercentage: Double = 0.5,
        private val cloudChapter: Int = 2,
        private val cloudPos: Int = 3,
    ) : PositionMapper {
        override fun toRemote(progress: BookProgress, anchorText: String?): RemotePosition {
            return RemotePosition("/body/DocFragment[1]/body/p[9]", localPercentage, "txt:9")
        }

        override fun fromRemote(xpointer: String, percentage: Double): BookProgress {
            return BookProgress("book", "author", cloudChapter, cloudPos, 0, null)
        }

        override fun fromPercentage(percentage: Double): BookProgress {
            return BookProgress("book", "author", 1, 0, 0, null)
        }

        override fun paragraphKey(progress: BookProgress): String =
            "txt:${progress.durChapterIndex}:${progress.durChapterPos}"
    }
}
