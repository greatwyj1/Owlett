package com.example.birdingsoundmvp.precise

import com.example.birdingsoundmvp.audio.AudioChunker
import com.example.birdingsoundmvp.trip.TripAudioSegment
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BandpassWavClipExporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun exportSelectionKeepsHighBandAndPreservesWavShape() {
        val source = writeSyntheticWav("source_high.wav")
        val output = temporaryFolder.newFile("high.wav")
        val segment = segmentFor(source)

        val result = BandpassWavClipExporter.exportSelection(
            segment = segment,
            selectionStartTripAudioMs = 0L,
            selectionEndTripAudioMs = 1_000L,
            lowFrequencyHz = 3_000,
            highFrequencyHz = 7_000,
            outputFile = output
        )

        assertTrue(result.isSuccess)
        assertEquals(AudioChunker.DEFAULT_SAMPLE_RATE, readSampleRate(output))
        assertEquals(AudioChunker.DEFAULT_SAMPLE_RATE, readSamples(output).size)
        val samples = readSamples(output)
        assertTrue(toneEnergy(samples, 5_000.0) > toneEnergy(samples, 500.0) * 4.0)
    }

    @Test
    fun exportSelectionKeepsLowBandAndPreservesWavShape() {
        val source = writeSyntheticWav("source_low.wav")
        val output = temporaryFolder.newFile("low.wav")
        val segment = segmentFor(source)

        val result = BandpassWavClipExporter.exportSelection(
            segment = segment,
            selectionStartTripAudioMs = 0L,
            selectionEndTripAudioMs = 1_000L,
            lowFrequencyHz = 100,
            highFrequencyHz = 1_000,
            outputFile = output
        )

        assertTrue(result.isSuccess)
        assertEquals(AudioChunker.DEFAULT_SAMPLE_RATE, readSampleRate(output))
        assertEquals(AudioChunker.DEFAULT_SAMPLE_RATE, readSamples(output).size)
        val samples = readSamples(output)
        assertTrue(toneEnergy(samples, 500.0) > toneEnergy(samples, 5_000.0) * 4.0)
    }

    private fun writeSyntheticWav(name: String): File {
        val file = temporaryFolder.newFile(name)
        val sampleRate = AudioChunker.DEFAULT_SAMPLE_RATE
        val samples = ShortArray(sampleRate) { index ->
            val t = index.toDouble() / sampleRate
            val value = 0.45 * sin(2.0 * PI * 500.0 * t) + 0.45 * sin(2.0 * PI * 5_000.0 * t)
            (value * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            writeHeader(raf, samples.size * 2L, sampleRate)
            val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { bytes.putShort(it) }
            raf.write(bytes.array())
        }
        return file
    }

    private fun segmentFor(file: File): TripAudioSegment {
        return TripAudioSegment(
            index = 0,
            fileName = file.name,
            filePath = file.absolutePath,
            tripAudioStartMs = 0L,
            durationMs = 1_000L,
            wallStartMs = 0L,
            wallEndMs = 1_000L
        )
    }

    private fun readSampleRate(file: File): Int {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(24L)
            val bytes = ByteArray(4)
            raf.readFully(bytes)
            return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
        }
    }

    private fun readSamples(file: File): ShortArray {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(44L)
            val bytes = ByteArray((raf.length() - 44L).toInt())
            raf.readFully(bytes)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return ShortArray(bytes.size / 2) { buffer.short }
        }
    }

    private fun toneEnergy(samples: ShortArray, frequencyHz: Double): Double {
        val sampleRate = AudioChunker.DEFAULT_SAMPLE_RATE.toDouble()
        var real = 0.0
        var imag = 0.0
        samples.forEachIndexed { index, sample ->
            val angle = 2.0 * PI * frequencyHz * index / sampleRate
            real += sample * cos(angle)
            imag -= sample * sin(angle)
        }
        return real * real + imag * imag
    }

    private fun writeHeader(output: RandomAccessFile, dataBytes: Long, sampleRate: Int) {
        output.write("RIFF".toByteArray(Charsets.US_ASCII))
        output.writeIntLE((36L + dataBytes).toInt())
        output.write("WAVE".toByteArray(Charsets.US_ASCII))
        output.write("fmt ".toByteArray(Charsets.US_ASCII))
        output.writeIntLE(16)
        output.writeShortLE(1)
        output.writeShortLE(1)
        output.writeIntLE(sampleRate)
        output.writeIntLE(sampleRate * 2)
        output.writeShortLE(2)
        output.writeShortLE(16)
        output.write("data".toByteArray(Charsets.US_ASCII))
        output.writeIntLE(dataBytes.toInt())
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
    }

    private fun RandomAccessFile.writeShortLE(value: Int) {
        write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
    }
}
