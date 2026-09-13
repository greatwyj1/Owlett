package com.example.birdingsoundmvp.owlett

import com.example.birdingsoundmvp.settings.AppSettings
import com.google.gson.JsonObject
import com.google.gson.Gson
import com.google.gson.JsonParser

object OwlettSettingsPolicy {
    private val gson = Gson()
    val fields = mapOf(
        "appearance" to "system/light/dark",
        "colorTheme" to "green (清爽绿)/feather (羽色·灰褐)/gold (羽色·暖金)",
        "minimumAudioConfidence" to "0..1", "minimumMetaConfidence" to "0..1",
        "useMetaModel" to "boolean", "chunkOverlapSec" to "0..2.5",
        "showSpectrogram" to "boolean", "sortByAdjustedConfidence" to "boolean",
        "maxSelectionDurationSec" to "1..15", "defaultPreciseOverlapSec" to "0..2.5",
        "defaultPreciseMinConfidence" to "0..1", "defaultPreciseTopK" to "1..100",
        "owlettModelId" to "availableModels"
    )
    fun publicValues(value: AppSettings): Map<String, Any> = mapOf(
        "appearance" to value.appearance, "colorTheme" to com.example.birdingsoundmvp.settings.ColorTheme.normalize(value.colorTheme),
        "minimumAudioConfidence" to value.minimumAudioConfidence,
        "minimumMetaConfidence" to value.minimumMetaConfidence, "useMetaModel" to value.useMetaModel,
        "chunkOverlapSec" to value.chunkOverlapSec, "showSpectrogram" to value.showSpectrogram,
        "sortByAdjustedConfidence" to value.sortByAdjustedConfidence, "maxSelectionDurationSec" to value.maxSelectionDurationSec,
        "defaultPreciseOverlapSec" to value.defaultPreciseOverlapSec,
        "defaultPreciseMinConfidence" to value.defaultPreciseMinConfidence,
        "defaultPreciseTopK" to value.defaultPreciseTopK, "owlettModelId" to value.owlettModelId
    )
    fun snapshot(value: AppSettings): JsonObject =
        JsonParser.parseString(gson.toJson(publicValues(value))).asJsonObject

    fun applyConfirmed(current: AppSettings, patch: JsonObject, before: JsonObject?): AppSettings? {
        val next = apply(current, patch)
        val actual = snapshot(current)
        val desired = snapshot(next)
        // Compare persisted numeric values, not Float-backed JsonPrimitives. Only the approved
        // fields matter; unrelated edits survive, and already-applied assignments are replay-safe.
        if (before == null || patch.keySet().any { key ->
                !before.has(key) || (actual[key] != before[key] && actual[key] != desired[key])
            }) return null
        return next
    }

    fun apply(current: AppSettings, patch: JsonObject): AppSettings {
        require(patch.size() > 0) { "请指定要修改的设置" }
        require(patch.keySet().all { it in fields }) { "该设置未向助手开放，请在设置页面自行修改" }
        fun number(key: String, old: Float, range: ClosedFloatingPointRange<Float>): Float = patch[key]?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber) { "$key 需要数字" }
            it.asFloat.also { value -> require(value.isFinite() && value in range) { "$key 超出允许范围：$range" } }
        } ?: old
        fun boolean(key: String, old: Boolean): Boolean = patch[key]?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "$key 需要开关值" }; it.asBoolean
        } ?: old
        fun string(key: String, old: String, allowed: List<String>): String = patch[key]?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString in allowed) { "$key 不是可用选项" }; it.asString
        } ?: old
        val topK = number("defaultPreciseTopK", current.defaultPreciseTopK.toFloat(), 1f..100f)
        require(topK % 1f == 0f) { "识别结果数量必须是整数" }
        return current.copy(
            appearance = string("appearance", current.appearance, listOf("system", "light", "dark")),
            colorTheme = string("colorTheme", com.example.birdingsoundmvp.settings.ColorTheme.normalize(current.colorTheme),
                com.example.birdingsoundmvp.settings.ColorTheme.ids),
            minimumAudioConfidence = number("minimumAudioConfidence", current.minimumAudioConfidence, 0f..1f),
            minimumMetaConfidence = number("minimumMetaConfidence", current.minimumMetaConfidence, 0f..1f),
            useMetaModel = boolean("useMetaModel", current.useMetaModel),
            chunkOverlapSec = number("chunkOverlapSec", current.chunkOverlapSec, 0f..2.5f),
            showSpectrogram = boolean("showSpectrogram", current.showSpectrogram),
            sortByAdjustedConfidence = boolean("sortByAdjustedConfidence", current.sortByAdjustedConfidence),
            maxSelectionDurationSec = number("maxSelectionDurationSec", current.maxSelectionDurationSec, 1f..15f),
            defaultPreciseOverlapSec = number("defaultPreciseOverlapSec", current.defaultPreciseOverlapSec, 0f..2.5f),
            defaultPreciseMinConfidence = number("defaultPreciseMinConfidence", current.defaultPreciseMinConfidence, 0f..1f),
            defaultPreciseTopK = topK.toInt(),
            owlettModelId = string("owlettModelId", current.owlettModelId, current.owlettAvailableModels)
        )
    }
}
