package com.example.birdingsoundmvp.trip

import com.example.birdingsoundmvp.i18n.AppText

import android.content.Context
import com.example.birdingsoundmvp.audio.AudioChunker
import com.example.birdingsoundmvp.audio.SpectrogramColumn
import com.example.birdingsoundmvp.audio.SpectrogramComputer
import com.example.birdingsoundmvp.birdnet.DetectionResult
import com.google.gson.Gson
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class TripHistoryItem(
    val tripId: String,
    val path: String,
    val lastModifiedMs: Long,
    val audioBytes: Long,
    val detectionsBytes: Long,
    val hasSummary: Boolean,
    val startedAtMs: Long = lastModifiedMs,
    val endedAtMs: Long? = null,
    val durationMs: Long = 0L,
    val detectionCount: Int = 0,
    val uniqueSpeciesCount: Int = 0,
    val isCompleted: Boolean = hasSummary
)

data class TripReviewData(
    val session: TripSession,
    val detections: List<DetectionResult>,
    val spectrogramColumns: List<SpectrogramColumn>
)

class TripHistoryRepository(private val context: Context) {
    private val gson = Gson()

    private val tripsRoot: File
        get() = File(context.getExternalFilesDir(null), "trips")

    fun listTrips(): List<TripHistoryItem> {
        return tripsRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.map { dir ->
                val audio = File(dir, "audio.wav")
                val segmentsDir = File(dir, "segments")
                val detections = File(dir, "detections.jsonl")
                val summary = File(dir, "summary.json")
                val metadata = runCatching {
                    gson.fromJson(File(dir, "metadata.json").readText(), TripMetadata::class.java)
                }.getOrNull()
                val summaryData = runCatching {
                    gson.fromJson(summary.readText(), TripSummary::class.java)
                }.getOrNull()
                val savedDetections = loadDetectionsFile(detections)
                val segmentBytes = segmentsDir.listFiles()
                    ?.filter { it.isFile && it.extension.equals("wav", ignoreCase = true) }
                    ?.sumOf { it.length() }
                    ?: 0L
                TripHistoryItem(
                    tripId = dir.name,
                    path = dir.absolutePath,
                    lastModifiedMs = dir.lastModified(),
                    audioBytes = (audio.takeIf { it.exists() }?.length() ?: 0L) + segmentBytes,
                    detectionsBytes = detections.takeIf { it.exists() }?.length() ?: 0L,
                    hasSummary = summary.exists(),
                    startedAtMs = metadata?.startedAtMs ?: summaryData?.startedAtMs ?: dir.lastModified(),
                    endedAtMs = metadata?.endedAtMs ?: summaryData?.endedAtMs,
                    durationMs = metadata?.tripAudioDurationMs
                        ?: summaryData?.durationSec?.times(1000.0)?.toLong()
                        ?: 0L,
                    detectionCount = savedDetections.size.takeIf { it > 0 } ?: summaryData?.detectionCount ?: 0,
                    uniqueSpeciesCount = savedDetections
                        .map { it.scientificName.ifBlank { it.commonName }.trim().lowercase() }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .size
                        .takeIf { it > 0 }
                        ?: summaryData?.uniqueSpeciesCount
                        ?: 0,
                    isCompleted = metadata?.endedAtMs != null || summaryData != null
                )
            }
            ?.sortedByDescending { it.lastModifiedMs }
            .orEmpty()
    }

    fun storageBytes(): Long = tripsRoot.walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }

    fun deleteTrip(tripId: String): Boolean {
        val dir = File(tripsRoot, tripId)
        return dir.exists() && dir.deleteRecursively()
    }

    fun deleteTripAudio(tripId: String): Boolean {
        val dir = File(tripsRoot, tripId)
        val legacyAudio = File(dir, "audio.wav")
        val legacyDeleted = !legacyAudio.exists() || legacyAudio.delete()
        val segmentsDir = File(dir, "segments")
        val segmentsDeleted = !segmentsDir.exists() || segmentsDir.deleteRecursively()
        return legacyDeleted && segmentsDeleted
    }

    fun deleteAudioOlderThan(days: Int): Int {
        val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
        return listTrips().count { item ->
            if (item.lastModifiedMs >= cutoff) {
                false
            } else {
                deleteTripAudio(item.tripId)
            }
        }
    }

    suspend fun loadReviewData(
        tripId: String,
        onProgress: suspend (Float, String) -> Unit = { _, _ -> }
    ): TripReviewData? {
        onProgress(0.02f, AppText.get("Loading trip"))
        val session = TripSession.load(File(tripsRoot, tripId)) ?: return null
        currentCoroutineContext().ensureActive()
        onProgress(0.08f, AppText.get("Loading detections"))
        val detections = loadDetectionsFile(session.detectionsFile)
        currentCoroutineContext().ensureActive()
        val segmentCount = session.segments.size.coerceAtLeast(1)
        val columns = session.segments.flatMapIndexed { index, segment ->
            val segmentBase = 0.12f + (index / segmentCount.toFloat()) * 0.86f
            val segmentSpan = 0.86f / segmentCount.toFloat()
            buildSpectrogramColumns(File(segment.filePath), segment.tripAudioStartMs) { progress ->
                onProgress(
                    (segmentBase + segmentSpan * progress).coerceIn(0f, 0.98f),
                    AppText.format("Building spectrogram {0}/{1}", index + 1, session.segments.size)
                )
            }
        }
        onProgress(1f, AppText.get("Ready"))
        return TripReviewData(
            session = session,
            detections = detections,
            spectrogramColumns = columns
        )
    }

    fun appendDetections(tripId: String, detections: List<DetectionResult>) {
        if (detections.isEmpty()) return
        val session = TripSession.load(File(tripsRoot, tripId)) ?: return
        session.detectionsFile.appendText(
            detections.joinToString(separator = "\n", postfix = "\n") { gson.toJson(it) }
        )
    }

    fun loadDetections(tripId: String): List<DetectionResult> {
        val tripDir = File(tripsRoot, tripId)
        if (!tripDir.isDirectory) return emptyList()
        return loadDetectionsFile(File(tripDir, "detections.jsonl"))
    }

    private fun loadDetectionsFile(file: File): List<DetectionResult> {
        if (!file.exists()) return emptyList()
        return file.readLines()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank()) {
                    null
                } else {
                    runCatching { gson.fromJson(trimmed, DetectionResult::class.java) }.getOrNull()
                }
            }
    }

    private suspend fun buildSpectrogramColumns(
        file: File,
        segmentStartMs: Long,
        onProgress: suspend (Float) -> Unit
    ): List<SpectrogramColumn> {
        if (!file.exists() || file.length() <= WAV_HEADER_BYTES) return emptyList()
        val computer = SpectrogramComputer()
        val columns = mutableListOf<SpectrogramColumn>()
        var emittedColumnCount = 0L
        val totalAudioBytes = (file.length() - WAV_HEADER_BYTES).coerceAtLeast(1L)
        var processedBytes = 0L
        var lastProgressReportBytes = 0L
        file.inputStream().buffered().use { input ->
            var remainingHeader = WAV_HEADER_BYTES
            while (remainingHeader > 0) {
                val skipped = input.skip(remainingHeader.toLong()).toInt()
                if (skipped <= 0) return@use
                remainingHeader -= skipped
            }
            val bytes = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(bytes)
                if (read <= 1) break
                val usable = read - (read % 2)
                processedBytes += usable
                val buffer = ByteBuffer.wrap(bytes, 0, usable).order(ByteOrder.LITTLE_ENDIAN)
                val samples = ShortArray(usable / 2)
                for (index in samples.indices) {
                    samples[index] = buffer.short
                }
                computer.appendPcm(samples).forEach { values ->
                    val columnTimeMs = segmentStartMs + samplesToMs(
                        emittedColumnCount * SpectrogramComputer.DEFAULT_HOP_SIZE
                    )
                    columns += SpectrogramColumn(columnTimeMs, values)
                    emittedColumnCount++
                }
                if (processedBytes - lastProgressReportBytes >= PROGRESS_REPORT_BYTES) {
                    lastProgressReportBytes = processedBytes
                    onProgress((processedBytes / totalAudioBytes.toFloat()).coerceIn(0f, 1f))
                }
            }
        }
        onProgress(1f)
        return columns
    }

    private fun samplesToMs(samples: Long): Long {
        return samples * 1000L / AudioChunker.DEFAULT_SAMPLE_RATE
    }

    private companion object {
        const val WAV_HEADER_BYTES = 44
        const val PROGRESS_REPORT_BYTES = 256 * 1024
    }
}
