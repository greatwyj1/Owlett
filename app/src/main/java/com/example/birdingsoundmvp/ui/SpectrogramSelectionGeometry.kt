package com.example.birdingsoundmvp.ui

import com.example.birdingsoundmvp.i18n.AppText

import kotlin.math.abs
import kotlin.math.roundToInt

enum class SpectrogramInteractionMode(
    val label: String
) {
    SLIDE(AppText.get("滑动")),
    TIME_SELECTION(AppText.get("时间")),
    FREQUENCY_SELECTION(AppText.get("频谱"));

    fun next(): SpectrogramInteractionMode {
        return when (this) {
            SLIDE -> TIME_SELECTION
            TIME_SELECTION -> FREQUENCY_SELECTION
            FREQUENCY_SELECTION -> SLIDE
        }
    }
}

data class SpectrogramFrequencySelectionBounds(
    val startMs: Long,
    val endMs: Long,
    val lowFrequencyHz: Int,
    val highFrequencyHz: Int
) {
    val durationMs: Long
        get() = endMs - startMs

    val frequencyBandHz: Int
        get() = highFrequencyHz - lowFrequencyHz

    val isFrequencyBandValid: Boolean
        get() = frequencyBandHz >= SpectrogramSelectionGeometry.MIN_FREQUENCY_BAND_HZ
}

data class SpectrogramTimelineMarker(
    val startMs: Long,
    val endMs: Long? = null
) {
    val isRange: Boolean
        get() = endMs != null && endMs > startMs
}

object SpectrogramSelectionGeometry {
    const val MAX_VISIBLE_FREQUENCY_HZ = 15_000
    const val MIN_FREQUENCY_BAND_HZ = 100

    fun viewportStartAfterDragDelta(
        currentViewportStartMs: Long,
        dragDeltaPx: Float,
        width: Float,
        viewportDurationMs: Long,
        maxViewportStartMs: Long
    ): Long {
        if (width <= 0f) return currentViewportStartMs.coerceIn(0L, maxViewportStartMs)
        val deltaMs = (dragDeltaPx / width * viewportDurationMs).toLong()
        return (currentViewportStartMs - deltaMs).coerceIn(0L, maxViewportStartMs)
    }

    fun xToTripTime(
        x: Float,
        width: Float,
        displayStartMs: Long,
        displayEndMs: Long
    ): Long {
        val ratio = if (width <= 0f) 0f else (x / width).coerceIn(0f, 1f)
        return displayStartMs + ((displayEndMs - displayStartMs).coerceAtLeast(1L) * ratio).toLong()
    }

    fun yToFrequencyHz(
        y: Float,
        height: Float
    ): Int {
        val ratioFromTop = if (height <= 0f) 0f else (y / height).coerceIn(0f, 1f)
        return (MAX_VISIBLE_FREQUENCY_HZ * (1f - ratioFromTop)).roundToInt().coerceIn(0, MAX_VISIBLE_FREQUENCY_HZ)
    }

    fun normalizeFrequencySelection(
        startX: Float,
        endX: Float,
        startY: Float,
        endY: Float,
        width: Float,
        height: Float,
        displayStartMs: Long,
        displayEndMs: Long,
        maxSelectionDurationMs: Long
    ): SpectrogramFrequencySelectionBounds {
        val rawStartMs = xToTripTime(startX, width, displayStartMs, displayEndMs)
        val rawEndMs = xToTripTime(endX, width, displayStartMs, displayEndMs)
        val clampedEndMs = clampSelectionEnd(rawStartMs, rawEndMs, maxSelectionDurationMs)
        val lowMs = minOf(rawStartMs, clampedEndMs)
        val highMs = maxOf(rawStartMs, clampedEndMs)

        val startHz = yToFrequencyHz(startY, height)
        val endHz = yToFrequencyHz(endY, height)
        return SpectrogramFrequencySelectionBounds(
            startMs = lowMs,
            endMs = highMs,
            lowFrequencyHz = minOf(startHz, endHz),
            highFrequencyHz = maxOf(startHz, endHz)
        )
    }

    fun clampSelectionEnd(startMs: Long, endMs: Long, maxDurationMs: Long): Long {
        val delta = endMs - startMs
        if (abs(delta) <= maxDurationMs) return endMs
        return if (delta >= 0) startMs + maxDurationMs else startMs - maxDurationMs
    }

    fun timelineMarkerFor(
        timeMarkerMs: Long?,
        selection: SpectrogramSelection?
    ): SpectrogramTimelineMarker? {
        timeMarkerMs?.let { return SpectrogramTimelineMarker(startMs = it) }
        return selection?.let {
            SpectrogramTimelineMarker(
                startMs = it.startMs,
                endMs = it.endMs
            )
        }
    }
}
