package io.legado.app.help.sync

import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.URLEncoder

class KosyncClient(
    baseUrl: String,
    private val username: String,
    private val authKey: String,
    private val client: OkHttpClient,
) {

    private val root = baseUrl.trimEnd('/')

    fun capabilityKey(): String = "$root\n$authKey"

    suspend fun auth(): Boolean {
        val response = execute(request("/users/auth").get().build())
        response.use {
            if (it.code == 401) return false
            if (!it.isSuccessful) {
                throw KosyncException("KOSync auth failed: HTTP ${it.code}", it.code)
            }
            return true
        }
    }

    suspend fun getProgress(document: String): KosyncProgressDto? {
        val response = execute(request("/syncs/progress/$document").get().build())
        response.use {
            if (it.code == 404) return null
            if (it.code == 401) {
                throw KosyncException("KOSync unauthorized", 401)
            }
            if (!it.isSuccessful) {
                throw KosyncException("KOSync get progress failed: HTTP ${it.code}", it.code)
            }
            val body = it.body.string()
            if (body.isBlank()) return null
            val dto = runCatching { GSON.fromJson(body, KosyncProgressDto::class.java) }
                .getOrNull()
                ?: throw KosyncException("Invalid KOSync progress response", it.code)
            if (dto.progress.isNullOrBlank() && dto.percentage == null) return null
            return dto
        }
    }

    suspend fun putProgress(
        document: String,
        progress: String,
        percentage: Double,
        device: String,
        deviceId: String,
        metadata: KosyncMetadata? = null,
    ): Long? {
        val body = progressBody(document, progress, percentage, device, deviceId, metadata)
        val response = execute(request("/syncs/progress").put(body).build())
        response.use {
            if (it.code == 401) {
                throw KosyncException("KOSync unauthorized", 401)
            }
            if (!it.isSuccessful) {
                throw KosyncException("KOSync put progress failed: HTTP ${it.code}", it.code)
            }
            return responseTimestamp(it.body.string(), document)
        }
    }

    /**
     * crosspoint-sync 的 PUT /syncs/progress 只接受前进，后退会被静默丢弃。
     * 覆盖云端进度（允许后退）时必须走 PUT /api/v1/progress。
     * 官方 kosync 没有该接口，返回 404 时由调用方回退。
     */
    suspend fun putProgressExtended(
        document: String,
        progress: String,
        percentage: Double,
        device: String,
        deviceId: String,
        metadata: KosyncMetadata? = null,
    ): Long? {
        val body = progressBody(document, progress, percentage, device, deviceId, metadata)
        val response = execute(request("/api/v1/progress").put(body).build())
        response.use {
            if (it.code == 401) {
                throw KosyncException("KOSync unauthorized", 401)
            }
            if (it.code == 404) {
                throw KosyncException("KOSync extended API is not supported", 404)
            }
            if (!it.isSuccessful) {
                throw KosyncException("KOSync put progress failed: HTTP ${it.code}", it.code)
            }
            return responseTimestamp(it.body.string(), document)
        }
    }

    private fun progressBody(
        document: String,
        progress: String,
        percentage: Double,
        device: String,
        deviceId: String,
        metadata: KosyncMetadata?,
    ) = GSON.toJson(buildMap<String, Any> {
        put("document", document)
        put("progress", progress)
        put("percentage", percentage)
        put("device", device)
        put("device_id", deviceId)
        if (metadata != null) {
            put("metadata", metadata)
        }
    }).toRequestBody("application/json; charset=UTF-8".toMediaType())

    private suspend fun responseTimestamp(text: String, document: String): Long? {
        val timestamp = runCatching {
            GSON.fromJson(text, KosyncProgressDto::class.java)?.timestamp
        }.getOrNull()
        return timestamp ?: getProgress(document)?.timestamp
    }

    suspend fun listProgress(limit: Int = 500): List<KosyncListItem> {
        val result = ArrayList<KosyncListItem>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val path = buildString {
                append("/api/v1/progress?limit=").append(limit)
                cursor?.let {
                    append("&cursor=").append(URLEncoder.encode(it, "UTF-8"))
                }
            }
            val response = execute(request(path).get().build())
            response.use {
                if (it.code == 401) {
                    throw KosyncException("KOSync unauthorized", 401)
                }
                if (it.code == 404) {
                    throw KosyncException("KOSync extended API is not supported", 404)
                }
                if (!it.isSuccessful) {
                    throw KosyncException("KOSync list progress failed: HTTP ${it.code}", it.code)
                }
                val body = it.body.string()
                val page = runCatching {
                    GSON.fromJson(body, KosyncListResponse::class.java)
                }.getOrNull() ?: throw KosyncException("Invalid progress list response", it.code)
                result.addAll(page.items)
                cursor = page.next
                if (cursor.isNullOrBlank()) return result
            }
        }
        return result
    }

    suspend fun supportsExtendedApi(): Boolean {
        val response = execute(request("/api/v1/progress?limit=1").get().build())
        response.use {
            if (it.code == 401) {
                throw KosyncException("KOSync unauthorized", 401)
            }
            return it.isSuccessful
        }
    }

    suspend fun putDocumentInfo(document: String, coverUrl: String): Boolean {
        val body = GSON.toJson(mapOf("cover_url" to coverUrl))
            .toRequestBody("application/json; charset=UTF-8".toMediaType())
        val response = execute(request("/api/v1/documents/$document/info").put(body).build())
        response.use {
            if (it.code == 401) {
                throw KosyncException("KOSync unauthorized", 401)
            }
            if (!it.isSuccessful) {
                throw KosyncException("KOSync put document info failed: HTTP ${it.code}", it.code)
            }
            return true
        }
    }

    private fun request(path: String): Request.Builder {
        return Request.Builder()
            .url("$root$path")
            .header("Accept", "application/vnd.koreader.v1+json")
            .header("x-auth-user", username)
            .header("x-auth-key", authKey)
    }

    private suspend fun execute(request: Request): Response {
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute()
        }
    }

    private companion object {
        const val MAX_PAGES = 100
    }
}
