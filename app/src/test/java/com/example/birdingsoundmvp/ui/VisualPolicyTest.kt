package com.example.birdingsoundmvp.ui

import com.example.birdingsoundmvp.settings.AppSettings
import com.example.birdingsoundmvp.settings.ColorTheme
import com.example.birdingsoundmvp.owlett.OwlettSettingsPolicy
import com.example.birdingsoundmvp.share.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class VisualPolicyTest {
    private fun patch(text: String) = JsonParser.parseString(text).asJsonObject
    private val segments = listOf(ShareAudioSegment(0, "original.wav", 0, 4000), ShareAudioSegment(1, "next.wav", 4000, 4000))
    private val state = MainUiState(selection = SpectrogramSelection(1000, 2500, true))

    @Test fun oldSettingsDefaultToGreen() {
        assertEquals("green", Gson().fromJson("""{"appearance":"dark"}""", AppSettings::class.java).colorTheme)
        assertEquals("green", ColorTheme.normalize(null))
        assertEquals("green", ColorTheme.normalize("future-theme"))
    }
    @Test fun allThemesHaveReadableForegroundContrast() {
        ColorTheme.ids.forEach { theme -> listOf(false, true).forEach { dark ->
            val colors = owlettColors(theme, dark)
            listOf(colors.primary to colors.onPrimary, colors.background to colors.onBackground,
                colors.surface to colors.onSurface, colors.surface to colors.onSurfaceVariant).forEach { (back, front) ->
                val low = minOf(back.luminance(), front.luminance())
                val high = maxOf(back.luminance(), front.luminance())
                assertTrue("$theme dark=$dark has unreadable text", (high + .05f) / (low + .05f) >= 4.5f)
            }
        } }
    }
    @Test fun agentColorChangePreservesAppearanceAndNeedsFreshConfirmation() {
        val old = AppSettings(appearance = "dark")
        val before = OwlettSettingsPolicy.snapshot(old)
        val change = patch("""{"colorTheme":"gold"}""")
        val next = OwlettSettingsPolicy.applyConfirmed(old, change, before)!!
        assertEquals("gold", next.colorTheme)
        assertEquals("dark", next.appearance)
        assertEquals(next, OwlettSettingsPolicy.applyConfirmed(next, change, before))
        assertNull(OwlettSettingsPolicy.applyConfirmed(old.copy(colorTheme = "feather"), change, before))
        assertTrue(runCatching { OwlettSettingsPolicy.apply(old, patch("""{"colorTheme":"invalid"}""")) }.isFailure)
    }
    @Test fun oldProposalDoesNotOverwriteNewTheme() {
        val before = OwlettSettingsPolicy.snapshot(AppSettings()).apply { remove("colorTheme") }
        val next = OwlettSettingsPolicy.applyConfirmed(AppSettings(colorTheme = "feather"), patch("""{"appearance":"dark"}"""), before)!!
        assertEquals("feather", next.colorTheme)
        assertEquals("dark", next.appearance)
    }
    @Test fun shareRequiresValidSelectionAndAudio() {
        assertNull(ShareSelectionPolicy.error(state, segments) { true })
        assertNotNull(ShareSelectionPolicy.error(state, segments) { false })
        assertNotNull(ShareSelectionPolicy.error(state.copy(selection = null, spectrogramTimeMarkerMs = 1500), segments) { true })
        assertNotNull(ShareSelectionPolicy.error(state.copy(selection = SpectrogramSelection(3000, 4500, true)), segments) { true })
        assertNotNull(ShareSelectionPolicy.error(state.copy(tripState = TripRecordingState.RECORDING), segments) { true })
    }
    @Test fun shareRejectsBusyLongAndInvalidRanges() {
        assertNotNull(ShareSelectionPolicy.error(state.copy(shareUiState = ShareUiState(isProcessing = true)), segments) { true })
        assertNotNull(ShareSelectionPolicy.error(state.copy(settings = AppSettings(maxSelectionDurationSec = 1f)), segments) { true })
        assertNotNull(ShareSelectionPolicy.error(state.copy(selection = state.selection!!.copy(isValid = false)), segments) { true })
    }
    @Test fun spectralShareWaitsForMatchingFilteredFile() {
        val selection = state.selection!!.copy(lowFrequencyHz = 1000, highFrequencyHz = 8000)
        val spectral = state.copy(selection = selection)
        assertNotNull(ShareSelectionPolicy.error(spectral, segments) { true })
        val ready = spectral.copy(filteredSelectionClip = FilteredSelectionClipUiState(tempFilePath = "filtered.wav", selectionKey = selection.selectionKey))
        assertNull(ShareSelectionPolicy.error(ready, segments) { true })
        assertNotNull(ShareSelectionPolicy.error(ready, segments) { it != "filtered.wav" })
        assertNotNull(ShareSelectionPolicy.error(ready.copy(filteredSelectionClip = ready.filteredSelectionClip.copy(isLoading = true)), segments) { true })
    }
    @Test fun thumbnailsAreBoundedAndDoNotUpscaleSmallSources() {
        assertEquals(1, thumbnailSampleSize(80, 60))
        assertEquals(16, thumbnailSampleSize(4096, 3072))
        assertEquals(1, thumbnailSampleSize(0, 0))
    }
}
