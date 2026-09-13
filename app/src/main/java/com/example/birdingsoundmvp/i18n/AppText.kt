package com.example.birdingsoundmvp.i18n

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Shared text resources for Compose, background services, Canvas and JVM-only skills. */
object AppText {
    private val translations: Map<String, String> by lazy {
        val type = object : TypeToken<Map<String, String>>() {}.type
        requireNotNull(AppText::class.java.getResourceAsStream("/owlett/zh-CN.json"))
            .bufferedReader(Charsets.UTF_8).use { Gson().fromJson(it, type) }
    }

    fun get(key: String): String = translations[key] ?: key

    fun format(key: String, vararg values: Any?): String =
        Regex("\\{(\\d+)\\}").replace(get(key)) { match ->
            values.getOrNull(match.groupValues[1].toInt())?.toString().orEmpty()
        }
}
