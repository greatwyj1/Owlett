package com.example.birdingsoundmvp.birdnet

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.util.Locale

data class ZhengBirdNameEntry(
    val number: Int? = null,
    val scientificName: String = "",
    val englishName: String = "",
    val chineseName: String = "",
    val genusChineseName: String = ""
)

private data class ZhengBirdNameAsset(
    val sourceUrl: String = "",
    val sourceTitle: String = "",
    val sourceNote: String = "",
    val entryCount: Int = 0,
    val entries: List<ZhengBirdNameEntry> = emptyList()
)

class ZhengBirdNameResolver private constructor(
    zhengEntries: List<ZhengBirdNameEntry>,
    fallbackEntries: List<ZhengBirdNameEntry>
) {
    private val byScientificName: Map<String, String> = buildMap {
        zhengEntries.forEach { entry ->
            val key = entry.scientificName.normalizeNameKey()
            if (key.isNotEmpty() && entry.chineseName.isNotBlank()) {
                putIfAbsent(key, entry.chineseName)
            }
        }
    }

    private val byEnglishName: Map<String, String> = buildMap {
        zhengEntries.forEach { entry ->
            val key = entry.englishName.normalizeNameKey()
            if (key.isNotEmpty() && entry.chineseName.isNotBlank()) {
                putIfAbsent(key, entry.chineseName)
            }
        }
    }

    private val fallbackByScientificName: Map<String, String> = buildMap {
        fallbackEntries.forEach { entry ->
            val key = entry.scientificName.normalizeNameKey()
            if (key.isNotEmpty() && entry.chineseName.isNotBlank()) {
                putIfAbsent(key, entry.chineseName)
            }
        }
    }

    private val fallbackByEnglishName: Map<String, String> = buildMap {
        fallbackEntries.forEach { entry ->
            val key = entry.englishName.normalizeNameKey()
            if (key.isNotEmpty() && entry.chineseName.isNotBlank()) {
                putIfAbsent(key, entry.chineseName)
            }
        }
    }

    fun resolve(scientificName: String, commonName: String): String? {
        val scientificKey = scientificName.normalizeNameKey()
        byScientificName[scientificKey]?.let { return it }

        val commonKey = commonName.normalizeNameKey()
        byEnglishName[commonKey]?.let { return it }

        fallbackByScientificName[scientificKey]?.let { return it }
        return fallbackByEnglishName[commonKey]
    }

    companion object {
        private const val TAG = "ZhengBirdNameResolver"
        private const val ZHENG_ASSET_NAME = "zheng_bird_names.json"
        private const val FALLBACK_ASSET_NAME = "birdnet_taxonomy_zh.json"

        fun fromAssets(context: Context): ZhengBirdNameResolver {
            val gson = Gson()
            val zhengEntries = loadEntries(context, gson, ZHENG_ASSET_NAME)
            val fallbackEntries = loadEntries(context, gson, FALLBACK_ASSET_NAME)
            return ZhengBirdNameResolver(zhengEntries, fallbackEntries)
        }

        private fun loadEntries(context: Context, gson: Gson, assetName: String): List<ZhengBirdNameEntry> {
            return runCatching {
                context.assets.open(assetName).bufferedReader().use { reader ->
                    gson.fromJson(reader, ZhengBirdNameAsset::class.java).entries
                }
            }.getOrElse { throwable ->
                Log.e(TAG, "Failed to load $assetName", throwable)
                emptyList()
            }
        }
    }
}

private fun String.normalizeNameKey(): String {
    return trim()
        .replace('’', '\'')
        .replace('‘', '\'')
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.US)
}
