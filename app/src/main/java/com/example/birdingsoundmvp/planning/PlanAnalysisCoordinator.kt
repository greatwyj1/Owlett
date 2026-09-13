package com.example.birdingsoundmvp.planning

import com.example.birdingsoundmvp.i18n.AppText

import java.time.LocalDate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class PlanAnalysisStage {
    HISTORY,
    CURRENT_YEAR,
    RARE_BIRDS,
    SPECIES_NAMES,
    SAVING
}

data class PlanAnalysisProgress(
    val stage: PlanAnalysisStage,
    val completedDays: Int,
    val totalDays: Int,
    val message: String
) {
    val fraction: Float
        get() = if (totalDays <= 0) 0f else completedDays.toFloat() / totalDays.toFloat()
}

sealed class PlanAnalysisResult {
    data class Success(val snapshot: PlanAnalysisSnapshot) : PlanAnalysisResult()
    data class Failure(val message: String) : PlanAnalysisResult()
}

class PlanAnalysisCoordinator(
    private val repository: PlanAnalysisStore,
    private val client: PlanEbirdDataSource,
    private val displayNameResolver: (scientificName: String, commonName: String) -> String? = { _, _ -> null },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val requiresApiKey: Boolean = true,
    private val useDailyCache: Boolean = true,
    private val supportsNotable: Boolean = true
) {
    private val requestSemaphore = Semaphore(MAX_CONCURRENT_REQUESTS)

    suspend fun analyze(
        plan: Plan,
        apiKey: String,
        today: LocalDate = LocalDate.now(),
        onProgress: (PlanAnalysisProgress) -> Unit = {}
    ): PlanAnalysisResult = coroutineScope {
        if (plan.hotspotId.isBlank()) {
            return@coroutineScope PlanAnalysisResult.Failure(AppText.get("Select an eBird hotspot before organizing bird activity."))
        }
        if (requiresApiKey && apiKey.isBlank()) {
            return@coroutineScope PlanAnalysisResult.Failure(AppText.get("Add your eBird API key in Settings first."))
        }
        val plannedDate = runCatching { LocalDate.parse(plan.plannedDate) }.getOrNull()
            ?: return@coroutineScope PlanAnalysisResult.Failure(AppText.get("The plan date is invalid."))
        val dates = PlanAnalysisDatePlanner.datesFor(plannedDate, today)
        val totalDateRequests = dates.historicalDates.size + dates.currentDates.size
        var completed = 0

        suspend fun loadGroup(
            groupDates: List<LocalDate>,
            historical: Boolean,
            stage: PlanAnalysisStage,
            stageMessage: String
        ): List<EbirdObservationDayResult> {
            val output = mutableListOf<EbirdObservationDayResult>()
            groupDates.chunked(MAX_CONCURRENT_REQUESTS).forEach { chunk ->
                val batch = chunk.map { date ->
                    async { loadObservationDay(plan.hotspotId, date, historical, apiKey) }
                }.awaitAll()
                output += batch
                completed += batch.size
                onProgress(
                    PlanAnalysisProgress(
                        stage = stage,
                        completedDays = completed,
                        totalDays = totalDateRequests,
                        message = stageMessage
                    )
                )
            }
            return output
        }

        onProgress(
            PlanAnalysisProgress(
                PlanAnalysisStage.HISTORY,
                completed,
                totalDateRequests,
                AppText.get("Loading reports from the previous two years")
            )
        )
        val historical = loadGroup(
            groupDates = dates.historicalDates,
            historical = true,
            stage = PlanAnalysisStage.HISTORY,
            stageMessage = AppText.get("Loading reports from the previous two years")
        )
        val historicalSuccessRate = if (historical.isEmpty()) {
            0f
        } else {
            historical.count(EbirdObservationDayResult::isSuccess).toFloat() / historical.size.toFloat()
        }
        if (historicalSuccessRate < MINIMUM_HISTORY_SUCCESS_RATE) {
            val successful = historical.count(EbirdObservationDayResult::isSuccess)
            return@coroutineScope PlanAnalysisResult.Failure(
                AppText.format("Only {0} of {1} historical dates loaded. ", successful, historical.size) +
                    AppText.get("The previous snapshot was kept; check the network and retry.")
            )
        }

        onProgress(
            PlanAnalysisProgress(
                PlanAnalysisStage.CURRENT_YEAR,
                completed,
                totalDateRequests,
                if (dates.currentDates.isEmpty()) AppText.get("No current-year data is available yet") else AppText.get("Loading target-year reports")
            )
        )
        val current = loadGroup(
            groupDates = dates.currentDates,
            historical = false,
            stage = PlanAnalysisStage.CURRENT_YEAR,
            stageMessage = AppText.get("Loading target-year reports")
        )

        onProgress(
            PlanAnalysisProgress(
                PlanAnalysisStage.RARE_BIRDS,
                completed,
                totalDateRequests,
                if (supportsNotable) AppText.get("Checking notable reports from the last 30 days") else "查询最近30天记录"
            )
        )
        val notableResponse = requestSemaphore.withPermit {
            fetchWithRetry { client.fetchRecentNotableObservations(apiKey, plan.hotspotId, 30) }
        }
        val notable = (notableResponse as? EbirdRecentObservationsResponse.Success)?.observations.orEmpty()
        val notableWarning = (notableResponse as? EbirdRecentObservationsResponse.Failure)?.let {
            AppText.format("Recent notable reports could not be refreshed: {0}", it.message)
        }

        val observations = (historical + current).flatMap(EbirdObservationDayResult::observations) + notable
        val representativeByKey = observations
            .filter { canonicalSpeciesKey(it.speciesCode, it.scientificName, it.commonName).isNotBlank() }
            .associateBy { canonicalSpeciesKey(it.speciesCode, it.scientificName, it.commonName) }
        onProgress(
            PlanAnalysisProgress(
                PlanAnalysisStage.SPECIES_NAMES,
                completed,
                totalDateRequests,
                AppText.get("Matching species names")
            )
        )
        val displayNames = representativeByKey.mapNotNull { (key, observation) ->
            displayNameResolver(observation.scientificName, observation.commonName)
                ?.takeIf(String::isNotBlank)
                ?.let { key to it }
        }.toMap()

        val (stats, coverage) = PlanStatisticsCalculator.calculate(
            planId = plan.id,
            historical = historical,
            current = current,
            displayNameZhByKey = displayNames
        )
        val rare = PlanStatisticsCalculator.aggregateRareObservations(
            planId = plan.id,
            observations = notable,
            displayNameZhByKey = displayNames
        )
        val failedDays = historical.count { !it.isSuccess } + current.count { !it.isSuccess }
        val coverageWarning = failedDays.takeIf { it > 0 }?.let {
            AppText.format("部分日期未能查询", it)
        }
        val snapshot = PlanAnalysisSnapshot(
            targetYear = dates.targetYear,
            targetMonth = dates.targetMonth,
            generatedAtMs = nowMillis(),
            coverage = coverage,
            speciesStats = stats,
            rareObservations = rare,
            warning = listOfNotNull(coverageWarning, notableWarning)
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
        )
        onProgress(
            PlanAnalysisProgress(
                PlanAnalysisStage.SAVING,
                totalDateRequests,
                totalDateRequests,
                AppText.get("Saving analysis")
            )
        )
        repository.replaceAnalysis(plan.id, snapshot)
        PlanAnalysisResult.Success(snapshot)
    }

    private suspend fun loadObservationDay(
        hotspotId: String,
        date: LocalDate,
        historical: Boolean,
        apiKey: String
    ): EbirdObservationDayResult {
        val cached = if (useDailyCache) repository.getCachedObservationDay(
            hotspotId = hotspotId,
            observationDate = date,
            minFetchedAtMs = nowMillis() - CACHE_VALIDITY_MS
        ) else null
        if (cached != null) {
            return EbirdObservationDayResult(date, historical, true, cached.observations)
        }
        return when (val response = requestSemaphore.withPermit {
            fetchWithRetry { client.fetchHistoricObservations(apiKey, hotspotId, date) }
        }) {
            is EbirdRecentObservationsResponse.Success -> {
                if (useDailyCache) repository.putCachedObservationDay(hotspotId, date, response.observations, nowMillis())
                EbirdObservationDayResult(date, historical, true, response.observations)
            }
            is EbirdRecentObservationsResponse.Failure -> {
                if (response.statusCode == 401 || response.statusCode == 403) throw EbirdRequestException(response)
                EbirdObservationDayResult(date, historical, false, error = response.message)
            }
        }
    }

    private suspend fun fetchWithRetry(
        request: suspend () -> EbirdRecentObservationsResponse
    ): EbirdRecentObservationsResponse {
        if (client.handlesRetries) return request()
        var attempt = 0
        while (true) {
            val response = request()
            if (response !is EbirdRecentObservationsResponse.Failure ||
                !response.retryable || attempt >= MAX_RETRIES
            ) {
                return response
            }
            val fallback = BASE_RETRY_DELAY_MS * (1L shl attempt)
            delay((response.retryAfterMs ?: fallback).coerceAtMost(MAX_RETRY_DELAY_MS))
            attempt += 1
        }
    }

    private companion object {
        const val MAX_CONCURRENT_REQUESTS = 2
        const val MAX_RETRIES = 2
        const val BASE_RETRY_DELAY_MS = 500L
        const val MAX_RETRY_DELAY_MS = 60_000L
        const val CACHE_VALIDITY_MS = 7L * 24L * 60L * 60L * 1_000L
        const val MINIMUM_HISTORY_SUCCESS_RATE = 0.5f
    }
}

interface PlanAnalysisStore {
    fun getCachedObservationDay(
        hotspotId: String,
        observationDate: LocalDate,
        minFetchedAtMs: Long
    ): EbirdDailyCacheEntry?

    fun putCachedObservationDay(
        hotspotId: String,
        observationDate: LocalDate,
        observations: List<EbirdRecentObservation>,
        fetchedAtMs: Long = System.currentTimeMillis()
    )

    fun replaceAnalysis(planId: Long, snapshot: PlanAnalysisSnapshot)
}
