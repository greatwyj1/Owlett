package com.example.birdingsoundmvp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrogramSelectionGeometryTest {
    @Test
    fun xToTripTimeMapsCanvasEdgesToViewportEdges() {
        assertEquals(10_000L, SpectrogramSelectionGeometry.xToTripTime(0f, 200f, 10_000L, 14_000L))
        assertEquals(14_000L, SpectrogramSelectionGeometry.xToTripTime(200f, 200f, 10_000L, 14_000L))
        assertEquals(12_000L, SpectrogramSelectionGeometry.xToTripTime(100f, 200f, 10_000L, 14_000L))
    }

    @Test
    fun slideDragDeltaMovesViewportByFingerDistance() {
        assertEquals(
            7_000L,
            SpectrogramSelectionGeometry.viewportStartAfterDragDelta(
                currentViewportStartMs = 5_000L,
                dragDeltaPx = -100f,
                width = 200f,
                viewportDurationMs = 4_000L,
                maxViewportStartMs = 20_000L
            )
        )
        assertEquals(
            3_000L,
            SpectrogramSelectionGeometry.viewportStartAfterDragDelta(
                currentViewportStartMs = 5_000L,
                dragDeltaPx = 100f,
                width = 200f,
                viewportDurationMs = 4_000L,
                maxViewportStartMs = 20_000L
            )
        )
    }

    @Test
    fun slideDragDeltaClampsViewportBounds() {
        assertEquals(
            0L,
            SpectrogramSelectionGeometry.viewportStartAfterDragDelta(
                currentViewportStartMs = 100L,
                dragDeltaPx = 100f,
                width = 200f,
                viewportDurationMs = 4_000L,
                maxViewportStartMs = 8_000L
            )
        )
        assertEquals(
            8_000L,
            SpectrogramSelectionGeometry.viewportStartAfterDragDelta(
                currentViewportStartMs = 7_500L,
                dragDeltaPx = -100f,
                width = 200f,
                viewportDurationMs = 4_000L,
                maxViewportStartMs = 8_000L
            )
        )
    }

    @Test
    fun yToFrequencyMapsTopToVisibleMaximumAndBottomToZero() {
        assertEquals(15_000, SpectrogramSelectionGeometry.yToFrequencyHz(0f, 300f))
        assertEquals(0, SpectrogramSelectionGeometry.yToFrequencyHz(300f, 300f))
        assertEquals(7_500, SpectrogramSelectionGeometry.yToFrequencyHz(150f, 300f))
    }

    @Test
    fun normalizeFrequencySelectionSortsReversedDragAxes() {
        val selection = SpectrogramSelectionGeometry.normalizeFrequencySelection(
            startX = 180f,
            endX = 20f,
            startY = 40f,
            endY = 260f,
            width = 200f,
            height = 300f,
            displayStartMs = 10_000L,
            displayEndMs = 14_000L,
            maxSelectionDurationMs = 15_000L
        )

        assertEquals(10_400L, selection.startMs)
        assertEquals(13_600L, selection.endMs)
        assertEquals(2_000, selection.lowFrequencyHz)
        assertEquals(13_000, selection.highFrequencyHz)
        assertTrue(selection.isFrequencyBandValid)
    }

    @Test
    fun normalizeFrequencySelectionRejectsBandsNarrowerThanOneHundredHz() {
        val selection = SpectrogramSelectionGeometry.normalizeFrequencySelection(
            startX = 0f,
            endX = 100f,
            startY = 100f,
            endY = 101f,
            width = 200f,
            height = 300f,
            displayStartMs = 0L,
            displayEndMs = 4_000L,
            maxSelectionDurationMs = 15_000L
        )

        assertFalse(selection.isFrequencyBandValid)
    }

    @Test
    fun timelineMarkerForTimeMarkerCreatesSinglePointMarker() {
        val marker = SpectrogramSelectionGeometry.timelineMarkerFor(
            timeMarkerMs = 12_345L,
            selection = null
        )

        assertEquals(12_345L, marker?.startMs)
        assertNull(marker?.endMs)
        assertFalse(marker?.isRange ?: true)
    }

    @Test
    fun timelineMarkerForSelectionCreatesRangeMarker() {
        val marker = SpectrogramSelectionGeometry.timelineMarkerFor(
            timeMarkerMs = null,
            selection = SpectrogramSelection(
                startMs = 1_000L,
                endMs = 2_500L,
                isValid = true
            )
        )

        assertEquals(1_000L, marker?.startMs)
        assertEquals(2_500L, marker?.endMs)
        assertTrue(marker?.isRange ?: false)
    }

    @Test
    fun timelineMarkerIsNullWhenNothingIsSelectedOrMarked() {
        assertNull(SpectrogramSelectionGeometry.timelineMarkerFor(timeMarkerMs = null, selection = null))
    }
}
