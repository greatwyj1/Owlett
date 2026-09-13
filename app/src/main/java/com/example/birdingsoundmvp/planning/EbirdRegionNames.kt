package com.example.birdingsoundmvp.planning

import java.text.Normalizer
import java.util.Locale

object EbirdRegionNames {
    private val provinces = mapOf(
        "11" to "北京", "12" to "天津", "13" to "河北", "14" to "山西", "15" to "内蒙古",
        "21" to "辽宁", "22" to "吉林", "23" to "黑龙江", "31" to "上海", "32" to "江苏",
        "33" to "浙江", "34" to "安徽", "35" to "福建", "36" to "江西", "37" to "山东",
        "41" to "河南", "42" to "湖北", "43" to "湖南", "44" to "广东", "45" to "广西",
        "46" to "海南", "50" to "重庆", "51" to "四川", "52" to "贵州", "53" to "云南",
        "54" to "西藏", "61" to "陕西", "62" to "甘肃", "63" to "青海", "64" to "宁夏", "65" to "新疆"
    )
    private val englishProvinces = listOf("Beijing", "Tianjin", "Hebei", "Shanxi", "Inner Mongolia",
        "Liaoning", "Jilin", "Heilongjiang", "Shanghai", "Jiangsu", "Zhejiang", "Anhui", "Fujian",
        "Jiangxi", "Shandong", "Henan", "Hubei", "Hunan", "Guangdong", "Guangxi", "Hainan",
        "Chongqing", "Sichuan", "Guizhou", "Yunnan", "Tibet", "Shaanxi", "Gansu", "Qinghai", "Ningxia", "Xinjiang")
        .zip(provinces.keys).associate { (name, code) -> "CN-$code" to name }
    private val aliases = mapOf("中国大陆" to "CN", "中华人民共和国" to "CN", "中国" to "CN",
        "usa" to "US", "uk" to "GB", "内蒙古自治区" to "CN-15", "广西壮族自治区" to "CN-45",
        "宁夏回族自治区" to "CN-64", "新疆维吾尔自治区" to "CN-65", "xizang" to "CN-54")
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("[（）(),，·]+"), " ").trim()

    fun displayName(code: String, fallback: String): String = when {
        code.startsWith("CN-") -> provinces[code.removePrefix("CN-")] ?: fallback
        code.length == 2 && code in Locale.getISOCountries() -> Locale("", code).getDisplayCountry(Locale.SIMPLIFIED_CHINESE)
        else -> fallback
    }

    fun matches(option: EbirdRegionOption, query: String): Boolean {
        fun clean(s: String) = normalize(s).removeSuffix("省").removeSuffix("市").removeSuffix("自治区")
        val candidate = clean(query)
        if (aliases[normalize(query)] == option.code) return true
        return listOf(option.code, option.name, displayName(option.code, option.name),
            englishProvinces[option.code].orEmpty(),
            if (option.code.length == 2) Locale("", option.code).getDisplayCountry(Locale.ENGLISH) else "")
            .filter(String::isNotBlank).any { clean(it) == candidate }
    }
}
