package io.legado.app.help.sync

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class TestHttpServer : AutoCloseable {

    data class Request(
        val method: String,
        val path: String,
        val query: String,
        val headers: Map<String, String>,
        val body: String,
    )

    data class Response(
        val code: Int,
        val body: String,
    )

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val routes = ConcurrentHashMap<String, (Request) -> Response>()
    private val worker = thread(start = true, isDaemon = true, name = "test-http") {
        while (!socket.isClosed) {
            try {
                socket.accept().use(::handle)
            } catch (_: Exception) {
                break
            }
        }
    }

    val baseUrl: String
        get() = "http://127.0.0.1:${socket.localPort}"

    fun route(path: String, handler: (Request) -> Response) {
        routes[path] = handler
    }

    private fun handle(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val headerBytes = ByteArrayOutputStream()
        var tail = 0
        while (true) {
            val b = input.read()
            if (b == -1) return
            headerBytes.write(b)
            tail = (tail shl 8) or b
            if (tail == 0x0D0A0D0A) break
        }
        val lines = headerBytes.toString("US-ASCII").split("\r\n")
        val requestLine = lines.first().split(' ')
        if (requestLine.size < 2) return
        val method = requestLine[0]
        val target = requestLine[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
        val headers = lines.drop(1)
            .filter { it.contains(':') }
            .associate {
                it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
            }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            if (count <= 0) break
            offset += count
        }
        val response = routes.entries
            .filter { (route, _) -> path == route || path.startsWith("$route/") }
            .maxByOrNull { it.key.length }
            ?.value
            ?.invoke(Request(method, path, query, headers, body.decodeToString()))
            ?: Response(404, "{}")
        val bytes = response.body.toByteArray()
        val reason = when (response.code) {
            200 -> "OK"
            201 -> "Created"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val output = client.getOutputStream()
        output.write(
            (
                "HTTP/1.1 ${response.code} $reason\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray()
        )
        output.write(bytes)
        output.flush()
    }

    override fun close() {
        socket.close()
        worker.join(1_000)
    }
}
