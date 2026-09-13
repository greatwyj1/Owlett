package com.example.birdingsoundmvp.birdnet

import com.example.birdingsoundmvp.i18n.AppText

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.util.Log
import com.example.birdingsoundmvp.audio.AudioChunk
import com.example.birdingsoundmvp.audio.AudioChunker
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class TensorDescription(
    val shape: List<Int>,
    val dtype: String,
    val elements: Int
)

data class BirdNetModelStatus(
    val loaded: Boolean,
    val message: String,
    val fileName: String = "",
    val input: TensorDescription? = null,
    val output: TensorDescription? = null,
    val labelCount: Int = 0,
    val chunkDurationSec: Double = AudioChunker.DEFAULT_CHUNK_DURATION_SEC.toDouble(),
    val hopDurationSec: Double = AudioChunker.DEFAULT_CHUNK_DURATION_SEC.toDouble()
)

class BirdNetClassifier private constructor(
    private val interpreter: Interpreter,
    private val labels: List<SpeciesLabel>,
    private val preprocessor: BirdNetPreprocessor,
    val status: BirdNetModelStatus
) : AutoCloseable {
    fun classify(
        chunk: AudioChunk,
        topK: Int = DEFAULT_TOP_K,
        displayThreshold: Float = DEFAULT_DISPLAY_THRESHOLD
    ): List<SpeciesPrediction> {
        val inputTensor = interpreter.getInputTensor(0)
        val outputTensor = interpreter.getOutputTensor(0)
        val input = preprocessor.prepareInput(chunk.samples, inputTensor)
        val output = ByteBuffer.allocateDirect(outputTensor.numBytes()).order(ByteOrder.nativeOrder())

        val startedNs = System.nanoTime()
        interpreter.run(input, output)
        val inferenceTimeMs = (System.nanoTime() - startedNs) / 1_000_000
        Log.d(TAG, "Audio inference ${chunk.startSec}-${chunk.endSec}s took ${inferenceTimeMs}ms")
        val scores = output.parseScores(outputTensor)

        return scores
            .withIndex()
            .filter { it.value >= displayThreshold }
            .sortedByDescending { it.value }
            .take(topK)
            .map { indexed ->
                val label = labels.getOrNull(indexed.index) ?: SpeciesLabel(
                    scientificName = "",
                    commonName = "Label ${indexed.index}"
                )
                SpeciesPrediction(
                    labelIndex = indexed.index,
                    scientificName = label.scientificName,
                    commonName = label.commonName,
                    audioConfidence = indexed.value,
                    inferenceTimeMs = inferenceTimeMs
                )
            }
    }

    fun debugTop5FromWavFile(wavFile: File) {
        runCatching {
            val samples = FileInputStream(wavFile).use {
                WavDebugReader.readFirstMono16BitChunk(it, preprocessor.config.expectedSamples)
            }
            val chunk = AudioChunk(
                samples = samples,
                sampleRate = preprocessor.config.sampleRate,
                startSec = 0.0,
                endSec = preprocessor.config.chunkLengthSec
            )
            classify(chunk, topK = 5, displayThreshold = 0f).forEachIndexed { rank, prediction ->
                Log.i(
                    TAG,
                    "Debug WAV top-${rank + 1}: ${prediction.scientificName} / ${prediction.commonName} " +
                        "audio=${prediction.audioConfidence} inference=${prediction.inferenceTimeMs}ms"
                )
            }
        }.onFailure { throwable ->
            Log.e(TAG, "Debug WAV inference failed: ${wavFile.absolutePath}", throwable)
        }
    }

    fun debugTop5FromAssetWav(context: Context, assetName: String) {
        runCatching {
            val samples = context.assets.open(assetName).use {
                WavDebugReader.readFirstMono16BitChunk(it, preprocessor.config.expectedSamples)
            }
            val chunk = AudioChunk(
                samples = samples,
                sampleRate = preprocessor.config.sampleRate,
                startSec = 0.0,
                endSec = preprocessor.config.chunkLengthSec
            )
            classify(chunk, topK = 5, displayThreshold = 0f).forEachIndexed { rank, prediction ->
                Log.i(
                    TAG,
                    "Debug asset WAV top-${rank + 1}: ${prediction.scientificName} / ${prediction.commonName} " +
                        "audio=${prediction.audioConfidence} inference=${prediction.inferenceTimeMs}ms"
                )
            }
        }.onFailure { throwable ->
            Log.e(TAG, "Debug asset WAV inference failed: $assetName", throwable)
        }
    }

    override fun close() {
        interpreter.close()
    }

    private fun ByteBuffer.parseScores(outputTensor: Tensor): FloatArray {
        rewind()
        return when (outputTensor.dataType()) {
            DataType.FLOAT32 -> FloatArray(outputTensor.numElements()) { getFloat() }
            DataType.UINT8 -> FloatArray(outputTensor.numElements()) {
                (get().toInt() and 0xFF) / 255f
            }
            DataType.INT8 -> FloatArray(outputTensor.numElements()) {
                get().toFloat() / Byte.MAX_VALUE.toFloat()
            }
            else -> error(AppText.format("Unsupported output tensor dtype: {0}", outputTensor.dataType()))
        }
    }

    companion object {
        private const val TAG = "BirdNetClassifier"
        private const val MODEL_ASSET = "birdnet_model.tflite"
        private const val LABELS_ASSET = "labels.txt"
        private const val DEFAULT_TOP_K = 10
        private const val DEFAULT_DISPLAY_THRESHOLD = 0.05f

        fun createOrNull(context: Context): Pair<BirdNetClassifier?, BirdNetModelStatus> {
            val labels = runCatching { loadLabels(context) }
                .onFailure { Log.e(TAG, AppText.get("Failed to load labels"), it) }
                .getOrDefault(emptyList())

            val mappedModel = runCatching { loadMappedModel(context, MODEL_ASSET) }
                .onFailure {
                    Log.e(TAG, AppText.get("Failed to load model"), it)
                }
                .getOrNull()

            if (mappedModel == null) {
                return null to BirdNetModelStatus(
                    loaded = false,
                    message = AppText.format("Missing or invalid assets/{0}. Add a real BirdNET TFLite model.", MODEL_ASSET),
                    fileName = MODEL_ASSET,
                    labelCount = labels.size
                )
            }

            return runCatching {
                val preprocessor = BirdNetPreprocessor()
                val interpreter = Interpreter(mappedModel, Interpreter.Options().setNumThreads(2))
                val input = interpreter.getInputTensor(0).describe()
                val output = interpreter.getOutputTensor(0).describe()
                val status = BirdNetModelStatus(
                    loaded = true,
                    message = AppText.get("Model loaded"),
                    fileName = MODEL_ASSET,
                    input = input,
                    output = output,
                    labelCount = labels.size,
                    chunkDurationSec = preprocessor.config.chunkLengthSec,
                    hopDurationSec = preprocessor.config.hopLengthSec
                )
                Log.i(TAG, "Audio model file: $MODEL_ASSET")
                Log.i(TAG, "Audio input tensor: shape=${input.shape}, dtype=${input.dtype}, elements=${input.elements}")
                Log.i(TAG, "Audio output tensor: shape=${output.shape}, dtype=${output.dtype}, elements=${output.elements}")
                Log.i(TAG, "Labels loaded: ${labels.size}")
                Log.i(TAG, "Chunk duration: ${preprocessor.config.chunkLengthSec}s; hop duration: ${preprocessor.config.hopLengthSec}s")
                BirdNetClassifier(interpreter, labels, preprocessor, status) to status
            }.getOrElse { throwable ->
                Log.e(TAG, AppText.get("Failed to initialize interpreter"), throwable)
                null to BirdNetModelStatus(
                    loaded = false,
                    message = AppText.format("TFLite init failed: {0}", throwable.message),
                    fileName = MODEL_ASSET,
                    labelCount = labels.size
                )
            }
        }

        private fun Tensor.describe(): TensorDescription = TensorDescription(
            shape = shape().toList(),
            dtype = dataType().name,
            elements = numElements()
        )

        private fun loadMappedModel(context: Context, assetName: String): MappedByteBuffer {
            val assetFileDescriptor: AssetFileDescriptor = context.assets.openFd(assetName)
            FileInputStream(assetFileDescriptor.fileDescriptor).use { input ->
                val channel = input.channel
                return channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    assetFileDescriptor.startOffset,
                    assetFileDescriptor.declaredLength
                )
            }
        }

        private fun loadLabels(context: Context): List<SpeciesLabel> {
            return context.assets.open(LABELS_ASSET).bufferedReader().useLines { lines ->
                lines
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { parseLabelForSharedUse(it) }
                    .toList()
            }
        }

        fun parseLabelForSharedUse(rawLabel: String): SpeciesLabel {
            val withoutIndex = rawLabel.replace(Regex("^\\d+\\s+"), "").trim()
            val semicolonParts = withoutIndex.split(';').map { it.trim() }
            if (semicolonParts.size >= 2) {
                return SpeciesLabel(
                    scientificName = semicolonParts[0],
                    commonName = semicolonParts[1]
                )
            }

            val underscoreParts = withoutIndex.split('_', limit = 2).map { it.trim() }
            if (underscoreParts.size == 2) {
                return SpeciesLabel(
                    scientificName = underscoreParts[0].replace('_', ' '),
                    commonName = underscoreParts[1].replace('_', ' ')
                )
            }

            return SpeciesLabel(scientificName = "", commonName = withoutIndex)
        }
    }
}

data class SpeciesLabel(
    val scientificName: String,
    val commonName: String
)

private object WavDebugReader {
    fun readFirstMono16BitChunk(input: InputStream, sampleCount: Int): ShortArray {
        val header = ByteArray(44)
        require(input.read(header) == header.size) { AppText.get("Invalid WAV header") }
        val samples = ShortArray(sampleCount)
        val twoBytes = ByteArray(2)
        var index = 0
        while (index < samples.size && input.read(twoBytes) == 2) {
            val lo = twoBytes[0].toInt() and 0xFF
            val hi = twoBytes[1].toInt()
            samples[index] = ((hi shl 8) or lo).toShort()
            index++
        }
        return samples
    }
}
