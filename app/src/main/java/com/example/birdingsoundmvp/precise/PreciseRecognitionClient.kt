package com.example.birdingsoundmvp.precise

import com.example.birdingsoundmvp.i18n.AppText

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

data class PreciseRecognitionMeta(
    val request_id: String = UUID.randomUUID().toString(),
    val trip_id: String,
    val selection_start_trip_audio_ms: Long,
    val selection_end_trip_audio_ms: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val week: Int? = null,
    val acoustic_model: String,
    val min_confidence: Double,
    val overlap_sec: Double,
    val top_k: Int
)

data class PreciseRecognitionDetection(
    val scientificName: String,
    val commonName: String,
    val displayNameZh: String? = null,
    val confidence: Float,
    val clipStartSec: Double,
    val clipEndSec: Double,
    val tripAudioStartMs: Long,
    val tripAudioEndMs: Long
)

data class PreciseRecognitionSummary(
    val scientificName: String,
    val commonName: String,
    val displayNameZh: String? = null,
    val maxConfidence: Float,
    val meanConfidence: Float,
    val numDetections: Int,
    val firstStartSec: Double,
    val lastEndSec: Double,
    val firstTripAudioStartMs: Long,
    val lastTripAudioEndMs: Long
)

data class PreciseRecognitionAnalysisParams(
    val acousticModel: String,
    val windowSec: Double,
    val overlapSec: Double,
    val minConfidence: Double,
    val topK: Int,
    val smartChunking: Boolean
)

data class PreciseRecognitionResult(
    val requestId: String,
    val durationSec: Double,
    val model: String,
    val analysisParams: PreciseRecognitionAnalysisParams?,
    val detections: List<PreciseRecognitionDetection>,
    val summary: List<PreciseRecognitionSummary>,
    val warnings: List<String>
)

sealed class PreciseRecognitionError(val message: String) {
    data class NetworkError(val detail: String) : PreciseRecognitionError(AppText.format("network error: {0}", detail))
    data class ServerError(val code: Int, val detail: String) : PreciseRecognitionError(AppText.format("server error ({0}): {1}", code, detail))
    data class InvalidResponse(val detail: String) : PreciseRecognitionError(AppText.format("invalid response: {0}", detail))
    data class SelectionExportFailed(val detail: String) : PreciseRecognitionError(AppText.format("selection export failed: {0}", detail))
}

sealed class PreciseRecognitionResponse {
    data class Success(val result: PreciseRecognitionResult) : PreciseRecognitionResponse()
    data class Failure(val error: PreciseRecognitionError) : PreciseRecognitionResponse()
}

class PreciseRecognitionClient(
    private val serverUrl: String
) {
    private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

    fun recognize(
        audioFile: File,
        meta: PreciseRecognitionMeta,
        bearerToken: String = ""
    ): PreciseRecognitionResponse {
        val request = try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("meta", gson.toJson(meta))
                .addFormDataPart(
                    "audio",
                    audioFile.name,
                    audioFile.asRequestBody("audio/wav".toMediaType())
                )
                .build()
            val builder = Request.Builder()
                .url(serverUrl)
                .post(body)
                .header("ngrok-skip-browser-warning", "true")
                .header("User-Agent", "Owlett-Android/0.1")
                .header("Accept", "application/json")
            bearerToken.trim().takeIf { it.isNotEmpty() }?.let { token ->
                builder.header("Authorization", "Bearer $token")
            }
            builder.build()
        } catch (throwable: Throwable) {
            return PreciseRecognitionResponse.Failure(
                PreciseRecognitionError.NetworkError(throwable.message ?: throwable.javaClass.simpleName)
            )
        }

        return try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    PreciseRecognitionResponse.Failure(
                        PreciseRecognitionError.ServerError(
                            response.code,
                            parseErrorMessage(body, response.message)
                        )
                    )
                } else {
                    parseResult(body, meta.selection_start_trip_audio_ms)
                }
            }
        } catch (throwable: Throwable) {
            PreciseRecognitionResponse.Failure(
                PreciseRecognitionError.NetworkError(throwable.message ?: throwable.javaClass.simpleName)
            )
        }
    }

    private fun parseResult(
        body: String,
        selectionStartTripAudioMs: Long
    ): PreciseRecognitionResponse {
        return try {
            val root = JsonParser.parseString(body).asJsonObject
            val detectionsJson = when {
                root.has("detections") && root.get("detections").isJsonArray -> root.getAsJsonArray("detections")
                else -> return PreciseRecognitionResponse.Failure(
                    PreciseRecognitionError.InvalidResponse(AppText.get("missing detections array"))
                )
            }
            val detections = detectionsJson.mapNotNull { element ->
                val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val scientific = item.stringOrEmpty("scientific_name", "scientificName", "sci_name")
                val common = item.stringOrEmpty("common_name", "commonName", "label")
                val confidence = item.floatOrNull("confidence", "score", "probability") ?: return@mapNotNull null
                val startSec = item.doubleOrNull("start_sec", "startSec", "clip_start_sec") ?: 0.0
                val endSec = item.doubleOrNull("end_sec", "endSec", "clip_end_sec") ?: startSec
                PreciseRecognitionDetection(
                    scientificName = scientific,
                    commonName = common,
                    confidence = confidence,
                    clipStartSec = startSec,
                    clipEndSec = endSec,
                    tripAudioStartMs = selectionStartTripAudioMs + (startSec * 1000.0).toLong(),
                    tripAudioEndMs = selectionStartTripAudioMs + (endSec * 1000.0).toLong()
                )
            }
            val summary = root.arrayOrEmpty("summary").mapNotNull { element ->
                val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val scientific = item.stringOrEmpty("scientific_name")
                val common = item.stringOrEmpty("common_name")
                val firstStartSec = item.doubleOrNull("first_start_sec") ?: 0.0
                val lastEndSec = item.doubleOrNull("last_end_sec") ?: firstStartSec
                PreciseRecognitionSummary(
                    scientificName = scientific,
                    commonName = common,
                    maxConfidence = item.floatOrNull("max_confidence") ?: 0f,
                    meanConfidence = item.floatOrNull("mean_confidence") ?: 0f,
                    numDetections = item.intOrNull("num_detections") ?: 0,
                    firstStartSec = firstStartSec,
                    lastEndSec = lastEndSec,
                    firstTripAudioStartMs = selectionStartTripAudioMs + (firstStartSec * 1000.0).toLong(),
                    lastTripAudioEndMs = selectionStartTripAudioMs + (lastEndSec * 1000.0).toLong()
                )
            }
            val analysisParams = root.get("analysis_params")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.let { params ->
                    PreciseRecognitionAnalysisParams(
                        acousticModel = params.stringOrEmpty("acoustic_model"),
                        windowSec = params.doubleOrNull("window_sec") ?: 0.0,
                        overlapSec = params.doubleOrNull("overlap_sec") ?: 0.0,
                        minConfidence = params.doubleOrNull("min_confidence") ?: 0.0,
                        topK = params.intOrNull("top_k") ?: 0,
                        smartChunking = params.booleanOrNull("smart_chunking") ?: false
                    )
                }
            PreciseRecognitionResponse.Success(
                PreciseRecognitionResult(
                    requestId = root.stringOrEmpty("request_id"),
                    durationSec = root.doubleOrNull("duration_sec") ?: 0.0,
                    model = root.stringOrEmpty("model"),
                    analysisParams = analysisParams,
                    detections = detections,
                    summary = summary,
                    warnings = root.stringArrayOrEmpty("warnings")
                )
            )
        } catch (throwable: Throwable) {
            PreciseRecognitionResponse.Failure(
                PreciseRecognitionError.InvalidResponse(throwable.message ?: throwable.javaClass.simpleName)
            )
        }
    }

    private fun parseErrorMessage(body: String, fallback: String): String {
        return runCatching {
            val root = JsonParser.parseString(body).asJsonObject
            val error = root.get("error")?.takeIf { !it.isJsonNull }?.asString
            val details = root.get("details")?.takeIf { !it.isJsonNull }?.toString()
            listOfNotNull(error, details).joinToString(": ").ifBlank { body }
        }.getOrElse { body.ifBlank { fallback.ifBlank { AppText.get("empty error response") } } }
    }

    private fun com.google.gson.JsonObject.stringOrEmpty(vararg names: String): String {
        return names.firstNotNullOfOrNull { name ->
            get(name)?.takeIf { !it.isJsonNull }?.asString
        }.orEmpty()
    }

    private fun com.google.gson.JsonObject.floatOrNull(vararg names: String): Float? {
        return names.firstNotNullOfOrNull { name ->
            runCatching { get(name)?.takeIf { !it.isJsonNull }?.asFloat }.getOrNull()
        }
    }

    private fun com.google.gson.JsonObject.doubleOrNull(vararg names: String): Double? {
        return names.firstNotNullOfOrNull { name ->
            runCatching { get(name)?.takeIf { !it.isJsonNull }?.asDouble }.getOrNull()
        }
    }

    private fun com.google.gson.JsonObject.intOrNull(vararg names: String): Int? {
        return names.firstNotNullOfOrNull { name ->
            runCatching { get(name)?.takeIf { !it.isJsonNull }?.asInt }.getOrNull()
        }
    }

    private fun com.google.gson.JsonObject.booleanOrNull(vararg names: String): Boolean? {
        return names.firstNotNullOfOrNull { name ->
            runCatching { get(name)?.takeIf { !it.isJsonNull }?.asBoolean }.getOrNull()
        }
    }

    private fun com.google.gson.JsonObject.arrayOrEmpty(name: String): List<com.google.gson.JsonElement> {
        return get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
    }

    private fun com.google.gson.JsonObject.stringArrayOrEmpty(name: String): List<String> {
        return arrayOrEmpty(name).mapNotNull { element ->
            runCatching { element.takeIf { !it.isJsonNull }?.asString }.getOrNull()
        }
    }

    companion object {
        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
