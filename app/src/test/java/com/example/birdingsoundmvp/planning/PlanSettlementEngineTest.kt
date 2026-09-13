package com.example.birdingsoundmvp.planning

import com.example.birdingsoundmvp.birdnet.DetectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanSettlementEngineTest {
    @Test
    fun mergesTripsBySpeciesAndClassifiesHitMissedAndExtra() {
        val expected = listOf(
            expected("amecro", "Corvus brachyrhynchos", "American Crow"),
            expected("norcar", "Cardinalis cardinalis", "Northern Cardinal")
        )
        val trips = listOf(
            TripDetectionSet("trip-1", listOf(detection("Corvus brachyrhynchos", "American Crow", 0.62f, "realtime"))),
            TripDetectionSet(
                "trip-2",
                listOf(
                    detection("Corvus brachyrhynchos", "American Crow", 0.91f, "precise"),
                    detection("Turdus merula", "Common Blackbird", 0.78f, "realtime")
                )
            )
        )

        val result = PlanSettlementEngine.build(1L, expected, trips, emptyList())
        val crow = result.first { it.speciesKey == "amecro" }

        assertEquals(PlanSettlementCategory.HIT, crow.category)
        assertEquals(2, crow.tripCount)
        assertEquals(0.91f, crow.maxConfidence, 0.0001f)
        assertEquals(setOf("realtime", "precise"), crow.detectionSources)
        assertEquals(PlanSettlementCategory.MISSED, result.first { it.speciesKey == "norcar" }.category)
        assertEquals(PlanSettlementCategory.EXTRA, result.first { it.commonName == "Common Blackbird" }.category)
    }

    @Test
    fun manualScientificNameMatchesExpectedSpeciesCodeIdentity() {
        val expected = listOf(expected("amecro", "Corvus brachyrhynchos", "American Crow"))
        val manual = override(
            key = "corvus brachyrhynchos",
            scientific = "Corvus brachyrhynchos",
            common = "American Crow",
            action = PlanSettlementOverrideAction.MANUAL_ADD
        )

        val result = PlanSettlementEngine.build(1L, expected, emptyList(), listOf(manual))

        assertEquals(1, result.size)
        assertEquals("amecro", result.single().speciesKey)
        assertEquals(PlanSettlementCategory.HIT, result.single().category)
        assertEquals("manual", result.single().origin)
    }

    @Test
    fun exclusionRemovesRecognitionWithoutChangingExpectedList() {
        val expected = listOf(expected("amecro", "Corvus brachyrhynchos", "American Crow"))
        val detection = TripDetectionSet(
            "trip-1",
            listOf(detection("Corvus brachyrhynchos", "American Crow", 0.9f, "precise"))
        )
        val exclude = override(
            key = "amecro",
            scientific = "Corvus brachyrhynchos",
            common = "American Crow",
            action = PlanSettlementOverrideAction.EXCLUDE
        )

        val excluded = PlanSettlementEngine.build(1L, expected, listOf(detection), listOf(exclude))
        val reset = PlanSettlementEngine.build(1L, expected, listOf(detection), emptyList())

        assertEquals(PlanSettlementCategory.MISSED, excluded.single().category)
        assertFalse(excluded.single().origin.contains("app"))
        assertEquals(PlanSettlementCategory.HIT, reset.single().category)
        assertTrue(reset.single().origin.contains("app"))
    }

    @Test
    fun legacyScientificKeyAndEbirdSpeciesCodeAreOneExpectedSpecies() {
        val expected = listOf(
            expected("amecro", "Corvus brachyrhynchos", "American Crow"),
            PlanExpectedSpecies(
                1L,
                "corvus brachyrhynchos",
                "",
                "Corvus brachyrhynchos",
                "American Crow",
                "美洲鸦",
                "legacy_manual",
                0L
            )
        )

        val result = PlanSettlementEngine.build(1L, expected, emptyList(), emptyList())

        assertEquals(1, result.size)
        assertEquals("amecro", result.single().speciesKey)
        assertEquals(PlanSettlementCategory.MISSED, result.single().category)
    }

    private fun expected(code: String, scientific: String, common: String) = PlanExpectedSpecies(
        1L,
        code,
        code,
        scientific,
        common,
        null,
        "analysis",
        0L
    )

    private fun override(
        key: String,
        scientific: String,
        common: String,
        action: PlanSettlementOverrideAction
    ) = PlanSettlementOverride(1L, key, "", scientific, common, null, action, 0L)

    private fun detection(
        scientific: String,
        common: String,
        confidence: Float,
        source: String
    ) = DetectionResult(
        tripId = "",
        timestampMs = 0L,
        audioStartSec = 0.0,
        audioEndSec = 3.0,
        scientificName = scientific,
        commonName = common,
        displayNameZh = null,
        audioConfidence = confidence,
        metaConfidence = null,
        adjustedConfidence = confidence,
        metaAvailable = false,
        isInChecklist = false,
        locationFiltered = false,
        alertTriggered = false,
        inferenceTimeMs = 0L,
        source = source
    )
}
