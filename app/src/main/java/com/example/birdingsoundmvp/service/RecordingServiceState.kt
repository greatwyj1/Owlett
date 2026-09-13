package com.example.birdingsoundmvp.service

import com.example.birdingsoundmvp.i18n.AppText

import com.example.birdingsoundmvp.birdnet.BirdNetMetaModelStatus
import com.example.birdingsoundmvp.birdnet.BirdNetModelStatus
import com.example.birdingsoundmvp.birdnet.MergedDetectionCard
import com.example.birdingsoundmvp.precise.PreciseRecognitionResult
import com.example.birdingsoundmvp.trip.PauseMarker
import com.example.birdingsoundmvp.trip.TripSession
import com.example.birdingsoundmvp.ui.PreciseRecognitionUiState
import com.example.birdingsoundmvp.ui.QuickPreciseUiState
import com.example.birdingsoundmvp.ui.TripRecordingState

data class RecordingServiceState(
    val tripState: TripRecordingState = TripRecordingState.STOPPED,
    val recordingStatus: String = AppText.get("Idle"),
    val modelStatus: BirdNetModelStatus = BirdNetModelStatus(false, AppText.get("Not loaded")),
    val metaModelStatus: BirdNetMetaModelStatus = BirdNetMetaModelStatus(false, AppText.get("Not loaded")),
    val checklistStatus: String = AppText.get("Checklist not loaded"),
    val elapsedSec: Long = 0L,
    val wallElapsedSec: Long = 0L,
    val wavBytesWritten: Long = 0L,
    val lastAlertStatus: String = AppText.get("No alerts"),
    val locationStatus: String = AppText.get("Location off"),
    val savePath: String = "",
    val summary: String = "",
    val recentDetections: List<MergedDetectionCard> = emptyList(),
    val pauseMarkers: List<PauseMarker> = emptyList(),
    val spectrogramDurationMs: Long = 0L,
    val quickPrecise: QuickPreciseUiState = QuickPreciseUiState(),
    val preciseRecognition: PreciseRecognitionUiState = PreciseRecognitionUiState(),
    val currentSession: TripSession? = null,
    val lastSession: TripSession? = null,
    val canStart: Boolean = true,
    val canStop: Boolean = false
) {
    val latestPreciseResult: PreciseRecognitionResult?
        get() = preciseRecognition.result
}
