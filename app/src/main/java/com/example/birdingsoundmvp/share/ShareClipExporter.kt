package com.example.birdingsoundmvp.share

import com.example.birdingsoundmvp.i18n.AppText

import com.example.birdingsoundmvp.precise.BandpassWavClipExporter
import com.example.birdingsoundmvp.precise.WavClipExporter
import com.example.birdingsoundmvp.trip.TripAudioSegment
import com.example.birdingsoundmvp.ui.SpectrogramSelection
import java.io.File

object ShareClipExporter {
    fun exportSelectionAudio(
        tripId: String,
        segment: TripAudioSegment,
        selection: SpectrogramSelection,
        outputDir: File,
        filteredClipFile: File? = null
    ): Result<File> = runCatching {
        require(selection.isValid) { AppText.get("selection is invalid") }
        require(selection.endMs > selection.startMs) { AppText.get("selection is empty") }
        outputDir.mkdirs()
        val outputFile = File(
            outputDir,
            "${safeName(tripId)}_${selection.startMs}_${selection.endMs}_${System.currentTimeMillis()}.wav"
        )
        if (selection.hasFrequencyRange) {
            if (filteredClipFile != null && filteredClipFile.exists() && filteredClipFile.isFile) {
                filteredClipFile.copyTo(outputFile, overwrite = true)
                return@runCatching outputFile
            }
            val lowHz = selection.lowFrequencyHz ?: error(AppText.get("missing low frequency"))
            val highHz = selection.highFrequencyHz ?: error(AppText.get("missing high frequency"))
            return@runCatching BandpassWavClipExporter.exportSelection(
                segment = segment,
                selectionStartTripAudioMs = selection.startMs,
                selectionEndTripAudioMs = selection.endMs,
                lowFrequencyHz = lowHz,
                highFrequencyHz = highHz,
                outputFile = outputFile
            ).getOrThrow()
        }
        WavClipExporter.exportSelection(
            segment = segment,
            selectionStartTripAudioMs = selection.startMs,
            selectionEndTripAudioMs = selection.endMs,
            outputFile = outputFile
        ).getOrThrow()
    }

    private fun safeName(value: String): String {
        return value.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "trip" }
    }
}
