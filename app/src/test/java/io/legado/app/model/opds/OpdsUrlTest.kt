package io.legado.app.model.opds

import org.junit.Assert.assertEquals
import org.junit.Test

class OpdsUrlTest {

    @Test
    fun `bare host appends opds`() {
        assertEquals("http://host:8083/opds", OpdsUrl.normalize("http://host:8083"))
    }

    @Test
    fun `trailing slash appends opds`() {
        assertEquals("https://host/calibre/opds", OpdsUrl.normalize("https://host/calibre/"))
    }

    @Test
    fun `existing opds is not duplicated`() {
        assertEquals("https://host/opds", OpdsUrl.normalize("https://host/opds/"))
    }

    @Test
    fun `relative href resolves against feed url`() {
        assertEquals(
            "https://host/opds/new?offset=60",
            OpdsUrl.resolve("https://host/opds/new?", "/opds/new?offset=60")
        )
    }

    @Test
    fun `scheme is added when missing`() {
        assertEquals("http://192.168.1.10:8083/opds", OpdsUrl.normalize("192.168.1.10:8083"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank address is rejected`() {
        OpdsUrl.normalize("  ")
    }

    @Test
    fun `search encodes chinese`() {
        assertEquals(
            "https://host/opds/search/%E4%B8%89%E4%BD%93",
            OpdsUrl.search("https://host/opds", "三体")
        )
    }
}
