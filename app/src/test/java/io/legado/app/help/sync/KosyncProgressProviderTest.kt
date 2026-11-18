package io.legado.app.help.sync

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KosyncProgressProviderTest {

    private lateinit var server: TestHttpServer
    private lateinit var provider: KosyncProgressProvider
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
                    """{"document":"doc","progress":"/body/DocFragment[1]/body/p[1]","percentage":0.5,"timestamp":10}""",
                )

                "PUT" -> TestHttpServer.Response(200, """{"document":"doc","timestamp":20}""")
                else -> TestHttpServer.Response(405, "{}")
            }
        }
        server.route("/api/v1/progress") {
            TestHttpServer.Response(
                200,
                """{"items":[{"document":"doc","progress":"/body/DocFragment[1]/body/p[1]","percentage":0.5,"timestamp":10}],"next":null}""",
            )
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
            mapperFactory = { FakeMapper() },
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

    private class FakeMapper : PositionMapper {
        override fun toRemote(progress: BookProgress, anchorText: String?): RemotePosition {
            return RemotePosition("/body/DocFragment[1]/body/p[1]", 0.5, "txt:1")
        }

        override fun fromRemote(xpointer: String, percentage: Double): BookProgress {
            return BookProgress("book", "author", 2, 3, 0, null)
        }

        override fun fromPercentage(percentage: Double): BookProgress {
            return BookProgress("book", "author", 1, 0, 0, null)
        }

        override fun paragraphKey(progress: BookProgress): String = "txt:1"
    }
}
