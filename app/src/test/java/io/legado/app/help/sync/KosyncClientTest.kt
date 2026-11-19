package io.legado.app.help.sync

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KosyncClientTest {

    private lateinit var server: TestHttpServer
    private lateinit var client: KosyncClient
    private var authCode = 200
    private var getCode = 200
    private var putCode = 200
    private var listCode = 200
    private var putResponse = """{"document":"doc","timestamp":123}"""
    private var getResponse =
        """{"document":"doc","progress":"/body/DocFragment[1]/body/p[2]","percentage":0.5,"timestamp":100}"""
    private var lastAuthUser: String? = null
    private var lastAuthKey: String? = null
    private var lastPutBody: String? = null
    private val listPages = mutableListOf<String>()
    private var listIndex = 0

    @Before
    fun setUp() {
        server = TestHttpServer()
        server.route("/users/auth") { request ->
            lastAuthUser = request.headers["x-auth-user"]
            lastAuthKey = request.headers["x-auth-key"]
            TestHttpServer.Response(authCode, """{"authorized":"OK"}""")
        }
        server.route("/syncs/progress") { request ->
            when (request.method) {
                "GET" -> TestHttpServer.Response(getCode, getResponse)
                "PUT" -> {
                    lastPutBody = request.body
                    TestHttpServer.Response(putCode, putResponse)
                }

                else -> TestHttpServer.Response(405, "{}")
            }
        }
        server.route("/api/v1/progress") {
            if (listCode != 200) {
                TestHttpServer.Response(listCode, "{}")
            } else {
                val body = listPages.getOrNull(listIndex) ?: """{"items":[],"next":null}"""
                listIndex++
                TestHttpServer.Response(200, body)
            }
        }
        client = KosyncClient(
            baseUrl = server.baseUrl,
            username = "user",
            authKey = "key",
            client = OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `auth sends credentials and returns true on 200`() = runBlocking {
        assertTrue(client.auth())
        assertEquals("user", lastAuthUser)
        assertEquals("key", lastAuthKey)
    }

    @Test
    fun `auth returns false on 401`() = runBlocking {
        authCode = 401
        assertFalse(client.auth())
    }

    @Test
    fun `getProgress returns null for empty object`() = runBlocking {
        getResponse = "{}"
        assertNull(client.getProgress("doc"))
    }

    @Test(expected = KosyncException::class)
    fun `getProgress throws on invalid JSON body`() {
        getResponse = "<html>not json</html>"
        runBlocking { client.getProgress("doc") }
    }

    @Test
    fun `getProgress returns null for 404`() = runBlocking {
        getCode = 404
        assertNull(client.getProgress("doc"))
    }

    @Test
    fun `getProgress parses progress and timestamp`() = runBlocking {
        val progress = client.getProgress("doc")
        assertEquals("doc", progress?.document)
        assertEquals("/body/DocFragment[1]/body/p[2]", progress?.progress)
        assertEquals(0.5, progress?.percentage)
        assertEquals(100L, progress?.timestamp)
    }

    @Test
    fun `putProgress sends body and returns server timestamp`() = runBlocking {
        assertEquals(
            123L,
            client.putProgress(
                "doc",
                "/body/DocFragment[1]/body/p[2]",
                0.5,
                "Legado",
                "device",
                KosyncMetadata(filename = "book.epub", title = "书名", authors = "作者"),
            ),
        )
        val body = requireNotNull(lastPutBody)
        assertTrue(body.contains("\"document\""))
        assertTrue(body.contains("\"device_id\""))
        assertTrue(body.contains("\"Legado\""))
        assertTrue(body.contains("\"metadata\""))
        assertTrue(body.contains("\"book.epub\""))
        assertTrue(body.contains("\"书名\""))
        assertTrue(body.contains("\"作者\""))
    }

    @Test
    fun `putProgress refetches timestamp when response has none`() = runBlocking {
        putResponse = "{}"
        getResponse =
            """{"progress":"/body/DocFragment[1]/body/p[2]","percentage":0.5,"timestamp":200}"""
        assertEquals(
            200L,
            client.putProgress("doc", "/body/DocFragment[1]/body/p[2]", 0.5, "Legado", "device"),
        )
    }

    @Test
    fun `listProgress follows next cursor`() = runBlocking {
        listPages += """{"items":[{"document":"a","progress":"/body/DocFragment[1]/body/p[1]","percentage":0.1,"timestamp":1}],"next":"cursor-1"}"""
        listPages += """{"items":[{"document":"b","progress":"/body/DocFragment[1]/body/p[2]","percentage":0.2,"timestamp":2}],"next":null}"""
        val items = client.listProgress()
        assertEquals(2, items.size)
        assertEquals("a", items[0].document)
        assertEquals("b", items[1].document)
    }

    @Test
    fun `supportsExtendedApi is true on 200 and false on 404`() = runBlocking {
        assertTrue(client.supportsExtendedApi())
        listCode = 404
        assertFalse(client.supportsExtendedApi())
    }

    @Test(expected = KosyncException::class)
    fun `getProgress throws on 401`() {
        getCode = 401
        runBlocking { client.getProgress("doc") }
    }
}
