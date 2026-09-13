package com.example.birdingsoundmvp.audio

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PlaybackCoordinatorTest {
    @After fun reset() { PlaybackCoordinator.stop(); PlaybackCoordinator.recording = false }

    @Test fun changingOwnerStopsOnlyPreviousPlayback() {
        val first = Any()
        val second = Any()
        var firstStops = 0
        var secondStops = 0
        assertTrue(PlaybackCoordinator.acquire(first) { firstStops++; PlaybackCoordinator.release(first) })
        assertTrue(PlaybackCoordinator.acquire(second) { secondStops++ })
        assertEquals(1, firstStops)
        PlaybackCoordinator.release(first)
        PlaybackCoordinator.stop()
        assertEquals(1, secondStops)
        PlaybackCoordinator.stop()
        assertEquals(1, secondStops)
    }

    @Test fun recordingStopsAndBlocksPlaybackUntilPaused() {
        val owner = Any()
        var stops = 0
        assertTrue(PlaybackCoordinator.acquire(owner) { stops++ })
        PlaybackCoordinator.recording = true
        assertEquals(1, stops)
        assertFalse(PlaybackCoordinator.acquire(Any()) {})
        PlaybackCoordinator.recording = false
        assertTrue(PlaybackCoordinator.acquire(Any()) {})
    }

    @Test fun releasedPlayerIsNeverCalledAgain() {
        val owner = Any()
        assertTrue(PlaybackCoordinator.acquire(owner) { fail("Released player callback") })
        PlaybackCoordinator.release(owner)
        PlaybackCoordinator.stop()
        PlaybackCoordinator.recording = true
    }
}
