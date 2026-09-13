package com.example.birdingsoundmvp.owlett

import java.util.Locale

data class BirdCallRegion(val country: String, val locality: String = "", val origin: String = "系统定位")

object BirdCallRegions {
    fun countryName(value: String): String? {
        val text = value.trim()
        val aliases = mapOf("中国大陆" to "CN", "中国" to "CN", "美国" to "US", "英国" to "GB", "UK" to "GB")
        val requested = aliases[text] ?: text
        val code = Locale.getISOCountries().firstOrNull { code ->
            code.equals(requested, true) || listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE)
                .any { Locale("", code).getDisplayCountry(it).equals(requested, true) }
        } ?: return null
        return when (code) { "TW" -> "Taiwan"; "HK" -> "Hong Kong"; "MO" -> "Macao"; else -> Locale("", code).getDisplayCountry(Locale.ENGLISH) }
    }

    suspend fun resolve(country: String, locality: String, global: Boolean, attached: BirdCallRegion?,
                        device: suspend () -> BirdCallRegion?): BirdCallRegion? {
        if (global) return BirdCallRegion("", "", "用户指定全球")
        if (country.isNotBlank()) return BirdCallRegion(requireNotNull(countryName(country)) { "请指定有效国家名称或两位国家代码" }, locality.trim(), "对话指定地区")
        val fallback = attached ?: device() ?: return null
        return if (locality.isBlank()) fallback else fallback.copy(locality = locality.trim(), origin = "对话指定地区")
    }
}
