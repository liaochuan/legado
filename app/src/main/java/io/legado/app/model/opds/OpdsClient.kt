package io.legado.app.model.opds

import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import okhttp3.OkHttpClient
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder

open class OpdsClient(
    private val client: OkHttpClient = okHttpClient
) {

    open suspend fun getFeed(url: String, authorization: String): OpdsFeed {
        val response = try {
            client.newCallResponse(retry = 1) {
                this.url(url)
                header("Authorization", authorization)
                header("Accept", "application/atom+xml,application/xml;q=0.9,*/*;q=0.8")
                get()
            }
        } catch (e: IOException) {
            throw OpdsException(OpdsError.NETWORK, "网络请求失败", e)
        }
        response.use {
            ensureSuccess(it.code, it.priorResponse != null)
            val body = it.body?.string().orEmpty()
            return try {
                OpdsFeedParser.parse(body, it.request.url.toString())
            } catch (e: OpdsException) {
                throw e
            } catch (e: Exception) {
                throw OpdsException(OpdsError.NOT_OPDS, "返回内容不是 OPDS 目录", e)
            }
        }
    }

    open suspend fun isAccessibleWithoutAuth(url: String): Boolean {
        return try {
            client.newCallResponse(retry = 0) {
                this.url(url)
                get()
            }.use { it.isSuccessful }
        } catch (e: IOException) {
            false
        }
    }

    open suspend fun download(url: String, authorization: String): DownloadedFile {
        val response = try {
            client.newCallResponse(retry = 1) {
                this.url(url)
                header("Authorization", authorization)
                get()
            }
        } catch (e: IOException) {
            throw OpdsException(OpdsError.NETWORK, "网络请求失败", e)
        }
        try {
            ensureSuccess(response.code, response.priorResponse != null)
            val body = response.body ?: throw OpdsException(OpdsError.SERVER, "下载内容为空")
            return DownloadedFile(
                stream = body.byteStream(),
                fileName = parseFileName(response.header("Content-Disposition"))
            )
        } catch (e: Exception) {
            response.close()
            throw e
        }
    }

    private fun ensureSuccess(code: Int, redirected: Boolean = false) {
        when {
            code == 401 && redirected -> throw OpdsException(
                OpdsError.UNAUTHORIZED_REDIRECT,
                "跳转后认证失败，可能由反向代理未转发 Authorization 头导致"
            )
            code == 401 -> throw OpdsException(OpdsError.UNAUTHORIZED, "认证失败")
            code == 403 -> throw OpdsException(OpdsError.FORBIDDEN, "没有下载权限")
            code == 404 -> throw OpdsException(OpdsError.NOT_FOUND, "地址不是 OPDS 目录")
            code in 500..599 -> throw OpdsException(OpdsError.SERVER, "书库服务异常")
            code !in 200..299 -> throw OpdsException(OpdsError.SERVER, "请求失败：HTTP $code")
        }
    }

    companion object {
        fun parseFileName(contentDisposition: String?): String? {
            if (contentDisposition.isNullOrBlank()) return null
            val encoded = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
                .find(contentDisposition)?.groupValues?.get(1)?.trim()
            if (!encoded.isNullOrEmpty()) {
                return runCatching {
                    // RFC 5987 是百分号编码，+ 是普通字符，不能按表单编码处理
                    URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8")
                }.getOrNull()
            }
            val plain = Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
                .find(contentDisposition)?.groupValues?.get(1)?.trim()
            return plain?.takeIf { it.isNotEmpty() }
        }
    }
}

data class DownloadedFile(
    val stream: InputStream,
    val fileName: String?
)
