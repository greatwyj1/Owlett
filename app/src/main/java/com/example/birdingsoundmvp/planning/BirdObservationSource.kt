package com.example.birdingsoundmvp.planning

import android.app.Activity
import android.app.Application
import java.time.LocalDate
import java.util.ServiceLoader

data class ObservationSourceCapabilities(
    val requiresApiKey: Boolean,
    val supportsNotable: Boolean,
    val usesAppForeground: Boolean = false,
    val supportsSearchModes: Boolean = false
)

class ObservationQueryNeedsRefinement : IllegalStateException("用户选择先缩小查询范围，已停止后续报告读取。请建议更短日期、具体区县或精确鸟点名，等待用户选择；不要原样重试。")

interface BirdObservationSource {
    val id: String
    val displayName: String
    val capabilities: ObservationSourceCapabilities
    val progressMessage: kotlinx.coroutines.flow.StateFlow<String>? get() = null
    val largeQueryWarning: kotlinx.coroutines.flow.StateFlow<String?>? get() = null
    suspend fun countries(apiKey: String): EbirdRegionsResponse
    suspend fun regions(apiKey: String, parent: String, forceRefresh: Boolean = false): EbirdRegionsResponse
    suspend fun searchLocations(apiKey: String, region: String, keyword: String,
                                start: LocalDate, end: LocalDate, mode: String = "exact"): EbirdHotspotSearchResponse
    suspend fun areaRegions(apiKey: String, province: String, city: String): EbirdRegionsResponse =
        EbirdRegionsResponse.Failure("这个来源不支持市区查询")
    fun combineLocations(locations: List<EbirdHotspotMatch>, keyword: String): EbirdHotspotMatch = locations.single()
    fun observationsFor(plan: Plan): PlanEbirdDataSource
    fun attach(activity: Activity) {}
    fun foreground(active: Boolean) {}
    fun openPage(activity: Activity) {}
    fun continueQuery() {}
    fun narrowQuery() {}
    fun refreshCache() {}
}

interface BirdObservationSourcePlugin {
    fun create(application: Application): BirdObservationSource
}

class EbirdObservationSource(private val client: EbirdRecentObservationsClient) : BirdObservationSource {
    override val id = "ebird"
    override val displayName = "eBird"
    override val capabilities = ObservationSourceCapabilities(requiresApiKey = true, supportsNotable = true)
    override suspend fun countries(apiKey: String) = client.listCountries(apiKey)
    override suspend fun regions(apiKey: String, parent: String, forceRefresh: Boolean) = client.listSubnationalRegions(apiKey, parent, forceRefresh)
    override suspend fun searchLocations(apiKey: String, region: String, keyword: String,
                                        start: LocalDate, end: LocalDate, mode: String) = client.searchHotspots(apiKey, region, keyword)
    override fun observationsFor(plan: Plan) = client
}

class BirdObservationSources private constructor(application: Application) {
    private val sources = linkedMapOf<String, BirdObservationSource>()
    init {
        val ebird = EbirdObservationSource(EbirdRecentObservationsClient(
            cacheDirectory = java.io.File(application.cacheDir, "ebird-reference")))
        sources[ebird.id] = ebird
        ServiceLoader.load(BirdObservationSourcePlugin::class.java, application.classLoader).forEach { plugin ->
            val source = plugin.create(application)
            check(source.id !in sources) { "Duplicate observation source" }
            sources[source.id] = source
        }
    }
    fun all(): List<BirdObservationSource> = sources.values.toList()
    fun find(id: String): BirdObservationSource? = sources[id]
    fun require(id: String): BirdObservationSource = sources[id] ?: error("当前版本不支持此鸟况来源")
    fun attach(activity: Activity) = sources.values.forEach { it.attach(activity) }
    fun foreground(active: Boolean) = sources.values.forEach { it.foreground(active) }

    companion object {
        @Volatile private var instance: BirdObservationSources? = null
        fun get(application: Application): BirdObservationSources = instance ?: synchronized(this) {
            instance ?: BirdObservationSources(application).also { instance = it }
        }
    }
}

object RecentObservationLabel {
    fun label(historicalActiveDays: Int, frequency: Float?): String = when {
        historicalActiveDays < 20 -> "历史样本不足"
        frequency == null || frequency <= 0f -> "历史样本未记录"
        frequency <= 0.05f -> "历史低频"
        else -> "近期记录"
    }
}
