package com.example.birdingsoundmvp.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "birding_settings")

class SettingsRepository(private val context: Context) {
    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            appearance = prefs[Keys.APPEARANCE] ?: "system",
            colorTheme = ColorTheme.normalize(prefs[Keys.COLOR_THEME]),
            minimumAudioConfidence = prefs[Keys.MIN_AUDIO] ?: 0.05f,
            minimumMetaConfidence = prefs[Keys.MIN_META] ?: 0.03f,
            useMetaModel = prefs[Keys.USE_META] ?: false,
            useLocation = prefs[Keys.USE_LOCATION] ?: false,
            chunkOverlapSec = prefs[Keys.CHUNK_OVERLAP] ?: 0f,
            showSpectrogram = prefs[Keys.SHOW_SPECTROGRAM] ?: true,
            sortByAdjustedConfidence = prefs[Keys.SORT_ADJUSTED] ?: false,
            preciseRecognitionServerUrl = prefs[Keys.PRECISE_URL] ?: AppSettings.DEFAULT_PRECISE_RECOGNITION_SERVER_URL,
            preciseRecognitionAuthToken = prefs[Keys.PRECISE_AUTH_TOKEN] ?: "",
            ebirdApiKey = prefs[Keys.EBIRD_API_KEY] ?: "",
            preciseAcousticModel = prefs[Keys.PRECISE_ACOUSTIC_MODEL] ?: AppSettings.DEFAULT_PRECISE_ACOUSTIC_MODEL,
            maxSelectionDurationSec = prefs[Keys.MAX_SELECTION_SEC] ?: 15f,
            defaultPreciseOverlapSec = prefs[Keys.PRECISE_OVERLAP_SEC] ?: 1.5f,
            defaultPreciseMinConfidence = prefs[Keys.PRECISE_MIN_CONFIDENCE] ?: 0.01f,
            defaultPreciseTopK = prefs[Keys.PRECISE_TOP_K] ?: 10,
            owlettModelId = prefs[Keys.OWLETT_MODEL] ?: AppSettings.DEFAULT_OWLETT_MODEL,
            owlettAvailableModels = prefs[Keys.OWLETT_MODELS]
                ?.sorted()
                ?.takeIf(List<String>::isNotEmpty)
                ?: AppSettings.DEFAULT_OWLETT_MODELS,
            owlettAutomaticSkillsEnabled = prefs[Keys.OWLETT_AUTO_SKILLS] ?: true,
            owlettSystemPrompt = prefs[Keys.OWLETT_PROMPT] ?: "",
            owlettAutomationMode = prefs[Keys.OWLETT_AUTOMATION] ?: "confirm_writes"
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            val current = AppSettings(
                appearance = prefs[Keys.APPEARANCE] ?: "system",
                colorTheme = ColorTheme.normalize(prefs[Keys.COLOR_THEME]),
                minimumAudioConfidence = prefs[Keys.MIN_AUDIO] ?: 0.05f,
                minimumMetaConfidence = prefs[Keys.MIN_META] ?: 0.03f,
                useMetaModel = prefs[Keys.USE_META] ?: false,
                useLocation = prefs[Keys.USE_LOCATION] ?: false,
                chunkOverlapSec = prefs[Keys.CHUNK_OVERLAP] ?: 0f,
                showSpectrogram = prefs[Keys.SHOW_SPECTROGRAM] ?: true,
                sortByAdjustedConfidence = prefs[Keys.SORT_ADJUSTED] ?: false,
                preciseRecognitionServerUrl = prefs[Keys.PRECISE_URL] ?: AppSettings.DEFAULT_PRECISE_RECOGNITION_SERVER_URL,
                preciseRecognitionAuthToken = prefs[Keys.PRECISE_AUTH_TOKEN] ?: "",
                ebirdApiKey = prefs[Keys.EBIRD_API_KEY] ?: "",
                preciseAcousticModel = prefs[Keys.PRECISE_ACOUSTIC_MODEL] ?: AppSettings.DEFAULT_PRECISE_ACOUSTIC_MODEL,
                maxSelectionDurationSec = prefs[Keys.MAX_SELECTION_SEC] ?: 15f,
                defaultPreciseOverlapSec = prefs[Keys.PRECISE_OVERLAP_SEC] ?: 1.5f,
                defaultPreciseMinConfidence = prefs[Keys.PRECISE_MIN_CONFIDENCE] ?: 0.01f,
                defaultPreciseTopK = prefs[Keys.PRECISE_TOP_K] ?: 10,
                owlettModelId = prefs[Keys.OWLETT_MODEL] ?: AppSettings.DEFAULT_OWLETT_MODEL,
                owlettAvailableModels = prefs[Keys.OWLETT_MODELS]
                    ?.sorted()
                    ?.takeIf(List<String>::isNotEmpty)
                    ?: AppSettings.DEFAULT_OWLETT_MODELS,
                owlettAutomaticSkillsEnabled = prefs[Keys.OWLETT_AUTO_SKILLS] ?: true,
            owlettSystemPrompt = prefs[Keys.OWLETT_PROMPT] ?: "",
            owlettAutomationMode = prefs[Keys.OWLETT_AUTOMATION] ?: "confirm_writes"
            )
            val next = transform(current)
            prefs[Keys.APPEARANCE] = next.appearance
            prefs[Keys.COLOR_THEME] = ColorTheme.normalize(next.colorTheme)
            prefs[Keys.MIN_AUDIO] = next.minimumAudioConfidence
            prefs[Keys.MIN_META] = next.minimumMetaConfidence
            prefs[Keys.USE_META] = next.useMetaModel
            prefs[Keys.USE_LOCATION] = next.useLocation
            prefs[Keys.CHUNK_OVERLAP] = next.chunkOverlapSec
            prefs[Keys.SHOW_SPECTROGRAM] = next.showSpectrogram
            prefs[Keys.SORT_ADJUSTED] = next.sortByAdjustedConfidence
            prefs[Keys.PRECISE_URL] = next.preciseRecognitionServerUrl
            prefs[Keys.PRECISE_AUTH_TOKEN] = next.preciseRecognitionAuthToken
            prefs[Keys.EBIRD_API_KEY] = next.ebirdApiKey
            prefs[Keys.PRECISE_ACOUSTIC_MODEL] = next.preciseAcousticModel
            prefs[Keys.MAX_SELECTION_SEC] = next.maxSelectionDurationSec
            prefs[Keys.PRECISE_OVERLAP_SEC] = next.defaultPreciseOverlapSec
            prefs[Keys.PRECISE_MIN_CONFIDENCE] = next.defaultPreciseMinConfidence
            prefs[Keys.PRECISE_TOP_K] = next.defaultPreciseTopK
            prefs[Keys.OWLETT_MODEL] = next.owlettModelId
            prefs[Keys.OWLETT_MODELS] = next.owlettAvailableModels.toSet()
            prefs[Keys.OWLETT_AUTO_SKILLS] = next.owlettAutomaticSkillsEnabled
            prefs[Keys.OWLETT_PROMPT] = next.owlettSystemPrompt.take(10_000)
            prefs[Keys.OWLETT_AUTOMATION] = if (next.owlettAutomationMode == "automatic") "automatic" else "confirm_writes"
        }
    }

    private object Keys {
        val OWLETT_PROMPT = stringPreferencesKey("owlett_system_prompt")
        val OWLETT_AUTOMATION = stringPreferencesKey("owlett_automation_mode")
        val APPEARANCE = stringPreferencesKey("appearance")
        val COLOR_THEME = stringPreferencesKey("color_theme")
        val MIN_AUDIO = floatPreferencesKey("minimum_audio_confidence")
        val MIN_META = floatPreferencesKey("minimum_meta_confidence")
        val USE_META = booleanPreferencesKey("use_meta_model")
        val USE_LOCATION = booleanPreferencesKey("use_location")
        val CHUNK_OVERLAP = floatPreferencesKey("chunk_overlap_sec")
        val SHOW_SPECTROGRAM = booleanPreferencesKey("show_spectrogram")
        val SORT_ADJUSTED = booleanPreferencesKey("sort_by_adjusted_confidence")
        val PRECISE_URL = stringPreferencesKey("precise_recognition_server_url")
        val PRECISE_AUTH_TOKEN = stringPreferencesKey("precise_recognition_auth_token")
        val EBIRD_API_KEY = stringPreferencesKey("ebird_api_key")
        val PRECISE_ACOUSTIC_MODEL = stringPreferencesKey("precise_acoustic_model")
        val MAX_SELECTION_SEC = floatPreferencesKey("max_selection_duration_sec")
        val PRECISE_OVERLAP_SEC = floatPreferencesKey("default_precise_overlap_sec")
        val PRECISE_MIN_CONFIDENCE = floatPreferencesKey("default_precise_min_confidence")
        val PRECISE_TOP_K = intPreferencesKey("default_precise_top_k")
        val OWLETT_MODEL = stringPreferencesKey("owlett_model_id")
        val OWLETT_MODELS = stringSetPreferencesKey("owlett_available_models")
        val OWLETT_AUTO_SKILLS = booleanPreferencesKey("owlett_automatic_skills_enabled")
    }
}
