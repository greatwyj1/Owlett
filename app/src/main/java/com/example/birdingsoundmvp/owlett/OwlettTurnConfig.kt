package com.example.birdingsoundmvp.owlett

import com.example.birdingsoundmvp.settings.AppSettings
import com.google.gson.Gson

data class OwlettTurnConfig(
    val prompt: String = "", val automationMode: String = "confirm_writes",
    val modelId: String = AppSettings.DEFAULT_OWLETT_MODEL,
    val automaticSkillsEnabled: Boolean = true,
    val skillInstructions: Map<String, String> = emptyMap()
) {
    val automatic: Boolean get() = automationMode == "automatic"
    val effectivePrompt: String get() = prompt.take(10_000).ifBlank { OwlettContextBuilder.SYSTEM_PROMPT }
    fun toJson(): String = Gson().toJson(this)
    companion object {
        fun from(settings: AppSettings) = OwlettTurnConfig(settings.owlettSystemPrompt, settings.owlettAutomationMode,
            settings.owlettModelId, settings.owlettAutomaticSkillsEnabled,
            OwlettSceneSkills.all.associate { it.descriptor.id to it.instructions() })
        fun decode(json: String?): OwlettTurnConfig = runCatching {
            val value = com.google.gson.JsonParser.parseString(json).asJsonObject
            OwlettTurnConfig(value.get("prompt")?.takeUnless { it.isJsonNull }?.asString.orEmpty().take(10000),
                value.get("automationMode")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it == "automatic" } ?: "confirm_writes",
                value.get("modelId")?.takeUnless { it.isJsonNull }?.asString ?: AppSettings.DEFAULT_OWLETT_MODEL,
                value.get("automaticSkillsEnabled")?.takeUnless { it.isJsonNull }?.asBoolean ?: true,
                value.getAsJsonObject("skillInstructions")?.entrySet()?.associate { it.key to it.value.asString } ?: emptyMap())
        }.getOrDefault(OwlettTurnConfig())
    }
}
