package io.legado.app.model.opds

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class OpdsClientTest {

    private var capturedRequest: Request? = null

    private fun okClient(
        code: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
        redirected: Boolean = false
    ): OkHttpClient {
        return OkHttpClient.Builder().addInterceptor { chain ->
            capturedRequest = chain.request()
            val prior = if (redirected) {
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(302)
                    .message("Found")
                    .body("".toResponseBody(null))
                    .build()
            } else {
                null
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .apply { prior?.let { priorResponse(it) } }
                .body(body.toResponseBody("application/atom+xml".toMediaType()))
                .build()
        }.build()
    }

    @Test
    fun `sends basic auth and parses feed`() = runBlocking {
        val xml = javaClass.classLoader!!.getResource("opds/root.xml")!!.readText()
        val client = OpdsClient(okClient(200, xml))
        val feed = client.getFeed("https://host/opds", "Basic dTpw")
        assertEquals(4, feed.entries.size)
        assertEquals("Basic dTpw", capturedRequest?.header("Authorization"))
    }

    @Test
    fun `401 maps to unauthorized`() = runBlocking {
        val error = runCatching {
            OpdsClient(okClient(401, "unauthorized")).getFeed("https://host/opds", "Basic dTpw")
        }.exceptionOrNull()
        assertEquals(OpdsError.UNAUTHORIZED, (error as OpdsException).error)
    }

    @Test
    fun `401 after redirect maps to redirect hint`() = runBlocking {
        val error = runCatching {
            OpdsClient(okClient(401, "unauthorized", redirected = true))
                .getFeed("https://host/opds", "Basic dTpw")
        }.exceptionOrNull()
        assertEquals(OpdsError.UNAUTHORIZED_REDIRECT, (error as OpdsException).error)
    }

    @Test
    fun `403 maps to forbidden`() = runBlocking {
        val error = runCatching {
            OpdsClient(okClient(403, "forbidden")).getFeed("https://host/opds", "Basic dTpw")
        }.exceptionOrNull()
        assertEquals(OpdsError.FORBIDDEN, (error as OpdsException).error)
    }

    @Test
    fun `404 maps to not found`() = runBlocking {
        val error = runCatching {
            OpdsClient(okClient(404, "not found")).getFeed("https://host/opds", "Basic dTpw")
        }.exceptionOrNull()
        assertEquals(OpdsError.NOT_FOUND, (error as OpdsException).error)
    }

    @Test
    fun `download reads rfc5987 filename`() = runBlocking {
        val client = OpdsClient(
            okClient(
                200, "book",
                mapOf("Content-Disposition" to "attachment; filename*=UTF-8''%E4%B8%AD%E6%96%87%20%E4%B9%A6%E5%90%8D.epub")
            )
        )
        val file = client.download("https://host/download", "Basic dTpw")
        assertEquals("中文 书名.epub", file.fileName)
        file.stream.close()
    }

    @Test
    fun `download maps unauthorized`() = runBlocking {
        val error = runCatching {
            OpdsClient(okClient(401, "no")).download("https://host/download", "Basic dTpw")
        }.exceptionOrNull()
        assertEquals(OpdsError.UNAUTHORIZED, (error as OpdsException).error)
    }

    @Test
    fun `filename keeps plus signs`() {
        assertEquals(
            "C++ Primer.epub",
            OpdsClient.parseFileName("attachment; filename*=UTF-8''C%2B%2B%20Primer.epub")
        )
    }

    @Test
    fun `parseFileName decodes plain filename`() {
        assertEquals(
            "book.txt",
            OpdsClient.parseFileName("attachment; filename=\"book.txt\"")
        )
        assertEquals(
            "中文 书名.epub",
            OpdsClient.parseFileName("attachment; filename*=UTF-8''%E4%B8%AD%E6%96%87%20%E4%B9%A6%E5%90%8D.epub")
        )
    }
}
