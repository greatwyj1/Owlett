package com.example.birdingsoundmvp

import com.example.birdingsoundmvp.i18n.AppText

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import com.example.birdingsoundmvp.location.LocationProvider
import com.example.birdingsoundmvp.birdnet.DetectionResult
import com.example.birdingsoundmvp.birdnet.mergeDetectionCards
import com.example.birdingsoundmvp.precise.BandpassWavClipExporter
import com.example.birdingsoundmvp.precise.PreciseRecognitionClient
import com.example.birdingsoundmvp.precise.PreciseRecognitionMeta
import com.example.birdingsoundmvp.precise.PreciseRecognitionResponse
import com.example.birdingsoundmvp.precise.WavClipExporter
import com.example.birdingsoundmvp.planning.PlansTripsViewModel
import com.example.birdingsoundmvp.owlett.OwlettViewModel
import com.example.birdingsoundmvp.share.ShareAudioSegment
import com.example.birdingsoundmvp.share.ShareClipExporter
import com.example.birdingsoundmvp.share.ShareContentPlanner
import com.example.birdingsoundmvp.share.ShareExportType
import com.example.birdingsoundmvp.share.ShareFrameRenderer
import com.example.birdingsoundmvp.share.ShareVideoRenderer
import com.example.birdingsoundmvp.share.ShareVideoFramePlanner
import com.example.birdingsoundmvp.service.RecordingRecognitionService
import com.example.birdingsoundmvp.settings.AppSettings
import com.example.birdingsoundmvp.settings.SettingsRepository
import com.example.birdingsoundmvp.taxonomy.BirdTaxonomyRepository
import com.example.birdingsoundmvp.trip.TripHistoryRepository
import com.example.birdingsoundmvp.trip.TripSession
import com.example.birdingsoundmvp.ui.AppTab
import com.example.birdingsoundmvp.ui.FilteredSelectionClipUiState
import com.example.birdingsoundmvp.ui.MainScreen
import com.example.birdingsoundmvp.ui.MainUiState
import com.example.birdingsoundmvp.ui.PreciseRecognitionUiState
import com.example.birdingsoundmvp.ui.SelectionPlaybackUiState
import com.example.birdingsoundmvp.ui.ShareUiState
import com.example.birdingsoundmvp.ui.SpeciesDetailUiState
import com.example.birdingsoundmvp.ui.SpectrogramFrequencySelectionBounds
import com.example.birdingsoundmvp.ui.SpectrogramInteractionMode
import com.example.birdingsoundmvp.ui.SpectrogramSelection
import com.example.birdingsoundmvp.ui.TripRecordingState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var uiState by mutableStateOf(MainUiState())
    private var selectedTab by mutableStateOf(AppTab.RECORDING)
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var tripHistoryRepository: TripHistoryRepository
    private lateinit var locationProvider: LocationProvider
    private lateinit var birdTaxonomyRepository: BirdTaxonomyRepository
    private lateinit var plansTripsViewModel: PlansTripsViewModel
    private lateinit var owlettViewModel: OwlettViewModel

    private var playbackJob: Job? = null
    private var reviewLoadJob: Job? = null
    private var speciesDetailJob: Job? = null
    private var filterExportJob: Job? = null
    private var shareJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentSession: TripSession? = null
    private var lastSession: TripSession? = null
    private var wasActiveTrip = false
    private var lastPreciseResultRequestId: String? = null
    private var isReviewingTrip by mutableStateOf(false)
    private var reviewTripId by mutableStateOf<String?>(null)
    private var reviewSpectrogramColumns = emptyList<com.example.birdingsoundmvp.audio.SpectrogramColumn>()
    private var reviewBaseDetections = emptyList<DetectionResult>()
    private var reviewUnsavedDetections = emptyList<DetectionResult>()
    private var reviewHasUnsavedPreciseRuns by mutableStateOf(false)
    private var reviewIsLoading by mutableStateOf(false)
    private var reviewLoadProgress by mutableStateOf(0f)
    private var reviewLoadMessage by mutableStateOf("")

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updateUi { copy(permissionStatus = if (granted) AppText.get("Granted") else AppText.get("Denied")) }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updateUi { copy(notificationPermissionStatus = if (granted) AppText.get("Granted") else AppText.get("Denied")) }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        updateUi {
            copy(
                locationPermissionStatus = if (granted) AppText.get("Granted") else AppText.get("Denied"),
                locationStatus = localLocationStatus(uiState.settings)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.birdingsoundmvp.planning.BirdObservationSources.get(application).attach(this)
        enableEdgeToEdge()

        settingsRepository = SettingsRepository(this)
        tripHistoryRepository = TripHistoryRepository(this)
        locationProvider = LocationProvider(this)
        birdTaxonomyRepository = BirdTaxonomyRepository(this)
        plansTripsViewModel = ViewModelProvider(this)[PlansTripsViewModel::class.java]
        owlettViewModel = ViewModelProvider(this)[OwlettViewModel::class.java]

        uiState = uiState.copy(
            permissionStatus = permissionStatus(),
            notificationPermissionStatus = notificationPermissionStatus(),
            locationPermissionStatus = locationPermissionStatus()
        )

        lifecycleScope.launch {
            settingsRepository.settings.collect { settings ->
                plansTripsViewModel.setEbirdApiKey(settings.ebirdApiKey)
                updateUi {
                    copy(
                        settings = settings,
                        locationStatus = localLocationStatus(settings)
                    )
                }
            }
        }

        lifecycleScope.launch {
            RecordingRecognitionService.state.collect { serviceState ->
                if (isReviewingTrip) return@collect
                currentSession = serviceState.currentSession
                lastSession = serviceState.lastSession ?: lastSession
                val activeTrip = serviceState.tripState != TripRecordingState.STOPPED
                val recordingNow = serviceState.tripState == TripRecordingState.RECORDING
                if (recordingNow && uiState.filteredSelectionClip != FilteredSelectionClipUiState()) {
                    clearFilteredSelectionClip()
                }
                val stoppedAfterActiveTrip = wasActiveTrip && !activeTrip
                wasActiveTrip = activeTrip
                val preciseResult = serviceState.preciseRecognition.result
                val newPreciseHistoryItem = if (
                    preciseResult != null &&
                    preciseResult.requestId.isNotBlank() &&
                    preciseResult.requestId != lastPreciseResultRequestId
                ) {
                    lastPreciseResultRequestId = preciseResult.requestId
                    serviceState.preciseRecognition.copy(
                        isLoading = false,
                        completedAtMs = System.currentTimeMillis()
                    )
                } else {
                    null
                }

                updateUi {
                    val totalDuration = serviceState.spectrogramDurationMs
                    val maxStart = (totalDuration - viewportDurationMs).coerceAtLeast(0L)
                    val nextViewportStart = if (autoFollowLive) {
                        liveViewportStart(totalDuration)
                    } else {
                        viewportStartMs.coerceIn(0L, maxStart)
                    }
                    copy(
                        modelStatus = serviceState.modelStatus,
                        metaModelStatus = serviceState.metaModelStatus,
                        checklistStatus = serviceState.checklistStatus,
                        recordingStatus = serviceState.recordingStatus,
                        elapsedSec = serviceState.elapsedSec,
                        wallElapsedSec = serviceState.wallElapsedSec,
                        wavBytesWritten = serviceState.wavBytesWritten,
                        lastAlertStatus = serviceState.lastAlertStatus,
                        locationStatus = serviceState.locationStatus,
                        savePath = serviceState.savePath,
                        summary = serviceState.summary,
                        recentDetections = serviceState.recentDetections,
                        pauseMarkers = serviceState.pauseMarkers,
                        spectrogramDurationMs = totalDuration,
                        viewportStartMs = nextViewportStart,
                        spectrogramColumns = currentSpectrogramWindow(
                            nextViewportStart,
                            nextViewportStart + viewportDurationMs
                        ),
                        quickPrecise = serviceState.quickPrecise,
                        preciseRecognition = serviceState.preciseRecognition,
                        preciseRecognitionHistory = if (newPreciseHistoryItem != null) {
                            (listOf(newPreciseHistoryItem) + preciseRecognitionHistory).take(50)
                        } else {
                            preciseRecognitionHistory
                        },
                        tripState = serviceState.tripState,
                        spectrogramInteractionMode = if (recordingNow) SpectrogramInteractionMode.SLIDE else spectrogramInteractionMode,
                        selection = if (recordingNow) null else selection,
                        spectrogramTimeMarkerMs = if (recordingNow) null else spectrogramTimeMarkerMs,
                        filteredSelectionClip = if (recordingNow) FilteredSelectionClipUiState() else filteredSelectionClip,
                        canStart = serviceState.canStart,
                        canStop = serviceState.canStop
                    )
                }
                if (stoppedAfterActiveTrip) {
                    refreshTrips()
                }
            }
        }

        setContent {
            MainScreen(
                state = uiState.copy(isDataTransferBlocked = reviewIsLoading || reviewHasUnsavedPreciseRuns || filterExportJob?.isActive == true || shareJob?.isActive == true),
                plansTripsViewModel = plansTripsViewModel,
                owlettViewModel = owlettViewModel,
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                onRequestLocationPermission = { requestLocationPermission() },
                onStartTrip = { startTrip() },
                onPauseTrip = { pauseTrip() },
                onResumeTrip = { resumeTrip() },
                onStopTrip = { stopTrip() },
                onViewportChanged = { updateViewport(it) },
                onSpectrogramInteractionModeChanged = { changeSpectrogramInteractionMode(it) },
                onSelectionChanged = { startMs, endMs -> updateSelection(startMs, endMs) },
                onFrequencySelectionChanged = { updateFrequencySelection(it) },
                onTimeMarkerChanged = { updateTimeMarker(it) },
                onShareAnchorRequested = { x, y -> showShareAnchor(x, y) },
                onShareAnchorClicked = { com.example.birdingsoundmvp.ui.OwlettHelp.showChapter(this, "share", ::showShareOptions) },
                onShareTypeSelected = { selectShareType(it) },
                onConfirmShare = { exportShareSelection() },
                onDismissShare = { clearShareUi(deleteGeneratedFile = true) },
                onPlaySelection = { toggleSelectionPlayback() },
                onResultSelected = { startMs, endMs -> selectResultRange(startMs, endMs) },
                onSpeciesDetailSelected = { scientificName, commonName ->
                    showSpeciesDetail(scientificName, commonName)
                },
                onDismissSpeciesDetail = { dismissSpeciesDetail() },
                onQuickPreciseClick = { com.example.birdingsoundmvp.ui.OwlettHelp.requestAudio(this, uiState.settings.preciseRecognitionServerUrl, { selectedTab = AppTab.SETTINGS }, ::quickPrecise) },
                onPreciseRecognition = { com.example.birdingsoundmvp.ui.OwlettHelp.requestAudio(this, uiState.settings.preciseRecognitionServerUrl, { selectedTab = AppTab.SETTINGS }, ::runPreciseRecognition) },
                onSettingsChanged = { updateSettings(it) },
                onTestNotification = { testNotification() },
                onReviewTrip = { openReviewTrip(it) },
                onReviewClip = { clip -> owlettViewModel.stopAudio(); openReviewTrip(clip.tripId, clip) },
                isReviewingTrip = isReviewingTrip,
                reviewTripId = reviewTripId,
                reviewIsLoading = reviewIsLoading,
                reviewLoadProgress = reviewLoadProgress,
                reviewLoadMessage = reviewLoadMessage,
                hasUnsavedReviewPreciseResults = reviewHasUnsavedPreciseRuns,
                onBackFromReview = { requestCloseReview() },
                onSaveReviewPreciseResults = { saveAndCloseReview() },
                onDiscardReviewPreciseResults = { closeReview(discardUnsaved = true) }
            )
        }
    }

    private fun requestAudioPermission() {
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun testNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startService(RecordingRecognitionService.testNotificationIntent(this))
    }

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun localLocationStatus(settings: AppSettings): String {
        if (!settings.useLocation) return AppText.get("Location off")
        if (!locationProvider.hasLocationPermission()) return AppText.get("Location permission needed")
        val location = locationProvider.lastKnownLocation()
        return if (location == null) {
            AppText.get("Location unavailable")
        } else {
            AppText.format("Location {0}, {1} ({2})", "%.4f".format(location.latitude), "%.4f".format(location.longitude), location.provider)
        }
    }

    private fun updateSettings(settings: AppSettings) {
        lifecycleScope.launch {
            settingsRepository.update { settings }
        }
    }

    private fun showSpeciesDetail(scientificName: String, commonName: String) {
        speciesDetailJob?.cancel()
        val displayName = commonName.ifBlank { scientificName }
        updateUi {
            copy(
                speciesDetail = SpeciesDetailUiState(
                    isVisible = true,
                    isLoading = true,
                    requestedName = displayName
                )
            )
        }
        speciesDetailJob = lifecycleScope.launch(Dispatchers.IO) {
            val detail = runCatching {
                birdTaxonomyRepository.findSpecies(scientificName, commonName)
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (!uiState.speciesDetail.isVisible ||
                    uiState.speciesDetail.requestedName != displayName
                ) {
                    return@withContext
                }
                updateUi {
                    copy(
                        speciesDetail = if (detail == null) {
                            SpeciesDetailUiState(
                                isVisible = true,
                                requestedName = displayName,
                                error = AppText.get("No taxonomy record found.")
                            )
                        } else {
                            SpeciesDetailUiState(
                                isVisible = true,
                                requestedName = displayName,
                                detail = detail
                            )
                        }
                    )
                }
            }
        }
    }

    private fun dismissSpeciesDetail() {
        speciesDetailJob?.cancel()
        speciesDetailJob = null
        updateUi { copy(speciesDetail = SpeciesDetailUiState()) }
    }

    private fun startTrip() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermission()
            return
        }
        requestNotificationPermissionIfNeeded()
        stopSelectionPlayback()
        clearFilteredSelectionClip()
        clearShareUi(deleteGeneratedFile = true)
        updateUi {
            copy(
                spectrogramInteractionMode = SpectrogramInteractionMode.SLIDE,
                viewportStartMs = 0L,
                spectrogramDurationMs = 0L,
                spectrogramColumns = emptyList(),
                autoFollowLive = true,
                selection = null,
                spectrogramTimeMarkerMs = null,
                selectionPlayback = SelectionPlaybackUiState(),
                filteredSelectionClip = FilteredSelectionClipUiState(),
                preciseRecognitionHistory = emptyList()
            )
        }
        lastPreciseResultRequestId = null
        ContextCompat.startForegroundService(
            this,
            RecordingRecognitionService.startIntent(this)
        )
    }

    private fun pauseTrip() {
        startService(RecordingRecognitionService.pauseIntent(this))
    }

    private fun resumeTrip() {
        stopSelectionPlayback()
        clearFilteredSelectionClip()
        clearShareUi(deleteGeneratedFile = true)
        updateUi {
            val nextStart = liveViewportStart(spectrogramDurationMs)
            copy(
                spectrogramInteractionMode = SpectrogramInteractionMode.SLIDE,
                autoFollowLive = true,
                viewportStartMs = nextStart,
                spectrogramColumns = currentSpectrogramWindow(
                    nextStart,
                    nextStart + viewportDurationMs
                ),
                selection = null,
                spectrogramTimeMarkerMs = null,
                filteredSelectionClip = FilteredSelectionClipUiState()
            )
        }
        startService(RecordingRecognitionService.resumeIntent(this))
    }

    private fun stopTrip() {
        startService(RecordingRecognitionService.stopIntent(this))
    }

    private fun quickPrecise() {
        startService(RecordingRecognitionService.quickPreciseIntent(this))
    }

    private fun liveViewportStart(totalDurationMs: Long): Long {
        return (totalDurationMs - uiState.viewportDurationMs).coerceAtLeast(0L)
    }

    private fun currentSpectrogramWindow(startMs: Long, endMs: Long): List<com.example.birdingsoundmvp.audio.SpectrogramColumn> {
        if (!isReviewingTrip) return RecordingRecognitionService.spectrogramWindow(startMs, endMs)
        val visible = reviewSpectrogramColumns.filter { it.tripAudioTimeMs in startMs..endMs }
        if (visible.size <= SPECTROGRAM_DISPLAY_COLUMNS) return visible
        val step = visible.size.toDouble() / SPECTROGRAM_DISPLAY_COLUMNS
        return List(SPECTROGRAM_DISPLAY_COLUMNS) { index ->
            visible[(index * step).toInt().coerceIn(0, visible.lastIndex)]
        }
    }

    private fun updateViewport(viewportStartMs: Long) {
        val maxStart = (uiState.spectrogramDurationMs - uiState.viewportDurationMs).coerceAtLeast(0L)
        val nextStart = viewportStartMs.coerceIn(0L, maxStart)
        updateUi {
            copy(
                spectrogramColumns = currentSpectrogramWindow(
                    nextStart,
                    nextStart + viewportDurationMs
                ),
                viewportStartMs = nextStart,
                autoFollowLive = false
            )
        }
    }

    private fun changeSpectrogramInteractionMode(mode: SpectrogramInteractionMode) {
        stopSelectionPlayback()
        clearFilteredSelectionClip()
        clearShareUi(deleteGeneratedFile = true)
        updateUi {
            copy(
                spectrogramInteractionMode = mode,
                selection = null,
                spectrogramTimeMarkerMs = null,
                selectionPlayback = SelectionPlaybackUiState(),
                preciseRecognition = preciseRecognition.copy(error = null, result = null),
                filteredSelectionClip = FilteredSelectionClipUiState()
            )
        }
    }

    private fun updateTimeMarker(timeMs: Long) {
        if (uiState.tripState == TripRecordingState.RECORDING) return
        stopSelectionPlayback()
        clearFilteredSelectionClip()
        clearShareUi(deleteGeneratedFile = true)
        val markerMs = timeMs.coerceIn(0L, uiState.spectrogramDurationMs.coerceAtLeast(0L))
        updateUi {
            copy(
                spectrogramTimeMarkerMs = markerMs,
                selection = null,
                selectionPlayback = SelectionPlaybackUiState(),
                filteredSelectionClip = FilteredSelectionClipUiState(),
                preciseRecognition = preciseRecognition.copy(error = null, result = null)
            )
        }
    }

    private fun updateSelection(startMs: Long, endMs: Long) {
        if (uiState.tripState == TripRecordingState.RECORDING) return
        clearFilteredSelectionClip()
        clearShareUi(deleteGeneratedFile = true)
        stopSelectionPlayback()
        val maxDurationMs = (uiState.settings.maxSelectionDurationSec * 1000f).toLong()
        val maxSelectableMs = uiState.spectrogramDurationMs.coerceAtLeast(0L)
        val orderedStart = minOf(startMs, endMs).coerceIn(0L, maxSelectableMs)
        val orderedEnd = maxOf(startMs, endMs).coerceIn(0L, maxSelectableMs)
        val clampedEnd = minOf(orderedEnd, orderedStart + maxDurationMs)
        val crossesPause = uiState.pauseMarkers.any { marker ->
            orderedStart < marker.tripAudioTimeMs && marker.tripAudioTimeMs < clampedEnd
        }
        val valid = clampedEnd > orderedStart && !crossesPause
        updateUi {
            copy(
                selection = SpectrogramSelection(
                    startMs = orderedStart,
                    endMs = clampedEnd,
                    isValid = valid,
                    message = if (crossesPause) AppText.get("Selection cannot cross a pause marker.") else null
                ),
                spectrogramTimeMarkerMs = null,
                selectionPlayback = SelectionPlaybackUiState(),
                filteredSelectionClip = FilteredSelectionClipUiState(),
                preciseRecognition = preciseRecognition.copy(error = null, result = null)
            )
        }
    }

    private fun updateFrequencySelection(bounds: SpectrogramFrequencySelectionBounds) {
        if (uiState.tripState == TripRecordingState.RECORDING) return
        stopSelectionPlayback()
        clearFilteredSelectionClip()
        clearShareUi(deleteGeneratedFile = true)

        val maxSelectableMs = uiState.spectrogramDurationMs.coerceAtLeast(0L)
        val startMs = bounds.startMs.coerceIn(0L, maxSelectableMs)
        val endMs = bounds.endMs.coerceIn(startMs, maxSelectableMs)
        val crossesPause = uiState.pauseMarkers.any { marker ->
            startMs < marker.tripAudioTimeMs && marker.tripAudioTimeMs < endMs
        }
        val session = currentSession ?: lastSession
        val segment = session?.segmentForSelection(startMs, endMs)
        val message = when {
            endMs <= startMs -> AppText.get("Selection is empty.")
            !bounds.isFrequencyBandValid -> AppText.get("Frequency band must be at least 100 Hz.")
            crossesPause -> AppText.get("Selection cannot cross a pause marker.")
            segment == null -> AppText.get("Selection must be inside a single audio segment.")
            else -> null
        }
        val selection = SpectrogramSelection(
            startMs = startMs,
            endMs = endMs,
            isValid = message == null,
            message = message,
            lowFrequencyHz = bounds.lowFrequencyHz,
            highFrequencyHz = bounds.highFrequencyHz
        )

        updateUi {
            copy(
                selection = selection,
                spectrogramTimeMarkerMs = null,
                selectionPlayback = SelectionPlaybackUiState(),
                filteredSelectionClip = if (selection.isValid) {
                    FilteredSelectionClipUiState(
                        isLoading = true,
                        selectionKey = selection.selectionKey
                    )
                } else {
                    FilteredSelectionClipUiState(error = message)
                },
                preciseRecognition = preciseRecognition.copy(error = null, result = null)
            )
        }

        if (selection.isValid && session != null && segment != null) {
            exportFilteredSelection(session, segment, selection)
        }
    }

    private fun exportFilteredSelection(
        session: TripSession,
        segment: com.example.birdingsoundmvp.trip.TripAudioSegment,
        selection: SpectrogramSelection
    ) {
        val lowHz = selection.lowFrequencyHz ?: return
        val highHz = selection.highFrequencyHz ?: return
        val selectionKey = selection.selectionKey
        filterExportJob?.cancel()
        filterExportJob = lifecycleScope.launch {
            val tempFile = File(cacheDir, "spectral_selection_${selectionKey.hashCode()}_${System.currentTimeMillis()}.wav")
            val exported = withContext(Dispatchers.IO) {
                BandpassWavClipExporter.exportSelection(
                    segment = segment,
                    selectionStartTripAudioMs = selection.startMs,
                    selectionEndTripAudioMs = selection.endMs,
                    lowFrequencyHz = lowHz,
                    highFrequencyHz = highHz,
                    outputFile = tempFile
                )
            }
            val currentSelection = uiState.selection
            if (currentSelection?.selectionKey != selectionKey || currentSession != session && lastSession != session) {
                tempFile.delete()
                return@launch
            }
            exported
                .onSuccess { file ->
                    updateUi {
                        copy(
                            filteredSelectionClip = FilteredSelectionClipUiState(
                                tempFilePath = file.absolutePath,
                                selectionKey = selectionKey
                            )
                        )
                    }
                }
                .onFailure { throwable ->
                    tempFile.delete()
                    updateUi {
                        copy(
                            filteredSelectionClip = FilteredSelectionClipUiState(
                                error = AppText.format("filter export failed: {0}", throwable.message ?: throwable.javaClass.simpleName),
                                selectionKey = selectionKey
                            )
                        )
                    }
                }
        }
    }

    private fun selectResultRange(startMs: Long, endMs: Long) {
        if (uiState.tripState != TripRecordingState.PAUSED && uiState.tripState != TripRecordingState.STOPPED) return
        if (endMs <= startMs) return
        clearFilteredSelectionClip()
        clearShareUi(deleteGeneratedFile = true)
        stopSelectionPlayback()
        val maxStart = (uiState.spectrogramDurationMs - uiState.viewportDurationMs).coerceAtLeast(0L)
        val viewportStart = (startMs - 500L).coerceIn(0L, maxStart)
        val selectionStart = startMs.coerceIn(0L, uiState.spectrogramDurationMs)
        val selectionEnd = endMs.coerceIn(selectionStart, uiState.spectrogramDurationMs)
        val crossesPause = uiState.pauseMarkers.any { marker ->
            selectionStart < marker.tripAudioTimeMs && marker.tripAudioTimeMs < selectionEnd
        }
        updateUi {
            copy(
                autoFollowLive = false,
                viewportStartMs = viewportStart,
                spectrogramColumns = currentSpectrogramWindow(
                    viewportStart,
                    viewportStart + viewportDurationMs
                ),
                selection = SpectrogramSelection(
                    startMs = selectionStart,
                    endMs = selectionEnd,
                    isValid = selectionEnd > selectionStart && !crossesPause,
                    message = if (crossesPause) AppText.get("Selection cannot cross a pause marker.") else null
                ),
                spectrogramTimeMarkerMs = null,
                selectionPlayback = SelectionPlaybackUiState(),
                filteredSelectionClip = FilteredSelectionClipUiState(),
                preciseRecognition = preciseRecognition.copy(error = null)
            )
        }
    }

    private fun toggleSelectionPlayback() {
        if (uiState.selectionPlayback.isPlaying) {
            stopSelectionPlayback()
        } else {
            playSelection()
        }
    }

    private fun playSelection() {
        if (uiState.tripState == TripRecordingState.RECORDING) return
        val session = currentSession ?: lastSession ?: return
        val markerMs = uiState.spectrogramTimeMarkerMs
        val selectedRange = if (markerMs == null) uiState.selection else null
        val filteredPlaybackFile = filteredClipFileForSelection(selectedRange)
        if (selectedRange?.hasFrequencyRange == true && filteredPlaybackFile == null) {
            updateUi {
                copy(
                    selectionPlayback = SelectionPlaybackUiState(
                        error = uiState.filteredSelectionClip.error ?: AppText.get("filtered playback is not ready")
                    )
                )
            }
            return
        }
        val range = playbackRange(session, markerMs, selectedRange)
        if (range == null) {
            updateUi {
                copy(
                    selectionPlayback = SelectionPlaybackUiState(
                        error = AppText.get("playback failed: no playable audio in the current range")
                    )
                )
            }
            return
        }
        val (segment, playbackStartMs, playbackEndMs) = range
        alignViewportToPlaybackStart(playbackStartMs)

        lifecycleScope.launch {
            stopSelectionPlayback()
            val audioFile = filteredPlaybackFile ?: run {
                val clipFile = File(session.tripDir, "selected_playback.wav")
                val exported = withContext(Dispatchers.IO) {
                    WavClipExporter.exportSelection(
                        segment = segment,
                        selectionStartTripAudioMs = playbackStartMs,
                        selectionEndTripAudioMs = playbackEndMs,
                        outputFile = clipFile
                    )
                }
                exported.getOrElse { throwable ->
                    updateUi {
                        copy(
                            selectionPlayback = SelectionPlaybackUiState(
                                error = AppText.format("playback failed: {0}", throwable.message ?: throwable.javaClass.simpleName)
                            )
                        )
                    }
                    return@launch
                }
            }

            if (!com.example.birdingsoundmvp.audio.PlaybackCoordinator.acquire(this@MainActivity, ::stopSelectionPlayback)) return@launch
            val player = runCatching {
                MediaPlayer().apply {
                    setDataSource(audioFile.absolutePath)
                    setOnCompletionListener {
                        runOnUiThread { stopSelectionPlayback() }
                    }
                    setOnErrorListener { _, _, _ ->
                        runOnUiThread {
                            stopSelectionPlayback()
                            updateUi {
                                copy(selectionPlayback = SelectionPlaybackUiState(error = AppText.get("playback failed: playback error")))
                            }
                        }
                        true
                    }
                    prepare()
                    start()
                }
            }.getOrElse { throwable ->
                updateUi {
                    copy(
                        selectionPlayback = SelectionPlaybackUiState(
                            error = AppText.format("playback failed: {0}", throwable.message ?: throwable.javaClass.simpleName)
                        )
                    )
                }
                return@launch
            }

            mediaPlayer = player
            updateUi {
                copy(
                    selectionPlayback = SelectionPlaybackUiState(
                        isPlaying = true,
                        playbackTripAudioTimeMs = playbackStartMs
                    )
                )
            }
            playbackJob = lifecycleScope.launch {
                while (mediaPlayer === player && runCatching { player.isPlaying }.getOrDefault(false)) {
                    updateUi {
                        copy(
                            selectionPlayback = selectionPlayback.copy(
                                isPlaying = true,
                                playbackTripAudioTimeMs = (playbackStartMs + player.currentPosition)
                                    .coerceAtMost(playbackEndMs)
                            )
                        )
                    }
                    delay(50L)
                }
            }
        }
    }

    private fun alignViewportToPlaybackStart(playbackStartMs: Long) {
        val maxStart = (uiState.spectrogramDurationMs - uiState.viewportDurationMs).coerceAtLeast(0L)
        val nextStart = playbackStartMs.coerceIn(0L, maxStart)
        updateUi {
            copy(
                autoFollowLive = false,
                viewportStartMs = nextStart,
                spectrogramColumns = currentSpectrogramWindow(
                    nextStart,
                    nextStart + viewportDurationMs
                )
            )
        }
    }

    private fun playbackRange(
        session: TripSession,
        markerMs: Long?,
        selectedRange: SpectrogramSelection?
    ): Triple<com.example.birdingsoundmvp.trip.TripAudioSegment, Long, Long>? {
        markerMs?.let { marker ->
            val requestedStartMs = marker.coerceIn(0L, uiState.spectrogramDurationMs.coerceAtLeast(0L))
            val segment = session.segments.firstOrNull { candidate ->
                val start = candidate.tripAudioStartMs
                val end = candidate.tripAudioStartMs + candidate.durationMs
                requestedStartMs >= start && requestedStartMs < end
            } ?: return null
            val requestedEndMs = segment.tripAudioStartMs + segment.durationMs
            if (requestedEndMs <= requestedStartMs) return null
            return Triple(segment, requestedStartMs, requestedEndMs)
        }

        val selection = selectedRange?.takeIf { it.isValid }
        val requestedStartMs = selection?.startMs ?: uiState.viewportStartMs
        val requestedEndMs = selection?.endMs ?: uiState.spectrogramDurationMs
        if (requestedEndMs <= requestedStartMs) return null
        session.segmentForSelection(requestedStartMs, requestedEndMs)?.let {
            return Triple(it, requestedStartMs, requestedEndMs)
        }
        val segment = session.segments.firstOrNull { candidate ->
            val start = candidate.tripAudioStartMs
            val end = candidate.tripAudioStartMs + candidate.durationMs
            requestedStartMs >= start && requestedStartMs < end
        } ?: session.segments.firstOrNull { candidate ->
            candidate.tripAudioStartMs == requestedStartMs && candidate.durationMs > 0L
        } ?: return null
        val segmentEndMs = segment.tripAudioStartMs + segment.durationMs
        val endMs = minOf(requestedEndMs, segmentEndMs)
        if (endMs <= requestedStartMs) return null
        return Triple(segment, requestedStartMs, endMs)
    }

    private fun stopSelectionPlayback() {
        com.example.birdingsoundmvp.audio.PlaybackCoordinator.release(this)
        playbackJob?.cancel()
        playbackJob = null
        mediaPlayer?.let { player ->
            runCatching {
                if (player.isPlaying) player.stop()
            }
            runCatching { player.release() }
        }
        mediaPlayer = null
        if (uiState.selectionPlayback.isPlaying || uiState.selectionPlayback.playbackTripAudioTimeMs != null) {
            updateUi { copy(selectionPlayback = SelectionPlaybackUiState()) }
        }
    }

    private fun clearFilteredSelectionClip() {
        filterExportJob?.cancel()
        filterExportJob = null
        uiState.filteredSelectionClip.tempFilePath.takeIf { it.isNotBlank() }?.let { path ->
            runCatching { File(path).delete() }
        }
        if (uiState.filteredSelectionClip != FilteredSelectionClipUiState()) {
            updateUi { copy(filteredSelectionClip = FilteredSelectionClipUiState()) }
        }
    }

    private fun filteredClipFileForSelection(selection: SpectrogramSelection?): File? {
        val clip = uiState.filteredSelectionClip
        if (!clip.isReadyFor(selection)) return null
        return File(clip.tempFilePath).takeIf { it.exists() && it.isFile }
    }

    private fun showShareAnchor(x: Float, y: Float) {
        if (shareSelectionError(uiState) != null) return
        val selection = uiState.selection?.takeIf { it.isValid } ?: return
        if (selection.endMs - selection.startMs > (uiState.settings.maxSelectionDurationSec * 1000).toLong()) {
            updateUi { copy(preciseRecognition = preciseRecognition.copy(error = "片段超过精准识别时长上限，请在频谱中重新选择较短范围。")) }
            return
        }
        if (uiState.tripState == TripRecordingState.RECORDING) return
        updateUi {
            copy(
                shareUiState = ShareUiState(
                    showAnchorButton = true,
                    anchorX = x,
                    anchorY = y,
                    selectedType = shareUiState.selectedType
                ),
                selection = selection
            )
        }
    }

    private fun showShareOptions() {
        if (uiState.shareUiState.isProcessing) return
        val availabilityError = shareSelectionError(uiState)
        updateUi {
            copy(
                shareUiState = shareUiState.copy(
                    showAnchorButton = false,
                    showOptions = true,
                    error = availabilityError,
                    progress = 0f,
                    message = ""
                )
            )
        }
    }

    private fun selectShareType(type: ShareExportType) {
        updateUi { copy(shareUiState = shareUiState.copy(selectedType = type, error = null)) }
    }

    private fun clearShareUi(deleteGeneratedFile: Boolean) {
        shareJob?.cancel()
        shareJob = null
        val generatedPath = uiState.shareUiState.generatedPath
        if (deleteGeneratedFile && generatedPath.isNotBlank()) {
            runCatching { File(generatedPath).delete() }
        }
        if (uiState.shareUiState != ShareUiState()) {
            updateUi { copy(shareUiState = ShareUiState()) }
        }
    }

    private fun exportShareSelection() {
        if (uiState.shareUiState.isProcessing) return
        shareSelectionError(uiState)?.let { reason ->
            updateUi { copy(shareUiState = shareUiState.copy(showOptions = true, error = reason)) }
            return
        }
        val selection = uiState.selection?.takeIf { it.isValid } ?: return
        if (selection.endMs - selection.startMs > (uiState.settings.maxSelectionDurationSec * 1000).toLong()) {
            updateUi { copy(preciseRecognition = preciseRecognition.copy(error = "片段超过精准识别时长上限，请在频谱中重新选择较短范围。")) }
            return
        }
        if (uiState.tripState == TripRecordingState.RECORDING) return
        val session = currentSession ?: lastSession ?: return
        val shareSegments = session.segments.map {
            ShareAudioSegment(
                index = it.index,
                filePath = it.filePath,
                tripAudioStartMs = it.tripAudioStartMs,
                durationMs = it.durationMs
            )
        }
        val segmentIndex = ShareContentPlanner.validateSingleSegmentRange(
            selectionStartMs = selection.startMs,
            selectionEndMs = selection.endMs,
            segments = shareSegments
        ).getOrElse { throwable ->
            updateUi {
                copy(
                    shareUiState = shareUiState.copy(
                        showOptions = true,
                        isProcessing = false,
                        error = throwable.message ?: AppText.get("selection export failed")
                    )
                )
            }
            return
        }.index
        val segment = session.segments.firstOrNull { it.index == segmentIndex } ?: return
        val exportType = uiState.shareUiState.selectedType
        val shareDir = File(cacheDir, "share")
        shareJob?.cancel()
        shareJob = lifecycleScope.launch {
            updateUi {
                copy(
                    shareUiState = shareUiState.copy(
                        showOptions = true,
                        showAnchorButton = false,
                        isProcessing = true,
                        progress = 0.05f,
                        message = AppText.get("准备分享内容..."),
                        error = null
                    )
                )
            }
            val audioFile = withContext(Dispatchers.IO) {
                ShareClipExporter.exportSelectionAudio(
                    tripId = session.tripId,
                    segment = segment,
                    selection = selection,
                    outputDir = shareDir,
                    filteredClipFile = filteredClipFileForSelection(selection)
                )
            }.getOrElse { throwable ->
                updateUi {
                    copy(
                        shareUiState = shareUiState.copy(
                            isProcessing = false,
                            showOptions = true,
                            error = AppText.format("音频生成失败：{0}", throwable.message ?: throwable.javaClass.simpleName)
                        )
                    )
                }
                return@launch
            }

            val outputFile = if (exportType == ShareExportType.AUDIO) {
                updateShareProgress(0.95f, AppText.get("音频已生成，准备打开分享面板..."))
                audioFile
            } else {
                val lines = ShareContentPlanner.resultLinesForSelection(
                    selectionStartMs = selection.startMs,
                    selectionEndMs = selection.endMs,
                    preciseStates = listOf(uiState.preciseRecognition) + uiState.preciseRecognitionHistory,
                    realtimeCards = uiState.recentDetections
                )
                val frameWindow = ShareVideoFramePlanner.centeredSpectrogramWindow(
                    selectionStartMs = selection.startMs,
                    selectionEndMs = selection.endMs,
                    availableStartMs = 0L,
                    availableEndMs = uiState.spectrogramDurationMs
                )
                val recordingTimeMs = segment.wallStartMs +
                    (selection.startMs - segment.tripAudioStartMs).coerceAtLeast(0L)
                val frameData = ShareFrameRenderer.FrameData(
                    selectionStartMs = selection.startMs,
                    selectionEndMs = selection.endMs,
                    spectrogramStartMs = frameWindow.startMs,
                    spectrogramEndMs = frameWindow.endMs,
                    recordingTimeMs = recordingTimeMs,
                    columns = currentSpectrogramWindow(frameWindow.startMs, frameWindow.endMs),
                    detections = lines,
                    selectionLowFrequencyHz = selection.lowFrequencyHz?.toFloat(),
                    selectionHighFrequencyHz = selection.highFrequencyHz?.toFloat()
                )
                val videoFile = File(
                    shareDir,
                    "${session.tripId}_${selection.startMs}_${selection.endMs}_${System.currentTimeMillis()}.mp4"
                )
                val previewFile = File(shareDir, "share_video_preview.png")
                val brandIcon = BitmapFactory.decodeResource(resources, R.drawable.owlett_icon)
                withContext(Dispatchers.IO) {
                    try {
                        runCatching {
                            ShareFrameRenderer.writePreviewPng(previewFile, frameData, brandIcon).getOrThrow()
                            ShareVideoRenderer.render(
                                audioFile = audioFile,
                                outputFile = videoFile,
                                frameData = frameData,
                                brandIcon = brandIcon
                            ) { progress ->
                                updateShareProgress(0.15f + progress * 0.8f, AppText.get("正在生成视频..."))
                            }.getOrThrow()
                        }
                    } finally {
                        brandIcon?.recycle()
                    }
                }.getOrElse { throwable ->
                    updateUi {
                        copy(
                            shareUiState = shareUiState.copy(
                                isProcessing = false,
                                showOptions = true,
                                error = AppText.format("视频生成失败：{0}", throwable.message ?: throwable.javaClass.simpleName)
                            )
                        )
                    }
                    return@launch
                }
            }

            updateUi {
                copy(
                    shareUiState = shareUiState.copy(
                        isProcessing = false,
                        showOptions = false,
                        progress = 1f,
                        message = "",
                        generatedPath = outputFile.absolutePath,
                        generatedMimeType = exportType.mimeType
                    )
                )
            }
            shareFile(outputFile, exportType.mimeType)
            updateUi { copy(shareUiState = ShareUiState(selectedType = exportType)) }
        }
    }

    private fun updateShareProgress(progress: Float, message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            updateUi {
                copy(
                    shareUiState = shareUiState.copy(
                        isProcessing = true,
                        progress = progress.coerceIn(0f, 1f),
                        message = message
                    )
                )
            }
        }
    }

    private fun shareFile(file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(sendIntent, AppText.get("分享音频/视频")))
    }

    private fun persistFilteredClip(session: TripSession, sourceFile: File): Result<File> = runCatching {
        require(sourceFile.exists() && sourceFile.isFile) { AppText.get("filtered clip does not exist") }
        val output = File(session.tripDir, "spectral_precise_${System.currentTimeMillis()}.wav")
        sourceFile.copyTo(output, overwrite = true)
    }

    private fun runPreciseRecognition() {
        val selection = uiState.selection?.takeIf { it.isValid } ?: return
        if (selection.endMs - selection.startMs > (uiState.settings.maxSelectionDurationSec * 1000).toLong()) {
            updateUi { copy(preciseRecognition = preciseRecognition.copy(error = "片段超过精准识别时长上限，请在频谱中重新选择较短范围。")) }
            return
        }
        if (uiState.tripState == TripRecordingState.RECORDING) return
        val filteredFile = if (selection.hasFrequencyRange) {
            filteredClipFileForSelection(selection) ?: run {
                updateUi {
                    copy(
                        filteredSelectionClip = filteredSelectionClip.copy(
                            isLoading = false,
                            error = filteredSelectionClip.error ?: AppText.get("filtered clip is not ready")
                        )
                    )
                }
                return
            }
        } else {
            null
        }
        if (isReviewingTrip) {
            runReviewPreciseRecognition(selection.startMs, selection.endMs, filteredFile)
            return
        }
        startService(
            RecordingRecognitionService.preciseSelectionIntent(
                this,
                selection.startMs,
                selection.endMs,
                filteredClipPath = filteredFile?.absolutePath,
                lowFrequencyHz = selection.lowFrequencyHz,
                highFrequencyHz = selection.highFrequencyHz
            )
        )
    }

    private fun runReviewPreciseRecognition(startMs: Long, endMs: Long, filteredAudioFile: File? = null) {
        val session = lastSession ?: return
        val segment = session.segmentForSelection(startMs, endMs)
        if (segment == null && filteredAudioFile == null) {
            updateUi {
                copy(
                    preciseRecognition = PreciseRecognitionUiState(
                        error = AppText.get("selection export failed: selection is not inside a single segment"),
                        selectionStartMs = startMs,
                        selectionEndMs = endMs
                    )
                )
            }
            return
        }
        lifecycleScope.launch {
            updateUi {
                copy(
                    preciseRecognition = PreciseRecognitionUiState(
                        isLoading = true,
                        selectionStartMs = startMs,
                        selectionEndMs = endMs
                    )
                )
            }
            val audioFile = if (filteredAudioFile != null) {
                val persisted = withContext(Dispatchers.IO) {
                    persistFilteredClip(session, filteredAudioFile)
                }
                persisted.getOrElse { throwable ->
                    updateUi {
                        copy(
                            preciseRecognition = PreciseRecognitionUiState(
                                isLoading = false,
                                error = AppText.format("filtered clip save failed: {0}", throwable.message ?: throwable.javaClass.simpleName),
                                selectionStartMs = startMs,
                                selectionEndMs = endMs
                            )
                        )
                    }
                    return@launch
                }
            } else {
                val clipFile = File(session.tripDir, "review_selected_clip.wav")
                val exported = withContext(Dispatchers.IO) {
                    WavClipExporter.exportSelection(
                        segment = segment ?: return@withContext Result.failure(IllegalStateException(AppText.get("selection is not inside a single segment"))),
                        selectionStartTripAudioMs = startMs,
                        selectionEndTripAudioMs = endMs,
                        outputFile = clipFile
                    )
                }
                exported.getOrElse { throwable ->
                    updateUi {
                        copy(
                            preciseRecognition = PreciseRecognitionUiState(
                                isLoading = false,
                                error = AppText.format("selection export failed: {0}", throwable.message ?: throwable.javaClass.simpleName),
                                selectionStartMs = startMs,
                                selectionEndMs = endMs
                            )
                        )
                    }
                    return@launch
                }
            }
            val settings = uiState.settings
            val preciseLocation = if (settings.useLocation) locationProvider.lastKnownLocation() else null
            val meta = PreciseRecognitionMeta(
                trip_id = session.tripId,
                selection_start_trip_audio_ms = startMs,
                selection_end_trip_audio_ms = endMs,
                lat = preciseLocation?.latitude,
                lon = preciseLocation?.longitude,
                week = com.example.birdingsoundmvp.location.BirdNetWeekCalculator.currentBirdNetWeek(),
                acoustic_model = settings.preciseAcousticModel,
                min_confidence = settings.defaultPreciseMinConfidence.toDouble(),
                overlap_sec = settings.defaultPreciseOverlapSec.toDouble(),
                top_k = settings.defaultPreciseTopK
            )
            val response = withContext(Dispatchers.IO) {
                PreciseRecognitionClient(settings.preciseRecognitionServerUrl).recognize(
                    audioFile = audioFile,
                    meta = meta,
                    bearerToken = settings.preciseRecognitionAuthToken
                )
            }
            when (response) {
                is PreciseRecognitionResponse.Success -> {
                    reviewHasUnsavedPreciseRuns = true
                    val completedState = PreciseRecognitionUiState(
                        isLoading = false,
                        clipPath = audioFile.absolutePath,
                        result = response.result,
                        completedAtMs = System.currentTimeMillis(),
                        selectionStartMs = startMs,
                        selectionEndMs = endMs
                    )
                    val newDetections = response.result.detections.map { detection ->
                        DetectionResult(
                            tripId = session.tripId,
                            timestampMs = System.currentTimeMillis(),
                            audioStartSec = detection.tripAudioStartMs / 1000.0,
                            audioEndSec = detection.tripAudioEndMs / 1000.0,
                            scientificName = detection.scientificName,
                            commonName = detection.commonName,
                            displayNameZh = detection.displayNameZh,
                            audioConfidence = detection.confidence,
                            metaConfidence = null,
                            adjustedConfidence = detection.confidence,
                            metaAvailable = false,
                            isInChecklist = false,
                            locationFiltered = false,
                            alertTriggered = false,
                            inferenceTimeMs = 0L,
                            source = "precise"
                        )
                    }
                    reviewUnsavedDetections = reviewUnsavedDetections + newDetections
                    updateUi {
                        copy(
                            preciseRecognition = completedState,
                            preciseRecognitionHistory = (listOf(completedState) + preciseRecognitionHistory).take(50)
                        )
                    }
                }

                is PreciseRecognitionResponse.Failure -> {
                    updateUi {
                        copy(
                            preciseRecognition = PreciseRecognitionUiState(
                                isLoading = false,
                                clipPath = audioFile.absolutePath,
                                error = response.error.message,
                                selectionStartMs = startMs,
                                selectionEndMs = endMs
                            )
                        )
                    }
                }
            }
        }
    }

    private fun refreshTrips() {
        plansTripsViewModel.refresh()
    }

    private fun openReviewTrip(tripId: String, clip: com.example.birdingsoundmvp.owlett.OwlettAudioClip? = null) {
        stopSelectionPlayback()
        clearFilteredSelectionClip()
        clearShareUi(deleteGeneratedFile = true)
        reviewLoadJob?.cancel()
        isReviewingTrip = true
        reviewTripId = tripId
        reviewIsLoading = true
        reviewLoadProgress = 0f
        reviewLoadMessage = AppText.get("Loading trip")
        reviewSpectrogramColumns = emptyList()
        reviewBaseDetections = emptyList()
        reviewUnsavedDetections = emptyList()
        reviewHasUnsavedPreciseRuns = false
        updateUi {
            copy(
                recordingStatus = AppText.get("Review loading"),
                recentDetections = emptyList(),
                spectrogramColumns = emptyList(),
                pauseMarkers = emptyList(),
                viewportStartMs = 0L,
                spectrogramDurationMs = 0L,
                selection = null,
                spectrogramTimeMarkerMs = null,
                selectionPlayback = SelectionPlaybackUiState(),
                filteredSelectionClip = FilteredSelectionClipUiState(),
                preciseRecognition = PreciseRecognitionUiState(),
                preciseRecognitionHistory = emptyList(),
                tripState = TripRecordingState.STOPPED,
                canStart = false,
                canStop = false
            )
        }
        reviewLoadJob = lifecycleScope.launch(Dispatchers.IO) {
            val data = tripHistoryRepository.loadReviewData(tripId) { progress, message ->
                withContext(Dispatchers.Main) {
                    reviewLoadProgress = progress
                    reviewLoadMessage = message
                }
            }
            withContext(Dispatchers.Main) {
                if (data == null) {
                    updateUi { copy(summary = AppText.get("Review failed: trip not found")) }
                    closeReview(discardUnsaved = true)
                    return@withContext
                }
                reviewIsLoading = false
                reviewLoadProgress = 1f
                reviewLoadMessage = AppText.get("Ready")
                reviewSpectrogramColumns = data.spectrogramColumns
                reviewBaseDetections = data.detections.sortedByDescending { it.timestampMs }
                reviewUnsavedDetections = emptyList()
                reviewHasUnsavedPreciseRuns = false
                currentSession = null
                lastSession = data.session
                val durationMs = data.session.tripAudioDurationMs
                updateUi {
                    copy(
                        recordingStatus = AppText.get("Review"),
                        elapsedSec = durationMs / 1000L,
                        wallElapsedSec = 0L,
                        wavBytesWritten = data.session.segments.sumOf { java.io.File(it.filePath).length() },
                        savePath = data.session.tripDir.absolutePath,
                        summary = "",
                        recentDetections = mergeDetectionCards(reviewBaseDetections),
                        spectrogramColumns = currentSpectrogramWindow(clip?.startMs ?: 0L, (clip?.startMs ?: 0L) + viewportDurationMs),
                        pauseMarkers = data.session.pauseMarkers,
                        viewportStartMs = clip?.startMs ?: 0L,
                        spectrogramDurationMs = durationMs,
                        autoFollowLive = false,
                        selection = clip?.let { SpectrogramSelection(it.startMs, it.endMs, isValid = true) },
                        spectrogramTimeMarkerMs = null,
                        selectionPlayback = SelectionPlaybackUiState(),
                        filteredSelectionClip = FilteredSelectionClipUiState(),
                        quickPrecise = com.example.birdingsoundmvp.ui.QuickPreciseUiState(),
                        preciseRecognition = com.example.birdingsoundmvp.ui.PreciseRecognitionUiState(),
                        preciseRecognitionHistory = emptyList(),
                        tripState = TripRecordingState.STOPPED,
                        canStart = false,
                        canStop = false
                    )
                }
            }
        }
    }

    private fun requestCloseReview() {
        if (reviewIsLoading) {
            reviewLoadJob?.cancel()
            closeReview(discardUnsaved = true)
            return
        }
        if (!reviewHasUnsavedPreciseRuns) {
            closeReview(discardUnsaved = true)
        }
    }

    private fun saveAndCloseReview() {
        val tripId = reviewTripId ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            tripHistoryRepository.appendDetections(tripId, reviewUnsavedDetections)
            plansTripsViewModel.markTripUpdated(tripId)
            withContext(Dispatchers.Main) {
                closeReview(discardUnsaved = true)
                refreshTrips()
            }
        }
    }

    private fun closeReview(discardUnsaved: Boolean) {
        stopSelectionPlayback()
        if (!discardUnsaved && reviewUnsavedDetections.isNotEmpty()) return
        clearFilteredSelectionClip()
        clearShareUi(deleteGeneratedFile = true)
        reviewLoadJob?.cancel()
        reviewLoadJob = null
        isReviewingTrip = false
        reviewTripId = null
        reviewIsLoading = false
        reviewLoadProgress = 0f
        reviewLoadMessage = ""
        reviewSpectrogramColumns = emptyList()
        reviewBaseDetections = emptyList()
        reviewUnsavedDetections = emptyList()
        reviewHasUnsavedPreciseRuns = false
        updateUi { copy(spectrogramTimeMarkerMs = null) }
        currentSession = RecordingRecognitionService.state.value.currentSession
        lastSession = RecordingRecognitionService.state.value.lastSession ?: lastSession
        selectedTab = AppTab.TRIPS
        refreshTrips()
    }

    private fun permissionStatus(): String {
        return if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            AppText.get("Granted")
        } else {
            AppText.get("Not granted")
        }
    }

    private fun notificationPermissionStatus(): String {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            AppText.get("Granted")
        } else {
            AppText.get("Not granted")
        }
    }

    private fun locationPermissionStatus(): String {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return if (fine || coarse) AppText.get("Granted") else AppText.get("Not granted")
    }

    private fun updateUi(reducer: MainUiState.() -> MainUiState) {
        val next = uiState.reducer()
        uiState = next.copy(shareAudioAvailable = shareSelectionError(next) == null)
    }

    private fun shareSelectionError(state: MainUiState): String? {
        val segments = (currentSession ?: lastSession)?.segments.orEmpty().map {
            ShareAudioSegment(it.index, it.filePath, it.tripAudioStartMs, it.durationMs)
        }
        return com.example.birdingsoundmvp.share.ShareSelectionPolicy.error(state, segments) { File(it).isFile }
    }

    override fun onStart() {
        super.onStart()
        com.example.birdingsoundmvp.planning.BirdObservationSources.get(application).foreground(true)
    }

    override fun onStop() {
        com.example.birdingsoundmvp.planning.BirdObservationSources.get(application).foreground(false)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSelectionPlayback()
        clearFilteredSelectionClip()
        clearShareUi(deleteGeneratedFile = true)
    }

    private companion object {
        const val SPECTROGRAM_DISPLAY_COLUMNS = 320
    }
}
