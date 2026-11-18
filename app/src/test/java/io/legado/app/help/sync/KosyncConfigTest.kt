package io.legado.app.help.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KosyncConfigTest {

    @Test
    fun `isConfigured requires server username and auth key`() {
        assertFalse(config(serverUrl = "", username = "u", authKey = "k").isConfigured())
        assertFalse(config(serverUrl = "http://a", username = "", authKey = "k").isConfigured())
        assertFalse(config(serverUrl = "http://a", username = "u", authKey = "").isConfigured())
        assertTrue(config().isConfigured())
    }

    @Test
    fun `fingerprint ignores trailing slash and device settings`() {
        val a = config(serverUrl = "http://a/")
        val b = config(serverUrl = "http://a").copy(deviceName = "other", batchOnStartup = false)
        assertEquals(a.fingerprint(), b.fingerprint())
    }

    @Test
    fun `fingerprint changes with account`() {
        val base = config()
        assertNotEquals(base.fingerprint(), base.copy(username = "other").fingerprint())
        assertNotEquals(base.fingerprint(), base.copy(authKey = "other").fingerprint())
        assertNotEquals(base.fingerprint(), base.copy(serverUrl = "http://b").fingerprint())
    }

    @Test
    fun `shouldReset is true only when fingerprint changes`() {
        val old = config()
        assertFalse(KosyncConfig.shouldReset(old, old.copy(deviceName = "x")))
        assertTrue(KosyncConfig.shouldReset(old, old.copy(username = "other")))
        assertTrue(KosyncConfig.shouldReset(null, old))
    }

    private fun config(
        serverUrl: String = "http://a",
        username: String = "u",
        authKey: String = "k",
    ): KosyncConfig {
        return KosyncConfig(
            serverUrl = serverUrl,
            username = username,
            authKey = authKey,
            deviceName = "Legado",
            batchOnStartup = true,
        )
    }
}
