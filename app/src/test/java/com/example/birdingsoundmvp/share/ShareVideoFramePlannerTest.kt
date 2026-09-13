package com.example.birdingsoundmvp.share

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareVideoFramePlannerTest {
    @Test
    fun centeredWindowIsFourSecondsAroundSelectionCenter() {
        val window = ShareVideoFramePlanner.centeredSpectrogramWindow(
            selectionStartMs = 5_000L,
            selectionEndMs = 6_000L,
            availableStartMs = 0L,
            availableEndMs = 12_000L
        )

        assertEquals(3_500L, window.startMs)
        assertEquals(7_500L, window.endMs)
        assertEquals(4_000L, window.durationMs)
        assertEquals(0L, window.missingLeftMs)
        assertEquals(0L, window.missingRightMs)
    }

    @Test
    fun centeredWindowKeepsBlackPaddingWhenAudioCannotFillFourSeconds() {
        val window = ShareVideoFramePlanner.centeredSpectrogramWindow(
            selectionStartMs = 200L,
            selectionEndMs = 400L,
            availableStartMs = 0L,
            availableEndMs = 1_500L
        )

        assertEquals(-1_700L, window.startMs)
        assertEquals(2_300L, window.endMs)
        assertEquals(1_700L, window.missingLeftMs)
        assertEquals(800L, window.missingRightMs)
    }
}
