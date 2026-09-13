package com.example.birdingsoundmvp.planning

import com.example.birdingsoundmvp.i18n.AppText

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import java.io.File
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class EbirdRecentObservation(
    val speciesCode: String,
    val scientificName: String,
    val commonName: String,
    val locationId: String,
    val locationName: String,
    val observedAt: String,
    val count: Int?,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val observationValid: Boolean = true,
    val observationReviewed: Boolean = true,
    val submissionId: String = "",
    val sourceUrl: String? = null
)

data class EbirdHotspotMatch(
    val locationId: String,
    val name: String,
    val latitude: Double?,
    val longitude: Double?,
    val countryCode: String,
    val subnational1Code: String,
    val latestObservationDate: String?,
    val sourceId: String = "ebird",
    val sourceLocationJson: String = "{}",
    val regionName: String = subnational1Code
)

sealed class EbirdRecentObservationsResponse {
    data class Success(val observations: List<EbirdRecentObservation>) : EbirdRecentObservationsResponse()
    data class Failure(
        val message: String,
        val statusCode: Int? = null,
        val retryAfterMs: Long? = null,
        val retryable: Boolean = false
    ) : EbirdRecentObservationsResponse()
}

sealed class EbirdHotspotSearchResponse {
    data class Success(val hotspots: List<EbirdHotspotMatch>, val cacheNotice: String? = null) : EbirdHotspotSearchResponse()
    data class Failure(
        val message: String,
        val statusCode: Int? = null,
        val retryAfterMs: Long? = null,
        val retryable: Boolean = false
    ) : EbirdHotspotSearchResponse()
}

sealed class EbirdRegionsResponse {
    data class Success(val regions: List<EbirdRegionOption>, val cacheNotice: String? = null) : EbirdRegionsResponse()
    data class Failure(
        val message: String,
        val statusCode: Int? = null,
        val retryAfterMs: Long? = null,
        val retryable: Boolean = false
    ) : EbirdRegionsResponse()
}

interface PlanEbirdDataSource {
    val handlesRetries: Boolean get() = false
    suspend fun fetchHistoricObservations(
        apiKey: String,
        hotspotId: String,
        date: LocalDate
    ): EbirdRecentObservationsResponse

    suspend fun fetchRecentNotableObservations(
        apiKey: String,
        hotspotId: String,
        backDays: Int = 30
    ): EbirdRecentObservationsResponse
}

class EbirdRecentObservationsClient(
    private val httpClient: OkHttpClient = defaultHttpClient,
    baseUrl: String = DEFAULT_BASE_URL,
    cacheDirectory: File? = null,
    maxRetries: Int = 2
) : PlanEbirdDataSource {
    private val baseUrl = baseUrl.trimEnd('/')
    override val handlesRetries = true
    private val transport = EbirdHttpTransport(httpClient, cacheDirectory, maxRetries)

    suspend fun listCountries(apiKey: String): EbirdRegionsResponse =
        listRegions(apiKey, "country", "world")

    suspend fun searchHotspots(apiKey: String, regionCode: String, keyword: String, maxResults: Int = 20): EbirdHotspotSearchResponse {
        if (apiKey.isBlank()) return EbirdHotspotSearchResponse.Failure(AppText.get("请先在设置中填写 eBird 密钥。"))
        if (keyword.isBlank()) return EbirdHotspotSearchResponse.Failure(AppText.get("请输入鸟点关键词。"))
        val query = EbirdRegionNames.normalize(keyword)
        val tokens = query.split(Regex("\\s+")).filter(String::isNotBlank)
        if (tokens.isEmpty()) return EbirdHotspotSearchResponse.Failure(AppText.get("请输入鸟点关键词。"))
        val region = regionCode.trim().ifBlank { "CN" }
        return try {
            val entry = transport.get(request(apiKey, "$baseUrl/ref/hotspot/${encode(region)}?fmt=json"), "hotspots-${encode(region)}")
            val matches = parseHotspots(entry.body).filter { hotspot ->
                val name = EbirdRegionNames.normalize("${hotspot.name} ${hotspot.locationId}")
                tokens.all(name::contains)
            }.sortedWith(compareByDescending<EbirdHotspotMatch> { EbirdRegionNames.normalize(it.name) == query }
                .thenBy { it.name.length }).take(maxResults.coerceAtLeast(1))
            EbirdHotspotSearchResponse.Success(matches, cacheNotice(entry))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val failure = failure(error)
            EbirdHotspotSearchResponse.Failure(failure.message, failure.statusCode, failure.retryAfterMs, failure.retryable)
        }
    }

    suspend fun listSubnationalRegions(apiKey: String, parentRegionCode: String = "CN", forceRefresh: Boolean = false): EbirdRegionsResponse =
        listRegions(apiKey, "subnational1", parentRegionCode, forceRefresh)

    private suspend fun listRegions(apiKey: String, level: String, parentRegionCode: String, forceRefresh: Boolean = false): EbirdRegionsResponse {
        if (apiKey.isBlank()) return EbirdRegionsResponse.Failure(AppText.get("请先在设置中填写 eBird 密钥。"))
        return try {
            val entry = transport.get(
                request(apiKey, "$baseUrl/ref/region/list/${encode(level)}/${encode(parentRegionCode)}?fmt=json"),
                "regions-${encode(level)}-${encode(parentRegionCode)}", forceRefresh
            )
            EbirdRegionsResponse.Success(parseRegions(entry.body).map {
                it.copy(name = EbirdRegionNames.displayName(it.code, it.name))
            }, cacheNotice(entry))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val failure = failure(error)
            EbirdRegionsResponse.Failure(failure.message, failure.statusCode, failure.retryAfterMs, failure.retryable)
        }
    }

    suspend fun testConnection(apiKey: String): EbirdRegionsResponse = try {
        if (apiKey.isBlank()) EbirdRegionsResponse.Failure(AppText.get("请先填写 eBird 密钥。"))
        else {
            val entry = transport.get(request(apiKey, "$baseUrl/ref/region/list/country/world?fmt=json"))
            EbirdRegionsResponse.Success(parseRegions(entry.body))
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) {
        val failure = failure(error)
        EbirdRegionsResponse.Failure(failure.message, failure.statusCode, failure.retryAfterMs, failure.retryable)
    }

    override suspend fun fetchHistoricObservations(apiKey: String, hotspotId: String, date: LocalDate): EbirdRecentObservationsResponse =
        fetchObservationRequest(apiKey, hotspotId, "$baseUrl/data/obs/${encode(hotspotId)}/historic/${date.year}/${date.monthValue}/${date.dayOfMonth}?detail=full&includeProvisional=true&cat=species&maxResults=10000")

    override suspend fun fetchRecentNotableObservations(apiKey: String, hotspotId: String, backDays: Int): EbirdRecentObservationsResponse =
        fetchObservationRequest(apiKey, hotspotId, "$baseUrl/data/obs/${encode(hotspotId)}/recent/notable?back=${backDays.coerceIn(1, 30)}&detail=full&includeProvisional=true&maxResults=10000")

    suspend fun fetchRecentObservations(apiKey: String, hotspotIds: List<String>, backDays: Int = DEFAULT_BACK_DAYS): EbirdRecentObservationsResponse {
        val observations = mutableListOf<EbirdRecentObservation>()
        if (hotspotIds.isEmpty()) return EbirdRecentObservationsResponse.Failure(AppText.get("请先选择 eBird 鸟点。"))
        hotspotIds.distinct().forEach { id ->
            when (val result = fetchObservationRequest(apiKey, id, "$baseUrl/data/obs/${encode(id)}/recent?back=${backDays.coerceIn(1, 30)}&detail=full&includeProvisional=true&maxResults=10000")) {
                is EbirdRecentObservationsResponse.Success -> observations += result.observations
                is EbirdRecentObservationsResponse.Failure -> return result
            }
        }
        return EbirdRecentObservationsResponse.Success(observations)
    }

    private suspend fun fetchObservationRequest(token: String, id: String, url: String): EbirdRecentObservationsResponse {
        if (token.isBlank()) return EbirdRecentObservationsResponse.Failure(AppText.get("请先在设置中填写 eBird 密钥。"))
        if (id.isBlank()) return EbirdRecentObservationsResponse.Failure(AppText.get("请先选择 eBird 鸟点。"))
        return try {
            EbirdRecentObservationsResponse.Success(parseObservations(transport.get(request(token, url)).body, id))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure(error) }
    }

    private fun request(token: String, url: String): Request = Request.Builder().url(url)
        .header("X-eBirdApiToken", token.trim()).header("Accept", "application/json").get().build()

    private fun failure(error: Exception): EbirdRecentObservationsResponse.Failure =
        (error as? EbirdRequestException)?.failure
            ?: EbirdRecentObservationsResponse.Failure(AppText.get("eBird 数据读取失败，请稍后重试。"))

    private fun cacheNotice(entry: EbirdReferenceEntry): String? = if (!entry.stale) null else
        AppText.get("当前显示离线目录，更新于 ") + java.time.Instant.ofEpochMilli(entry.fetchedAtMs)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()

    private fun parseObservations(body: String, fallbackLocationId: String): List<EbirdRecentObservation> {
        val root = JsonParser.parseString(body)
        if (!root.isJsonArray) return emptyList()
        return root.asJsonArray.mapNotNull { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val scientific = item.stringOrEmpty("sciName")
            val common = item.stringOrEmpty("comName")
            if (scientific.isBlank() && common.isBlank()) return@mapNotNull null
            EbirdRecentObservation(
                speciesCode = item.stringOrEmpty("speciesCode"),
                scientificName = scientific,
                commonName = common,
                locationId = item.stringOrEmpty("locId").ifBlank { fallbackLocationId },
                locationName = item.stringOrEmpty("locName"),
                observedAt = item.stringOrEmpty("obsDt"),
                count = item.intOrNull("howMany"),
                latitude = item.doubleOrNull("lat", "latitude"),
                longitude = item.doubleOrNull("lng", "lon", "longitude"),
                observationValid = item.booleanOrDefault("obsValid", true),
                observationReviewed = item.booleanOrDefault("obsReviewed", true),
                submissionId = item.stringOrEmpty("subId")
            )
        }
    }

    private fun parseHotspots(body: String): List<EbirdHotspotMatch> {
        val root = JsonParser.parseString(body)
        if (!root.isJsonArray) return emptyList()
        return root.asJsonArray.mapNotNull { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val locationId = item.stringOrEmpty("locId")
            val name = item.stringOrEmpty("locName")
            if (locationId.isBlank() || name.isBlank()) return@mapNotNull null
            EbirdHotspotMatch(
                locationId = locationId,
                name = name,
                latitude = item.doubleOrNull("lat", "latitude"),
                longitude = item.doubleOrNull("lng", "lon", "longitude"),
                countryCode = item.stringOrEmpty("countryCode"),
                subnational1Code = item.stringOrEmpty("subnational1Code"),
                latestObservationDate = item.stringOrEmpty("latestObsDt").ifBlank { null }
            )
        }
    }

    private fun parseRegions(body: String): List<EbirdRegionOption> {
        val root = JsonParser.parseString(body)
        if (!root.isJsonArray) return emptyList()
        return root.asJsonArray.mapNotNull { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val code = item.stringOrEmpty("code")
            val name = item.stringOrEmpty("name")
            if (code.isBlank() || name.isBlank()) null else EbirdRegionOption(code, name)
        }.sortedBy { it.name.lowercase() }
    }

    private fun JsonObject.stringOrEmpty(name: String): String =
        get(name)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }.orEmpty()

    private fun JsonObject.doubleOrNull(vararg names: String): Double? {
        names.forEach { name ->
            val element = get(name)?.takeIf { !it.isJsonNull } ?: return@forEach
            runCatching { element.asDouble }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun JsonObject.intOrNull(name: String): Int? =
        get(name)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asInt }.getOrNull() }

    private fun JsonObject.booleanOrDefault(name: String, default: Boolean): Boolean =
        get(name)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asBoolean }.getOrNull() } ?: default

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun retryAfterMillis(value: String?): Long? {
        val raw = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        raw.toLongOrNull()?.let { return (it * 1_000L).coerceAtLeast(0L) }
        val retryAt = runCatching {
            ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull() ?: return null
        return (retryAt - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    companion object {
        const val DEFAULT_BACK_DAYS = 30
        const val DEFAULT_BASE_URL = "https://api.ebird.org/v2"

        private val defaultHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .callTimeout(45, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
