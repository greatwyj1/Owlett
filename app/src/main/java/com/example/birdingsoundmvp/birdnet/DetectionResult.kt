package com.example.birdingsoundmvp.birdnet

data class SpeciesPrediction(
    val labelIndex: Int,
    val scientificName: String,
    val commonName: String,
    val audioConfidence: Float,
    val inferenceTimeMs: Long
)

data class DetectionResult(
    val tripId: String,
    val timestampMs: Long,
    val audioStartSec: Double,
    val audioEndSec: Double,
    val scientificName: String,
    val commonName: String,
    val displayNameZh: String?,
    val audioConfidence: Float,
    val metaConfidence: Float?,
    val adjustedConfidence: Float,
    val metaAvailable: Boolean,
    val isInChecklist: Boolean,
    val locationFiltered: Boolean,
    val alertTriggered: Boolean,
    val inferenceTimeMs: Long,
    val source: String? = null
)

data class MergedDetectionCard(
    val current: DetectionResult,
    val previousSegments: List<DetectionResult> = emptyList(),
    val updatedAtMs: Long = current.timestampMs
) {
    val speciesKey: String
        get() = current.speciesKey()
}

fun DetectionResult.speciesKey(): String {
    return scientificName.ifBlank { commonName }.trim().lowercase()
}

fun DetectionResult.isPreciseSource(): Boolean {
    return source.equals("precise", ignoreCase = true)
}

fun mergeDetectionCards(detectionsNewestFirst: List<DetectionResult>): List<MergedDetectionCard> {
    val groups = linkedMapOf<String, MergedDetectionCard>()
    detectionsNewestFirst.asReversed().forEach { detection ->
        val key = detection.speciesKey()
        val existing = groups[key]
        groups[key] = if (existing == null) {
            MergedDetectionCard(current = detection, updatedAtMs = detection.timestampMs)
        } else if (existing.current.isContinuousWith(detection)) {
            existing.copy(
                current = existing.current.mergeWith(detection),
                updatedAtMs = maxOf(existing.updatedAtMs, detection.timestampMs)
            )
        } else {
            existing.copy(
                current = detection,
                previousSegments = listOf(existing.current) + existing.previousSegments,
                updatedAtMs = detection.timestampMs
            )
        }
    }
    return groups.values.sortedByDescending { it.updatedAtMs }
}

private fun DetectionResult.isContinuousWith(next: DetectionResult): Boolean {
    if (speciesKey() != next.speciesKey()) return false
    return next.audioStartSec <= audioEndSec + CONTINUOUS_DETECTION_GAP_SEC
}

private fun DetectionResult.mergeWith(next: DetectionResult): DetectionResult {
    return copy(
        timestampMs = maxOf(timestampMs, next.timestampMs),
        audioStartSec = minOf(audioStartSec, next.audioStartSec),
        audioEndSec = maxOf(audioEndSec, next.audioEndSec),
        audioConfidence = maxOf(audioConfidence, next.audioConfidence),
        metaConfidence = listOfNotNull(metaConfidence, next.metaConfidence).maxOrNull(),
        adjustedConfidence = maxOf(adjustedConfidence, next.adjustedConfidence),
        metaAvailable = metaAvailable || next.metaAvailable,
        isInChecklist = isInChecklist || next.isInChecklist,
        locationFiltered = locationFiltered && next.locationFiltered,
        alertTriggered = alertTriggered || next.alertTriggered,
        inferenceTimeMs = next.inferenceTimeMs,
        source = source ?: next.source
    )
}

private const val CONTINUOUS_DETECTION_GAP_SEC = 0.75
