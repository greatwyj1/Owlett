package com.example.birdingsoundmvp.settings

object ColorTheme {
    val ids = listOf("green", "feather", "gold")
    fun normalize(value: String?): String = value?.takeIf { it in ids } ?: "green"
    fun label(value: String): String = when (normalize(value)) {
        "feather" -> "羽色·灰褐"
        "gold" -> "羽色·暖金"
        else -> "清爽绿"
    }
}
