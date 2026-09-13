package com.example.birdingsoundmvp.owlett

import com.example.birdingsoundmvp.i18n.AppText

object OwlettTitleGenerator {
    fun fromFirstMessage(message: String, maxLength: Int = 36): String {
        val normalized = message.lineSequence()
            .firstOrNull()
            .orEmpty()
            .trim()
            .replace(Regex("\\s+"), " ")
            .ifBlank { AppText.get("New conversation") }
        return if (normalized.length <= maxLength) normalized else normalized.take(maxLength - 3).trimEnd() + "..."
    }
}
