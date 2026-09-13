package com.example.birdingsoundmvp.birdnet

import com.example.birdingsoundmvp.i18n.AppText

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class BirdNetMetaModelStatus(
    val loaded: Boolean,
    val message: String,
    val fileName: String = "",
    val input: TensorDescription? = null,
    val output: TensorDescription? = null
)

class BirdNetMetaModel private constructor(
    private val interpreter: Interpreter,
    private val labels: List<SpeciesLabel>,
    val status: BirdNetMetaModelStatus
) : AutoCloseable {
    fun predict(input: MetaModelInput): List<SpeciesRangeResult> {
        val inputTensor = interpreter.getInputTensor(0)
        val outputTensor = interpreter.getOutputTensor(0)
        val inputBuffer = ByteBuffer.allocateDirect(inputTensor.numBytes()).order(ByteOrder.nativeOrder())
        val values = input.toFloatArray()

        when (inputTensor.dataType()) {
            DataType.FLOAT32 -> values.forEach { inputBuffer.putFloat(it) }
            else -> error(AppText.format("Unsupported meta input dtype: {0}", inputTensor.dataType()))
        }
        inputBuffer.rewind()

        val outputBuffer = ByteBuffer.allocateDirect(outputTensor.numBytes()).order(ByteOrder.nativeOrder())
        interpreter.run(inputBuffer, outputBuffer)
        val scores = outputBuffer.parseScores(outputTensor)

        return scores.mapIndexed { index, score ->
            val label = labels.getOrNull(index) ?: SpeciesLabel("", "Label $index")
            SpeciesRangeResult(
                labelIndex = index,
                scientificName = label.scientificName,
                commonName = label.commonName,
                occurrenceProbability = score
            )
        }
    }

    override fun close() {
        interpreter.close()
    }

    private fun ByteBuffer.parseScores(outputTensor: Tensor): FloatArray {
        rewind()
        return when (outputTensor.dataType()) {
            DataType.FLOAT32 -> FloatArray(outputTensor.numElements()) { getFloat() }
            DataType.UINT8 -> FloatArray(outputTensor.numElements()) { (get().toInt() and 0xFF) / 255f }
            DataType.INT8 -> FloatArray(outputTensor.numElements()) { get().toFloat() / Byte.MAX_VALUE.toFloat() }
            else -> error(AppText.format("Unsupported meta output dtype: {0}", outputTensor.dataType()))
        }
    }

    companion object {
        private const val TAG = "BirdNetMetaModel"
        private const val LABELS_ASSET = "labels.txt"
        private const val META_PREFIX = "BirdNET_GLOBAL_6K_V2.4_MData_Model_"

        fun createOrNull(context: Context): Pair<BirdNetMetaModel?, BirdNetMetaModelStatus> {
            val fileName = context.assets.list("")?.firstOrNull {
                it.startsWith(META_PREFIX) && it.endsWith(".tflite")
            }

            if (fileName == null) {
                return null to BirdNetMetaModelStatus(
                    loaded = false,
                    message = AppText.get("Meta model not found in assets")
                )
            }

            val labels = runCatching { loadLabels(context) }
                .onFailure { Log.e(TAG, AppText.get("Failed to load labels"), it) }
                .getOrDefault(emptyList())

            return runCatching {
                val interpreter = Interpreter(loadMappedModel(context, fileName), Interpreter.Options().setNumThreads(1))
                val input = interpreter.getInputTensor(0).describe()
                val output = interpreter.getOutputTensor(0).describe()
                Log.i(TAG, "Meta model file: $fileName")
                Log.i(TAG, "Meta input tensor: shape=${input.shape}, dtype=${input.dtype}, elements=${input.elements}")
                Log.i(TAG, "Meta output tensor: shape=${output.shape}, dtype=${output.dtype}, elements=${output.elements}")
                val status = BirdNetMetaModelStatus(
                    loaded = true,
                    message = AppText.get("Meta model loaded"),
                    fileName = fileName,
                    input = input,
                    output = output
                )
                BirdNetMetaModel(interpreter, labels, status) to status
            }.getOrElse { throwable ->
                Log.e(TAG, AppText.get("Failed to initialize meta model"), throwable)
                null to BirdNetMetaModelStatus(
                    loaded = false,
                    message = AppText.format("Meta model init failed: {0}", throwable.message),
                    fileName = fileName
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
                return input.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    assetFileDescriptor.startOffset,
                    assetFileDescriptor.declaredLength
                )
            }
        }

        private fun loadLabels(context: Context): List<SpeciesLabel> {
            return context.assets.open(LABELS_ASSET).bufferedReader().useLines { lines ->
                lines.map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { BirdNetClassifier.parseLabelForSharedUse(it) }
                    .toList()
            }
        }
    }
}
