package com.example.birdingsoundmvp.share

import com.example.birdingsoundmvp.i18n.AppText

import com.example.birdingsoundmvp.birdnet.MergedDetectionCard
import com.example.birdingsoundmvp.precise.PreciseRecognitionDetection
import com.example.birdingsoundmvp.ui.PreciseRecognitionUiState

data class ShareAudioSegment(
    val index: Int,
    val filePath: String,
    val tripAudioStartMs: Long,
    val durationMs: Long
) {
    val tripAudioEndMs: Long
        get() = tripAudioStartMs + durationMs
}

enum class ShareDetectionSource {
    PRECISE,
    REALTIME,
    EMPTY
}

enum class ShareExportType(
    val label: String,
    val mimeType: String,
    val extension: String
) {
    AUDIO(AppText.get("纯音频"), "audio/wav", "wav"),
    VIDEO(AppText.get("视频"), "video/mp4", "mp4")
}

data class ShareDetectionLine(
    val source: ShareDetectionSource,
    val displayName: String,
    val modelName: String = "",
    val confidence: Float? = null,
    val startMs: Long? = null,
    val endMs: Long? = null
) {
    val hasConfidence: Boolean
        get() = confidence != null
}

object ShareContentPlanner {
    const val NO_RESULT_TEXT = "无识别结果"

    fun validateSingleSegmentRange(
        selectionStartMs: Long,
        selectionEndMs: Long,
        segments: List<ShareAudioSegment>
    ): Result<ShareAudioSegment> = runCatching {
        require(selectionEndMs > selectionStartMs) { AppText.get("selection is empty") }
        segments.firstOrNull { segment ->
            selectionStartMs >= segment.tripAudioStartMs && selectionEndMs <= segment.tripAudioEndMs
        } ?: throw IllegalArgumentException(AppText.get("selection must be inside a single audio segment"))
    }

    fun resultLinesForSelection(
        selectionStartMs: Long,
        selectionEndMs: Long,
        preciseStates: List<PreciseRecognitionUiState>,
        realtimeCards: List<MergedDetectionCard>
    ): List<ShareDetectionLine> {
        val preciseLines = preciseStates
            .filter { it.selectionStartMs == selectionStartMs && it.selectionEndMs == selectionEndMs }
            .flatMap { state ->
                val result = state.result ?: return@flatMap emptyList()
                val modelName = preciseModelName(result.model, result.analysisParams?.acousticModel)
                result.detections.map { modelName to it }
            }
            .sortedByDescending { it.second.confidence }
            .take(5)
            .map { (modelName, detection) -> detection.toShareLine(modelName) }

        val realtimeLines = realtimeCards
            .flatMap { listOf(it.current) + it.previousSegments }
            .filter {
                val startMs = (it.audioStartSec * 1000.0).toLong()
                val endMs = (it.audioEndSec * 1000.0).toLong()
                startMs < selectionEndMs && endMs > selectionStartMs
            }
            .sortedWith(compareByDescending<com.example.birdingsoundmvp.birdnet.DetectionResult> { it.audioConfidence }.thenBy { it.audioStartSec })
            .take(5)
            .map {
                ShareDetectionLine(
                    source = ShareDetectionSource.REALTIME,
                    displayName = displayName(it.displayNameZh, it.commonName, it.scientificName),
                    modelName = it.source?.takeIf { source -> source.isNotBlank() } ?: AppText.get("BirdNET local"),
                    confidence = it.audioConfidence,
                    startMs = (it.audioStartSec * 1000.0).toLong(),
                    endMs = (it.audioEndSec * 1000.0).toLong()
                )
            }

        val lines = preciseLines + realtimeLines
        return lines.ifEmpty {
            listOf(ShareDetectionLine(source = ShareDetectionSource.EMPTY, displayName = NO_RESULT_TEXT))
        }
    }

    private fun PreciseRecognitionDetection.toShareLine(modelName: String): ShareDetectionLine {
        return ShareDetectionLine(
            source = ShareDetectionSource.PRECISE,
            displayName = displayName(displayNameZh, commonName, scientificName),
            modelName = modelName,
            confidence = confidence,
            startMs = tripAudioStartMs,
            endMs = tripAudioEndMs
        )
    }

    private fun preciseModelName(model: String, acousticModel: String?): String {
        return model.takeIf { it.isNotBlank() }
            ?: acousticModel?.takeIf { it.isNotBlank() }
            ?: AppText.get("Precise")
    }

    private fun displayName(displayNameZh: String?, commonName: String, scientificName: String): String {
        return displayNameZh?.takeIf { it.isNotBlank() }
            ?: commonName.takeIf { it.isNotBlank() }
            ?: scientificName
    }
}
