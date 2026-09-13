package com.example.birdingsoundmvp.precise

import com.example.birdingsoundmvp.i18n.AppText

import com.example.birdingsoundmvp.audio.AudioChunker
import com.example.birdingsoundmvp.trip.TripAudioSegment
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavClipExporter {
    private const val WAV_HEADER_BYTES = 44L
    private const val CHANNELS = 1
    private const val BITS_PER_SAMPLE = 16
    private const val BYTES_PER_SAMPLE = 2L

    fun exportSelection(
        segment: TripAudioSegment,
        selectionStartTripAudioMs: Long,
        selectionEndTripAudioMs: Long,
        outputFile: File
    ): Result<File> = runCatching {
        val sourceFile = File(segment.filePath)
        require(sourceFile.exists()) { AppText.format("segment file does not exist: {0}", segment.fileName) }
        require(selectionEndTripAudioMs > selectionStartTripAudioMs) { AppText.get("selection is empty") }

        val startInSegmentMs = selectionStartTripAudioMs - segment.tripAudioStartMs
        val durationMs = selectionEndTripAudioMs - selectionStartTripAudioMs
        require(startInSegmentMs >= 0L) { AppText.get("selection starts before segment") }
        require(startInSegmentMs + durationMs <= segment.durationMs) { AppText.get("selection extends beyond segment") }

        val sampleRate = AudioChunker.DEFAULT_SAMPLE_RATE
        val startSample = startInSegmentMs * sampleRate / 1000L
        val sampleCount = durationMs * sampleRate / 1000L
        val startByte = WAV_HEADER_BYTES + startSample * BYTES_PER_SAMPLE
        val dataBytes = sampleCount * BYTES_PER_SAMPLE

        outputFile.parentFile?.mkdirs()
        RandomAccessFile(sourceFile, "r").use { input ->
            RandomAccessFile(outputFile, "rw").use { output ->
                output.setLength(0)
                writeHeader(output, dataBytes, sampleRate)
                input.seek(startByte)
                copyBytes(input, output, dataBytes)
            }
        }
        outputFile
    }

    private fun copyBytes(input: RandomAccessFile, output: RandomAccessFile, byteCount: Long) {
        val buffer = ByteArray(16 * 1024)
        var remaining = byteCount
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) break
            output.write(buffer, 0, read)
            remaining -= read
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
