package com.example.birdingsoundmvp.owlett

import com.example.birdingsoundmvp.audio.SpectrogramPalette
import com.example.birdingsoundmvp.birdnet.DetectionResult
import com.example.birdingsoundmvp.settings.AppSettings
import com.example.birdingsoundmvp.trip.*
import com.example.birdingsoundmvp.transfer.TransferValidation
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class OwlettReleaseLogicTest {
    private fun detection(start: Double, end: Double, score: Float, source: String = "realtime") =
        DetectionResult("trip", 1, start, end, "Cuculus canorus", "Common Cuckoo", "大杜鹃",
            score, null, 0.01f, false, false, false, false, 1, source)
    private fun segment(index: Int, start: Long, duration: Long) = TripAudioSegment(index, "test.wav", "/unused/test.wav", start, duration, 0, null)

    @Test fun scoreOperatorsUseAudioScoreAndKeepPauseBoundaries() {
        val detections = listOf(detection(0.0, 2.0, .3f), detection(2.0, 4.0, .7f),
            detection(3.0, 5.0, .8f, "precise"), detection(5.0, 7.0, .9f))
        val clips = OwlettTripClips.collect("trip", "Cuculus canorus", detections, listOf(segment(0, 0, 5000), segment(1, 5000, 5000)), .3f, true)
        assertEquals(2, clips.size)
        assertEquals(1500L, clips[0].startMs)
        assertEquals(5000L, clips[0].endMs)
        assertEquals(5000L, clips[1].startMs)
        assertEquals(.8f, clips[0].confidence)
        assertEquals(setOf("精准识别", "实时识别"), clips[0].sources.toSet())
        val inclusive = OwlettTripClips.collect("trip", "Cuculus canorus", detections, listOf(segment(0, 0, 5000)), .3f, false)
        assertEquals(0L, inclusive.single().startMs)
    }

    @Test fun thresholdIsExplicit() {
        assertEquals(.3f, OwlettTripClips.threshold("30%"))
        assertEquals(.3f, OwlettTripClips.threshold("0.3"))
        assertNull(OwlettTripClips.threshold(null))
        assertTrue(runCatching { OwlettTripClips.threshold("3") }.isFailure)
        assertTrue(runCatching { OwlettTripClips.threshold("NaN") }.isFailure)
    }

    @Test fun morningUsesLocalOverlapAndCurrentYear() {
        val zone = ZoneId.of("Asia/Shanghai")
        fun time(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
        val trip = TripHistoryItem("trip", "", 0, 1, 1, true, time("2026-09-06T05:30"), time("2026-09-06T06:30"))
        assertTrue(OwlettTripClips.matchesDate(trip, "9.6", "上午", LocalDate.of(2026, 9, 7), zone))
        assertFalse(OwlettTripClips.matchesDate(trip, "9.6", "下午", LocalDate.of(2026, 9, 7), zone))
        assertFalse(OwlettTripClips.matchesDate(trip, "9.6", "上午", LocalDate.of(2025, 9, 7), zone))
    }

    @Test fun promptSnapshotIsImmutableAndMalformedConfigIsSafe() {
        val original = OwlettTurnConfig.from(AppSettings(owlettSystemPrompt = "Test persona", owlettAutomationMode = "automatic"))
        assertEquals(original, OwlettTurnConfig.decode(original.toJson()))
        assertFalse(OwlettTurnConfig.decode("""{"prompt":null,"automationMode":null}""").automatic)
        assertTrue(OwlettTurnConfig.decode("invalid").effectivePrompt.isNotBlank())
        val messages = OwlettContextBuilder.build(emptyList(), turnConfig = original)
        assertTrue(messages.first().content.contains("Test persona"))
        assertTrue(messages.first().content.contains(OwlettContextBuilder.OPERATION_RULES))
    }

    @Test fun migrationRejectsTraversalAndExcludesCredentials() {
        listOf("../key", "/private/key", "trips/../../key.wav", "trips/a/../x.wav", "trips/a/key.pem", "trips/a//b.wav", "trips/a\\b.wav").forEach {
            assertFalse(it, TransferValidation.safePath(it))
        }
        assertTrue(TransferValidation.safePath("trips/recording/segments/segment_000.wav"))
        val safe = TransferValidation.sanitized(AppSettings(ebirdApiKey = "test-key", preciseRecognitionAuthToken = "test-token", owlettAutomationMode = "automatic"))
        assertEquals("", safe.ebirdApiKey); assertEquals("", safe.preciseRecognitionAuthToken)
        assertEquals("confirm_writes", safe.owlettAutomationMode)
    }

    @Test fun infernoIsSharedOpaqueClampedAndHasWarmPeak() {
        assertEquals(0xFF000004.toInt(), SpectrogramPalette.argb(0f))
        assertEquals(0xFFFCFFA4.toInt(), SpectrogramPalette.argb(1f))
        assertEquals(SpectrogramPalette.argb(0f), SpectrogramPalette.argb(Float.NaN))
        assertEquals(SpectrogramPalette.argb(1f), SpectrogramPalette.argb(2f))
    }
}
