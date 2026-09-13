package com.example.birdingsoundmvp.owlett

import com.example.birdingsoundmvp.birdnet.DetectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OwlettPlanAttachmentLogicTest {
    @Test
    fun `recognized species are deduplicated with highest confidence sources and trip count`() {
        val result = OwlettPlanAttachmentLogic.aggregateRecognizedSpecies(
            listOf(
                "trip-a" to listOf(
                    detection("Corvus macrorhynchos", "Large-billed Crow", 0.42f, null),
                    detection("Corvus macrorhynchos", "Large-billed Crow", 0.81f, "precise")
                ),
                "trip-b" to listOf(
                    detection("Corvus macrorhynchos", "Large-billed Crow", 0.66f, null),
                    detection("Parus minor", "Japanese Tit", 0.55f, null)
                )
            )
        )

        assertEquals(2, result.size)
        val crow = result.first()
        assertEquals("Corvus macrorhynchos", crow.scientificName)
        assertEquals(0.81f, crow.maxConfidence)
        assertEquals(2, crow.tripCount)
        assertTrue(crow.sources.containsAll(setOf("realtime", "precise")))
    }

    private fun detection(
        scientificName: String,
        commonName: String,
        confidence: Float,
        source: String?
    ) = DetectionResult(
        tripId = "unused",
        timestampMs = 1,
        audioStartSec = 0.0,
        audioEndSec = 3.0,
        scientificName = scientificName,
        commonName = commonName,
        displayNameZh = null,
        audioConfidence = confidence,
        metaConfidence = null,
        adjustedConfidence = confidence,
        metaAvailable = false,
        isInChecklist = true,
        locationFiltered = false,
        alertTriggered = false,
        inferenceTimeMs = 10,
        source = source
    )
}
