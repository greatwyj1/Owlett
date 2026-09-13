package com.example.birdingsoundmvp.audio

data class AudioChunk(
    val samples: ShortArray,
    val sampleRate: Int,
    val startSec: Double,
    val endSec: Double
)

class AudioChunker(
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    chunkDurationSec: Double = DEFAULT_CHUNK_DURATION_SEC.toDouble(),
    hopDurationSec: Double = chunkDurationSec,
    startOffsetSec: Double = 0.0
) {
    private val chunkSamples = (sampleRate * chunkDurationSec).toInt()
    private val hopSamples = (sampleRate * hopDurationSec).toInt().coerceIn(1, chunkSamples)
    private val currentChunk = ShortArray(chunkSamples)
    private var samplesInCurrentChunk = 0
    private val initialStartSample = (sampleRate * startOffsetSec).toLong()
    private var nextChunkStartSample = initialStartSample

    fun append(buffer: ShortArray, readCount: Int): List<AudioChunk> {
        if (readCount <= 0) return emptyList()

        val chunks = mutableListOf<AudioChunk>()
        var inputOffset = 0

        while (inputOffset < readCount) {
            val toCopy = minOf(chunkSamples - samplesInCurrentChunk, readCount - inputOffset)
            buffer.copyInto(
                destination = currentChunk,
                destinationOffset = samplesInCurrentChunk,
                startIndex = inputOffset,
                endIndex = inputOffset + toCopy
            )
            samplesInCurrentChunk += toCopy
            inputOffset += toCopy

            if (samplesInCurrentChunk == chunkSamples) {
                val startSample = nextChunkStartSample
                val endSample = startSample + chunkSamples
                chunks += AudioChunk(
                    samples = currentChunk.copyOf(),
                    sampleRate = sampleRate,
                    startSec = startSample.toDouble() / sampleRate,
                    endSec = endSample.toDouble() / sampleRate
                )
                nextChunkStartSample += hopSamples
                val remaining = chunkSamples - hopSamples
                if (remaining > 0) {
                    currentChunk.copyInto(
                        destination = currentChunk,
                        destinationOffset = 0,
                        startIndex = hopSamples,
                        endIndex = chunkSamples
                    )
                }
                samplesInCurrentChunk = remaining
            }
        }

        return chunks
    }

    fun reset() {
        samplesInCurrentChunk = 0
        nextChunkStartSample = initialStartSample
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val DEFAULT_CHUNK_DURATION_SEC = 3
        const val DEFAULT_CHUNK_SAMPLES = DEFAULT_SAMPLE_RATE * DEFAULT_CHUNK_DURATION_SEC
    }
}
