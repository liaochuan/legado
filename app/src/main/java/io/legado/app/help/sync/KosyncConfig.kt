package io.legado.app.help.sync

import java.security.MessageDigest

data class KosyncConfig(
    val serverUrl: String,
    val username: String,
    val authKey: String,
    val deviceName: String,
    val batchOnStartup: Boolean,
) {

    fun isConfigured(): Boolean {
        return serverUrl.isNotBlank() && username.isNotBlank() && authKey.isNotBlank()
    }

    fun fingerprint(): String {
        val raw = "${serverUrl.trim().trimEnd('/')}\n${username.trim()}\n$authKey"
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun shouldReset(old: KosyncConfig?, new: KosyncConfig): Boolean {
            return old?.fingerprint() != new.fingerprint()
        }
    }
}
