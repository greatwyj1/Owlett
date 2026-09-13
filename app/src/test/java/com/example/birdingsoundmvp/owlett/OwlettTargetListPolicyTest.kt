package com.example.birdingsoundmvp.owlett

import com.example.birdingsoundmvp.planning.PlanExpectedSpecies
import com.example.birdingsoundmvp.planning.PlanRareObservation
import com.example.birdingsoundmvp.planning.PlanSpeciesStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OwlettTargetListPolicyTest {
    @Test
    fun `frequency filter reports manual removals and can preserve them`() {
        val existing = listOf(
            expected("common", "Common Bird", "analysis"),
            expected("manual", "Hand-added Bird", "manual")
        )
        val stats = listOf(
            stat("common", "Common Bird", 0.8f),
            stat("scarce", "Scarce Bird", 0.12f)
        )

        val removingManual = OwlettTargetListPolicy.calculate(
            planId = 1,
            mode = "max_combined_frequency",
            names = emptyList(),
            threshold = 0.2f,
            preserveManual = false,
            existing = existing,
            stats = stats,
            rare = emptyList(),
            now = 100
        )
        val preservingManual = OwlettTargetListPolicy.calculate(
            planId = 1,
            mode = "max_combined_frequency",
            names = emptyList(),
            threshold = 0.2f,
            preserveManual = true,
            existing = existing,
            stats = stats,
            rare = emptyList(),
            now = 100
        )

        assertEquals(setOf("scarce"), removingManual.desired.map { it.speciesKey }.toSet())
        assertEquals(listOf("manual"), removingManual.manuallyRemoved.map { it.speciesKey })
        assertEquals(setOf("scarce", "manual"), preservingManual.desired.map { it.speciesKey }.toSet())
    }

    @Test
    fun `recent rare replacement and local manual additions are deterministic`() {
        val rare = listOf(rare("rare", "Rare Bird"))
        val rareOnly = OwlettTargetListPolicy.calculate(
            planId = 1,
            mode = "keep_recent_rare",
            names = emptyList(),
            threshold = null,
            preserveManual = false,
            existing = listOf(expected("common", "Common Bird", "analysis")),
            stats = emptyList(),
            rare = rare,
            now = 200
        )
        assertEquals(listOf("rare"), rareOnly.desired.map { it.speciesKey })

        val local = expected("local", "Local Bird", "manual_agent")
        val added = OwlettTargetListPolicy.calculate(
            planId = 1,
            mode = "add",
            names = listOf("Local Bird"),
            threshold = null,
            preserveManual = false,
            existing = emptyList(),
            stats = emptyList(),
            rare = emptyList(),
            localMatches = listOf(local),
            now = 200
        )
        assertEquals(listOf("local"), added.added.map { it.speciesKey })
        assertTrue(added.removed.isEmpty())
    }

    @Test
    fun `preview version changes when analysis or expected list changes`() {
        val original = listOf(expected("a", "A", "manual", selectedAt = 1))
        val changed = listOf(expected("a", "A", "manual", selectedAt = 2))

        assertNotEquals(
            OwlettTargetListPolicy.versionToken(10, original),
            OwlettTargetListPolicy.versionToken(11, original)
        )
        assertNotEquals(
            OwlettTargetListPolicy.versionToken(10, original),
            OwlettTargetListPolicy.versionToken(10, changed)
        )
    }

    private fun expected(
        key: String,
        name: String,
        source: String,
        selectedAt: Long = 1
    ) = PlanExpectedSpecies(
        planId = 1,
        speciesKey = key,
        speciesCode = "",
        scientificName = key,
        commonName = name,
        displayNameZh = null,
        source = source,
        selectedAtMs = selectedAt
    )

    private fun stat(key: String, name: String, frequency: Float) = PlanSpeciesStat(
        planId = 1,
        speciesKey = key,
        speciesCode = "",
        scientificName = key,
        commonName = name,
        displayNameZh = null,
        historicalFrequency = frequency,
        currentFrequency = frequency,
        combinedFrequency = frequency,
        historicalObservedDays = 1,
        currentObservedDays = 1,
        lastSeenDate = null
    )

    private fun rare(key: String, name: String) = PlanRareObservation(
        planId = 1,
        speciesKey = key,
        speciesCode = "",
        scientificName = key,
        commonName = name,
        displayNameZh = null,
        observedAt = "2026-09-01",
        locationId = "L1",
        locationName = "Hotspot",
        latitude = null,
        longitude = null,
        count = 1,
        reportCount = 1,
        provisional = false
    )
}
