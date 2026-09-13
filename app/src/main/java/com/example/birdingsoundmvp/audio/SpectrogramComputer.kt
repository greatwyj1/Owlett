package com.example.birdingsoundmvp.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class SpectrogramComputer(
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
    private val hopSize: Int = DEFAULT_HOP_SIZE,
    private val outputBins: Int = 96
) {
    private val rolling = FloatArray(windowSize)
    private var filled = 0
    private var writeIndex = 0
    private var samplesUntilNext = windowSize
    private val hann = FloatArray(windowSize) { index ->
        (0.5 - 0.5 * cos(2.0 * PI * index / (windowSize - 1))).toFloat()
    }

    fun appendPcm(buffer: ShortArray): List<FloatArray> {
        val columns = mutableListOf<FloatArray>()
        for (sample in buffer) {
            rolling[writeIndex] = sample.toFloat() / 32768f
            writeIndex = (writeIndex + 1) % windowSize
            if (filled < windowSize) filled++

            samplesUntilNext--
            if (filled == windowSize && samplesUntilNext <= 0) {
                columns += computeColumn()
                samplesUntilNext = hopSize
            }
        }
        return columns
    }

    private fun computeColumn(): FloatArray {
        val real = FloatArray(windowSize) { index ->
            val sourceIndex = (writeIndex + index) % windowSize
            rolling[sourceIndex] * hann[index]
        }
        val imag = FloatArray(windowSize)
        fft(real, imag)
        val linearBins = windowSize / 2
        val magnitudesDb = FloatArray(outputBins)
        val binsPerOutput = linearBins.toFloat() / outputBins

        for (bin in 0 until outputBins) {
            val start = (bin * binsPerOutput).toInt()
            val end = (((bin + 1) * binsPerOutput).toInt()).coerceAtLeast(start + 1).coerceAtMost(linearBins)
            var sum = 0f
            for (i in start until end) {
                sum += sqrt(real[i] * real[i] + imag[i] * imag[i])
            }
            val avg = sum / (end - start)
            magnitudesDb[bin] = (20f * log10(avg + 1e-6f))
        }

        val minDb = -80f
        val maxDb = 0f
        return FloatArray(outputBins) { index ->
            ((magnitudesDb[index] - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
        }
    }

    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]
                real[i] = real[j]
                real[j] = tr
                val ti = imag[i]
                imag[i] = imag[j]
                imag[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wLenReal = cos(angle).toFloat()
            val wLenImag = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var wReal = 1f
                var wImag = 0f
                for (k in 0 until len / 2) {
                    val even = i + k
                    val odd = i + k + len / 2
                    val oddReal = real[odd] * wReal - imag[odd] * wImag
                    val oddImag = real[odd] * wImag + imag[odd] * wReal
                    real[odd] = real[even] - oddReal
                    imag[odd] = imag[even] - oddImag
                    real[even] += oddReal
                    imag[even] += oddImag
                    val nextReal = wReal * wLenReal - wImag * wLenImag
                    wImag = wReal * wLenImag + wImag * wLenReal
                    wReal = nextReal
                }
                i += len
            }
            len = len shl 1
        }
    }

    companion object {
        const val DEFAULT_WINDOW_SIZE = 1024
        const val DEFAULT_HOP_SIZE = 512
    }
}
