package com.example.birdingsoundmvp.planning

import java.time.LocalDate
import java.time.YearMonth

data class PlanAnalysisDates(
    val targetYear: Int,
    val targetMonth: Int,
    val historicalDates: List<LocalDate>,
    val currentDates: List<LocalDate>
)

data class EbirdObservationDayResult(
    val date: LocalDate,
    val isHistorical: Boolean,
    val isSuccess: Boolean,
    val observations: List<EbirdRecentObservation> = emptyList(),
    val error: String? = null
)

object PlanAnalysisDatePlanner {
    fun datesFor(plannedDate: LocalDate, today: LocalDate): PlanAnalysisDates {
        val targetMonth = YearMonth.from(plannedDate)
        val yesterday = today.minusDays(1)
        val historical = (plannedDate.year - 2..plannedDate.year - 1)
            .flatMap { year -> datesInMonth(YearMonth.of(year, plannedDate.monthValue)) }
            .filter { !it.isAfter(yesterday) }
        val current = datesInMonth(targetMonth).filter { !it.isAfter(yesterday) }
        return PlanAnalysisDates(
            targetYear = plannedDate.year,
            targetMonth = plannedDate.monthValue,
            historicalDates = historical,
            currentDates = current
        )
    }

    private fun datesInMonth(month: YearMonth): List<LocalDate> =
        (1..month.lengthOfMonth()).map(month::atDay)
}

object PlanStatisticsCalculator {
    fun calculate(
        planId: Long,
        historical: List<EbirdObservationDayResult>,
        current: List<EbirdObservationDayResult>,
        displayNameZhByKey: Map<String, String> = emptyMap()
    ): Pair<List<PlanSpeciesStat>, PlanAnalysisCoverage> {
        val successfulHistorical = historical.filter { it.isSuccess }
        val successfulCurrent = current.filter { it.isSuccess }
        val activeHistorical = successfulHistorical.filter { it.observations.isNotEmpty() }
        val activeCurrent = successfulCurrent.filter { it.observations.isNotEmpty() }
        val allObservations = (successfulHistorical + successfulCurrent).flatMap { it.observations }
        val resolver = ObservationIdentityResolver(allObservations)
        val historicalDaysBySpecies = speciesDays(activeHistorical, resolver)
        val currentDaysBySpecies = speciesDays(activeCurrent, resolver)
        val observationsBySpecies = allObservations
            .filter { resolver.key(it).isNotBlank() }
            .groupBy(resolver::key)

        val allKeys = historicalDaysBySpecies.keys + currentDaysBySpecies.keys
        val stats = allKeys.mapNotNull { key ->
            val speciesObservations = observationsBySpecies[key].orEmpty()
            val representative = speciesObservations.maxWithOrNull(
                compareBy<EbirdRecentObservation> { it.speciesCode.isNotBlank() }
                    .thenBy { it.observedAt }
            ) ?: return@mapNotNull null
            val historicalFrequency = frequency(
                historicalDaysBySpecies[key]?.size ?: 0,
                activeHistorical.size
            )
            val currentFrequency = frequency(
                currentDaysBySpecies[key]?.size ?: 0,
                activeCurrent.size
            )
            val combined = listOfNotNull(historicalFrequency, currentFrequency).averageOrZero()
            PlanSpeciesStat(
                planId = planId,
                speciesKey = key,
                speciesCode = representative.speciesCode,
                scientificName = representative.scientificName,
                commonName = representative.commonName,
                displayNameZh = displayNameZhByKey[key]?.takeIf { it.isNotBlank() },
                historicalFrequency = historicalFrequency,
                currentFrequency = currentFrequency,
                combinedFrequency = combined,
                historicalObservedDays = historicalDaysBySpecies[key]?.size ?: 0,
                currentObservedDays = currentDaysBySpecies[key]?.size ?: 0,
                lastSeenDate = speciesObservations.maxOfOrNull { it.observedAt }?.takeIf { it.isNotBlank() }
            )
        }.sortedWith(
            compareByDescending<PlanSpeciesStat> { it.combinedFrequency }
                .thenByDescending { it.currentFrequency ?: -1f }
                .thenByDescending { it.historicalFrequency ?: -1f }
                .thenBy { it.displayName.lowercase() }
        )

        return stats to PlanAnalysisCoverage(
            historicalRequestedDays = historical.size,
            historicalSuccessfulDays = successfulHistorical.size,
            historicalActiveDays = activeHistorical.size,
            currentRequestedDays = current.size,
            currentSuccessfulDays = successfulCurrent.size,
            currentActiveDays = activeCurrent.size
        )
    }

    fun aggregateRareObservations(
        planId: Long,
        observations: List<EbirdRecentObservation>,
        displayNameZhByKey: Map<String, String> = emptyMap()
    ): List<PlanRareObservation> {
        val resolver = ObservationIdentityResolver(observations)
        return observations
            .filter { resolver.key(it).isNotBlank() }
            .groupBy(resolver::key)
            .mapNotNull { (key, speciesObservations) ->
                val latest = speciesObservations.maxByOrNull { it.observedAt } ?: return@mapNotNull null
                val identity = speciesObservations.firstOrNull { it.speciesCode.isNotBlank() } ?: latest
                PlanRareObservation(
                    planId = planId,
                    speciesKey = key,
                    speciesCode = identity.speciesCode,
                    scientificName = identity.scientificName.ifBlank { latest.scientificName },
                    commonName = identity.commonName.ifBlank { latest.commonName },
                    displayNameZh = displayNameZhByKey[key]?.takeIf { it.isNotBlank() },
                    observedAt = latest.observedAt,
                    locationId = latest.locationId,
                    locationName = latest.locationName,
                    latitude = latest.latitude,
                    longitude = latest.longitude,
                    count = latest.count,
                    reportCount = speciesObservations.size,
                    provisional = speciesObservations.any { !it.observationReviewed || !it.observationValid },
                    sourceUrl = latest.sourceUrl
                )
            }
            .sortedWith(
                compareByDescending<PlanRareObservation> { it.observedAt }
                    .thenByDescending { it.reportCount }
            )
    }

    private fun speciesDays(
        days: List<EbirdObservationDayResult>,
        resolver: ObservationIdentityResolver
    ): Map<String, Set<LocalDate>> {
        val datesBySpecies = mutableMapOf<String, MutableSet<LocalDate>>()
        days.forEach { day ->
            day.observations.asSequence()
                .map(resolver::key)
                .filter { it.isNotBlank() }
                .distinct()
                .forEach { key -> datesBySpecies.getOrPut(key, ::mutableSetOf).add(day.date) }
        }
        return datesBySpecies
    }

    private fun frequency(observedDays: Int, activeDays: Int): Float? =
        if (activeDays <= 0) null else observedDays.toFloat() / activeDays.toFloat()

    private fun List<Float>.averageOrZero(): Float =
        if (isEmpty()) 0f else sum() / size.toFloat()

    private class ObservationIdentityResolver(observations: List<EbirdRecentObservation>) {
        private val codeByScientificName = observations.mapNotNull { observation ->
            val code = observation.speciesCode.normalized().takeIf(String::isNotBlank) ?: return@mapNotNull null
            observation.scientificName.normalized().takeIf(String::isNotBlank)?.let { it to code }
        }.toMap()
        private val codeByCommonName = observations.mapNotNull { observation ->
            val code = observation.speciesCode.normalized().takeIf(String::isNotBlank) ?: return@mapNotNull null
            observation.commonName.normalized().takeIf(String::isNotBlank)?.let { it to code }
        }.toMap()

        fun key(observation: EbirdRecentObservation): String {
            val ownCode = observation.speciesCode.normalized()
            if (ownCode.isNotBlank()) return ownCode
            val scientific = observation.scientificName.normalized()
            if (scientific.isNotBlank()) return codeByScientificName[scientific] ?: scientific
            val common = observation.commonName.normalized()
            return codeByCommonName[common] ?: common
        }

        private fun String.normalized(): String = trim().lowercase()
    }
}

object PlanExpectedSelectionPolicy {
    fun generatedKeysAfterRefresh(
        existingGeneratedKeys: Set<String>,
        rankedStats: List<PlanSpeciesStat>,
        rareObservations: List<PlanRareObservation>,
        isFirstAnalysis: Boolean
    ): Set<String> {
        val availableKeys = (rankedStats.map(PlanSpeciesStat::speciesKey) +
            rareObservations.map(PlanRareObservation::speciesKey)).toSet()
        return if (isFirstAnalysis) {
            rankedStats.map(PlanSpeciesStat::speciesKey).toSet()
        } else {
            existingGeneratedKeys intersect availableKeys
        }
    }
}
