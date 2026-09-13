package com.example.birdingsoundmvp.planning

import com.example.birdingsoundmvp.trip.TripHistoryItem

data class Plan(
    val id: Long,
    val name: String,
    val plannedDate: String,
    val regionCode: String,
    val hotspotId: String,
    val hotspotName: String,
    val hotspotLatitude: Double?,
    val hotspotLongitude: Double?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val analysisGeneratedAtMs: Long?,
    val analysisTargetYear: Int?,
    val analysisTargetMonth: Int?,
    val historicalRequestedDays: Int,
    val historicalSuccessfulDays: Int,
    val historicalActiveDays: Int,
    val currentRequestedDays: Int,
    val currentSuccessfulDays: Int,
    val currentActiveDays: Int,
    val regionName: String = regionCode,
    val sourceId: String = "ebird",
    val sourceLocationJson: String = "{}",
    val statisticKind: String = "report_day_frequency",
    val deletedAtMs: Long? = null
) {
    val needsHotspot: Boolean
        get() = hotspotId.isBlank()

    val hasAnalysis: Boolean
        get() = analysisGeneratedAtMs != null

    val location: EbirdHotspotMatch
        get() = EbirdHotspotMatch(hotspotId, hotspotName, hotspotLatitude, hotspotLongitude,
            regionCode.substringBefore('-').takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "CN", regionCode, null, sourceId, sourceLocationJson, regionName)
}

data class PlanSpeciesStat(
    val planId: Long,
    val speciesKey: String,
    val speciesCode: String,
    val scientificName: String,
    val commonName: String,
    val displayNameZh: String?,
    val historicalFrequency: Float?,
    val currentFrequency: Float?,
    val combinedFrequency: Float,
    val historicalObservedDays: Int,
    val currentObservedDays: Int,
    val lastSeenDate: String?
) {
    val displayName: String
        get() = displayNameZh?.takeIf { it.isNotBlank() }
            ?: commonName.ifBlank { scientificName.ifBlank { speciesCode } }
}

data class PlanExpectedSpecies(
    val planId: Long,
    val speciesKey: String,
    val speciesCode: String,
    val scientificName: String,
    val commonName: String,
    val displayNameZh: String?,
    val source: String,
    val selectedAtMs: Long
) {
    val displayName: String
        get() = displayNameZh?.takeIf { it.isNotBlank() }
            ?: commonName.ifBlank { scientificName.ifBlank { speciesCode } }
}

data class PlanRareObservation(
    val planId: Long,
    val speciesKey: String,
    val speciesCode: String,
    val scientificName: String,
    val commonName: String,
    val displayNameZh: String?,
    val observedAt: String,
    val locationId: String,
    val locationName: String,
    val latitude: Double?,
    val longitude: Double?,
    val count: Int?,
    val reportCount: Int,
    val provisional: Boolean,
    val sourceUrl: String? = null
) {
    val displayName: String
        get() = displayNameZh?.takeIf { it.isNotBlank() }
            ?: commonName.ifBlank { scientificName.ifBlank { speciesCode } }
}

data class PlanTripLink(
    val planId: Long,
    val tripId: String,
    val linkedAtMs: Long
)

data class PlanSettlement(
    val planId: Long,
    val generatedAtMs: Long,
    val isStale: Boolean,
    val analysisGeneratedAtMs: Long?,
    val linkedTripSignature: String
)

enum class PlanSettlementCategory(val databaseValue: String) {
    HIT("hit"),
    MISSED("missed"),
    EXTRA("extra");

    companion object {
        fun fromDatabase(value: String): PlanSettlementCategory =
            entries.firstOrNull { it.databaseValue == value } ?: EXTRA
    }
}

enum class PlanSettlementOverrideAction(val databaseValue: String) {
    MANUAL_ADD("manual_add"),
    EXCLUDE("exclude");

    companion object {
        fun fromDatabase(value: String): PlanSettlementOverrideAction =
            entries.firstOrNull { it.databaseValue == value } ?: EXCLUDE
    }
}

data class PlanSettlementSpecies(
    val planId: Long,
    val speciesKey: String,
    val speciesCode: String,
    val scientificName: String,
    val commonName: String,
    val displayNameZh: String?,
    val category: PlanSettlementCategory,
    val origin: String,
    val maxConfidence: Float,
    val tripCount: Int,
    val detectionSources: Set<String>
) {
    val displayName: String
        get() = displayNameZh?.takeIf { it.isNotBlank() }
            ?: commonName.ifBlank { scientificName.ifBlank { speciesCode } }
}

data class PlanSettlementOverride(
    val planId: Long,
    val speciesKey: String,
    val speciesCode: String,
    val scientificName: String,
    val commonName: String,
    val displayNameZh: String?,
    val action: PlanSettlementOverrideAction,
    val updatedAtMs: Long
)

data class PlanSummaryItem(
    val plan: Plan,
    val expectedSpeciesCount: Int,
    val linkedTripCount: Int,
    val settlement: PlanSettlement?
)

data class ManagedTripItem(
    val trip: TripHistoryItem,
    val linkedPlans: List<Plan>
)

data class PlanDetailData(
    val plan: Plan,
    val speciesStats: List<PlanSpeciesStat>,
    val expectedSpecies: List<PlanExpectedSpecies>,
    val rareObservations: List<PlanRareObservation>,
    val linkedTrips: List<TripHistoryItem>,
    val settlement: PlanSettlement?,
    val settlementSpecies: List<PlanSettlementSpecies>,
    val settlementOverrides: List<PlanSettlementOverride>
)

data class PlansTripsData(
    val plans: List<PlanSummaryItem> = emptyList(),
    val trips: List<ManagedTripItem> = emptyList(),
    val storageBytes: Long = 0L
)

data class PlanAnalysisCoverage(
    val historicalRequestedDays: Int,
    val historicalSuccessfulDays: Int,
    val historicalActiveDays: Int,
    val currentRequestedDays: Int,
    val currentSuccessfulDays: Int,
    val currentActiveDays: Int
)

data class PlanAnalysisSnapshot(
    val targetYear: Int,
    val targetMonth: Int,
    val generatedAtMs: Long,
    val coverage: PlanAnalysisCoverage,
    val speciesStats: List<PlanSpeciesStat>,
    val rareObservations: List<PlanRareObservation>,
    val warning: String? = null
)

data class EbirdDailyCacheEntry(
    val observations: List<EbirdRecentObservation>,
    val fetchedAtMs: Long
)

data class EbirdRegionOption(
    val code: String,
    val name: String
)

data class AgentActionReceipt(
    val operationId: String,
    val skillId: String,
    val planId: Long?,
    val resultJson: String,
    val createdAtMs: Long
)

fun canonicalSpeciesKey(
    speciesCode: String,
    scientificName: String,
    commonName: String
): String {
    return speciesCode.trim().lowercase().takeIf { it.isNotBlank() }
        ?: scientificName.trim().lowercase().takeIf { it.isNotBlank() }
        ?: commonName.trim().lowercase()
}

fun speciesIdentityAliases(
    speciesKey: String,
    speciesCode: String,
    scientificName: String,
    commonName: String
): Set<String> = buildSet {
    listOf(speciesKey, speciesCode, scientificName, commonName).forEach { value ->
        value.trim().lowercase().takeIf { it.isNotBlank() }?.let(::add)
    }
}
