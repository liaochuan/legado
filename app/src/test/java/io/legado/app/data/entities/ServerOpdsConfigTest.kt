package io.legado.app.data.entities

import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerOpdsConfigTest {

    @Test
    fun `opds config round trips through server json`() {
        val server = Server(
            type = Server.TYPE.OPDS,
            config = GSON.toJson(Server.OpdsConfig("https://calibre.example/opds", "u", "p"))
        )
        val config = server.getOpdsConfig()
        assertEquals("https://calibre.example/opds", config?.url)
        assertEquals("u", config?.username)
        assertEquals("p", config?.password)
    }

    @Test
    fun `webdav server has no opds config`() {
        val server = Server(type = Server.TYPE.WEBDAV, config = "{}")
        assertNull(server.getOpdsConfig())
    }
}
