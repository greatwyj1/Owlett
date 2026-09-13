package com.example.birdingsoundmvp.owlett

import com.example.birdingsoundmvp.birdnet.DetectionResult
import com.example.birdingsoundmvp.trip.TripAudioSegment
import com.example.birdingsoundmvp.trip.TripHistoryItem
import java.time.*

data class OwlettTripPickerItem(val tripId: String, val label: String, val startedAtMs: Long, val durationMs: Long, val detectionCount: Int)
data class OwlettAudioClip(val tripId: String, val segmentIndex: Int, val startMs: Long, val endMs: Long, val confidence: Float, val sources: List<String>) {
    val id: String get() = "$tripId:$segmentIndex:$startMs:$endMs"
}
data class OwlettClipPlayback(val clipId: String? = null, val isPlaying: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0, val continuous: Boolean = false)

object OwlettTripClips {
    private val TripAudioSegment.tripAudioEndMs: Long get() = tripAudioStartMs + durationMs
    fun threshold(value: String?): Float? {
        if (value.isNullOrBlank()) return null
        val text = value.trim()
        val number = text.removeSuffix("%").toFloatOrNull() ?: error("请用 0～1 的小数或百分比表示置信度，例如 0.3 或 30%。")
        val score = if (text.endsWith("%")) number / 100f else number
        require(score.isFinite() && score in 0f..1f) { "置信度应为 0～1，例如 0.3（30%）；请确认筛选阈值。" }
        return score
    }

    fun matchesDate(trip: TripHistoryItem, date: String?, period: String?, now: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (date.isNullOrBlank()) return true
        val normalized = date.replace('.', '-').replace('/', '-')
        val parts = normalized.split('-')
        val day = when (parts.size) {
            2 -> LocalDate.of(now.year, parts[0].toInt(), parts[1].toInt())
            3 -> LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
            else -> error("日期需要明确到月和日。")
        }
        val hours = when (period) { "morning", "上午" -> 6 to 12; "afternoon", "下午" -> 12 to 18; "evening", "晚上" -> 18 to 24; else -> 0 to 24 }
        val start = day.atStartOfDay(zone).plusHours(hours.first.toLong()).toInstant().toEpochMilli()
        val end = day.atStartOfDay(zone).plusHours(hours.second.toLong()).toInstant().toEpochMilli()
        return trip.startedAtMs < end && (trip.endedAtMs ?: trip.startedAtMs + trip.durationMs) > start
    }

    fun collect(tripId: String, species: String, detections: List<DetectionResult>, segments: List<TripAudioSegment>, minimum: Float?, strict: Boolean): List<OwlettAudioClip> {
        val qualifying = detections.filter { it.scientificName.equals(species, true) && it.audioConfidence.isFinite() &&
            (minimum == null || if (strict) it.audioConfidence > minimum else it.audioConfidence >= minimum) }
        return segments.flatMap { segment ->
            val windows = qualifying.mapNotNull { detection ->
                val start = maxOf((detection.audioStartSec * 1000).toLong(), segment.tripAudioStartMs)
                val end = minOf((detection.audioEndSec * 1000).toLong(), segment.tripAudioEndMs)
                if (end <= start) null else OwlettAudioClip(tripId, segment.index, start, end, detection.audioConfidence,
                    listOf(if (detection.source.equals("precise", true)) "精准识别" else "实时识别"))
            }.sortedBy { it.startMs }
            val merged = mutableListOf<OwlettAudioClip>()
            windows.forEach { clip ->
                val previous = merged.lastOrNull()
                if (previous != null && clip.startMs <= previous.endMs) merged[merged.lastIndex] = previous.copy(
                    endMs = maxOf(previous.endMs, clip.endMs), confidence = maxOf(previous.confidence, clip.confidence), sources = (previous.sources + clip.sources).distinct())
                else merged += clip
            }
            merged.map { it.copy(startMs = maxOf(segment.tripAudioStartMs, it.startMs - 500), endMs = minOf(segment.tripAudioEndMs, it.endMs + 500)) }
        }.sortedBy { it.startMs }
    }
}
