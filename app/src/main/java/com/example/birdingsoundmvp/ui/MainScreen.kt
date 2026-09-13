package com.example.birdingsoundmvp.ui
import com.example.birdingsoundmvp.ui.OwlettButton as Button
import com.example.birdingsoundmvp.ui.OwlettOutlinedButton as OutlinedButton

import com.example.birdingsoundmvp.i18n.AppText

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.res.painterResource
import com.example.birdingsoundmvp.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.FilledIconButton
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.ui.draw.clip
import com.example.birdingsoundmvp.taxonomy.BirdTaxonomyRepository
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.birdingsoundmvp.birdnet.BirdNetMetaModelStatus
import com.example.birdingsoundmvp.birdnet.BirdNetModelStatus
import com.example.birdingsoundmvp.birdnet.DetectionResult
import com.example.birdingsoundmvp.birdnet.MergedDetectionCard
import com.example.birdingsoundmvp.birdnet.isPreciseSource
import com.example.birdingsoundmvp.audio.SpectrogramColumn
import com.example.birdingsoundmvp.precise.PreciseRecognitionDetection
import com.example.birdingsoundmvp.precise.PreciseRecognitionResult
import com.example.birdingsoundmvp.planning.PlansTripsViewModel
import com.example.birdingsoundmvp.planning.PlansTripsSection
import com.example.birdingsoundmvp.owlett.OwlettViewModel
import com.example.birdingsoundmvp.settings.AppSettings
import com.example.birdingsoundmvp.share.ShareExportType
import com.example.birdingsoundmvp.taxonomy.BirdSpeciesDetail
import com.example.birdingsoundmvp.taxonomy.BirdSpeciesImage
import com.example.birdingsoundmvp.trip.PauseMarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.BitmapFactory
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppTab {
    RECORDING,
    TRIPS,
    OWLETT,
    SETTINGS
}

enum class TripRecordingState {
    STOPPED,
    RECORDING,
    PAUSED
}

data class SpectrogramSelection(
    val startMs: Long,
    val endMs: Long,
    val isValid: Boolean,
    val message: String? = null,
    val lowFrequencyHz: Int? = null,
    val highFrequencyHz: Int? = null
) {
    val durationMs: Long
        get() = endMs - startMs

    val hasFrequencyRange: Boolean
        get() = lowFrequencyHz != null && highFrequencyHz != null

    val selectionKey: String
        get() = listOf(startMs, endMs, lowFrequencyHz ?: -1, highFrequencyHz ?: -1).joinToString(":")
}

data class PreciseRecognitionUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val clipPath: String = "",
    val result: PreciseRecognitionResult? = null,
    val completedAtMs: Long = 0L,
    val selectionStartMs: Long? = null,
    val selectionEndMs: Long? = null
) {
    val results: List<PreciseRecognitionDetection>
        get() = result?.detections.orEmpty()
}

data class SelectionPlaybackUiState(
    val isPlaying: Boolean = false,
    val playbackTripAudioTimeMs: Long? = null,
    val error: String? = null
)

data class QuickPreciseUiState(
    val clickCount: Int = 0,
    val isUploading: Boolean = false,
    val error: String? = null
)

data class FilteredSelectionClipUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val tempFilePath: String = "",
    val selectionKey: String = ""
) {
    fun isReadyFor(selection: SpectrogramSelection?): Boolean {
        return selection != null &&
            selection.hasFrequencyRange &&
            !isLoading &&
            error == null &&
            tempFilePath.isNotBlank() &&
            selectionKey == selection.selectionKey
    }
}

data class ShareUiState(
    val showAnchorButton: Boolean = false,
    val anchorX: Float = 0f,
    val anchorY: Float = 0f,
    val showOptions: Boolean = false,
    val selectedType: ShareExportType = ShareExportType.AUDIO,
    val isProcessing: Boolean = false,
    val progress: Float = 0f,
    val message: String = "",
    val error: String? = null,
    val generatedPath: String = "",
    val generatedMimeType: String = ""
)

data class SpeciesDetailUiState(
    val isVisible: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val requestedName: String = "",
    val detail: BirdSpeciesDetail? = null
)

data class MainUiState(
    val isDataTransferBlocked: Boolean = false,
    val permissionStatus: String = AppText.get("Unknown"),
    val notificationPermissionStatus: String = AppText.get("Unknown"),
    val locationPermissionStatus: String = AppText.get("Not requested"),
    val modelStatus: BirdNetModelStatus = BirdNetModelStatus(false, AppText.get("Not loaded")),
    val metaModelStatus: BirdNetMetaModelStatus = BirdNetMetaModelStatus(false, AppText.get("Not loaded")),
    val checklistStatus: String = AppText.get("Checklist not loaded"),
    val recordingStatus: String = AppText.get("Idle"),
    val elapsedSec: Long = 0L,
    val wallElapsedSec: Long = 0L,
    val wavBytesWritten: Long = 0L,
    val lastAlertStatus: String = AppText.get("No alerts"),
    val locationStatus: String = AppText.get("Location off"),
    val savePath: String = "",
    val summary: String = "",
    val recentDetections: List<MergedDetectionCard> = emptyList(),
    val spectrogramColumns: List<SpectrogramColumn> = emptyList(),
    val pauseMarkers: List<PauseMarker> = emptyList(),
    val viewportStartMs: Long = 0L,
    val viewportDurationMs: Long = 4_000L,
    val spectrogramDurationMs: Long = 0L,
    val autoFollowLive: Boolean = true,
    val selection: SpectrogramSelection? = null,
    val spectrogramTimeMarkerMs: Long? = null,
    val spectrogramInteractionMode: SpectrogramInteractionMode = SpectrogramInteractionMode.SLIDE,
    val selectionPlayback: SelectionPlaybackUiState = SelectionPlaybackUiState(),
    val filteredSelectionClip: FilteredSelectionClipUiState = FilteredSelectionClipUiState(),
    val shareUiState: ShareUiState = ShareUiState(),
    val shareAudioAvailable: Boolean = false,
    val quickPrecise: QuickPreciseUiState = QuickPreciseUiState(),
    val speciesDetail: SpeciesDetailUiState = SpeciesDetailUiState(),
    val preciseRecognition: PreciseRecognitionUiState = PreciseRecognitionUiState(),
    val preciseRecognitionHistory: List<PreciseRecognitionUiState> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val tripState: TripRecordingState = TripRecordingState.STOPPED,
    val canStart: Boolean = true,
    val canStop: Boolean = false
)

@Composable
fun MainScreen(
    state: MainUiState,
    plansTripsViewModel: PlansTripsViewModel,
    owlettViewModel: OwlettViewModel,
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    onRequestLocationPermission: () -> Unit,
    onStartTrip: () -> Unit,
    onPauseTrip: () -> Unit,
    onResumeTrip: () -> Unit,
    onStopTrip: () -> Unit,
    onViewportChanged: (Long) -> Unit,
    onSpectrogramInteractionModeChanged: (SpectrogramInteractionMode) -> Unit,
    onSelectionChanged: (Long, Long) -> Unit,
    onFrequencySelectionChanged: (SpectrogramFrequencySelectionBounds) -> Unit,
    onTimeMarkerChanged: (Long) -> Unit,
    onShareAnchorRequested: (Float, Float) -> Unit,
    onShareAnchorClicked: () -> Unit,
    onShareTypeSelected: (ShareExportType) -> Unit,
    onConfirmShare: () -> Unit,
    onDismissShare: () -> Unit,
    onPlaySelection: () -> Unit,
    onResultSelected: (Long, Long) -> Unit,
    onSpeciesDetailSelected: (String, String) -> Unit,
    onDismissSpeciesDetail: () -> Unit,
    onQuickPreciseClick: () -> Unit,
    onPreciseRecognition: () -> Unit,
    onSettingsChanged: (AppSettings) -> Unit,
    onTestNotification: () -> Unit,
    onReviewTrip: (String) -> Unit,
    isReviewingTrip: Boolean,
    reviewTripId: String?,
    reviewIsLoading: Boolean,
    reviewLoadProgress: Float,
    reviewLoadMessage: String,
    hasUnsavedReviewPreciseResults: Boolean,
    onBackFromReview: () -> Unit,
    onSaveReviewPreciseResults: () -> Unit,
    onDiscardReviewPreciseResults: () -> Unit,
    onReviewClip: (com.example.birdingsoundmvp.owlett.OwlettAudioClip) -> Unit = {}
) {
    val density = LocalDensity.current
    val owlettImeVisible = selectedTab == AppTab.OWLETT && WindowInsets.ime.getBottom(density) > 0
    OwlettTheme(state.settings.appearance, state.settings.colorTheme) {
        if (selectedTab != AppTab.SETTINGS) FirstUseGuide(when (selectedTab) { AppTab.RECORDING -> "recording"; AppTab.TRIPS -> "plans"; else -> "owlett" })
        Surface(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (isReviewingTrip) {
                            if (reviewIsLoading) {
                                ReviewLoadingScreen(
                                    tripId = reviewTripId.orEmpty(),
                                    progress = reviewLoadProgress,
                                    message = reviewLoadMessage,
                                    onBack = onBackFromReview
                                )
                            } else {
                                ReviewScreen(
                                    state = state,
                                    tripId = reviewTripId.orEmpty(),
                                    hasUnsavedReviewPreciseResults = hasUnsavedReviewPreciseResults,
                                    onBack = onBackFromReview,
                                    onSave = onSaveReviewPreciseResults,
                                    onDiscard = onDiscardReviewPreciseResults,
                                    onViewportChanged = onViewportChanged,
                                    onSpectrogramInteractionModeChanged = onSpectrogramInteractionModeChanged,
                                    onSelectionChanged = onSelectionChanged,
                                    onFrequencySelectionChanged = onFrequencySelectionChanged,
                                    onTimeMarkerChanged = onTimeMarkerChanged,
                                    onShareAnchorRequested = onShareAnchorRequested,
                                    onShareAnchorClicked = onShareAnchorClicked,
                                    onPlaySelection = onPlaySelection,
                                    onResultSelected = onResultSelected,
                                    onSpeciesDetailSelected = onSpeciesDetailSelected,
                                    onPreciseRecognition = onPreciseRecognition
                                )
                            }
                        } else {
                            when (selectedTab) {
                            AppTab.RECORDING -> RecordingScreen(
                                state = state,
                                onViewportChanged = onViewportChanged,
                                onSpectrogramInteractionModeChanged = onSpectrogramInteractionModeChanged,
                                onSelectionChanged = onSelectionChanged,
                                onFrequencySelectionChanged = onFrequencySelectionChanged,
                                onTimeMarkerChanged = onTimeMarkerChanged,
                                onShareAnchorRequested = onShareAnchorRequested,
                                onShareAnchorClicked = onShareAnchorClicked,
                                onPlaySelection = onPlaySelection,
                                onResultSelected = onResultSelected,
                                onSpeciesDetailSelected = onSpeciesDetailSelected,
                                onPreciseRecognition = onPreciseRecognition
                            )

                            AppTab.TRIPS -> PlansTripsScreen(
                                viewModel = plansTripsViewModel,
                                onReviewTrip = onReviewTrip,
                                onOpenSpecies = onSpeciesDetailSelected
                            )

                            AppTab.OWLETT -> OwlettScreen(
                                viewModel = owlettViewModel,
                                onOpenSettings = { onTabSelected(AppTab.SETTINGS) },
                                onOpenPlan = { planId ->
                                    plansTripsViewModel.selectSection(PlansTripsSection.PLANS)
                                    plansTripsViewModel.openPlan(planId)
                                    onTabSelected(AppTab.TRIPS)
                                },
                                onOpenSpecies = onSpeciesDetailSelected,
                                onOpenClip = onReviewClip
                            )

                            AppTab.SETTINGS -> SettingsScreen(
                                state = state,
                                owlettViewModel = owlettViewModel,
                                onRequestLocationPermission = onRequestLocationPermission,
                                onSettingsChanged = onSettingsChanged,
                                onTestNotification = onTestNotification,
                                onManageStorage = { plansTripsViewModel.selectSection(PlansTripsSection.TRIPS); onTabSelected(AppTab.TRIPS) }
                            )
                        }
                        }
                    }

                    if (!isReviewingTrip && selectedTab == AppTab.RECORDING) {
                        RecordingBottomControls(
                            state = state,
                            onStartTrip = onStartTrip,
                            onPauseTrip = onPauseTrip,
                            onResumeTrip = onResumeTrip,
                            onStopTrip = onStopTrip,
                            onQuickPreciseClick = onQuickPreciseClick
                        )
                    }

                    if (shouldShowBottomNavigation(selectedTab, isReviewingTrip, owlettImeVisible)) {
                        OwlettBottomNavigation(selectedTab, { tab ->
                            if (tab == AppTab.TRIPS) plansTripsViewModel.refresh()
                            onTabSelected(tab)
                        })
                    }
                }
                ShareOptionsPanel(
                    state = state.shareUiState,
                    onTypeSelected = onShareTypeSelected,
                    onConfirm = onConfirmShare,
                    onDismiss = onDismissShare,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
                SpeciesDetailDialog(
                    state = state.speciesDetail,
                    onDismiss = onDismissSpeciesDetail
                )
            }
        }
    }
}

internal fun shouldShowBottomNavigation(
    selectedTab: AppTab,
    isReviewingTrip: Boolean,
    isImeVisible: Boolean
): Boolean = !isReviewingTrip && !(selectedTab == AppTab.OWLETT && isImeVisible)

@Composable
private fun ShareOptionsPanel(
    state: ShareUiState,
    onTypeSelected: (ShareExportType) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.showOptions && !state.isProcessing && state.error == null) return
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(AppText.get("分享选区"), fontWeight = FontWeight.SemiBold)
            if (state.isProcessing) {
                LinearProgressIndicator(progress = { state.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(state.message.ifBlank { AppText.get("正在生成...") }, style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ShareExportType.AUDIO, ShareExportType.VIDEO).forEach { type ->
                    val selected = state.selectedType == type
                    if (selected) {
                        Button(onClick = { onTypeSelected(type) }) { Text(type.label) }
                    } else {
                        OutlinedButton(onClick = { onTypeSelected(type) }) { Text(type.label) }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(AppText.get("取消")) }
                Button(onClick = onConfirm) { Text(AppText.get("确定")) }
            }
        }
    }
}

@Composable
private fun BottomNavLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 2,
        softWrap = true
    )
}

@Composable
internal fun RecordingScreen(
    state: MainUiState,
    onViewportChanged: (Long) -> Unit,
    onSpectrogramInteractionModeChanged: (SpectrogramInteractionMode) -> Unit,
    onSelectionChanged: (Long, Long) -> Unit,
    onFrequencySelectionChanged: (SpectrogramFrequencySelectionBounds) -> Unit,
    onTimeMarkerChanged: (Long) -> Unit,
    onShareAnchorRequested: (Float, Float) -> Unit,
    onShareAnchorClicked: () -> Unit,
    onPlaySelection: () -> Unit,
    onResultSelected: (Long, Long) -> Unit,
    onSpeciesDetailSelected: (String, String) -> Unit,
    onPreciseRecognition: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        StatusPanel(state = state)

        if (state.settings.showSpectrogram) {
            SpectrogramView(
                columns = state.spectrogramColumns,
                pauseMarkers = state.pauseMarkers,
                viewportStartMs = state.viewportStartMs,
                viewportDurationMs = state.viewportDurationMs,
                totalDurationMs = state.spectrogramDurationMs,
                autoFollowLive = state.autoFollowLive,
                isRecording = state.tripState == TripRecordingState.RECORDING,
                canSelect = state.tripState != TripRecordingState.RECORDING,
                interactionMode = state.spectrogramInteractionMode,
                selection = state.selection,
                timeMarkerMs = state.spectrogramTimeMarkerMs,
                shareUiState = state.shareUiState,
                shareAudioAvailable = state.shareAudioAvailable,
                selectionPlayback = state.selectionPlayback,
                filteredSelectionClip = state.filteredSelectionClip,
                preciseRecognition = state.preciseRecognition,
                maxSelectionDurationMs = (state.settings.maxSelectionDurationSec * 1000f).toLong(),
                onViewportChanged = onViewportChanged,
                onInteractionModeChanged = onSpectrogramInteractionModeChanged,
                onSelectionChanged = onSelectionChanged,
                onFrequencySelectionChanged = onFrequencySelectionChanged,
                onTimeMarkerChanged = onTimeMarkerChanged,
                onShareAnchorRequested = onShareAnchorRequested,
                onShareAnchorClicked = onShareAnchorClicked,
                onPlaySelection = onPlaySelection,
                onPreciseRecognition = onPreciseRecognition
            )
        }

        RecognitionResultsArea(
            state = state,
            onPlaySelection = onPlaySelection,
            onResultSelected = onResultSelected,
            onSpeciesDetailSelected = onSpeciesDetailSelected,
            onPreciseRecognition = onPreciseRecognition,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ReviewLoadingScreen(
    tripId: String,
    progress: Float,
    message: String,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = onBack) {
                Text(AppText.get("Back"))
            }
            Text(AppText.format("Review {0}", tripId), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%  ${message.ifBlank { AppText.get("Loading") }}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ReviewScreen(
    state: MainUiState,
    tripId: String,
    hasUnsavedReviewPreciseResults: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onViewportChanged: (Long) -> Unit,
    onSpectrogramInteractionModeChanged: (SpectrogramInteractionMode) -> Unit,
    onSelectionChanged: (Long, Long) -> Unit,
    onFrequencySelectionChanged: (SpectrogramFrequencySelectionBounds) -> Unit,
    onTimeMarkerChanged: (Long) -> Unit,
    onShareAnchorRequested: (Float, Float) -> Unit,
    onShareAnchorClicked: () -> Unit,
    onPlaySelection: () -> Unit,
    onResultSelected: (Long, Long) -> Unit,
    onSpeciesDetailSelected: (String, String) -> Unit,
    onPreciseRecognition: () -> Unit
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = {
                if (hasUnsavedReviewPreciseResults) showSaveDialog = true else onBack()
            }) {
                Text(AppText.get("Back"))
            }
            Text(AppText.format("Review {0}", tripId), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }

        if (state.settings.showSpectrogram) {
            SpectrogramView(
                columns = state.spectrogramColumns,
                pauseMarkers = state.pauseMarkers,
                viewportStartMs = state.viewportStartMs,
                viewportDurationMs = state.viewportDurationMs,
                totalDurationMs = state.spectrogramDurationMs,
                autoFollowLive = false,
                isRecording = false,
                canSelect = true,
                interactionMode = state.spectrogramInteractionMode,
                selection = state.selection,
                timeMarkerMs = state.spectrogramTimeMarkerMs,
                shareUiState = state.shareUiState,
                shareAudioAvailable = state.shareAudioAvailable,
                selectionPlayback = state.selectionPlayback,
                filteredSelectionClip = state.filteredSelectionClip,
                preciseRecognition = state.preciseRecognition,
                maxSelectionDurationMs = (state.settings.maxSelectionDurationSec * 1000f).toLong(),
                onViewportChanged = onViewportChanged,
                onInteractionModeChanged = onSpectrogramInteractionModeChanged,
                onSelectionChanged = onSelectionChanged,
                onFrequencySelectionChanged = onFrequencySelectionChanged,
                onTimeMarkerChanged = onTimeMarkerChanged,
                onShareAnchorRequested = onShareAnchorRequested,
                onShareAnchorClicked = onShareAnchorClicked,
                onPlaySelection = onPlaySelection,
                onPreciseRecognition = onPreciseRecognition
            )
        }

        RecognitionResultsArea(
            state = state,
            onPlaySelection = onPlaySelection,
            onResultSelected = onResultSelected,
            onSpeciesDetailSelected = onSpeciesDetailSelected,
            onPreciseRecognition = onPreciseRecognition,
            modifier = Modifier.weight(1f)
        )
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text(AppText.get("Save review results?")) },
            text = { Text(AppText.get("Save new precise recognition results to this trip?")) },
            confirmButton = {
                TextButton(onClick = {
                    showSaveDialog = false
                    onSave()
                }) { Text(AppText.get("Save")) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showSaveDialog = false
                        onDiscard()
                    }) { Text(AppText.get("Discard")) }
                    TextButton(onClick = { showSaveDialog = false }) { Text(AppText.get("Cancel")) }
                }
            }
        )
    }
}

@Composable
private fun StatusPanel(state: MainUiState) {
    var showDetails by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp),
        color = MaterialTheme.colorScheme.background
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(AppText.get("录音"), style = MaterialTheme.typography.titleLarge)
                Text(when (state.tripState) { TripRecordingState.RECORDING -> AppText.get("正在录音"); TripRecordingState.PAUSED -> AppText.get("已暂停"); else -> AppText.get("已停止") },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.tripState == TripRecordingState.RECORDING) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
            }
            Text(formatDuration(state.elapsedSec), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { showDetails = true }) { Icon(Icons.Default.Info, contentDescription = AppText.get("详细状态"), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (showDetails) {
        Dialog(onDismissRequest = { showDetails = false }) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(AppText.get("Status"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { showDetails = false }) {
                            Text(AppText.get("Close"))
                        }
                    }
                    FullStatusContent(state)
                }
            }
        }
    }
}

@Composable
private fun FullStatusContent(state: MainUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(AppText.format("Permission: {0}", state.permissionStatus))
            Text(AppText.format("Notifications: {0}", state.notificationPermissionStatus))
            Text(AppText.format("Model: {0}", state.modelStatus.message))
            val input = state.modelStatus.input
            val output = state.modelStatus.output
            if (input != null && output != null) {
                Text(
                    text = AppText.format("Audio {0}: in {1} {2}; out {3} {4}; labels {5}", state.modelStatus.fileName, input.shape, input.dtype, output.shape, output.dtype, state.modelStatus.labelCount),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = AppText.format("Chunk {0}s; hop {1}s", state.modelStatus.chunkDurationSec, state.settings.hopDurationSec),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(AppText.format("Meta: {0}", state.metaModelStatus.message))
            Text(AppText.format("Checklist: {0}", state.checklistStatus))
            Text(AppText.format("Trip audio: {0}; wall: {1}", formatDuration(state.elapsedSec), formatDuration(state.wallElapsedSec)))
            Text(AppText.format("WAV bytes: {0}", state.wavBytesWritten))
            Text(AppText.format("Location: {0}", state.locationStatus))
            Text(AppText.format("Alert: {0}", state.lastAlertStatus))
    }
}

private fun compactLocation(locationStatus: String): String {
    return when {
        locationStatus.startsWith(AppText.get("Location ")) -> locationStatus.removePrefix(AppText.get("Location ")).substringBefore(" (")
        else -> locationStatus
    }
}

@Composable
private fun RecognitionResultsArea(
    state: MainUiState,
    onPlaySelection: () -> Unit,
    onResultSelected: (Long, Long) -> Unit,
    onSpeciesDetailSelected: (String, String) -> Unit,
    onPreciseRecognition: () -> Unit,
    modifier: Modifier = Modifier
    ) {
    val preciseState = state.preciseRecognition
    val hasActivePreciseStatus = preciseState.isLoading || preciseState.error != null
    val feedItems = remember(state.recentDetections, state.preciseRecognitionHistory) {
        buildList {
            state.recentDetections.forEach { detection ->
                add(RecognitionFeedItem.Realtime(detection))
            }
            state.preciseRecognitionHistory.forEach { preciseItem ->
                add(RecognitionFeedItem.Precise(preciseItem))
            }
        }.sortedByDescending { it.sortTimeMs }
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("识别结果", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("${feedItems.size} 条", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (hasActivePreciseStatus) {
            item { PreciseStatusRow(preciseState) }
        }

        items(feedItems) { item ->
            when (item) {
                is RecognitionFeedItem.Precise -> PreciseRecognitionRunRow(
                    state = item.state,
                    canSelectResult = state.tripState == TripRecordingState.PAUSED ||
                        state.tripState == TripRecordingState.STOPPED,
                    onResultSelected = onResultSelected,
                    onSpeciesDetailSelected = onSpeciesDetailSelected
                )

                is RecognitionFeedItem.Realtime -> RealtimeDetectionRow(
                    card = item.card,
                    canSelectResult = state.tripState == TripRecordingState.PAUSED ||
                        state.tripState == TripRecordingState.STOPPED,
                    onResultSelected = onResultSelected,
                    onSpeciesDetailSelected = onSpeciesDetailSelected
                )
            }
        }

        if (feedItems.isEmpty() && !hasActivePreciseStatus) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(AppText.get("No detections yet"))
                }
            }
        }
    }
}

private sealed interface RecognitionFeedItem {
    val sortTimeMs: Long

    data class Realtime(val card: MergedDetectionCard) : RecognitionFeedItem {
        override val sortTimeMs: Long = card.updatedAtMs
    }

    data class Precise(val state: PreciseRecognitionUiState) : RecognitionFeedItem {
        override val sortTimeMs: Long = state.completedAtMs
    }
}

@Composable
internal fun RecordingBottomControls(
    state: MainUiState,
    onStartTrip: () -> Unit,
    onPauseTrip: () -> Unit,
    onResumeTrip: () -> Unit,
    onStopTrip: () -> Unit,
    onQuickPreciseClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    when (state.tripState) {
                        TripRecordingState.STOPPED -> onStartTrip()
                        TripRecordingState.RECORDING -> onPauseTrip()
                        TripRecordingState.PAUSED -> onResumeTrip()
                    }
                },
                enabled = state.tripState != TripRecordingState.STOPPED || state.canStart,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            ) {
                Icon(if (state.tripState == TripRecordingState.RECORDING) Icons.Default.Pause else Icons.Default.Mic,
                    contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(when (state.tripState) {
                    TripRecordingState.STOPPED -> "开始录音"
                    TripRecordingState.RECORDING -> "暂停录音"
                    TripRecordingState.PAUSED -> "继续录音"
                })
            }
            OutlinedButton(onClick = onStopTrip, enabled = state.canStop,
                modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
                Icon(Icons.Default.Stop, null, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("结束")
            }
            Box {
                var more by remember { mutableStateOf(false) }
                IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, "更多录音操作") }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text(quickPreciseLabel(state.quickPrecise)) },
                        enabled = !state.quickPrecise.isUploading, onClick = { more = false; onQuickPreciseClick() })
                }
            }
        }
        if (state.quickPrecise.isUploading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.quickPrecise.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SelectionPanel(
    state: MainUiState,
    onPlaySelection: () -> Unit,
    onPreciseRecognition: () -> Unit
) {
    val selection = state.selection ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!selection.isValid) {
                Text(selection.message ?: AppText.get("Invalid selection"), color = MaterialTheme.colorScheme.error)
            } else {
                Text(AppText.format("Selection {0} - {1} ({2})", formatMs(selection.startMs), formatMs(selection.endMs), formatMs(selection.durationMs)))
                state.selectionPlayback.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onPlaySelection,
                        enabled = state.tripState != TripRecordingState.RECORDING,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (state.selectionPlayback.isPlaying) AppText.get("Stop") else AppText.get("Play"))
                    }
                    Button(
                        onClick = onPreciseRecognition,
                        enabled = state.tripState != TripRecordingState.RECORDING && !state.preciseRecognition.isLoading,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (state.preciseRecognition.isLoading) AppText.get("Uploading...") else AppText.get("Precise Recognition"))
                    }
                }
            }
        }
    }
}

@Composable
private fun PreciseRecognitionPanel(state: PreciseRecognitionUiState) {
    if (!state.isLoading && state.error == null && state.result == null && state.clipPath.isBlank()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(AppText.get("Precise recognition"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (state.isLoading) Text(AppText.get("Loading..."))
            if (state.clipPath.isNotBlank()) Text(AppText.format("Clip: {0}", state.clipPath), style = MaterialTheme.typography.bodySmall)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.result?.let { result ->
                Text(
                    AppText.format("Model: {0}; clip {1}s", result.model.ifBlank { result.analysisParams?.acousticModel.orEmpty() }, "%.2f".format(result.durationSec)),
                    style = MaterialTheme.typography.bodySmall
                )
                result.analysisParams?.let { params ->
                    Text(
                        AppText.format("Params: {0}, window {1}s, overlap {2}s, min {3}, top {4}", params.acousticModel, "%.1f".format(params.windowSec), "%.1f".format(params.overlapSec), "%.2f".format(params.minConfidence), params.topK),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                result.warnings.forEach { warning ->
                    Text(AppText.format("Warning: {0}", warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (result.summary.isNotEmpty()) {
                    Text(AppText.get("Summary"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    result.summary.forEach { summary ->
                        val name = displaySpeciesName(summary.displayNameZh, summary.commonName, summary.scientificName)
                        Text(
                            AppText.format("{0}  max {1}  n={2}  trip {3}-{4}", name, "%.2f".format(summary.maxConfidence), summary.numDetections, formatMs(summary.firstTripAudioStartMs), formatMs(summary.lastTripAudioEndMs)),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            if (state.results.isEmpty() && !state.isLoading && state.error == null) {
                Text(AppText.get("No precise detections returned"))
            }
            if (state.results.isNotEmpty()) {
                Text(AppText.get("Detections"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            state.results.forEach { detection ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val name = displaySpeciesName(detection.displayNameZh, detection.commonName, detection.scientificName)
                    Text(name, fontWeight = FontWeight.SemiBold)
                    Text(
                        AppText.get("%.2f  clip %.2f-%.2fs  trip %s-%s").format(
                            detection.confidence,
                            detection.clipStartSec,
                            detection.clipEndSec,
                            formatMs(detection.tripAudioStartMs),
                            formatMs(detection.tripAudioEndMs)
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: MainUiState,
    owlettViewModel: OwlettViewModel,
    onRequestLocationPermission: () -> Unit,
    onSettingsChanged: (AppSettings) -> Unit,
    onTestNotification: () -> Unit,
    onManageStorage: () -> Unit
) {
    OwlettAppSettingsScreen(state, owlettViewModel, onRequestLocationPermission, onSettingsChanged, onTestNotification,
        onManageStorage = onManageStorage,
        diagnostics = { FullStatusContent(state) })
}


@Composable
internal fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun RealtimeDetectionRow(
    card: MergedDetectionCard,
    canSelectResult: Boolean,
    onResultSelected: (Long, Long) -> Unit,
    onSpeciesDetailSelected: (String, String) -> Unit
) {
    var expanded by remember(card.speciesKey) { mutableStateOf(false) }
    val detection = card.current
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
        .clickable(enabled = canSelectResult) {
            onResultSelected((detection.audioStartSec * 1000.0).toLong(), (detection.audioEndSec * 1000.0).toLong())
        }.padding(horizontal = 12.dp, vertical = 8.dp)) {
        val name = displaySpeciesName(detection.displayNameZh, detection.commonName, detection.scientificName)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            BirdThumbnail(detection.scientificName, detection.commonName) {
                onSpeciesDetailSelected(detection.scientificName, detection.commonName)
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(detection.commonName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("%.0f%%".format(detection.audioConfidence * 100), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Text(if (detection.isPreciseSource()) "精准识别" else "BirdNET", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (card.previousSegments.isNotEmpty()) {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) AppText.get("Hide") else AppText.get("More"))
                }
            }
        }
        Text(
            "${if (detection.isPreciseSource()) AppText.get("Precise") else AppText.get("Realtime")}  %.1f-%.1fs  ${formatClock(detection.timestampMs)}".format(
                detection.audioStartSec,
                detection.audioEndSec
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (expanded) {
            card.previousSegments.forEach { segment ->
                RealtimeSubDetectionRow(
                    detection = segment,
                    canSelectResult = canSelectResult,
                    onResultSelected = onResultSelected
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun RealtimeSubDetectionRow(
    detection: DetectionResult,
    canSelectResult: Boolean,
    onResultSelected: (Long, Long) -> Unit
) {
    val clickModifier = if (canSelectResult) {
        Modifier.clickable {
            onResultSelected(
                (detection.audioStartSec * 1000.0).toLong(),
                (detection.audioEndSec * 1000.0).toLong()
            )
        }
    } else {
        Modifier
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickModifier),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "%.1f-%.1fs".format(detection.audioStartSec, detection.audioEndSec),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            Text("%.2f".format(detection.audioConfidence), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun PreciseRecognitionRunRow(
    state: PreciseRecognitionUiState,
    canSelectResult: Boolean,
    onResultSelected: (Long, Long) -> Unit,
    onSpeciesDetailSelected: (String, String) -> Unit
) {
    val result = state.result
    val resultStartMs = result?.detections?.minOfOrNull { it.tripAudioStartMs }
    val resultEndMs = result?.detections?.maxOfOrNull { it.tripAudioEndMs }
    ResultCard(
        background = MaterialTheme.colorScheme.secondaryContainer,
        onClick = if (canSelectResult && resultStartMs != null && resultEndMs != null && resultEndMs > resultStartMs) {
            { onResultSelected(resultStartMs, resultEndMs) }
        } else {
            null
        }
    ) {
        Text("${AppText.get("Precise recognition")} · ${result?.model.orEmpty()}", fontWeight = FontWeight.SemiBold)
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (result == null) return@ResultCard
        if (result.detections.isEmpty()) {
            Text(AppText.get("No precise detections returned"), style = MaterialTheme.typography.bodySmall)
        } else {
            result.detections.take(5).forEach { detection ->
                val name = displaySpeciesName(detection.displayNameZh, detection.commonName, detection.scientificName)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    BirdThumbnail(detection.scientificName, detection.commonName) {
                        onSpeciesDetailSelected(detection.scientificName, detection.commonName)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(name, style = MaterialTheme.typography.titleSmall)
                        Text(detection.commonName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("%.0f%%".format(detection.confidence * 100), fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    AppText.get("clip %.2f-%.2fs  trip %s-%s").format(
                        detection.clipStartSec,
                        detection.clipEndSec,
                        formatMs(detection.tripAudioStartMs),
                        formatMs(detection.tripAudioEndMs)
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (result.detections.size > 5) {
                Text(AppText.format("+{0} more", result.detections.size - 5), style = MaterialTheme.typography.bodySmall)
            }
        }
        result.warnings.forEach { warning ->
            Text(AppText.format("Warning: {0}", warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PreciseDetectionRow(detection: PreciseRecognitionDetection) {
    ResultCard(background = MaterialTheme.colorScheme.secondaryContainer) {
        val name = displaySpeciesName(detection.displayNameZh, detection.commonName, detection.scientificName)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BirdThumbnail(detection.scientificName, detection.commonName)
            Text(name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
            Text("%.0f%%".format(detection.confidence * 100), fontWeight = FontWeight.Bold)
        }
        Text(
            AppText.get("Precise  clip %.2f-%.2fs  trip %s-%s").format(
                detection.clipStartSec,
                detection.clipEndSec,
                formatMs(detection.tripAudioStartMs),
                formatMs(detection.tripAudioEndMs)
            ),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun PreciseStatusRow(state: PreciseRecognitionUiState) {
    ResultCard(background = MaterialTheme.colorScheme.secondaryContainer) {
        Text(AppText.get("Precise recognition"), fontWeight = FontWeight.SemiBold)
        when {
            state.isLoading -> Text(AppText.get("Uploading..."), style = MaterialTheme.typography.bodySmall)
            state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            state.result != null -> Text(AppText.get("No precise detections returned"), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ResultCard(
    background: Color,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickModifier),
        colors = CardDefaults.cardColors(containerColor = background)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
private fun SpeciesDetailDialog(
    state: SpeciesDetailUiState,
    onDismiss: () -> Unit
) {
    if (!state.isVisible) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when {
                    state.isLoading -> {
                        Text(AppText.format("Loading {0}...", state.requestedName), fontWeight = FontWeight.SemiBold)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    state.error != null -> {
                        Text(state.requestedName.ifBlank { AppText.get("Species detail") }, fontWeight = FontWeight.SemiBold)
                        Text(state.error, color = MaterialTheme.colorScheme.error)
                    }

                    state.detail != null -> SpeciesDetailContent(state.detail)
                }
            }
        }
    }
}

@Composable
private fun SpeciesDetailContent(detail: BirdSpeciesDetail) {
    if (detail.images.isNotEmpty()) {
        SpeciesImageStrip(detail.images)
    } else {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(AppText.get("No image available"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    val title = detail.chineseName.ifBlank {
        detail.zhCnName.ifBlank {
            detail.commonName.ifBlank { detail.scientificName }
        }
    }
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    if (detail.commonName.isNotBlank()) {
        Text(detail.commonName, style = MaterialTheme.typography.bodyMedium)
    }
    Text(detail.scientificName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    detail.ibirding?.let { account ->
        HorizontalDivider()
        DetailRow(AppText.get("中文分类"), account.taxonomyCn)
        DetailRow(AppText.get("English taxonomy"), account.taxonomyEn)
        DetailRow(AppText.get("外形描述"), account.description)
        DetailRow(AppText.get("虹膜"), account.iris)
        DetailRow(AppText.get("嘴"), account.bill)
        DetailRow(AppText.get("脚"), account.feet)
        DetailRow(AppText.get("叫声"), account.voice)
        DetailRow(AppText.get("分布范围"), account.rangeText)
        DetailRow(AppText.get("中国分布状况"), account.chinaDistribution)
        DetailRow(AppText.get("习性"), account.habits)
        DetailRow(AppText.get("别名"), account.aliases)
        DetailRow(AppText.get("图注"), account.plateCaption)
        DetailRow(AppText.get("百科链接"), account.encyclopediaUrl)
        DetailRow(AppText.get("声音链接"), account.soundUrl)
        DetailRow(AppText.get("资料来源"), account.sourceUrl)
    }
}

@Composable
private fun SpeciesImageStrip(images: List<BirdSpeciesImage>) {
    val pagerState = rememberPagerState(pageCount = { images.size })
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            val image = images[page]
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SpeciesImage(
                    image = image,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                )
                Text(
                    image.source.ifBlank { if (image.isLocalAsset) AppText.get("中国鸟类野外手册") else AppText.get("BirdNET taxonomy") },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (images.size > 1) {
            Text(
                "${pagerState.currentPage + 1} / ${images.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun SpeciesImage(
    image: BirdSpeciesImage,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bitmapState = remember(image.url, image.isLocalAsset) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(image.url, image.isLocalAsset) {
        bitmapState.value = withContext(Dispatchers.IO) {
            runCatching {
                val bitmap = if (image.isLocalAsset) {
                    context.assets.open(image.url).use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                } else {
                    URL(image.url).openStream().use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                }
                bitmap?.asImageBitmap()
            }.getOrNull()
        }
    }
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        val bitmap = bitmapState.value
        if (bitmap == null) {
            Box(contentAlignment = Alignment.Center) {
                Text(AppText.get("Loading image"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Image(
                bitmap = bitmap,
                contentDescription = AppText.get("Species image"),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    if (value.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun quickPreciseLabel(state: QuickPreciseUiState): String {
    return when {
        state.isUploading -> AppText.get("uploading")
        state.clickCount > 0 -> state.clickCount.toString()
        else -> AppText.get("快速精准识别")
    }
}

@Composable
private fun quickPreciseColor(state: QuickPreciseUiState): Color {
    if (state.isUploading) return MaterialTheme.colorScheme.tertiary
    val fraction = ((state.clickCount.coerceIn(1, 5) - 1) / 4f).coerceIn(0f, 1f)
    return blend(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.error, fraction)
}

private fun blend(start: Color, end: Color, fraction: Float): Color {
    return Color(
        red = start.red + (end.red - start.red) * fraction,
        green = start.green + (end.green - start.green) * fraction,
        blue = start.blue + (end.blue - start.blue) * fraction,
        alpha = 1f
    )
}

@Composable
private fun DetectionList(
    detections: List<DetectionResult>,
    minimumMetaConfidence: Float,
    modifier: Modifier = Modifier
) {
    if (detections.isEmpty()) {
        Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(AppText.get("No detections yet"))
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(detections) { detection -> DetectionRow(detection, minimumMetaConfidence) }
    }
}

@Composable
private fun DetectionRow(detection: DetectionResult, minimumMetaConfidence: Float) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val name = displaySpeciesName(detection.displayNameZh, detection.commonName, detection.scientificName)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatClock(detection.timestampMs), style = MaterialTheme.typography.bodySmall)
                Text("%.2f".format(detection.audioConfidence), fontWeight = FontWeight.Bold)
            }
            Text(name)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("%.1f-%.1fs".format(detection.audioStartSec, detection.audioEndSec))
                if (detection.isInChecklist) Text(AppText.get("TARGET"), fontWeight = FontWeight.Bold)
                if (detection.locationFiltered) Text(AppText.get("needs review"), fontWeight = FontWeight.Bold)
                if (detection.metaAvailable && detection.metaConfidence != null) {
                    Text(AppText.get("meta %.2f").format(detection.metaConfidence))
                    if (detection.isInChecklist && detection.metaConfidence < minimumMetaConfidence) Text(AppText.get("low local prior"))
                }
                if (detection.alertTriggered) Text(AppText.get("Alerted"), fontWeight = FontWeight.Bold)
            }
            Text(AppText.format("adjusted %.2f; inference {0}ms", detection.inferenceTimeMs).format(detection.adjustedConfidence), style = MaterialTheme.typography.bodySmall)
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

private fun formatClock(timestampMs: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMs))
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.1f GB".format(mb / 1024.0)
}

private fun displaySpeciesName(displayNameZh: String?, commonName: String, scientificName: String): String {
    return displayNameZh?.takeIf { it.isNotBlank() }
        ?: commonName.ifBlank { scientificName.ifBlank { AppText.get("Unknown species") } }
}
