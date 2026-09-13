package com.example.birdingsoundmvp.owlett

import com.example.birdingsoundmvp.i18n.AppText

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class XenoCantoRecording(
    val id: String,
    val scientificName: String,
    val englishName: String,
    val recordist: String,
    val country: String,
    val location: String,
    val date: String,
    val soundType: String,
    val quality: String,
    val length: String,
    val license: String,
    val audioUrl: String,
    val sonogramUrl: String,
    val sourceUrl: String
)

sealed interface XenoCantoResponse {
    data class Success(val recordings: List<XenoCantoRecording>, val total: Int = recordings.size, val scopeNote: String = "") : XenoCantoResponse
    data class Failure(val message: String, val statusCode: Int? = null) : XenoCantoResponse
}

class XenoCantoClient(
    private val httpClient: OkHttpClient = defaultHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL
) {
    suspend fun search(
        apiKey: String,
        scientificName: String,
        maxResults: Int = 3,
        country: String = "",
        locality: String = ""
    ): XenoCantoResponse {
        val token = apiKey.trim()
        if (token.isBlank()) return XenoCantoResponse.Failure(AppText.get("Add a xeno-canto API key in Settings first."))
        val name = scientificName.trim()
        if (name.isBlank()) return XenoCantoResponse.Failure(AppText.get("A scientific species name is required."))
        val url = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("query", buildQuery(name, country, locality))
            .addQueryParameter("key", token)
            .addQueryParameter("per_page", maxResults.coerceIn(10, 20).toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()
        return try {
            val (code, body) = requestBody(request)
            if (code !in 200..299) {
                return XenoCantoResponse.Failure(
                    AppText.format("xeno-canto request failed ({0})", code), code
                )
            }
            val root = JsonParser.parseString(body).asJsonObject
            require(root.get("recordings")?.isJsonArray == true) { "Invalid recordings response" }
            XenoCantoResponse.Success(
                parseRecordings(body)
                    .sortedWith(compareBy<XenoCantoRecording> { qualityRank(it.quality) }.thenBy { it.id })
                    .take(maxResults.coerceAtLeast(1)),
                root.get("numRecordings")?.asInt ?: root.getAsJsonArray("recordings").size()
            )
        } catch (cancel: CancellationException) { throw cancel }
          catch (error: java.io.IOException) {
            XenoCantoResponse.Failure(AppText.get("xeno-canto 网络连接失败，请稍后重试。"))
        } catch (_: Exception) {
            XenoCantoResponse.Failure("xeno-canto 返回的数据无法读取，未将其当作没有鸣声记录。")
        }
    }

    suspend fun test(apiKey: String): XenoCantoResponse = search(apiKey, "Turdus merula", 1)

    suspend fun searchRegional(key: String, name: String, region: BirdCallRegion): XenoCantoResponse {
        val first = search(key, name, country = region.country, locality = region.locality)
        val scope = listOf(region.country, region.locality).filter(String::isNotBlank).joinToString(" / ").ifBlank { "全球" }
        if (first !is XenoCantoResponse.Success) return first
        if (first.total > 0 || region.origin == "对话指定地区" || region.country.isBlank())
            return first.copy(scopeNote = "${region.origin}：$scope" + if (first.total == 0) "；暂无记录，未擅自扩大范围" else "")
        val national = if (region.locality.isBlank()) first else search(key, name, country = region.country)
        if (national !is XenoCantoResponse.Success) return national
        if (national.total > 0) return national.copy(scopeNote = "$scope 未找到记录，已在所在国家 ${region.country} 查找")
        return when (val worldwide = search(key, name)) {
            is XenoCantoResponse.Success -> worldwide.copy(scopeNote = "所在国家 ${region.country} 没有该鸟种的鸣声记录，已扩大到全球")
            else -> worldwide
        }
    }

    private suspend fun requestBody(request: Request): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    try {
                        val result = response.code to response.body?.string().orEmpty()
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                }
            }
        })
    }

    internal fun buildQuery(name: String, country: String, locality: String): String {
        fun quote(value: String) = value.replace("\\", " ").replace("\"", " ").replace(Regex("\\s+"), " ").trim()
        return "sp:\"${quote(name)}\" grp:birds" +
            country.takeIf(String::isNotBlank)?.let { " cnt:\"${quote(it)}\"" }.orEmpty() +
            locality.takeIf(String::isNotBlank)?.let { " loc:\"${quote(it)}\"" }.orEmpty()
    }

    internal fun parseRecordings(body: String): List<XenoCantoRecording> {
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return emptyList()
        val recordings = root.getAsJsonArray("recordings") ?: return emptyList()
        return recordings.mapNotNull { element ->
            val item = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
            val id = item.string("id")
            val genus = item.string("gen")
            val species = item.string("sp")
            val scientific = listOf(genus, species).filter(String::isNotBlank).joinToString(" ")
            val audio = normalizeUrl(item.string("file"))
            if (!id.matches(Regex("[0-9]+")) || audio.isBlank()) return@mapNotNull null
            val sonogram = item.get("sono")?.takeIf(JsonElement::isJsonObject)?.asJsonObject?.let { sono ->
                sequenceOf("med", "small", "large", "full")
                    .map { name -> sono.string(name) }
                    .firstOrNull(String::isNotBlank)
                    .orEmpty()
            }.orEmpty()
            XenoCantoRecording(
                id = id,
                scientificName = scientific,
                englishName = item.string("en"),
                recordist = item.string("rec"),
                country = item.string("cnt"),
                location = item.string("loc"),
                date = item.string("date"),
                soundType = item.textOrList("type"),
                quality = item.string("q"),
                length = item.string("length"),
                license = item.string("lic"),
                audioUrl = audio,
                sonogramUrl = normalizeUrl(sonogram),
                sourceUrl = "https://xeno-canto.org/$id"
            )
        }
    }

    private fun JsonObject.string(name: String): String = get(name)
        ?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
        ?.let { runCatching { it.asString }.getOrNull() }
        .orEmpty()

    private fun JsonObject.textOrList(name: String): String {
        val value = get(name) ?: return ""
        return when {
            value.isJsonArray -> value.asJsonArray.mapNotNull { element ->
                element.takeIf { it.isJsonPrimitive }?.asString
            }.joinToString(", ")
            value.isJsonPrimitive -> runCatching { value.asString }.getOrDefault("")
            else -> ""
        }
    }

    private fun normalizeUrl(url: String): String = (when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://") -> "https://${url.removePrefix("http://")}" 
        else -> url
    }).toHttpUrlOrNull()?.takeIf { it.isHttps && it.username.isBlank() && it.password.isBlank() }?.toString().orEmpty()

    private fun qualityRank(value: String): Int = when (value.uppercase()) {
        "A" -> 0
        "B" -> 1
        "C" -> 2
        "D" -> 3
        "E" -> 4
        else -> 5
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://xeno-canto.org/api/3/recordings"
        private val defaultHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
