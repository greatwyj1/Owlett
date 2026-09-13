package com.example.birdingsoundmvp.trip

import android.content.Context
import com.example.birdingsoundmvp.birdnet.DetectionResult
import com.example.birdingsoundmvp.audio.AudioChunker
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TripSession(
    val tripId: String,
    val tripDir: File,
    val detectionsFile: File,
    val summaryFile: File,
    val metadataFile: File,
    val pauseMarkersFile: File,
    val segmentsDir: File,
    val startedAtMs: Long,
    initialSegments: List<TripAudioSegment> = emptyList(),
    initialPauseMarkers: List<PauseMarker> = emptyList()
) {
    private val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
    private val mutableSegments = initialSegments.toMutableList()
    private val mutablePauseMarkers = initialPauseMarkers.toMutableList()
    private var activeSegmentIndex: Int? = null

    val wavFile: File
        get() = segmentFile(0)

    val segments: List<TripAudioSegment>
        get() = mutableSegments.toList()

    val pauseMarkers: List<PauseMarker>
        get() = mutablePauseMarkers.toList()

    val tripAudioDurationMs: Long
        get() = mutableSegments.sumOf { it.durationMs }

    fun startSegment(wallStartMs: Long = System.currentTimeMillis()): TripAudioSegment {
        segmentsDir.mkdirs()
        val index = mutableSegments.size
        val segment = TripAudioSegment(
            index = index,
            fileName = segmentFileName(index),
            filePath = segmentFile(index).absolutePath,
            tripAudioStartMs = tripAudioDurationMs,
            durationMs = 0L,
            wallStartMs = wallStartMs,
            wallEndMs = null
        )
        mutableSegments += segment
        activeSegmentIndex = index
        writeMetadata()
        return segment
    }

    fun finishActiveSegment(dataBytesWritten: Long, wallEndMs: Long = System.currentTimeMillis()): TripAudioSegment? {
        val index = activeSegmentIndex ?: return null
        val current = mutableSegments.getOrNull(index) ?: return null
        val durationMs = audioBytesToDurationMs(dataBytesWritten)
        val finished = current.copy(durationMs = durationMs, wallEndMs = wallEndMs)
        mutableSegments[index] = finished
        activeSegmentIndex = null
        writeMetadata()
        return finished
    }

    fun addPauseMarker(wallTimeMs: Long = System.currentTimeMillis()): PauseMarker {
        val marker = PauseMarker(
            wallTimeMs = wallTimeMs,
            tripAudioTimeMs = tripAudioDurationMs
        )
        mutablePauseMarkers += marker
        writePauseMarkers()
        writeMetadata()
        return marker
    }

    fun segmentForSelection(startTripAudioMs: Long, endTripAudioMs: Long): TripAudioSegment? {
        return mutableSegments.firstOrNull { segment ->
            val segmentStart = segment.tripAudioStartMs
            val segmentEnd = segment.tripAudioStartMs + segment.durationMs
            startTripAudioMs >= segmentStart && endTripAudioMs <= segmentEnd
        }
    }

    fun writeMetadata(endedAtMs: Long? = null) {
        val metadata = TripMetadata(
            tripId = tripId,
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            tripAudioDurationMs = tripAudioDurationMs,
            segments = segments,
            pauseMarkers = pauseMarkers
        )
        metadataFile.writeText(gson.toJson(metadata))
    }

    fun writePauseMarkers() {
        pauseMarkersFile.writeText(gson.toJson(pauseMarkers))
    }

    private fun segmentFile(index: Int): File = File(segmentsDir, segmentFileName(index))

    companion object {
        fun create(context: Context): TripSession {
            val now = System.currentTimeMillis()
            val tripId = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date(now))
            val root = File(context.getExternalFilesDir(null), "trips")
            val tripDir = File(root, tripId).apply { mkdirs() }
            val segmentsDir = File(tripDir, "segments").apply { mkdirs() }
            return TripSession(
                tripId = tripId,
                tripDir = tripDir,
                detectionsFile = File(tripDir, "detections.jsonl"),
                summaryFile = File(tripDir, "summary.json"),
                metadataFile = File(tripDir, "metadata.json"),
                pauseMarkersFile = File(tripDir, "pause_markers.json"),
                segmentsDir = segmentsDir,
                startedAtMs = now
            ).also {
                it.writeMetadata()
                it.writePauseMarkers()
            }
        }

        fun load(tripDir: File): TripSession? {
            if (!tripDir.isDirectory) return null
            val metadataFile = File(tripDir, "metadata.json")
            val pauseMarkersFile = File(tripDir, "pause_markers.json")
            val segmentsDir = File(tripDir, "segments")
            val gson = Gson()
            val metadata = runCatching {
                if (metadataFile.exists()) {
                    gson.fromJson(metadataFile.readText(), TripMetadata::class.java)
                } else {
                    null
                }
            }.getOrNull()
            val segments = metadata?.segments?.takeIf { it.isNotEmpty() }
                ?: legacySegments(tripDir, segmentsDir)
            val pauseMarkers = metadata?.pauseMarkers
                ?: runCatching {
                    if (pauseMarkersFile.exists()) {
                        gson.fromJson(pauseMarkersFile.readText(), Array<PauseMarker>::class.java).toList()
                    } else {
                        emptyList()
                    }
                }.getOrElse { emptyList() }
            return TripSession(
                tripId = metadata?.tripId ?: tripDir.name,
                tripDir = tripDir,
                detectionsFile = File(tripDir, "detections.jsonl"),
                summaryFile = File(tripDir, "summary.json"),
                metadataFile = metadataFile,
                pauseMarkersFile = pauseMarkersFile,
                segmentsDir = segmentsDir,
                startedAtMs = metadata?.startedAtMs ?: tripDir.lastModified(),
                initialSegments = segments,
                initialPauseMarkers = pauseMarkers
            )
        }

        private fun legacySegments(tripDir: File, segmentsDir: File): List<TripAudioSegment> {
            val segmentFiles = segmentsDir.listFiles()
                ?.filter { it.isFile && it.extension.equals("wav", ignoreCase = true) }
                ?.sortedBy { it.name }
                .orEmpty()
            if (segmentFiles.isNotEmpty()) {
                var cursorMs = 0L
                return segmentFiles.mapIndexed { index, file ->
                    val durationMs = wavDurationMs(file)
                    TripAudioSegment(
                        index = index,
                        fileName = file.name,
                        filePath = file.absolutePath,
                        tripAudioStartMs = cursorMs,
                        durationMs = durationMs,
                        wallStartMs = tripDir.lastModified(),
                        wallEndMs = null
                    ).also {
                        cursorMs += durationMs
                    }
                }
            }
            val legacyAudio = File(tripDir, "audio.wav")
            if (!legacyAudio.exists()) return emptyList()
            return listOf(
                TripAudioSegment(
                    index = 0,
                    fileName = legacyAudio.name,
                    filePath = legacyAudio.absolutePath,
                    tripAudioStartMs = 0L,
                    durationMs = wavDurationMs(legacyAudio),
                    wallStartMs = tripDir.lastModified(),
                    wallEndMs = null
                )
            )
        }

        private fun wavDurationMs(file: File): Long {
            val dataBytes = (file.length() - 44L).coerceAtLeast(0L)
            return audioBytesToDurationMs(dataBytes)
        }

        private fun segmentFileName(index: Int): String = "segment_%03d.wav".format(index)

        private fun audioBytesToDurationMs(dataBytesWritten: Long): Long {
            val bytesPerSample = 2L
            val samples = dataBytesWritten / bytesPerSample
            return samples * 1000L / AudioChunker.DEFAULT_SAMPLE_RATE
        }
    }
}

data class TripAudioSegment(
    val index: Int,
    val fileName: String,
    val filePath: String,
    val tripAudioStartMs: Long,
    val durationMs: Long,
    val wallStartMs: Long,
    val wallEndMs: Long?
)

data class PauseMarker(
    val wallTimeMs: Long,
    val tripAudioTimeMs: Long
)

data class TripMetadata(
    val tripId: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val tripAudioDurationMs: Long,
    val segments: List<TripAudioSegment>,
    val pauseMarkers: List<PauseMarker>
)

data class TripSummary(
    val tripId: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val durationSec: Double,
    val wallDurationSec: Double,
    val audioFile: String,
    val audioSegments: List<String>,
    val detectionsFile: String,
    val detectionCount: Int,
    val alertCount: Int,
    val uniqueSpeciesCount: Int
) {
    companion object {
        fun from(session: TripSession, detections: List<DetectionResult>, endedAtMs: Long): TripSummary {
            return TripSummary(
                tripId = session.tripId,
                startedAtMs = session.startedAtMs,
                endedAtMs = endedAtMs,
                durationSec = session.tripAudioDurationMs / 1000.0,
                wallDurationSec = (endedAtMs - session.startedAtMs) / 1000.0,
                audioFile = session.wavFile.absolutePath,
                audioSegments = session.segments.map { it.filePath },
                detectionsFile = session.detectionsFile.absolutePath,
                detectionCount = detections.size,
                alertCount = detections.count { it.alertTriggered },
                uniqueSpeciesCount = detections
                    .map { it.scientificName.ifBlank { it.commonName } }
                    .filter { it.isNotBlank() }
                    .toSet()
                    .size
            )
        }
    }
}
