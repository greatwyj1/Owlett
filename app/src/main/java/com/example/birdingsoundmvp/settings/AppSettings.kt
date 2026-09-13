package com.example.birdingsoundmvp.settings

data class AppSettings(
    val appearance: String = "system",
    val colorTheme: String = "green",
    val minimumAudioConfidence: Float = 0.05f,
    val minimumMetaConfidence: Float = 0.03f,
    val useMetaModel: Boolean = false,
    val useLocation: Boolean = false,
    val chunkOverlapSec: Float = 0f,
    val showSpectrogram: Boolean = true,
    val sortByAdjustedConfidence: Boolean = false,
    val preciseRecognitionServerUrl: String = DEFAULT_PRECISE_RECOGNITION_SERVER_URL,
    val preciseRecognitionAuthToken: String = "",
    val ebirdApiKey: String = "",
    val preciseAcousticModel: String = DEFAULT_PRECISE_ACOUSTIC_MODEL,
    val maxSelectionDurationSec: Float = 15f,
    val defaultPreciseOverlapSec: Float = 1.5f,
    val defaultPreciseMinConfidence: Float = 0.01f,
    val defaultPreciseTopK: Int = 10,
    val owlettModelId: String = DEFAULT_OWLETT_MODEL,
    val owlettAvailableModels: List<String> = DEFAULT_OWLETT_MODELS,
    val owlettAutomaticSkillsEnabled: Boolean = true,
    val owlettSystemPrompt: String = "",
    val owlettAutomationMode: String = "confirm_writes"
) {
    val hopDurationSec: Double
        get() = (3.0 - chunkOverlapSec.coerceIn(0f, 2.5f)).coerceAtLeast(0.5).toDouble()

    companion object {
        const val DEFAULT_PRECISE_RECOGNITION_SERVER_URL = ""
        const val DEFAULT_PRECISE_ACOUSTIC_MODEL = "perch_v2"
        const val DEFAULT_OWLETT_MODEL = "deepseek-v4-flash"
        val DEFAULT_OWLETT_MODELS = listOf("deepseek-v4-flash", "deepseek-v4-pro")
    }
}
