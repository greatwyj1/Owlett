package com.example.birdingsoundmvp.planning

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanAnalysisLogicTest {
    @Test
    fun normalPastMonthIncludesTwoHistoricalYearsAndFullTargetYearMonth() {
        val dates = PlanAnalysisDatePlanner.datesFor(
            plannedDate = LocalDate.of(2025, 3, 12),
            today = LocalDate.of(2025, 6, 10)
        )

        assertEquals(62, dates.historicalDates.size)
        assertEquals(LocalDate.of(2023, 3, 1), dates.historicalDates.first())
        assertEquals(LocalDate.of(2024, 3, 31), dates.historicalDates.last())
        assertEquals(31, dates.currentDates.size)
    }

    @Test
    fun leapYearTargetFebruaryIncludesTwentyNineCurrentDates() {
        val dates = PlanAnalysisDatePlanner.datesFor(
            plannedDate = LocalDate.of(2024, 2, 10),
            today = LocalDate.of(2024, 4, 1)
        )

        assertEquals(56, dates.historicalDates.size)
        assertEquals(29, dates.currentDates.size)
        assertEquals(LocalDate.of(2024, 2, 29), dates.currentDates.last())
    }

    @Test
    fun currentMonthStopsAtYesterdayAndFutureMonthHasNoCurrentData() {
        val currentMonth = PlanAnalysisDatePlanner.datesFor(
            plannedDate = LocalDate.of(2026, 9, 20),
            today = LocalDate.of(2026, 9, 2)
        )
        val futureMonth = PlanAnalysisDatePlanner.datesFor(
            plannedDate = LocalDate.of(2026, 10, 20),
            today = LocalDate.of(2026, 9, 2)
        )

        assertEquals(listOf(LocalDate.of(2026, 9, 1)), currentMonth.currentDates)
        assertTrue(futureMonth.currentDates.isEmpty())
    }

    @Test
    fun reportingDayFrequencyDeduplicatesSpeciesAndExcludesEmptyAndFailedDays() {
        val historical = listOf(
            successDay(2024, 5, 1, observation("amecro"), observation("amecro"), observation("norcar")),
            successDay(2024, 5, 2, observation("amecro")),
            successDay(2024, 5, 3),
            EbirdObservationDayResult(LocalDate.of(2024, 5, 4), true, false, error = "network")
        )
        val current = listOf(successDay(2026, 5, 1, observation("norcar"), historical = false))

        val (stats, coverage) = PlanStatisticsCalculator.calculate(7L, historical, current)
        val crow = stats.first { it.speciesCode == "amecro" }
        val cardinal = stats.first { it.speciesCode == "norcar" }

        assertEquals(1f, crow.historicalFrequency ?: -1f, 0.0001f)
        assertEquals(0f, crow.currentFrequency ?: -1f, 0.0001f)
        assertEquals(0.5f, crow.combinedFrequency, 0.0001f)
        assertEquals(0.5f, cardinal.historicalFrequency ?: -1f, 0.0001f)
        assertEquals(1f, cardinal.currentFrequency ?: -1f, 0.0001f)
        assertEquals(0.75f, cardinal.combinedFrequency, 0.0001f)
        assertEquals(4, coverage.historicalRequestedDays)
        assertEquals(3, coverage.historicalSuccessfulDays)
        assertEquals(2, coverage.historicalActiveDays)
        assertEquals(1, coverage.currentActiveDays)
    }

    @Test
    fun initialExpectedSelectionUsesAllPossibleSpeciesAndRefreshPreservesOnlyExistingAvailableChoices() {
        val stats = (1..25).map { index -> stat("species-$index", 1f - index / 100f) }
        val first = PlanExpectedSelectionPolicy.generatedKeysAfterRefresh(
            existingGeneratedKeys = emptySet(),
            rankedStats = stats,
            rareObservations = emptyList(),
            isFirstAnalysis = true
        )
        val refreshed = PlanExpectedSelectionPolicy.generatedKeysAfterRefresh(
            existingGeneratedKeys = setOf("species-2", "species-22", "gone"),
            rankedStats = stats + stat("new", 0.99f),
            rareObservations = emptyList(),
            isFirstAnalysis = false
        )

        assertEquals(25, first.size)
        assertTrue("species-1" in first)
        assertTrue("species-21" in first)
        assertEquals(setOf("species-2", "species-22"), refreshed)
        assertFalse("new" in refreshed)
    }

    @Test
    fun notableAggregationUsesLatestReportAndTracksProvisionalReports() {
        val older = observation("amecro", observedAt = "2026-08-20 08:00", reviewed = true)
        val latest = observation("amecro", observedAt = "2026-08-31 12:00", reviewed = false)

        val result = PlanStatisticsCalculator.aggregateRareObservations(3L, listOf(older, latest))

        assertEquals(1, result.size)
        assertEquals("2026-08-31 12:00", result.single().observedAt)
        assertEquals(2, result.single().reportCount)
        assertTrue(result.single().provisional)
    }

    @Test
    fun speciesCodeAndScientificNameFallbackMergeIntoOneSpecies() {
        val withCode = observation("amecro")
        val withoutCode = withCode.copy(speciesCode = "", observedAt = "2026-05-02 08:00")
        val historical = listOf(
            EbirdObservationDayResult(LocalDate.of(2024, 5, 1), true, true, listOf(withCode)),
            EbirdObservationDayResult(LocalDate.of(2024, 5, 2), true, true, listOf(withoutCode))
        )

        val (stats, _) = PlanStatisticsCalculator.calculate(1L, historical, emptyList())

        assertEquals(1, stats.size)
        assertEquals("amecro", stats.single().speciesKey)
        assertEquals(2, stats.single().historicalObservedDays)
    }

    private fun successDay(
        year: Int,
        month: Int,
        day: Int,
        vararg observations: EbirdRecentObservation,
        historical: Boolean = true
    ) = EbirdObservationDayResult(
        LocalDate.of(year, month, day),
        historical,
        true,
        observations.toList()
    )

    private fun observation(
        code: String,
        observedAt: String = "2026-05-01 08:00",
        reviewed: Boolean = true
    ) = EbirdRecentObservation(
        speciesCode = code,
        scientificName = if (code == "amecro") "Corvus brachyrhynchos" else "Cardinalis cardinalis",
        commonName = if (code == "amecro") "American Crow" else "Northern Cardinal",
        locationId = "L1",
        locationName = "Test hotspot",
        observedAt = observedAt,
        count = 1,
        observationReviewed = reviewed
    )

    private fun stat(key: String, frequency: Float) = PlanSpeciesStat(
        planId = 1L,
        speciesKey = key,
        speciesCode = key,
        scientificName = key,
        commonName = key,
        displayNameZh = null,
        historicalFrequency = frequency,
        currentFrequency = frequency,
        combinedFrequency = frequency,
        historicalObservedDays = 1,
        currentObservedDays = 1,
        lastSeenDate = null
    )
}
