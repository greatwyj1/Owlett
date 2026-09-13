package com.example.birdingsoundmvp.share

import com.example.birdingsoundmvp.birdnet.DetectionResult
import com.example.birdingsoundmvp.birdnet.MergedDetectionCard
import com.example.birdingsoundmvp.precise.PreciseRecognitionDetection
import com.example.birdingsoundmvp.precise.PreciseRecognitionResult
import com.example.birdingsoundmvp.ui.PreciseRecognitionUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareContentPlannerTest {
    @Test
    fun validateShareRangeRejectsRangesAcrossSegments() {
        val result = ShareContentPlanner.validateSingleSegmentRange(
            selectionStartMs = 900L,
            selectionEndMs = 1_100L,
            segments = listOf(
                shareSegment(index = 0, startMs = 0L, durationMs = 1_000L),
                shareSegment(index = 1, startMs = 1_000L, durationMs = 1_000L)
            )
        )

        assertTrue(result.isFailure)
        assertEquals("选区必须位于同一段录音内", result.exceptionOrNull()?.message)
    }

    @Test
    fun validateShareRangeAcceptsRangeInsideOneSegment() {
        val result = ShareContentPlanner.validateSingleSegmentRange(
            selectionStartMs = 100L,
            selectionEndMs = 900L,
            segments = listOf(shareSegment(index = 0, startMs = 0L, durationMs = 1_000L))
        )

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow().index)
    }

    @Test
    fun resultLinesUsePreciseResultsOnlyWhenSelectionMatches() {
        val matchingPrecise = preciseState(
            selectionStartMs = 1_000L,
            selectionEndMs = 2_000L,
            detection = preciseDetection("Cyanopica cyanus", "Azure-winged Magpie", "灰喜鹊", 0.91f)
        )
        val otherPrecise = preciseState(
            selectionStartMs = 3_000L,
            selectionEndMs = 4_000L,
            detection = preciseDetection("Passer montanus", "Eurasian Tree Sparrow", "树麻雀", 0.99f)
        )

        val lines = ShareContentPlanner.resultLinesForSelection(
            selectionStartMs = 1_000L,
            selectionEndMs = 2_000L,
            preciseStates = listOf(otherPrecise, matchingPrecise),
            realtimeCards = emptyList()
        )

        assertEquals(1, lines.size)
        assertEquals(ShareDetectionSource.PRECISE, lines.first().source)
        assertEquals("灰喜鹊", lines.first().displayName)
        assertEquals("birdnet_v2_4", lines.first().modelName)
        assertEquals(0.91f, lines.first().confidence)
    }

    @Test
    fun resultLinesIncludeRealtimeDetectionsThatOverlapSelection() {
        val overlapping = realtimeDetection(
            scientificName = "Garrulus glandarius",
            commonName = "Eurasian Jay",
            displayNameZh = "松鸦",
            confidence = 0.73f,
            startSec = 1.5,
            endSec = 2.5
        )
        val outside = realtimeDetection(
            scientificName = "Turdus merula",
            commonName = "Common Blackbird",
            displayNameZh = "乌鸫",
            confidence = 0.95f,
            startSec = 4.0,
            endSec = 5.0
        )

        val lines = ShareContentPlanner.resultLinesForSelection(
            selectionStartMs = 1_000L,
            selectionEndMs = 3_000L,
            preciseStates = emptyList(),
            realtimeCards = listOf(MergedDetectionCard(overlapping), MergedDetectionCard(outside))
        )

        assertEquals(1, lines.size)
        assertEquals(ShareDetectionSource.REALTIME, lines.first().source)
        assertEquals("松鸦", lines.first().displayName)
    }

    @Test
    fun resultLinesReturnNoResultTextWhenNothingMatches() {
        val lines = ShareContentPlanner.resultLinesForSelection(
            selectionStartMs = 1_000L,
            selectionEndMs = 2_000L,
            preciseStates = emptyList(),
            realtimeCards = emptyList()
        )

        assertEquals(1, lines.size)
        assertEquals(ShareDetectionSource.EMPTY, lines.first().source)
        assertEquals("无识别结果", lines.first().displayName)
        assertFalse(lines.first().hasConfidence)
    }

    private fun shareSegment(index: Int, startMs: Long, durationMs: Long): ShareAudioSegment {
        return ShareAudioSegment(
            index = index,
            filePath = "segment_$index.wav",
            tripAudioStartMs = startMs,
            durationMs = durationMs
        )
    }

    private fun preciseState(
        selectionStartMs: Long,
        selectionEndMs: Long,
        detection: PreciseRecognitionDetection
    ): PreciseRecognitionUiState {
        return PreciseRecognitionUiState(
            result = PreciseRecognitionResult(
                requestId = "request",
                durationSec = 1.0,
                model = "birdnet_v2_4",
                analysisParams = null,
                detections = listOf(detection),
                summary = emptyList(),
                warnings = emptyList()
            ),
            selectionStartMs = selectionStartMs,
            selectionEndMs = selectionEndMs
        )
    }

    private fun preciseDetection(
        scientificName: String,
        commonName: String,
        displayNameZh: String,
        confidence: Float
    ): PreciseRecognitionDetection {
        return PreciseRecognitionDetection(
            scientificName = scientificName,
            commonName = commonName,
            displayNameZh = displayNameZh,
            confidence = confidence,
            clipStartSec = 0.0,
            clipEndSec = 1.0,
            tripAudioStartMs = 1_000L,
            tripAudioEndMs = 2_000L
        )
    }

    private fun realtimeDetection(
        scientificName: String,
        commonName: String,
        displayNameZh: String,
        confidence: Float,
        startSec: Double,
        endSec: Double
    ): DetectionResult {
        return DetectionResult(
            tripId = "trip",
            timestampMs = 0L,
            audioStartSec = startSec,
            audioEndSec = endSec,
            scientificName = scientificName,
            commonName = commonName,
            displayNameZh = displayNameZh,
            audioConfidence = confidence,
            metaConfidence = null,
            adjustedConfidence = confidence,
            metaAvailable = false,
            isInChecklist = false,
            locationFiltered = false,
            alertTriggered = false,
            inferenceTimeMs = 0L
        )
    }
}
