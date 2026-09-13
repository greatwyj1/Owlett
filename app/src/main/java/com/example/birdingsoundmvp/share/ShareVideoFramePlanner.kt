package com.example.birdingsoundmvp.share

import com.example.birdingsoundmvp.i18n.AppText

object ShareVideoFramePlanner {
    const val SPECTROGRAM_WINDOW_DURATION_MS = 4_000L

    data class SpectrogramWindow(
        val startMs: Long,
        val endMs: Long,
        val missingLeftMs: Long,
        val missingRightMs: Long
    ) {
        val durationMs: Long
            get() = endMs - startMs
    }

    fun centeredSpectrogramWindow(
        selectionStartMs: Long,
        selectionEndMs: Long,
        availableStartMs: Long,
        availableEndMs: Long
    ): SpectrogramWindow {
        require(selectionEndMs >= selectionStartMs) { AppText.get("selection end must not be before start") }
        val centerMs = selectionStartMs + (selectionEndMs - selectionStartMs) / 2L
        val startMs = centerMs - SPECTROGRAM_WINDOW_DURATION_MS / 2L
        val endMs = startMs + SPECTROGRAM_WINDOW_DURATION_MS
        return SpectrogramWindow(
            startMs = startMs,
            endMs = endMs,
            missingLeftMs = (availableStartMs - startMs).coerceAtLeast(0L),
            missingRightMs = (endMs - availableEndMs).coerceAtLeast(0L)
        )
    }
}
