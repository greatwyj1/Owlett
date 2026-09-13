package com.example.birdingsoundmvp.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFileWriter(
    private val file: File,
    private val sampleRate: Int = AudioChunker.DEFAULT_SAMPLE_RATE,
    private val channels: Int = 1,
    private val bitsPerSample: Int = 16
) {
    private var raf: RandomAccessFile? = null
    private var dataBytesWritten = 0L

    fun open() {
        file.parentFile?.mkdirs()
        raf = RandomAccessFile(file, "rw").apply {
            setLength(0)
            write(ByteArray(WAV_HEADER_BYTES))
        }
        dataBytesWritten = 0L
    }

    fun writePcm(buffer: ShortArray, readCount: Int) {
        val writer = raf ?: return
        if (readCount <= 0) return

        val byteBuffer = ByteBuffer
            .allocate(readCount * BYTES_PER_SAMPLE)
            .order(ByteOrder.LITTLE_ENDIAN)
        repeat(readCount) { index ->
            byteBuffer.putShort(buffer[index])
        }
        writer.write(byteBuffer.array())
        dataBytesWritten += readCount * BYTES_PER_SAMPLE
    }

    fun close() {
        val writer = raf ?: return
        writeHeader(writer)
        writer.close()
        raf = null
    }

    fun bytesWritten(): Long = dataBytesWritten

    private fun writeHeader(writer: RandomAccessFile) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val riffChunkSize = 36L + dataBytesWritten

        writer.seek(0)
        writer.writeAscii("RIFF")
        writer.writeIntLE(riffChunkSize.toInt())
        writer.writeAscii("WAVE")
        writer.writeAscii("fmt ")
        writer.writeIntLE(16)
        writer.writeShortLE(1)
        writer.writeShortLE(channels)
        writer.writeIntLE(sampleRate)
        writer.writeIntLE(byteRate)
        writer.writeShortLE(blockAlign)
        writer.writeShortLE(bitsPerSample)
        writer.writeAscii("data")
        writer.writeIntLE(dataBytesWritten.toInt())
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

    companion object {
        private const val WAV_HEADER_BYTES = 44
        private const val BYTES_PER_SAMPLE = 2
    }
}
