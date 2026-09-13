package com.example.birdingsoundmvp.ui
import com.example.birdingsoundmvp.ui.OwlettButton as Button
import com.example.birdingsoundmvp.ui.OwlettOutlinedButton as OutlinedButton

import com.example.birdingsoundmvp.i18n.AppText

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.birdingsoundmvp.audio.SpectrogramColumn
import com.example.birdingsoundmvp.trip.PauseMarker
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun SpectrogramView(
    columns: List<SpectrogramColumn>,
    pauseMarkers: List<PauseMarker>,
    viewportStartMs: Long,
    viewportDurationMs: Long,
    totalDurationMs: Long,
    autoFollowLive: Boolean,
    isRecording: Boolean,
    canSelect: Boolean,
    interactionMode: SpectrogramInteractionMode,
    selection: SpectrogramSelection?,
    timeMarkerMs: Long?,
    shareUiState: ShareUiState,
    selectionPlayback: SelectionPlaybackUiState,
    filteredSelectionClip: FilteredSelectionClipUiState,
    preciseRecognition: PreciseRecognitionUiState,
    maxSelectionDurationMs: Long,
    onViewportChanged: (Long) -> Unit,
    onInteractionModeChanged: (SpectrogramInteractionMode) -> Unit,
    onSelectionChanged: (Long, Long) -> Unit,
    onFrequencySelectionChanged: (SpectrogramFrequencySelectionBounds) -> Unit,
    onTimeMarkerChanged: (Long) -> Unit,
    onShareAnchorRequested: (Float, Float) -> Unit,
    onShareAnchorClicked: () -> Unit,
    onPlaySelection: () -> Unit,
    onPreciseRecognition: () -> Unit,
    shareAudioAvailable: Boolean = false,
    modifier: Modifier = Modifier
) {
    val maxViewportStart = (totalDurationMs - viewportDurationMs).coerceAtLeast(0L)
    val displayStartMs = viewportStartMs
    val displayEndMs = viewportStartMs + viewportDurationMs
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        SpectrogramCanvas(
            columns = columns,
            pauseMarkers = pauseMarkers,
            viewportStartMs = viewportStartMs,
            viewportDurationMs = viewportDurationMs,
            displayStartMs = displayStartMs,
            displayEndMs = displayEndMs,
            canSelect = canSelect,
            interactionMode = interactionMode,
            selection = selection,
            timeMarkerMs = timeMarkerMs,
            shareUiState = shareUiState,
            canShareSelection = shareAudioAvailable && !shareUiState.isProcessing,
            playbackPositionMs = selectionPlayback.playbackTripAudioTimeMs,
            maxSelectionDurationMs = maxSelectionDurationMs,
            maxViewportStart = maxViewportStart,
            onViewportChanged = onViewportChanged,
            onInteractionModeChanged = onInteractionModeChanged,
            onSelectionChanged = onSelectionChanged,
            onFrequencySelectionChanged = onFrequencySelectionChanged,
            onTimeMarkerChanged = onTimeMarkerChanged,
            onShareAnchorRequested = onShareAnchorRequested,
            onShareAnchorClicked = onShareAnchorClicked,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        TimelineScrubber(
            viewportStartMs = viewportStartMs,
            viewportDurationMs = viewportDurationMs,
            totalDurationMs = totalDurationMs,
            timelineMarker = SpectrogramSelectionGeometry.timelineMarkerFor(timeMarkerMs, selection),
            isRecording = isRecording,
            onViewportChanged = onViewportChanged
        )
        PlaybackControlBar(
            isRecording = isRecording,
            selection = selection,
            selectionPlayback = selectionPlayback,
            filteredSelectionClip = filteredSelectionClip,
            preciseRecognition = preciseRecognition,
            viewportStartMs = viewportStartMs,
            totalDurationMs = totalDurationMs,
            onPlaySelection = onPlaySelection,
            onPreciseRecognition = onPreciseRecognition,
            onShare = onShareAnchorClicked,
            canShare = shareAudioAvailable && !shareUiState.isProcessing
        )
    }
}

@Composable
private fun FrequencyAxisOverlay() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.Start
    ) {
        listOf("15 kHz", "12 kHz", "9 kHz", "6 kHz", "3 kHz", "0 Hz").forEach { label ->
            Text(
                text = label,
                color = Color(0xFFEAEAEA),
                modifier = Modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(2.dp)).padding(horizontal = 2.dp),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun SpectrogramCanvas(
    columns: List<SpectrogramColumn>,
    pauseMarkers: List<PauseMarker>,
    viewportStartMs: Long,
    viewportDurationMs: Long,
    displayStartMs: Long,
    displayEndMs: Long,
    canSelect: Boolean,
    interactionMode: SpectrogramInteractionMode,
    selection: SpectrogramSelection?,
    timeMarkerMs: Long?,
    shareUiState: ShareUiState,
    canShareSelection: Boolean,
    playbackPositionMs: Long?,
    maxSelectionDurationMs: Long,
    maxViewportStart: Long,
    onViewportChanged: (Long) -> Unit,
    onInteractionModeChanged: (SpectrogramInteractionMode) -> Unit,
    onSelectionChanged: (Long, Long) -> Unit,
    onFrequencySelectionChanged: (SpectrogramFrequencySelectionBounds) -> Unit,
    onTimeMarkerChanged: (Long) -> Unit,
    onShareAnchorRequested: (Float, Float) -> Unit,
    onShareAnchorClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    var frequencyDraft by remember { mutableStateOf<SpectrogramFrequencySelectionBounds?>(null) }
    val latestViewportStartMs by rememberUpdatedState(viewportStartMs)
    val latestDisplayStartMs by rememberUpdatedState(displayStartMs)
    val latestDisplayEndMs by rememberUpdatedState(displayEndMs)
    val latestOnViewportChanged by rememberUpdatedState(onViewportChanged)
    val latestOnSelectionChanged by rememberUpdatedState(onSelectionChanged)
    val latestOnFrequencySelectionChanged by rememberUpdatedState(onFrequencySelectionChanged)
    val latestOnTimeMarkerChanged by rememberUpdatedState(onTimeMarkerChanged)
    val latestOnShareAnchorRequested by rememberUpdatedState(onShareAnchorRequested)
    var canvasBounds by remember { mutableStateOf(IntSize.Zero) }
    var shareButtonBounds by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(210.dp)
            .onSizeChanged { canvasBounds = it }
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(
                    canSelect,
                    interactionMode,
                    canShareSelection,
                    viewportDurationMs,
                    maxSelectionDurationMs,
                    maxViewportStart
                ) {
                    if (!canSelect) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val touchSlop = viewConfiguration.touchSlop
                        val startOffset = down.position
                        val gestureDisplayStartMs = latestDisplayStartMs
                        val gestureDisplayEndMs = latestDisplayEndMs
                        val start = SpectrogramSelectionGeometry.xToTripTime(
                            startOffset.x,
                            size.width.toFloat(),
                            gestureDisplayStartMs,
                            gestureDisplayEndMs
                        )
                        var dragViewportStartMs = latestViewportStartMs
                        var lastDragX = startOffset.x
                        var selectionStarted = false
                        frequencyDraft = null

                        fun updateSelectionFromX(x: Float) {
                            val rawEnd = SpectrogramSelectionGeometry.xToTripTime(
                                x,
                                size.width.toFloat(),
                                gestureDisplayStartMs,
                                gestureDisplayEndMs
                            )
                            val clampedEnd = SpectrogramSelectionGeometry.clampSelectionEnd(start, rawEnd, maxSelectionDurationMs)
                            latestOnSelectionChanged(minOf(start, clampedEnd), maxOf(start, clampedEnd))
                        }

                        fun updateViewportFromDelta(deltaX: Float) {
                            if (size.width <= 0 || maxViewportStart <= 0L) return
                            dragViewportStartMs = SpectrogramSelectionGeometry.viewportStartAfterDragDelta(
                                currentViewportStartMs = dragViewportStartMs,
                                dragDeltaPx = deltaX,
                                width = size.width.toFloat(),
                                viewportDurationMs = viewportDurationMs,
                                maxViewportStartMs = maxViewportStart
                            )
                            latestOnViewportChanged(dragViewportStartMs)
                        }

                        fun updateFrequencyDraft(x: Float, y: Float): SpectrogramFrequencySelectionBounds {
                            val next = SpectrogramSelectionGeometry.normalizeFrequencySelection(
                                startX = startOffset.x,
                                endX = x,
                                startY = startOffset.y,
                                endY = y,
                                width = size.width.toFloat(),
                                height = size.height.toFloat(),
                                displayStartMs = gestureDisplayStartMs,
                                displayEndMs = gestureDisplayEndMs,
                                maxSelectionDurationMs = maxSelectionDurationMs
                            )
                            frequencyDraft = next
                            return next
                        }

                        var finalFrequencySelection: SpectrogramFrequencySelectionBounds? = null

                        fun processDragPosition(position: Offset) {
                            when (interactionMode) {
                                SpectrogramInteractionMode.SLIDE -> updateViewportFromDelta(position.x - lastDragX)
                                SpectrogramInteractionMode.TIME_SELECTION -> updateSelectionFromX(position.x)
                                SpectrogramInteractionMode.FREQUENCY_SELECTION -> {
                                    finalFrequencySelection = updateFrequencyDraft(position.x, position.y)
                                }
                            }
                            lastDragX = position.x
                        }

                        var firstDragPosition: Offset? = null
                        var releasedBeforeLongPress = false
                        if (canShareSelection) {
                            val longPressTriggered: Boolean = withTimeoutOrNull<Boolean>(1_500L) {
                                var keepWaiting = true
                                var result = true
                                while (keepWaiting) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null) {
                                        result = false
                                        keepWaiting = false
                                        continue
                                    }
                                    if (!change.pressed) {
                                        releasedBeforeLongPress = true
                                        result = false
                                        keepWaiting = false
                                        continue
                                    }
                                    val delta = change.position - startOffset
                                    if (abs(delta.x) > touchSlop || abs(delta.y) > touchSlop) {
                                        firstDragPosition = change.position
                                        result = false
                                        keepWaiting = false
                                    }
                                }
                                result
                            } ?: true
                            if (longPressTriggered) {
                                latestOnShareAnchorRequested(startOffset.x, startOffset.y)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    change.consume()
                                }
                                return@awaitEachGesture
                            }
                            if (releasedBeforeLongPress) {
                                latestOnTimeMarkerChanged(start)
                                return@awaitEachGesture
                            }
                            firstDragPosition?.let {
                                selectionStarted = true
                                processDragPosition(it)
                            }
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val delta = change.position - startOffset
                            if (!selectionStarted && (abs(delta.x) > touchSlop || abs(delta.y) > touchSlop)) {
                                selectionStarted = true
                            }
                            if (selectionStarted) {
                                processDragPosition(change.position)
                                if (change.positionChanged()) {
                                    change.consume()
                                }
                            }
                        }
                        if (!selectionStarted) {
                            latestOnTimeMarkerChanged(start)
                        } else if (interactionMode == SpectrogramInteractionMode.FREQUENCY_SELECTION) {
                            finalFrequencySelection?.let(latestOnFrequencySelectionChanged)
                            frequencyDraft = null
                        }
                    }
                }
        ) {
            val viewportEndMs = viewportStartMs + viewportDurationMs
            val displayDurationMs = (displayEndMs - displayStartMs).coerceAtLeast(1L)
            drawRect(Color.Black, size = size)
            val gridColor = Color(0x558FC5DA)
            for (i in 1..5) {
                val y = size.height * i / 6f
                drawRect(gridColor, topLeft = Offset(0f, y), size = Size(size.width, 1f))
            }
            val visible = columns.filter { it.tripAudioTimeMs in viewportStartMs..viewportEndMs }
            if (visible.isNotEmpty()) {
                val bins = visible.first().values.size
                val binHeight = size.height / bins.coerceAtLeast(1)
                visible.forEachIndexed { index, column ->
                    val x = ((column.tripAudioTimeMs - displayStartMs).toFloat() / displayDurationMs) * size.width
                    val nextTimeMs = visible.getOrNull(index + 1)?.tripAudioTimeMs ?: minOf(
                        viewportEndMs,
                        column.tripAudioTimeMs + estimateColumnDurationMs(visible)
                    )
                    val nextX = ((nextTimeMs - displayStartMs).toFloat() / displayDurationMs) * size.width
                    val columnWidth = (nextX - x).coerceAtLeast(1f) + 1.5f
                    column.values.forEachIndexed { y, value ->
                        val v = value.coerceIn(0f, 1f)
                        val color = Color(com.example.birdingsoundmvp.audio.SpectrogramPalette.argb(v))
                        drawRect(
                            color = color,
                            topLeft = Offset(x, size.height - (y + 1) * binHeight),
                            size = Size(columnWidth + 1f, binHeight + 1f)
                        )
                    }
                }
            }

            pauseMarkers.forEach { marker ->
                if (marker.tripAudioTimeMs in viewportStartMs..viewportEndMs) {
                    val x = ((marker.tripAudioTimeMs - displayStartMs).toFloat() / displayDurationMs) * size.width
                    drawRect(
                        color = Color(0xFFE53935),
                        topLeft = Offset(x - 1.5f, 0f),
                        size = Size(3f, size.height)
                    )
                }
            }

            timeMarkerMs?.let { markerMs ->
                if (markerMs in viewportStartMs..viewportEndMs) {
                    val x = ((markerMs - displayStartMs).toFloat() / displayDurationMs) * size.width
                    drawRect(
                        color = Color(0xFFFF354F),
                        topLeft = Offset(x - 2f, 0f),
                        size = Size(4f, size.height)
                    )
                }
            }

            selection?.let {
                val start = it.startMs.coerceIn(viewportStartMs, viewportEndMs)
                val end = it.endMs.coerceIn(viewportStartMs, viewportEndMs)
                if (end > start) {
                    val x1 = ((start - displayStartMs).toFloat() / displayDurationMs) * size.width
                    val x2 = ((end - displayStartMs).toFloat() / displayDurationMs) * size.width
                    val top = it.highFrequencyHz?.let { high -> frequencyToY(high, size.height) } ?: 0f
                    val bottom = it.lowFrequencyHz?.let { low -> frequencyToY(low, size.height) } ?: size.height
                    drawRect(
                        color = if (it.isValid) Color(0x552196F3) else Color(0x55FF5252),
                        topLeft = Offset(x1, top.coerceIn(0f, size.height)),
                        size = Size(x2 - x1, (bottom - top).coerceAtLeast(1f))
                    )
                    drawRect(Color.White.copy(alpha = 0.9f), Offset(x1, top.coerceIn(0f, size.height)),
                        Size(x2 - x1, (bottom - top).coerceAtLeast(1f)), style = Stroke(1.5.dp.toPx()))
                }
            }

            frequencyDraft?.let {
                val x1 = ((it.startMs - displayStartMs).toFloat() / displayDurationMs) * size.width
                val x2 = ((it.endMs - displayStartMs).toFloat() / displayDurationMs) * size.width
                val top = frequencyToY(it.highFrequencyHz, size.height).coerceIn(0f, size.height)
                val bottom = frequencyToY(it.lowFrequencyHz, size.height).coerceIn(0f, size.height)
                drawRect(
                    color = if (it.isFrequencyBandValid) Color(0x662196F3) else Color(0x66FF5252),
                    topLeft = Offset(x1, top),
                    size = Size((x2 - x1).coerceAtLeast(1f), (bottom - top).coerceAtLeast(1f))
                )
            }

            playbackPositionMs?.let { playbackMs ->
                if (playbackMs in viewportStartMs..viewportEndMs) {
                    val x = ((playbackMs - displayStartMs).toFloat() / displayDurationMs) * size.width
                    drawRect(
                        color = Color(0xFFFF354F),
                        topLeft = Offset(x - 2f, 0f),
                        size = Size(4f, size.height)
                    )
                }
            }
        }
        FrequencyAxisOverlay()
        if (canShareSelection && shareUiState.showAnchorButton) {
            OutlinedButton(
                onClick = onShareAnchorClicked,
                modifier = Modifier
                    .onSizeChanged { shareButtonBounds = it }
                    .offset {
                        IntOffset(
                            shareUiState.anchorX.roundToInt().coerceIn(0, (canvasBounds.width - shareButtonBounds.width).coerceAtLeast(0)),
                            (shareUiState.anchorY - shareButtonBounds.height).roundToInt().coerceIn(0, (canvasBounds.height - shareButtonBounds.height).coerceAtLeast(0))
                        )
                    }
                    .height(48.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Black.copy(alpha = 0.8f), contentColor = Color.White)
            ) {
                Text(AppText.get("分享"), style = MaterialTheme.typography.labelMedium)
            }
        }
        if (canSelect) {
            OutlinedButton(
                onClick = { onInteractionModeChanged(interactionMode.next()) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .height(48.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Black.copy(alpha = 0.8f), contentColor = Color.White)
            ) {
                Text(interactionMode.label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun TimelineScrubber(
    viewportStartMs: Long,
    viewportDurationMs: Long,
    totalDurationMs: Long,
    timelineMarker: SpectrogramTimelineMarker?,
    isRecording: Boolean,
    onViewportChanged: (Long) -> Unit
) {
    val palette = MaterialTheme.colorScheme
    val maxViewportStart = (totalDurationMs - viewportDurationMs).coerceAtLeast(0L)
    var dragStart by remember(viewportStartMs, maxViewportStart) {
        mutableStateOf(viewportStartMs.coerceIn(0L, maxViewportStart))
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(44.dp)
            .background(palette.background)
            .pointerInput(isRecording, maxViewportStart) {
                if (isRecording || maxViewportStart <= 0L) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun updateFromX(x: Float) {
                        val ratio = (x / size.width).coerceIn(0f, 1f)
                        val next = (maxViewportStart * ratio).toLong()
                        dragStart = next
                        onViewportChanged(next)
                    }
                    updateFromX(down.position.x)
                    drag(down.id) { change ->
                        updateFromX(change.position.x)
                        if (change.positionChanged()) change.consume()
                    }
                }
            }
    ) {
        val start = if (isRecording) maxViewportStart else dragStart
        val ratio = if (maxViewportStart <= 0L) 1f else (start.toFloat() / maxViewportStart).coerceIn(0f, 1f)
        val thumbX = size.width * ratio
        val trackY = 10.dp.toPx()
        drawRect(palette.outlineVariant, topLeft = Offset(0f, trackY - 2f), size = Size(size.width, 4f))
        drawRect(palette.primary, topLeft = Offset(0f, trackY - 2f), size = Size(thumbX, 4f))
        drawCircle(palette.primary, radius = 7f, center = Offset(thumbX, trackY))
        timelineMarker?.let { marker ->
            val duration = totalDurationMs.coerceAtLeast(1L)
            fun xForTime(timeMs: Long): Float {
                val ratio = (timeMs.toFloat() / duration).coerceIn(0f, 1f)
                return size.width * ratio
            }

            fun drawTriangle(x: Float) {
                val path = Path().apply {
                    moveTo(x, trackY + 2f)
                    lineTo(x - 7f, trackY + 13f)
                    lineTo(x + 7f, trackY + 13f)
                    close()
                }
                drawPath(path = path, color = Color(0xFFFF354F))
            }

            val markerStartX = xForTime(marker.startMs)
            val markerEndX = marker.endMs?.let(::xForTime)
            if (marker.isRange && markerEndX != null) {
                val lineStart = minOf(markerStartX, markerEndX)
                val lineEnd = maxOf(markerStartX, markerEndX)
                drawRect(
                    color = Color(0xFFFF354F),
                    topLeft = Offset(lineStart, trackY + 8f),
                    size = Size((lineEnd - lineStart).coerceAtLeast(1f), 3f)
                )
                drawTriangle(markerStartX)
                drawTriangle(markerEndX)
            } else {
                drawTriangle(markerStartX)
            }
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.onSurfaceVariant.toArgb()
                textSize = 11.sp.toPx()
                textAlign = Paint.Align.CENTER
            }
        val label = formatCompactMs(start)
        val halfText = (textPaint.measureText(label) / 2f).coerceAtMost(size.width / 2f)
        drawContext.canvas.nativeCanvas.drawText(label, thumbX.coerceIn(halfText, size.width - halfText),
            size.height - 3.dp.toPx(), textPaint)
    }
}

@Composable
internal fun PlaybackControlBar(
    isRecording: Boolean,
    selection: SpectrogramSelection?,
    selectionPlayback: SelectionPlaybackUiState,
    filteredSelectionClip: FilteredSelectionClipUiState,
    preciseRecognition: PreciseRecognitionUiState,
    viewportStartMs: Long,
    totalDurationMs: Long,
    onPlaySelection: () -> Unit,
    onPreciseRecognition: () -> Unit,
    onShare: () -> Unit = {},
    canShare: Boolean = false
) {
    val validSelection = selection?.takeIf { it.isValid }
    val frequencySuffix = validSelection
        ?.let(::formatFrequencyBand)
        ?.takeIf { it.isNotBlank() }
        ?.let { " $it" }
        .orEmpty()
    val timeText = when {
        selection?.isValid == false -> selection.message ?: AppText.get("Selection invalid")
        validSelection != null && validSelection.hasFrequencyRange && filteredSelectionClip.isLoading ->
            AppText.format("Filtering {0}-{1}{2}", formatCompactMs(validSelection.startMs), formatCompactMs(validSelection.endMs), frequencySuffix)
        validSelection != null && validSelection.hasFrequencyRange && filteredSelectionClip.error != null ->
            filteredSelectionClip.error
        validSelection != null -> "${formatCompactMs(validSelection.startMs)}-${formatCompactMs(validSelection.endMs)} / ${formatCompactMs(validSelection.durationMs)}$frequencySuffix"
        isRecording -> formatCompactMs(totalDurationMs)
        selectionPlayback.playbackTripAudioTimeMs != null -> "${formatCompactMs(selectionPlayback.playbackTripAudioTimeMs)} / ${formatCompactMs(totalDurationMs)}"
        else -> "${formatCompactMs(viewportStartMs)} / ${formatCompactMs(totalDurationMs)}"
    }
    val selectionAudioReady = validSelection?.hasFrequencyRange != true || filteredSelectionClip.isReadyFor(validSelection)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = timeText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onPlaySelection,
                enabled = !isRecording && totalDurationMs > viewportStartMs && selectionAudioReady,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                Icon(if (selectionPlayback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (selectionPlayback.isPlaying) "暂停" else "播放")
            }
            Button(
                onClick = onPreciseRecognition,
                enabled = !isRecording && validSelection != null && selectionAudioReady && !preciseRecognition.isLoading,
                modifier = Modifier.weight(1.3f).heightIn(min = 48.dp), shape = RoundedCornerShape(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Outlined.GraphicEq, null, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (preciseRecognition.isLoading) AppText.get("Uploading") else AppText.get("Precise"))
            }
            OutlinedIconButton(onClick = onShare, enabled = canShare, modifier = Modifier.size(48.dp), shape = RoundedCornerShape(8.dp)) {
                Icon(Icons.Outlined.Share, "分享选区")
            }
            }
        }
    }
}

private fun estimateColumnDurationMs(columns: List<SpectrogramColumn>): Long {
    if (columns.size < 2) return 12L
    return (columns.last().tripAudioTimeMs - columns.first().tripAudioTimeMs)
        .div((columns.size - 1).coerceAtLeast(1))
        .coerceAtLeast(1L)
}

private fun frequencyToY(frequencyHz: Int, height: Float): Float {
    val ratio = 1f - (frequencyHz.toFloat() / SpectrogramSelectionGeometry.MAX_VISIBLE_FREQUENCY_HZ).coerceIn(0f, 1f)
    return height * ratio
}

private fun formatFrequencyBand(selection: SpectrogramSelection): String {
    val low = selection.lowFrequencyHz ?: return ""
    val high = selection.highFrequencyHz ?: return ""
    return "${formatHz(low)}-${formatHz(high)}"
}

private fun formatHz(value: Int): String {
    return if (value >= 1000) {
        "%.1fkHz".format(value / 1000f)
    } else {
        "${value}Hz"
    }
}

private fun formatCompactMs(ms: Long?): String {
    val value = (ms ?: 0L).coerceAtLeast(0L)
    val totalSeconds = value / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val tenths = (value % 1000L) / 100L
    return "%02d:%02d.%d".format(minutes, seconds, tenths)
}
