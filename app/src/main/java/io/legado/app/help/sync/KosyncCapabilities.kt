package io.legado.app.help.sync

import java.util.concurrent.ConcurrentHashMap

object KosyncCapabilities {

    private val cache = ConcurrentHashMap<String, Boolean>()

    fun isSupported(serverUrl: String, authKey: String): Boolean {
        return isSupported(key(serverUrl, authKey))
    }

    fun isSupported(cacheKey: String): Boolean {
        return cache[cacheKey] == true
    }

    fun setSupported(serverUrl: String, authKey: String, supported: Boolean) {
        setSupported(key(serverUrl, authKey), supported)
    }

    fun setSupported(cacheKey: String, supported: Boolean) {
        cache[cacheKey] = supported
    }

    fun clear() {
        cache.clear()
    }

    private fun key(serverUrl: String, authKey: String): String {
        return "$serverUrl\n$authKey"
    }
}
