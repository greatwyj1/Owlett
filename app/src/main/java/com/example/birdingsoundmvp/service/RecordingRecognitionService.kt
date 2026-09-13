package com.example.birdingsoundmvp.service

import com.example.birdingsoundmvp.i18n.AppText

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.example.birdingsoundmvp.MainActivity
import com.example.birdingsoundmvp.R
import com.example.birdingsoundmvp.audio.AudioCaptureManager
import com.example.birdingsoundmvp.audio.AudioChunk
import com.example.birdingsoundmvp.audio.AudioChunker
import com.example.birdingsoundmvp.audio.InMemorySpectrogramStore
import com.example.birdingsoundmvp.audio.SpectrogramColumn
import com.example.birdingsoundmvp.audio.SpectrogramComputer
import com.example.birdingsoundmvp.birdnet.BirdNetClassifier
import com.example.birdingsoundmvp.birdnet.BirdNetMetaModel
import com.example.birdingsoundmvp.birdnet.BirdNetModelStatus
import com.example.birdingsoundmvp.birdnet.DetectionResult
import com.example.birdingsoundmvp.birdnet.MetaModelInput
import com.example.birdingsoundmvp.birdnet.ZhengBirdNameResolver
import com.example.birdingsoundmvp.birdnet.mergeDetectionCards
import com.example.birdingsoundmvp.location.BirdNetWeekCalculator
import com.example.birdingsoundmvp.location.LocationProvider
import com.example.birdingsoundmvp.notify.AlertPolicyEngine
import com.example.birdingsoundmvp.notify.VibrationNotifier
import com.example.birdingsoundmvp.precise.PreciseRecognitionClient
import com.example.birdingsoundmvp.precise.PreciseRecognitionError
import com.example.birdingsoundmvp.precise.PreciseRecognitionMeta
import com.example.birdingsoundmvp.precise.PreciseRecognitionResponse
import com.example.birdingsoundmvp.precise.PreciseRecognitionResult
import com.example.birdingsoundmvp.precise.WavClipExporter
import com.example.birdingsoundmvp.settings.AppSettings
import com.example.birdingsoundmvp.settings.SettingsRepository
import com.example.birdingsoundmvp.trip.DetectionLogger
import com.example.birdingsoundmvp.trip.TripAudioSegment
import com.example.birdingsoundmvp.trip.TripSession
import com.example.birdingsoundmvp.trip.TripSummary
import com.example.birdingsoundmvp.ui.PreciseRecognitionUiState
import com.example.birdingsoundmvp.ui.QuickPreciseUiState
import com.example.birdingsoundmvp.ui.TripRecordingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordingRecognitionService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)

    private lateinit var notificationManager: NotificationManager
    private lateinit var vibrationNotifier: VibrationNotifier
    private lateinit var audioCaptureManager: AudioCaptureManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var locationProvider: LocationProvider
    private lateinit var zhengBirdNameResolver: ZhengBirdNameResolver

    private var classifier: BirdNetClassifier? = null
    private var metaModel: BirdNetMetaModel? = null
    private var alertPolicyEngine: AlertPolicyEngine? = null
    private var mediaSession: MediaSession? = null
    private var currentSettings = AppSettings()

    private var chunkChannel: Channel<AudioChunk>? = null
    private var spectrumChannel: Channel<ShortArray>? = null
    private var inferenceJob: Job? = null
    private var spectrogramJob: Job? = null
    private var timerJob: Job? = null
    private var settingsJob: Job? = null
    private var currentSession: TripSession? = null
    private var detectionLogger: DetectionLogger? = null
    private val allDetections = mutableListOf<DetectionResult>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentSegmentBytes: Long = 0L
    private var quickPreciseJob: Job? = null
    private var quickPreciseClickCount = 0
    private var quickPreciseAnchorMs = 0L

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService() ?: error(AppText.get("NotificationManager unavailable"))
        vibrationNotifier = VibrationNotifier(this)
        audioCaptureManager = AudioCaptureManager(this, serviceScope)
        settingsRepository = SettingsRepository(this)
        locationProvider = LocationProvider(this)
        zhengBirdNameResolver = ZhengBirdNameResolver.fromAssets(this)
        createNotificationChannels()
        setupMediaSession()

        settingsJob = serviceScope.launch {
            settingsRepository.settings.collect {
                currentSettings = it
                refreshLocationStatus(it)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (com.example.birdingsoundmvp.transfer.DataTransferGate.active) return START_NOT_STICKY
        if (intent?.action in setOf(ACTION_QUICK_PRECISE_TRIGGER, ACTION_PRECISE_SELECTION) &&
            !com.example.birdingsoundmvp.ui.OwlettHelp.seen(this, com.example.birdingsoundmvp.ui.OwlettHelp.audioConsentKey(currentSettings.preciseRecognitionServerUrl))) {
            updateState { copy(quickPrecise = quickPrecise.copy(error = "请先在应用内配置并确认音频发送目标。")) }
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START_TRIP -> startTrip()
            ACTION_PAUSE_TRIP -> pauseTrip()
            ACTION_RESUME_TRIP -> resumeTrip()
            ACTION_STOP_TRIP -> stopTrip()
            ACTION_QUICK_PRECISE_TRIGGER -> onQuickPreciseTrigger()
            ACTION_TEST_NOTIFICATION -> showTestNotification()
            ACTION_PRECISE_SELECTION -> runPreciseSelection(
                intent.getLongExtra(EXTRA_SELECTION_START_MS, -1L),
                intent.getLongExtra(EXTRA_SELECTION_END_MS, -1L),
                intent.getStringExtra(EXTRA_FILTERED_CLIP_PATH),
                intent.getIntExtra(EXTRA_FILTER_LOW_HZ, -1).takeIf { it >= 0 },
                intent.getIntExtra(EXTRA_FILTER_HIGH_HZ, -1).takeIf { it >= 0 }
            )
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startTrip() {
        if (_state.value.tripState != TripRecordingState.STOPPED) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateState { copy(recordingStatus = AppText.get("Missing RECORD_AUDIO permission")) }
            stopSelf()
            return
        }

        startInForeground()
        acquireWakeLock()
        loadModelsIfNeeded()

        val session = TripSession.create(this)
        val logger = DetectionLogger(session)
        allDetections.clear()
        alertPolicyEngine?.reset()
        spectrogramStore.clear()
        currentSegmentBytes = 0L
        currentSession = session
        detectionLogger = logger

        updateState {
            copy(
                tripState = TripRecordingState.RECORDING,
                recordingStatus = AppText.get("Starting"),
                elapsedSec = 0,
                wallElapsedSec = 0,
                wavBytesWritten = 0,
                lastAlertStatus = AppText.get("No alerts"),
                savePath = session.tripDir.absolutePath,
                summary = "",
                recentDetections = emptyList(),
                pauseMarkers = emptyList(),
                spectrogramDurationMs = 0L,
                quickPrecise = QuickPreciseUiState(),
                preciseRecognition = PreciseRecognitionUiState(),
                currentSession = session,
                lastSession = session,
                canStart = false,
                canStop = true
            )
        }
        updateMediaPlaybackState()
        updateForegroundNotification()

        startTimer()
        startRecordingSegment(session, logger)
    }

    private fun startRecordingSegment(session: TripSession, logger: DetectionLogger) {
        val segment = session.startSegment()
        currentSegmentBytes = 0L
        val channel = Channel<AudioChunk>(capacity = 4)
        val specChannel = Channel<ShortArray>(capacity = 64)
        chunkChannel = channel
        spectrumChannel = specChannel

        val metaScores = buildMetaScores(currentSettings)
        startInferenceLoop(session, logger, channel, metaScores)
        startSpectrogramLoop(specChannel, segment.tripAudioStartMs)

        audioCaptureManager.start(
            wavFile = java.io.File(segment.filePath),
            chunkChannel = channel,
            spectrumChannel = specChannel,
            hopDurationSec = currentSettings.hopDurationSec,
            startOffsetSec = segment.tripAudioStartMs / 1000.0,
            onStatus = { status ->
                serviceScope.launch {
                    updateState { copy(recordingStatus = status) }
                    updateForegroundNotification()
                }
            },
            onBytesWritten = { bytes ->
                currentSegmentBytes = bytes
                val durationMs = activeTripAudioTimeMs()
                serviceScope.launch {
                    updateState {
                        copy(
                            wavBytesWritten = bytes,
                            elapsedSec = durationMs / 1000L,
                            spectrogramDurationMs = delayedLiveSpectrogramTimeMs(durationMs)
                        )
                    }
                }
            }
        )
    }

    private fun pauseTrip() {
        if (_state.value.tripState != TripRecordingState.RECORDING) return
        serviceScope.launch {
            updateState { copy(recordingStatus = AppText.get("Pausing")) }
            updateForegroundNotification()
            val session = currentSession ?: return@launch
            stopCurrentRecordingSegment()
            releaseWakeLock()
            session.addPauseMarker()
            updateState {
                copy(
                    recordingStatus = AppText.get("Paused"),
                    tripState = TripRecordingState.PAUSED,
                    pauseMarkers = session.pauseMarkers,
                    spectrogramDurationMs = session.tripAudioDurationMs,
                    currentSession = session,
                    canStart = false,
                    canStop = true
                )
            }
            updateMediaPlaybackState()
            updateForegroundNotification()
        }
    }

    private fun resumeTrip() {
        if (_state.value.tripState != TripRecordingState.PAUSED) return
        val session = currentSession ?: _state.value.currentSession ?: return
        val logger = detectionLogger ?: return
        acquireWakeLock()
        updateState {
            copy(
                recordingStatus = AppText.get("Resuming"),
                tripState = TripRecordingState.RECORDING,
                preciseRecognition = PreciseRecognitionUiState(),
                canStart = false,
                canStop = true
            )
        }
        updateMediaPlaybackState()
        updateForegroundNotification()
        startRecordingSegment(session, logger)
    }

    private fun stopTrip() {
        if (_state.value.tripState == TripRecordingState.STOPPED && currentSession == null) {
            stopSelf()
            return
        }
        serviceScope.launch {
            val wasRecording = _state.value.tripState == TripRecordingState.RECORDING
            updateState { copy(recordingStatus = AppText.get("Stopping"), canStop = false) }
            updateForegroundNotification()

            if (wasRecording) {
                stopCurrentRecordingSegment()
            }
            timerJob?.cancel()
            releaseWakeLock()
            resetQuickPreciseClicks()

            val session = currentSession
            val logger = detectionLogger
            if (session != null && logger != null) {
                val endedAtMs = System.currentTimeMillis()
                session.writeMetadata(endedAtMs)
                session.writePauseMarkers()
                val summary = TripSummary.from(session, allDetections, endedAtMs)
                withContext(Dispatchers.IO) {
                    logger.writeSummary(summary)
                    logger.close()
                }
                updateState {
                    copy(
                        recordingStatus = AppText.get("Stopped"),
                        elapsedSec = summary.durationSec.toLong(),
                        wallElapsedSec = summary.wallDurationSec.toLong(),
                        summary = AppText.format("Detections {0}, alerts {1}, species {2}", summary.detectionCount, summary.alertCount, summary.uniqueSpeciesCount),
                        tripState = TripRecordingState.STOPPED,
                        pauseMarkers = session.pauseMarkers,
                        spectrogramDurationMs = session.tripAudioDurationMs,
                        currentSession = null,
                        lastSession = session,
                        canStart = true,
                        canStop = false
                    )
                }
            } else {
                updateState {
                    copy(
                        recordingStatus = AppText.get("Stopped"),
                        tripState = TripRecordingState.STOPPED,
                        currentSession = null,
                        canStart = true,
                        canStop = false
                    )
                }
            }

            currentSession = null
            detectionLogger = null
            chunkChannel = null
            spectrumChannel = null
            inferenceJob = null
            spectrogramJob = null
            updateMediaPlaybackState()
            ServiceCompat.stopForeground(this@RecordingRecognitionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun stopCurrentRecordingSegment() {
        audioCaptureManager.stop()
        chunkChannel?.close()
        spectrumChannel?.close()
        inferenceJob?.join()
        spectrogramJob?.join()
        currentSession?.finishActiveSegment(currentSegmentBytes)
        currentSegmentBytes = 0L
        chunkChannel = null
        spectrumChannel = null
        inferenceJob = null
        spectrogramJob = null
    }

    private fun loadModelsIfNeeded() {
        if (classifier == null) {
            val (loadedClassifier, modelStatus) = BirdNetClassifier.createOrNull(this)
            classifier = loadedClassifier
            updateState { copy(modelStatus = modelStatus) }
        }
        if (metaModel == null) {
            val (loadedMetaModel, metaStatus) = BirdNetMetaModel.createOrNull(this)
            metaModel = loadedMetaModel
            updateState { copy(metaModelStatus = metaStatus) }
        }
        if (alertPolicyEngine == null) {
            val (engine, checklistStatus) = AlertPolicyEngine.fromAssets(this)
            alertPolicyEngine = engine
            updateState { copy(checklistStatus = checklistStatus) }
        }
    }

    private fun buildMetaScores(settings: AppSettings): Map<Int, Float>? {
        val model = metaModel ?: return null
        if (!settings.useMetaModel) return null

        val week = BirdNetWeekCalculator.currentBirdNetWeek()
        val location = if (settings.useLocation) locationProvider.lastKnownLocation() else null
        val input = if (location != null) {
            updateLocationStatus(location)
            MetaModelInput(
                latitude = location.latitude.toFloat(),
                longitude = location.longitude.toFloat(),
                week = week.toFloat()
            )
        } else {
            updateState {
                copy(
                    locationStatus = if (settings.useLocation) {
                        AppText.get("Location unavailable; meta uses missing-value fallback")
                    } else {
                        AppText.get("Location off; meta uses missing-value fallback")
                    }
                )
            }
            MetaModelInput.missing(week)
        }

        return runCatching {
            model.predict(input).associate { it.labelIndex to it.occurrenceProbability }
        }.getOrElse { throwable ->
            updateState { copy(metaModelStatus = metaModelStatus.copy(message = AppText.format("Meta inference failed: {0}", throwable.message))) }
            null
        }
    }

    private fun refreshLocationStatus(settings: AppSettings = currentSettings) {
        if (!settings.useLocation) {
            updateState { copy(locationStatus = AppText.get("Location off")) }
            return
        }
        if (!locationProvider.hasLocationPermission()) {
            updateState { copy(locationStatus = AppText.get("Location permission needed")) }
            return
        }
        val location = locationProvider.lastKnownLocation()
        if (location == null) {
            updateState { copy(locationStatus = AppText.get("Location unavailable")) }
        } else {
            updateLocationStatus(location)
        }
    }

    private fun updateLocationStatus(location: com.example.birdingsoundmvp.location.BirdingLocation) {
        updateState {
            copy(locationStatus = AppText.format("Location {0}, {1} ({2})", "%.4f".format(location.latitude), "%.4f".format(location.longitude), location.provider))
        }
    }

    private fun startInferenceLoop(
        session: TripSession,
        logger: DetectionLogger,
        channel: Channel<AudioChunk>,
        metaScores: Map<Int, Float>?
    ) {
        inferenceJob = serviceScope.launch(Dispatchers.Default) {
            for (chunk in channel) {
                val localClassifier = classifier ?: continue
                val settings = currentSettings
                val predictions = try {
                    localClassifier.classify(
                        chunk = chunk,
                        displayThreshold = settings.minimumAudioConfidence
                    )
                } catch (throwable: Throwable) {
                    updateStateOnMain {
                        copy(modelStatus = BirdNetModelStatus(false, AppText.format("Inference failed: {0}", throwable.message)))
                    }
                    emptyList()
                }

                for (prediction in predictions) {
                    val policy = alertPolicyEngine?.findPolicy(prediction)
                    val metaConfidence = metaScores?.get(prediction.labelIndex)
                    val metaAvailable = metaScores != null && metaConfidence != null
                    val locationFiltered = policy == null &&
                        metaAvailable &&
                        settings.useMetaModel &&
                        metaConfidence < settings.minimumMetaConfidence
                    val adjustedConfidence = if (metaAvailable) {
                        prediction.audioConfidence * (0.5f + 0.5f * metaConfidence.coerceIn(0f, 1f))
                    } else {
                        prediction.audioConfidence
                    }
                    val alertTriggered = policy != null &&
                        !locationFiltered &&
                        alertPolicyEngine?.shouldAlert(prediction, chunk.endSec) == true

                    val result = DetectionResult(
                        tripId = session.tripId,
                        timestampMs = System.currentTimeMillis(),
                        audioStartSec = chunk.startSec,
                        audioEndSec = chunk.endSec,
                        scientificName = prediction.scientificName,
                        commonName = prediction.commonName,
                        displayNameZh = zhengBirdNameResolver.resolve(
                            prediction.scientificName,
                            prediction.commonName
                        ) ?: policy?.displayNameZh,
                        audioConfidence = prediction.audioConfidence,
                        metaConfidence = metaConfidence,
                        adjustedConfidence = adjustedConfidence,
                        metaAvailable = metaAvailable,
                        isInChecklist = policy != null,
                        locationFiltered = locationFiltered,
                        alertTriggered = alertTriggered,
                        inferenceTimeMs = prediction.inferenceTimeMs
                    )

                    withContext(Dispatchers.IO) {
                        logger.logDetection(result)
                    }

                    updateStateOnMain {
                        allDetections.add(0, result)
                        copy(
                            recentDetections = mergedRealtimeDetections(settings),
                            lastAlertStatus = if (alertTriggered) {
                                AppText.format("Alerted: {0}", result.displayNameZh ?: result.commonName.ifBlank { result.scientificName })
                            } else {
                                lastAlertStatus
                            }
                        )
                    }

                    showDetectionNotification(result)
                    if (alertTriggered) {
                        vibrationNotifier.vibrate()
                    }
                }
            }
        }
    }

    private fun mergedRealtimeDetections(settings: AppSettings) = mergeDetectionCards(allDetections).take(100)

    private fun startSpectrogramLoop(channel: Channel<ShortArray>, segmentStartMs: Long) {
        val computer = SpectrogramComputer()
        spectrogramJob = serviceScope.launch(Dispatchers.Default) {
            var emittedColumnCount = 0L
            for (samples in channel) {
                val columns = computer.appendPcm(samples)
                columns.forEach { column ->
                    val columnTimeMs = segmentStartMs + samplesToMs(
                        emittedColumnCount * SpectrogramComputer.DEFAULT_HOP_SIZE
                    )
                    spectrogramStore.addColumn(SpectrogramColumn(columnTimeMs, column))
                    emittedColumnCount++
                }
            }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (_state.value.tripState != TripRecordingState.STOPPED) {
                val session = currentSession ?: _state.value.currentSession ?: break
                val now = System.currentTimeMillis()
                val audioMs = activeTripAudioTimeMs()
                val wallSec = (now - session.startedAtMs) / 1000L
                updateState {
                    copy(
                        elapsedSec = audioMs / 1000L,
                        wallElapsedSec = wallSec,
                        spectrogramDurationMs = if (_state.value.tripState == TripRecordingState.RECORDING) {
                            delayedLiveSpectrogramTimeMs(audioMs)
                        } else {
                            spectrogramDurationMs
                        }
                    )
                }
                updateForegroundNotification()
                delay(1000L)
            }
        }
    }

    private fun activeTripAudioTimeMs(): Long {
        val session = currentSession ?: _state.value.lastSession ?: return _state.value.spectrogramDurationMs
        val activeSegmentMs = currentSegmentBytes * 1000L / (AudioChunker.DEFAULT_SAMPLE_RATE * 2L)
        return session.tripAudioDurationMs + activeSegmentMs
    }

    private fun delayedLiveSpectrogramTimeMs(audioMs: Long): Long {
        return (audioMs - LIVE_SPECTROGRAM_DELAY_MS).coerceAtLeast(0L)
    }

    private fun samplesToMs(samples: Long): Long {
        return samples * 1000L / AudioChunker.DEFAULT_SAMPLE_RATE
    }

    private fun onQuickPreciseTrigger() {
        if (_state.value.quickPrecise.isUploading) return
        if (quickPreciseClickCount >= QUICK_PRECISE_MAX_CLICKS) return

        val session = currentSession ?: _state.value.lastSession
        if (session == null || session.segments.isEmpty()) {
            updateState { copy(quickPrecise = QuickPreciseUiState(error = AppText.get("No trip audio available"))) }
            return
        }

        vibrationNotifier.vibrate()
        if (quickPreciseClickCount == 0) {
            quickPreciseAnchorMs = if (_state.value.tripState == TripRecordingState.RECORDING && currentSession === session) {
                activeTripAudioTimeMs()
            } else {
                session.tripAudioDurationMs
            }
        }
        quickPreciseClickCount = (quickPreciseClickCount + 1).coerceAtMost(QUICK_PRECISE_MAX_CLICKS)
        updateState {
            copy(
                quickPrecise = QuickPreciseUiState(clickCount = quickPreciseClickCount),
                preciseRecognition = preciseRecognition.copy(error = null)
            )
        }

        quickPreciseJob?.cancel()
        quickPreciseJob = serviceScope.launch {
            delay(QUICK_PRECISE_CLICK_WINDOW_MS)
            val clickCount = quickPreciseClickCount
            val anchorMs = quickPreciseAnchorMs
            quickPreciseClickCount = 0
            quickPreciseAnchorMs = 0L
            runQuickPreciseRecognition(clickCount, anchorMs)
        }
        updateForegroundNotification()
    }

    private fun resetQuickPreciseClicks() {
        quickPreciseJob?.cancel()
        quickPreciseJob = null
        quickPreciseClickCount = 0
        quickPreciseAnchorMs = 0L
    }

    private suspend fun runQuickPreciseRecognition(clickCount: Int, anchorMs: Long) {
        if (clickCount <= 0) return
        val session = currentSession ?: _state.value.lastSession ?: return

        updateState {
            copy(
                quickPrecise = QuickPreciseUiState(clickCount = clickCount, isUploading = true),
                preciseRecognition = PreciseRecognitionUiState(isLoading = true)
            )
        }
        updateForegroundNotification()

        val segment = latestExportableSegment(session, anchorMs)
        if (segment == null) {
            val message = PreciseRecognitionError.SelectionExportFailed(AppText.get("no exportable audio segment")).message
            updateState {
                copy(
                    quickPrecise = QuickPreciseUiState(error = message),
                    preciseRecognition = PreciseRecognitionUiState(
                        isLoading = false,
                        error = message
                    )
                )
            }
            return
        }

        val segmentEndMs = (segment.tripAudioStartMs + segment.durationMs).coerceAtMost(anchorMs)
        val targetDurationMs = clickCount * QUICK_PRECISE_STEP_MS
        val selectionStartMs = maxOf(segment.tripAudioStartMs, segmentEndMs - targetDurationMs)
        val selectionEndMs = segmentEndMs
        if (selectionEndMs <= selectionStartMs) {
            val message = PreciseRecognitionError.SelectionExportFailed(AppText.get("selected audio is empty")).message
            updateState {
                copy(
                    quickPrecise = QuickPreciseUiState(error = message),
                    preciseRecognition = PreciseRecognitionUiState(isLoading = false, error = message)
                )
            }
            return
        }

        val clipFile = java.io.File(session.tripDir, "quick_precise_clip.wav")
        val exported = withContext(Dispatchers.IO) {
            WavClipExporter.exportSelection(
                segment = segment,
                selectionStartTripAudioMs = selectionStartMs,
                selectionEndTripAudioMs = selectionEndMs,
                outputFile = clipFile
            )
        }
        val audioFile = exported.getOrElse { throwable ->
            val message = PreciseRecognitionError.SelectionExportFailed(
                throwable.message ?: throwable.javaClass.simpleName
            ).message
            updateState {
                copy(
                    quickPrecise = QuickPreciseUiState(error = message),
                    preciseRecognition = PreciseRecognitionUiState(isLoading = false, error = message)
                )
            }
            return
        }

        val response = recognizeClip(session, audioFile, selectionStartMs, selectionEndMs)
        showPreciseRecognitionNotificationIfLocked(response)
        updateState {
            when (response) {
                is PreciseRecognitionResponse.Success -> copy(
                    quickPrecise = QuickPreciseUiState(),
                    preciseRecognition = PreciseRecognitionUiState(
                        isLoading = false,
                        clipPath = audioFile.absolutePath,
                        result = response.result,
                        selectionStartMs = selectionStartMs,
                        selectionEndMs = selectionEndMs
                    )
                )

                is PreciseRecognitionResponse.Failure -> copy(
                    quickPrecise = QuickPreciseUiState(error = response.error.message),
                    preciseRecognition = PreciseRecognitionUiState(
                        isLoading = false,
                        clipPath = audioFile.absolutePath,
                        error = response.error.message,
                        selectionStartMs = selectionStartMs,
                        selectionEndMs = selectionEndMs
                    )
                )
            }
        }
        updateForegroundNotification()
    }

    private fun latestExportableSegment(session: TripSession, anchorMs: Long): TripAudioSegment? {
        val latest = session.segments.lastOrNull() ?: return null
        val activeSegmentDurationMs = if (_state.value.tripState == TripRecordingState.RECORDING && currentSession === session) {
            currentSegmentBytes * 1000L / (AudioChunker.DEFAULT_SAMPLE_RATE * 2L)
        } else {
            latest.durationMs
        }
        val availableDurationMs = minOf(activeSegmentDurationMs, (anchorMs - latest.tripAudioStartMs).coerceAtLeast(0L))
        if (availableDurationMs <= 0L) return null
        return latest.copy(durationMs = availableDurationMs)
    }

    private fun runPreciseSelection(
        startMs: Long,
        endMs: Long,
        filteredClipPath: String? = null,
        lowFrequencyHz: Int? = null,
        highFrequencyHz: Int? = null
    ) {
        if (startMs < 0L || endMs <= startMs) return
        val session = currentSession ?: _state.value.lastSession ?: return
        val segment = session.segmentForSelection(startMs, endMs)
        if (segment == null && filteredClipPath.isNullOrBlank()) {
            updateState {
                copy(
                    preciseRecognition = PreciseRecognitionUiState(
                        error = PreciseRecognitionError.SelectionExportFailed(AppText.get("selection is not inside a single segment")).message,
                        selectionStartMs = startMs,
                        selectionEndMs = endMs
                    )
                )
            }
            return
        }
        serviceScope.launch {
            updateState {
                copy(
                    preciseRecognition = PreciseRecognitionUiState(
                        isLoading = true,
                        selectionStartMs = startMs,
                        selectionEndMs = endMs
                    )
                )
            }
            val audioFile = if (!filteredClipPath.isNullOrBlank()) {
                val persisted = withContext(Dispatchers.IO) {
                    persistFilteredClip(session, java.io.File(filteredClipPath))
                }
                persisted.getOrElse { throwable ->
                    updateState {
                        copy(
                            preciseRecognition = PreciseRecognitionUiState(
                                isLoading = false,
                                error = PreciseRecognitionError.SelectionExportFailed(
                                    AppText.format("filtered clip save failed: {0}", throwable.message ?: throwable.javaClass.simpleName)
                                ).message,
                                selectionStartMs = startMs,
                                selectionEndMs = endMs
                            )
                        )
                    }
                    return@launch
                }
            } else {
                val clipFile = java.io.File(session.tripDir, "selected_clip.wav")
                val exported = withContext(Dispatchers.IO) {
                    WavClipExporter.exportSelection(
                        segment = segment ?: return@withContext Result.failure(IllegalStateException(AppText.get("selection is not inside a single segment"))),
                        selectionStartTripAudioMs = startMs,
                        selectionEndTripAudioMs = endMs,
                        outputFile = clipFile
                    )
                }
                exported.getOrElse { throwable ->
                    updateState {
                        copy(
                            preciseRecognition = PreciseRecognitionUiState(
                                isLoading = false,
                                error = PreciseRecognitionError.SelectionExportFailed(
                                    throwable.message ?: throwable.javaClass.simpleName
                                ).message,
                                selectionStartMs = startMs,
                                selectionEndMs = endMs
                            )
                        )
                    }
                    return@launch
                }
            }

            val response = recognizeClip(session, audioFile, startMs, endMs)
            showPreciseRecognitionNotificationIfLocked(response)
            updateState {
                when (response) {
                    is PreciseRecognitionResponse.Success -> copy(
                        preciseRecognition = PreciseRecognitionUiState(
                            isLoading = false,
                            clipPath = audioFile.absolutePath,
                            result = response.result,
                            selectionStartMs = startMs,
                            selectionEndMs = endMs
                        )
                    )

                    is PreciseRecognitionResponse.Failure -> copy(
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

    private fun persistFilteredClip(session: TripSession, sourceFile: java.io.File): Result<java.io.File> = runCatching {
        require(sourceFile.exists() && sourceFile.isFile) { AppText.get("filtered clip does not exist") }
        val suffix = listOfNotNull(
            "spectral_precise",
            System.currentTimeMillis().toString()
        ).joinToString("_")
        val output = java.io.File(session.tripDir, "$suffix.wav")
        sourceFile.copyTo(output, overwrite = true)
    }

    private suspend fun recognizeClip(
        session: TripSession,
        audioFile: java.io.File,
        selectionStartMs: Long,
        selectionEndMs: Long
    ): PreciseRecognitionResponse {
        val settings = currentSettings
        val preciseLocation = if (settings.useLocation) locationProvider.lastKnownLocation() else null
        val meta = PreciseRecognitionMeta(
            trip_id = session.tripId,
            selection_start_trip_audio_ms = selectionStartMs,
            selection_end_trip_audio_ms = selectionEndMs,
            lat = preciseLocation?.latitude,
            lon = preciseLocation?.longitude,
            week = BirdNetWeekCalculator.currentBirdNetWeek(),
            acoustic_model = settings.preciseAcousticModel,
            min_confidence = settings.defaultPreciseMinConfidence.toDouble(),
            overlap_sec = settings.defaultPreciseOverlapSec.toDouble(),
            top_k = settings.defaultPreciseTopK
        )
        return withContext(Dispatchers.IO) {
            PreciseRecognitionClient(settings.preciseRecognitionServerUrl).recognize(
                audioFile = audioFile,
                meta = meta,
                bearerToken = settings.preciseRecognitionAuthToken
            )
        }.withZhengNames()
    }

    private fun PreciseRecognitionResponse.withZhengNames(): PreciseRecognitionResponse {
        return when (this) {
            is PreciseRecognitionResponse.Success -> copy(
                result = result.copy(
                    detections = result.detections.map { detection ->
                        detection.copy(
                            displayNameZh = zhengBirdNameResolver.resolve(
                                detection.scientificName,
                                detection.commonName
                            )
                        )
                    },
                    summary = result.summary.map { summary ->
                        summary.copy(
                            displayNameZh = zhengBirdNameResolver.resolve(
                                summary.scientificName,
                                summary.commonName
                            )
                        )
                    }
                )
            )

            is PreciseRecognitionResponse.Failure -> this
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "BirdingSoundRecording").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    if (_state.value.tripState == TripRecordingState.PAUSED) {
                        resumeTrip()
                    } else if (_state.value.tripState == TripRecordingState.STOPPED) {
                        startTrip()
                    }
                }

                override fun onPause() {
                    pauseTrip()
                }

                override fun onStop() {
                    stopTrip()
                }

                override fun onFastForward() {
                    onQuickPreciseTrigger()
                }

                override fun onSkipToNext() {
                    onQuickPreciseTrigger()
                }
            })
            isActive = true
        }
        updateMediaPlaybackState()
    }

    private fun updateMediaPlaybackState() {
        val playbackState = when (_state.value.tripState) {
            TripRecordingState.RECORDING -> PlaybackState.STATE_PLAYING
            TripRecordingState.PAUSED -> PlaybackState.STATE_PAUSED
            TripRecordingState.STOPPED -> PlaybackState.STATE_STOPPED
        }
        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_FAST_FORWARD or
                        PlaybackState.ACTION_SKIP_TO_NEXT
                )
                .addCustomAction(MEDIA_ACTION_END, AppText.get("End"), android.R.drawable.ic_menu_close_clear_cancel)
                .addCustomAction(MEDIA_ACTION_QUICK_PRECISE, AppText.get("Precise"), android.R.drawable.ic_menu_search)
                .setState(playbackState, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }

    private fun startInForeground() {
        ServiceCompat.startForeground(
            this,
            FOREGROUND_NOTIFICATION_ID,
            buildForegroundNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
        )
    }

    private fun updateForegroundNotification() {
        if (_state.value.tripState != TripRecordingState.STOPPED) {
            notificationManager.notify(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification())
        }
    }

    private fun buildForegroundNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val state = _state.value
        val primaryAction = if (state.tripState == TripRecordingState.RECORDING) {
            notificationAction(android.R.drawable.ic_media_pause, AppText.get("Pause"), ACTION_PAUSE_TRIP, 1)
        } else {
            notificationAction(android.R.drawable.ic_media_play, AppText.get("Resume"), ACTION_RESUME_TRIP, 1)
        }
        val stopAction = notificationAction(android.R.drawable.ic_menu_close_clear_cancel, AppText.get("End"), ACTION_STOP_TRIP, 2)
        val preciseAction = notificationAction(android.R.drawable.ic_menu_search, AppText.get("Precise"), ACTION_QUICK_PRECISE_TRIGGER, 3)

        return Notification.Builder(this, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_birding)
            .setContentTitle(AppText.get("BirdingSound"))
            .setContentText("${state.recordingStatus} ${formatDuration(state.elapsedSec)}")
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(primaryAction)
            .addAction(stopAction)
            .addAction(preciseAction)
            .build()
    }

    private fun notificationAction(icon: Int, title: String, action: String, requestCode: Int): Notification.Action {
        val intent = Intent(this, RecordingRecognitionService::class.java).setAction(action)
        val pendingIntent = PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(icon, title, pendingIntent).build()
    }

    private fun showDetectionNotification(result: DetectionResult) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val name = result.displayNameZh ?: result.commonName.ifBlank { result.scientificName.ifBlank { AppText.get("Unknown species") } }
        val contentIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_birding)
            .setContentTitle(name)
            .setContentText(
                AppText.get("%sConfidence %.2f at %.1f-%.1fs").format(
                    if (result.alertTriggered) AppText.get("Target. ") else "",
                    result.audioConfidence,
                    result.audioStartSec,
                    result.audioEndSec
                )
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    AppText.format("{0}; confidence %.2f at %.1f-%.1fs", name).format(
                        result.audioConfidence,
                        result.audioStartSec,
                        result.audioEndSec
                    )
                )
            )
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        notificationManager.notify(ALERT_NOTIFICATION_BASE_ID + (result.timestampMs % 10_000).toInt(), notification)
    }

    private fun showPreciseRecognitionNotificationIfLocked(response: PreciseRecognitionResponse) {
        updateForegroundNotification()
        val locked = isDeviceLocked()
        val notificationStatus = notificationPostStatus()
        if (!locked || !notificationStatus.canPost) {
            updateState {
                copy(lastAlertStatus = "Precise notification skipped: locked=$locked, ${notificationStatus.message}")
            }
            return
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            11,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val (title, text) = when (response) {
            is PreciseRecognitionResponse.Success -> preciseNotificationText(response.result)
            is PreciseRecognitionResponse.Failure -> {
                AppText.get("Precise recognition failed") to response.error.message
            }
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_birding)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        notificationManager.notify(PRECISE_NOTIFICATION_BASE_ID + (System.currentTimeMillis() % 10_000).toInt(), notification)
        updateState { copy(lastAlertStatus = AppText.get("Precise notification posted")) }
    }

    private fun showTestNotification() {
        val notificationStatus = notificationPostStatus()
        if (!notificationStatus.canPost) {
            updateState { copy(lastAlertStatus = "Test notification blocked: ${notificationStatus.message}") }
            return
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            12,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = AppText.get("If this appears, app notifications work while the screen is on.")
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_birding)
            .setContentTitle(AppText.get("BirdingSound test notification"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        notificationManager.notify(TEST_NOTIFICATION_ID, notification)
        updateState { copy(lastAlertStatus = AppText.get("Test notification posted")) }
    }

    private fun preciseNotificationText(result: PreciseRecognitionResult): Pair<String, String> {
        val best = result.detections.maxByOrNull { it.confidence }
        if (best == null) {
            return AppText.get("Precise recognition complete") to AppText.get("No precise detections returned.")
        }
        val name = best.displayNameZh ?: best.commonName.ifBlank { best.scientificName.ifBlank { AppText.get("Unknown species") } }
        return name to AppText.get("Confidence %.2f, clip %.1f-%.1fs, trip %s-%s").format(
            best.confidence,
            best.clipStartSec,
            best.clipEndSec,
            formatMs(best.tripAudioStartMs),
            formatMs(best.tripAudioEndMs)
        )
    }

    private fun isDeviceLocked(): Boolean {
        val keyguardLocked = getSystemService<KeyguardManager>()?.let { manager ->
            manager.isKeyguardLocked || if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                manager.isDeviceLocked
            } else {
                false
            }
        } == true
        val screenOff = getSystemService<PowerManager>()?.isInteractive == false
        return keyguardLocked || screenOff
    }

    private fun canPostNotifications(): Boolean {
        return notificationPostStatus().canPost
    }

    private fun notificationPostStatus(): NotificationPostStatus {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val appNotificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val alertsChannelEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.getNotificationChannel(CHANNEL_ALERTS)?.importance != NotificationManager.IMPORTANCE_NONE
        } else {
            true
        }
        return NotificationPostStatus(
            canPost = permissionGranted && appNotificationsEnabled && alertsChannelEnabled,
            message = "permission=$permissionGranted, appNotifications=$appNotificationsEnabled, alertsChannel=$alertsChannelEnabled"
        )
    }

    private data class NotificationPostStatus(
        val canPost: Boolean,
        val message: String
    )

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RECORDING,
                AppText.get("Recording controls"),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                AppText.get("Bird detections"),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService<PowerManager>() ?: return
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BirdingSoundMVP:RecordingRecognition"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    private fun updateState(reducer: RecordingServiceState.() -> RecordingServiceState) {
        _state.value = _state.value.reducer()
        com.example.birdingsoundmvp.audio.PlaybackCoordinator.recording = _state.value.tripState == TripRecordingState.RECORDING
    }

    private suspend fun updateStateOnMain(reducer: RecordingServiceState.() -> RecordingServiceState) {
        withContext(Dispatchers.Main) {
            updateState(reducer)
        }
    }

    override fun onDestroy() {
        serviceScope.launch {
            if (_state.value.tripState != TripRecordingState.STOPPED) {
                updateState {
                    copy(
                        tripState = TripRecordingState.STOPPED,
                        recordingStatus = AppText.get("Stopped"),
                        currentSession = null,
                        canStart = true,
                        canStop = false
                    )
                }
            }
            audioCaptureManager.stop()
            chunkChannel?.close()
            spectrumChannel?.close()
            inferenceJob?.join()
            spectrogramJob?.join()
            timerJob?.cancel()
            settingsJob?.cancel()
            detectionLogger?.close()
            classifier?.close()
            metaModel?.close()
            mediaSession?.release()
            releaseWakeLock()
            serviceJob.cancel()
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_TRIP = "com.example.birdingsoundmvp.action.START_TRIP"
        const val ACTION_PAUSE_TRIP = "com.example.birdingsoundmvp.action.PAUSE_TRIP"
        const val ACTION_RESUME_TRIP = "com.example.birdingsoundmvp.action.RESUME_TRIP"
        const val ACTION_STOP_TRIP = "com.example.birdingsoundmvp.action.STOP_TRIP"
        const val ACTION_QUICK_PRECISE_TRIGGER = "com.example.birdingsoundmvp.action.QUICK_PRECISE_TRIGGER"
        const val ACTION_TEST_NOTIFICATION = "com.example.birdingsoundmvp.action.TEST_NOTIFICATION"
        const val ACTION_PRECISE_SELECTION = "com.example.birdingsoundmvp.action.PRECISE_SELECTION"
        const val EXTRA_SELECTION_START_MS = "selection_start_ms"
        const val EXTRA_SELECTION_END_MS = "selection_end_ms"
        const val EXTRA_FILTERED_CLIP_PATH = "filtered_clip_path"
        const val EXTRA_FILTER_LOW_HZ = "filter_low_hz"
        const val EXTRA_FILTER_HIGH_HZ = "filter_high_hz"
        const val MEDIA_ACTION_END = "com.example.birdingsoundmvp.media.END"
        const val MEDIA_ACTION_QUICK_PRECISE = "com.example.birdingsoundmvp.media.QUICK_PRECISE"

        private const val SPECTROGRAM_DISPLAY_COLUMNS = 320
        private const val QUICK_PRECISE_STEP_MS = 3_000L
        private const val QUICK_PRECISE_MAX_CLICKS = 5
        private const val QUICK_PRECISE_CLICK_WINDOW_MS = 650L
        private const val LIVE_SPECTROGRAM_DELAY_MS = 600L
        private const val CHANNEL_RECORDING = "recording_controls"
        private const val CHANNEL_ALERTS = "bird_detection_alerts"
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val ALERT_NOTIFICATION_BASE_ID = 2000
        private const val PRECISE_NOTIFICATION_BASE_ID = 3000
        private const val TEST_NOTIFICATION_ID = 4000

        private val spectrogramStore = InMemorySpectrogramStore()
        private val _state = MutableStateFlow(RecordingServiceState())
        val state: StateFlow<RecordingServiceState> = _state.asStateFlow()

        fun startIntent(context: Context): Intent {
            return Intent(context, RecordingRecognitionService::class.java)
                .setAction(ACTION_START_TRIP)
        }

        fun pauseIntent(context: Context): Intent {
            return Intent(context, RecordingRecognitionService::class.java).setAction(ACTION_PAUSE_TRIP)
        }

        fun resumeIntent(context: Context): Intent {
            return Intent(context, RecordingRecognitionService::class.java).setAction(ACTION_RESUME_TRIP)
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, RecordingRecognitionService::class.java).setAction(ACTION_STOP_TRIP)
        }

        fun quickPreciseIntent(context: Context): Intent {
            return Intent(context, RecordingRecognitionService::class.java).setAction(ACTION_QUICK_PRECISE_TRIGGER)
        }

        fun testNotificationIntent(context: Context): Intent {
            return Intent(context, RecordingRecognitionService::class.java).setAction(ACTION_TEST_NOTIFICATION)
        }

        fun preciseSelectionIntent(
            context: Context,
            startMs: Long,
            endMs: Long,
            filteredClipPath: String? = null,
            lowFrequencyHz: Int? = null,
            highFrequencyHz: Int? = null
        ): Intent {
            return Intent(context, RecordingRecognitionService::class.java)
                .setAction(ACTION_PRECISE_SELECTION)
                .putExtra(EXTRA_SELECTION_START_MS, startMs)
                .putExtra(EXTRA_SELECTION_END_MS, endMs)
                .apply {
                    if (!filteredClipPath.isNullOrBlank()) putExtra(EXTRA_FILTERED_CLIP_PATH, filteredClipPath)
                    lowFrequencyHz?.let { putExtra(EXTRA_FILTER_LOW_HZ, it) }
                    highFrequencyHz?.let { putExtra(EXTRA_FILTER_HIGH_HZ, it) }
                }
        }

        fun spectrogramWindow(startMs: Long, endMs: Long): List<SpectrogramColumn> {
            return spectrogramStore.window(
                startMs = startMs,
                endMs = endMs,
                maxColumns = SPECTROGRAM_DISPLAY_COLUMNS
            )
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val min = seconds / 60
    val sec = seconds % 60
    return "%02d:%02d".format(min, sec)
}

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val millis = ms % 1000
    return "%02d:%02d.%03d".format(minutes, seconds, millis)
}
