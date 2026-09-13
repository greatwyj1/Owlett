package com.example.birdingsoundmvp.precise

import com.example.birdingsoundmvp.i18n.AppText

import com.example.birdingsoundmvp.audio.AudioChunker
import com.example.birdingsoundmvp.trip.TripAudioSegment
import com.example.birdingsoundmvp.ui.SpectrogramSelectionGeometry
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object BandpassWavClipExporter {
    private const val WAV_HEADER_BYTES = 44L
    private const val CHANNELS = 1
    private const val BITS_PER_SAMPLE = 16
    private const val BYTES_PER_SAMPLE = 2L
    private const val FILTER_Q = 0.70710678

    fun exportSelection(
        segment: TripAudioSegment,
        selectionStartTripAudioMs: Long,
        selectionEndTripAudioMs: Long,
        lowFrequencyHz: Int,
        highFrequencyHz: Int,
        outputFile: File
    ): Result<File> = runCatching {
        val sourceFile = File(segment.filePath)
        require(sourceFile.exists()) { AppText.format("segment file does not exist: {0}", segment.fileName) }
        require(selectionEndTripAudioMs > selectionStartTripAudioMs) { AppText.get("selection is empty") }
        require(highFrequencyHz - lowFrequencyHz >= SpectrogramSelectionGeometry.MIN_FREQUENCY_BAND_HZ) {
            AppText.get("frequency band is too narrow")
        }

        val startInSegmentMs = selectionStartTripAudioMs - segment.tripAudioStartMs
        val durationMs = selectionEndTripAudioMs - selectionStartTripAudioMs
        require(startInSegmentMs >= 0L) { AppText.get("selection starts before segment") }
        require(startInSegmentMs + durationMs <= segment.durationMs) { AppText.get("selection extends beyond segment") }

        val sampleRate = AudioChunker.DEFAULT_SAMPLE_RATE
        val startSample = startInSegmentMs * sampleRate / 1000L
        val sampleCount = durationMs * sampleRate / 1000L
        val startByte = WAV_HEADER_BYTES + startSample * BYTES_PER_SAMPLE
        val dataBytes = sampleCount * BYTES_PER_SAMPLE
        val samples = ShortArray(sampleCount.toInt())

        RandomAccessFile(sourceFile, "r").use { input ->
            input.seek(startByte)
            val bytes = ByteArray(dataBytes.toInt())
            input.readFully(bytes)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (index in samples.indices) {
                samples[index] = buffer.short
            }
        }

        val filtered = filterSamples(samples, sampleRate, lowFrequencyHz, highFrequencyHz)
        outputFile.parentFile?.mkdirs()
        RandomAccessFile(outputFile, "rw").use { output ->
            output.setLength(0)
            writeHeader(output, filtered.size * BYTES_PER_SAMPLE, sampleRate)
            val bytes = ByteBuffer.allocate(filtered.size * BYTES_PER_SAMPLE.toInt()).order(ByteOrder.LITTLE_ENDIAN)
            filtered.forEach { bytes.putShort(it) }
            output.write(bytes.array())
        }
        outputFile
    }

    internal fun filterSamples(
        samples: ShortArray,
        sampleRate: Int,
        lowFrequencyHz: Int,
        highFrequencyHz: Int
    ): ShortArray {
        val visibleMax = SpectrogramSelectionGeometry.MAX_VISIBLE_FREQUENCY_HZ
        val lowHz = lowFrequencyHz.coerceIn(0, visibleMax)
        val highHz = highFrequencyHz.coerceIn(0, visibleMax)
        if (lowHz <= 0 && highHz >= visibleMax) return samples.copyOf()

        var values = DoubleArray(samples.size) { samples[it] / 32768.0 }
        if (lowHz > 0) {
            values = Biquad.highPass(sampleRate, lowHz.toDouble()).process(values)
        }
        if (highHz < sampleRate / 2) {
            values = Biquad.lowPass(sampleRate, highHz.toDouble()).process(values)
        }
        return ShortArray(values.size) { index ->
            (values[index].coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort()
        }
    }

    private class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double
    ) {
        fun process(input: DoubleArray): DoubleArray {
            val output = DoubleArray(input.size)
            var x1 = 0.0
            var x2 = 0.0
            var y1 = 0.0
            var y2 = 0.0
            for (index in input.indices) {
                val x0 = input[index]
                val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                output[index] = y0
                x2 = x1
                x1 = x0
                y2 = y1
                y1 = y0
            }
            return output
        }

        companion object {
            fun lowPass(sampleRate: Int, cutoffHz: Double): Biquad {
                val cutoff = cutoffHz.coerceIn(1.0, sampleRate / 2.0 - 1.0)
                val omega = 2.0 * PI * cutoff / sampleRate
                val alpha = sin(omega) / (2.0 * FILTER_Q)
                val cosOmega = cos(omega)
                val rawB0 = (1.0 - cosOmega) / 2.0
                val rawB1 = 1.0 - cosOmega
                val rawB2 = (1.0 - cosOmega) / 2.0
                val rawA0 = 1.0 + alpha
                val rawA1 = -2.0 * cosOmega
                val rawA2 = 1.0 - alpha
                return normalized(rawB0, rawB1, rawB2, rawA0, rawA1, rawA2)
            }

            fun highPass(sampleRate: Int, cutoffHz: Double): Biquad {
                val cutoff = cutoffHz.coerceIn(1.0, sampleRate / 2.0 - 1.0)
                val omega = 2.0 * PI * cutoff / sampleRate
                val alpha = sin(omega) / (2.0 * FILTER_Q)
                val cosOmega = cos(omega)
                val rawB0 = (1.0 + cosOmega) / 2.0
                val rawB1 = -(1.0 + cosOmega)
                val rawB2 = (1.0 + cosOmega) / 2.0
                val rawA0 = 1.0 + alpha
                val rawA1 = -2.0 * cosOmega
                val rawA2 = 1.0 - alpha
                return normalized(rawB0, rawB1, rawB2, rawA0, rawA1, rawA2)
            }

            private fun normalized(
                rawB0: Double,
                rawB1: Double,
                rawB2: Double,
                rawA0: Double,
                rawA1: Double,
                rawA2: Double
            ): Biquad {
                return Biquad(
                    b0 = rawB0 / rawA0,
                    b1 = rawB1 / rawA0,
                    b2 = rawB2 / rawA0,
                    a1 = rawA1 / rawA0,
                    a2 = rawA2 / rawA0
                )
            }
        }
    }

    private fun writeHeader(output: RandomAccessFile, dataBytes: Long, sampleRate: Int) {
        val byteRate = sampleRate * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
        output.writeAscii("RIFF")
        output.writeIntLE((36L + dataBytes).toInt())
        output.writeAscii("WAVE")
        output.writeAscii("fmt ")
        output.writeIntLE(16)
        output.writeShortLE(1)
        output.writeShortLE(CHANNELS)
        output.writeIntLE(sampleRate)
        output.writeIntLE(byteRate)
        output.writeShortLE(blockAlign)
        output.writeShortLE(BITS_PER_SAMPLE)
        output.writeAscii("data")
        output.writeIntLE(dataBytes.toInt())
    }

    private fun RandomAccessFile.writeAscii(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
    }

    private fun RandomAccessFile.writeShortLE(value: Int) {
        write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
    }
}
