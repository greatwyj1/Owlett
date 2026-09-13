package com.example.birdingsoundmvp.birdnet

import com.example.birdingsoundmvp.i18n.AppText

import com.example.birdingsoundmvp.audio.AudioChunker
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Tensor
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class NormalizationMode {
    PCM_16BIT_TO_UNIT,
    PER_CHUNK_MIN_MAX
}

data class BirdNetPreprocessorConfig(
    val sampleRate: Int = AudioChunker.DEFAULT_SAMPLE_RATE,
    val chunkLengthSec: Double = AudioChunker.DEFAULT_CHUNK_DURATION_SEC.toDouble(),
    val hopLengthSec: Double = AudioChunker.DEFAULT_CHUNK_DURATION_SEC.toDouble(),
    val normalizationMode: NormalizationMode = NormalizationMode.PCM_16BIT_TO_UNIT
) {
    val expectedSamples: Int = (sampleRate * chunkLengthSec).toInt()
}

class BirdNetPreprocessor(
    val config: BirdNetPreprocessorConfig = BirdNetPreprocessorConfig()
) {
    fun prepareInput(samples: ShortArray, inputTensor: Tensor): ByteBuffer {
        val waveform = prepareWaveform(samples)
        return waveformToTensorBuffer(waveform, inputTensor)
    }

    fun prepareInput(samples: FloatArray, inputTensor: Tensor): ByteBuffer {
        val waveform = prepareWaveform(samples)
        return waveformToTensorBuffer(waveform, inputTensor)
    }

    fun prepareWaveform(samples: ShortArray): FloatArray {
        val raw = FloatArray(samples.size) { index ->
            samples[index].toFloat() / 32768.0f
        }
        return normalizeAndFit(raw)
    }

    fun prepareWaveform(samples: FloatArray): FloatArray {
        return normalizeAndFit(samples.copyOf())
    }

    private fun normalizeAndFit(samples: FloatArray): FloatArray {
        val normalized = when (config.normalizationMode) {
            NormalizationMode.PCM_16BIT_TO_UNIT -> samples.mapToUnitRange()
            NormalizationMode.PER_CHUNK_MIN_MAX -> samples.minMaxNormalize()
        }

        return FloatArray(config.expectedSamples) { index ->
            if (index < normalized.size) normalized[index] else 0f
        }
    }

    private fun FloatArray.mapToUnitRange(): FloatArray {
        return FloatArray(size) { index -> this[index].coerceIn(-1f, 1f) }
    }

    private fun FloatArray.minMaxNormalize(): FloatArray {
        if (isEmpty()) return this
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        forEach { value ->
            if (value < min) min = value
            if (value > max) max = value
        }
        val range = max - min
        if (range <= 1e-6f) return FloatArray(size)
        return FloatArray(size) { index ->
            (((this[index] - min) / range) * 2f - 1f).coerceIn(-1f, 1f)
        }
    }

    private fun waveformToTensorBuffer(waveform: FloatArray, inputTensor: Tensor): ByteBuffer {
        val inputElements = inputTensor.numElements()
        val dataType = inputTensor.dataType()
        val buffer = ByteBuffer.allocateDirect(inputTensor.numBytes()).order(ByteOrder.nativeOrder())

        /*
         * BirdNET V2.4 often ships as a raw waveform TFLite model with input [1, 144000].
         * If a supplied model instead exposes a spectrogram-shaped tensor, this MVP still
         * fills tensor elements from the prepared waveform by padding/truncation. Any future
         * mel conversion should stay in this class, not in classifier/audio/UI code.
         */
        when (dataType) {
            DataType.FLOAT32 -> {
                repeat(inputElements) { index ->
                    buffer.putFloat(if (index < waveform.size) waveform[index] else 0f)
                }
            }

            DataType.INT16 -> {
                repeat(inputElements) { index ->
                    val value = if (index < waveform.size) waveform[index] else 0f
                    buffer.putShort((value.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
                }
            }

            DataType.UINT8 -> {
                repeat(inputElements) { index ->
                    val value = if (index < waveform.size) waveform[index] else 0f
                    val unsigned = ((value.coerceIn(-1f, 1f) + 1f) * 127.5f).toInt()
                    buffer.put(unsigned.coerceIn(0, 255).toByte())
                }
            }

            else -> error(AppText.format("Unsupported input tensor dtype: {0}", dataType))
        }

        buffer.rewind()
        return buffer
    }
}
