package com.example.birdingsoundmvp.owlett

import com.example.birdingsoundmvp.i18n.AppText

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray

class DeepSeekRequestException(val statusCode: Int, val detail: String) : RuntimeException(
    when (statusCode) {
        400 -> AppText.get("请求参数未被模型接受，请重试。")
        401, 403 -> AppText.get("DeepSeek 密钥无效或没有访问权限，请检查设置。")
        429 -> AppText.get("DeepSeek 请求较多，请稍后重试。")
        in 500..599 -> AppText.get("DeepSeek 暂时不可用，请稍后重试。")
        else -> AppText.format("DeepSeek 请求失败（{0}），请重试。", statusCode)
    }
)

/** Runs beneath Ktor's SSE body wrapper; SDK streaming and retries remain unchanged. */
class DeepSeekCompatibility(private val thinkingEnabled: Boolean) : Interceptor {
    @Volatile var lastFailure: DeepSeekRequestException? = null
        private set

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        if (!request.url.encodedPath.endsWith("/chat/completions")) return chain.proceed(request)
        val sensitive = mutableListOf<String>()
        request.header("Authorization")?.removePrefix("Bearer ")?.let(sensitive::add)
        request.body?.let { body ->
            val buffer = Buffer()
            body.writeTo(buffer)
            val fields = Json.parseToJsonElement(buffer.readUtf8()).jsonObject.toMutableMap()
            fields["messages"]?.jsonArray?.forEach { message ->
                listOf("content", "reasoning_content").forEach { key ->
                    message.jsonObject[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let(sensitive::add)
                }
            }
            fields["thinking"] = JsonObject(mapOf("type" to JsonPrimitive(if (thinkingEnabled) "enabled" else "disabled")))
            if (thinkingEnabled) fields.remove("tool_choice")
            request = request.newBuilder().post(JsonObject(fields).toString().toRequestBody(JSON_TYPE)).build()
        }
        val response = chain.proceed(request)
        if (response.isSuccessful) {
            lastFailure = null
            return response
        }
        val error = DeepSeekRequestException(response.code, sanitizeServiceError(response.body?.string().orEmpty(), sensitive))
        lastFailure = error
        val body = JsonObject(mapOf("error" to JsonObject(mapOf(
            "message" to JsonPrimitive(error.message), "type" to JsonPrimitive("api_error"),
            "code" to JsonPrimitive(response.code.toString())
        )))).toString()
        return response.newBuilder().header("Content-Type", "application/json")
            .body(body.toResponseBody(JSON_TYPE)).build()
    }

    private companion object { val JSON_TYPE = "application/json; charset=utf-8".toMediaType() }
}

fun sanitizeServiceError(raw: String, sensitive: List<String> = emptyList()): String {
    val parsed = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
    val error = parsed?.get("error")
    var message = runCatching {
        (if (error is JsonObject) error["message"] else error ?: parsed?.get("message"))?.jsonPrimitive?.contentOrNull
    }.getOrNull() ?: return AppText.get("服务返回了非标准错误内容，已隐藏原始响应。")
    sensitive.filter { it.length >= 4 }.sortedByDescending(String::length).forEach {
        message = message.replace(it, "[已隐藏]")
    }
    return message
    .replace(Regex("(?i)Bearer\\s+[^\\s\"<>]+"), "Bearer [已隐藏]")
    .replace(Regex("sk-[A-Za-z0-9_-]+"), AppText.get("[密钥已隐藏]"))
    .replace(Regex("(?i)(api[_-]?key|token|authorization)\\s*[:=]\\s*[^,\\s}]+"), "$1=[已隐藏]")
    .replace(Regex("https?://[^\\s<>]+"), AppText.get("[服务地址]"))
    .take(600)
}
