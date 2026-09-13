package com.example.birdingsoundmvp.planning

import com.example.birdingsoundmvp.i18n.AppText

import com.google.gson.Gson
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class EbirdReferenceEntry(val body: String, val fetchedAtMs: Long, val stale: Boolean = false)
class EbirdRequestException(val failure: EbirdRecentObservationsResponse.Failure) : IOException(failure.message)

class EbirdHttpTransport(
    private val client: OkHttpClient,
    private val cacheDirectory: File? = null,
    private val maxRetries: Int = 2,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val memory = java.util.concurrent.ConcurrentHashMap<String, EbirdReferenceEntry>()
    private val gson = Gson()

    suspend fun get(request: Request, referenceKey: String? = null, forceRefresh: Boolean = false): EbirdReferenceEntry {
        val cached = referenceKey?.let(::readCache)
        if (!forceRefresh && cached != null && now() - cached.fetchedAtMs < CACHE_MS) return cached
        var lastFailure = EbirdRecentObservationsResponse.Failure(AppText.get("eBird 请求失败"))
        for (attempt in 0..maxRetries) {
            try {
                val result = execute(request)
                referenceKey?.let { writeCache(it, result) }
                return result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: EbirdRequestException) { lastFailure = error.failure }
            catch (error: IOException) {
                lastFailure = EbirdRecentObservationsResponse.Failure(
                    if (error is java.io.InterruptedIOException) AppText.get("eBird 请求超时，请稍后重试。") else AppText.get("无法连接 eBird，请检查网络后重试。"),
                    retryable = true
                )
            }
            if (!lastFailure.retryable || attempt == maxRetries) break
            delay((lastFailure.retryAfterMs ?: (500L shl attempt)).coerceIn(0, 60_000))
        }
        if (cached != null && lastFailure.retryable) return cached.copy(stale = true)
        throw EbirdRequestException(lastFailure)
    }

    private suspend fun execute(request: Request): EbirdReferenceEntry = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!response.isSuccessful) throw EbirdRequestException(failure(response))
                        val body = response.body?.string().orEmpty()
                        if (!com.google.gson.JsonParser.parseString(body).isJsonArray) {
                            throw EbirdRequestException(EbirdRecentObservationsResponse.Failure(AppText.get("eBird 返回了无法读取的数据，请稍后重试。")))
                        }
                        if (continuation.isActive) continuation.resume(EbirdReferenceEntry(body, now()))
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
        })
    }

    private fun failure(response: Response): EbirdRecentObservationsResponse.Failure {
        val code = response.code
        val message = when (code) {
            401, 403 -> AppText.get("eBird 密钥无效或无访问权限，请在设置中重新测试。")
            429 -> AppText.get("eBird 请求较多，请稍后重试。")
            400, 404 -> AppText.get("eBird 地区或鸟点无效，请重新选择。")
            in 500..599 -> AppText.get("eBird 服务暂时不可用，请稍后重试。")
            else -> AppText.format("eBird 请求失败（{0}）。", code)
        }
        val retry = response.header("Retry-After")
        val retryMs = retry?.toLongOrNull()?.times(1_000) ?: retry?.let {
            runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now() }.getOrNull()
        }
        return EbirdRecentObservationsResponse.Failure(message, code, retryMs?.coerceAtLeast(0), code == 429 || code >= 500)
    }

    private fun readCache(key: String): EbirdReferenceEntry? = memory[key] ?: runCatching {
        val file = cacheDirectory?.let { File(it, "$key.json") } ?: return null
        gson.fromJson(file.readText(), EbirdReferenceEntry::class.java)?.also { memory[key] = it }
    }.getOrNull()

    private fun writeCache(key: String, entry: EbirdReferenceEntry) {
        memory[key] = entry
        runCatching {
            cacheDirectory?.let { directory ->
                directory.mkdirs()
                val temp = File.createTempFile(key, ".tmp", directory)
                try {
                    temp.writeText(gson.toJson(entry))
                    temp.renameTo(File(directory, "$key.json"))
                } finally { temp.delete() }
            }
        }
    }

    companion object { const val CACHE_MS = 7L * 24 * 60 * 60 * 1_000 }
}
