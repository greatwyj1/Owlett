package com.example.birdingsoundmvp.owlett

data class OwlettConversation(
    val id: Long,
    val title: String,
    val manuallyRenamed: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

enum class OwlettMessageRole(val databaseValue: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    companion object {
        fun fromDatabase(value: String): OwlettMessageRole =
            entries.firstOrNull { it.databaseValue == value } ?: ASSISTANT
    }
}

enum class OwlettMessageStatus(val databaseValue: String) {
    COMPLETE("complete"),
    THINKING("thinking"),
    STREAMING("streaming"),
    STOPPED("stopped"),
    ERROR("error");

    companion object {
        fun fromDatabase(value: String): OwlettMessageStatus =
            entries.firstOrNull { it.databaseValue == value } ?: COMPLETE
    }
}

enum class OwlettSkillRunStatus(val databaseValue: String) {
    WAITING_INPUT("waiting_input"),
    WAITING_CONFIRMATION("waiting_confirmation"),
    EXECUTING("executing"),
    COMPLETE("complete"),
    CANCELLED("cancelled"),
    ERROR("error"),
    INTERRUPTED("interrupted");

    companion object {
        fun fromDatabase(value: String): OwlettSkillRunStatus =
            entries.firstOrNull { it.databaseValue == value } ?: ERROR
    }
}

data class OwlettMessage(
    val id: Long,
    val conversationId: Long,
    val role: OwlettMessageRole,
    val content: String,
    val attachmentJson: String?,
    val attachmentLabel: String?,
    val modelId: String?,
    val status: OwlettMessageStatus,
    val errorMessage: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val reasoningContent: String? = null,
    val toolCallsJson: String? = null,
    val toolCallId: String? = null,
    val manualSkillId: String? = null,
    val turnConfigJson: String? = null
)

data class OwlettSkillRun(
    val id: Long,
    val conversationId: Long,
    val assistantMessageId: Long,
    val toolCallId: String,
    val skillId: String,
    val argumentsJson: String,
    val previewJson: String?,
    val resultJson: String?,
    val status: OwlettSkillRunStatus,
    val errorMessage: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

data class OwlettSkillDescriptor(
    val id: String,
    val slashCommand: String,
    val displayName: String,
    val description: String,
    val writesAppData: Boolean
)

data class OwlettAnalysisProgress(
    val isRunning: Boolean,
    val progress: Float,
    val message: String,
    val error: String? = null
)

data class OwlettToolDefinition(
    val name: String,
    val description: String,
    val parametersJsonSchema: String
)

data class OwlettToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

enum class OwlettToolChoiceMode {
    NONE,
    AUTO,
    REQUIRED,
    NAMED
}

data class OwlettToolChoice(
    val mode: OwlettToolChoiceMode,
    val toolName: String? = null
)

data class OwlettPlanPickerItem(
    val id: Long,
    val name: String,
    val plannedDate: String,
    val hotspotName: String
)

data class PlanAttachmentSnapshot(
    val schemaVersion: Int = 2,
    val generatedAtMs: Long,
    val planId: Long,
    val planName: String,
    val plannedDate: String,
    val regionCode: String,
    val hotspot: PlanAttachmentHotspot,
    val expectedSpecies: List<PlanAttachmentSpecies>,
    val likelySpecies: List<PlanAttachmentLikelySpecies>,
    val rareObservations: List<PlanAttachmentRareObservation>,
    val linkedCompletedTrips: List<PlanAttachmentTrip>,
    val recognizedSpecies: List<PlanAttachmentRecognizedSpecies>,
    val likelySpeciesTotal: Int,
    val likelySpeciesOmitted: Int,
    val rareObservationsTotal: Int,
    val rareObservationsOmitted: Int,
    val recognizedSpeciesTotal: Int,
    val recognizedSpeciesOmitted: Int,
    val analysisAvailable: Boolean,
    val regionName: String = "",
    val observationSource: String = "ebird",
    val recentObservationKind: String = "notable"
)

data class PlanAttachmentHotspot(
    val id: String,
    val name: String,
    val latitude: Double?,
    val longitude: Double?
)

data class PlanAttachmentSpecies(
    val speciesCode: String,
    val scientificName: String,
    val commonName: String,
    val displayName: String
)

data class PlanAttachmentLikelySpecies(
    val speciesCode: String,
    val scientificName: String,
    val commonName: String,
    val displayName: String,
    val historicalFrequency: Float?,
    val currentFrequency: Float?,
    val combinedFrequency: Float
)

data class PlanAttachmentRareObservation(
    val speciesCode: String,
    val scientificName: String,
    val commonName: String,
    val displayName: String,
    val observedAt: String,
    val locationName: String,
    val count: Int?,
    val provisional: Boolean
)

data class PlanAttachmentTrip(
    val tripId: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val durationMs: Long,
    val detectionCount: Int,
    val uniqueSpeciesCount: Int
)

data class PlanAttachmentRecognizedSpecies(
    val scientificName: String,
    val commonName: String,
    val displayName: String,
    val maxConfidence: Float,
    val sources: Set<String>,
    val tripCount: Int
)

data class OwlettChatRequestMessage(
    val role: OwlettMessageRole,
    val content: String,
    val reasoningContent: String? = null,
    val toolCalls: List<OwlettToolCall> = emptyList(),
    val toolCallId: String? = null
)

data class OwlettChatRequest(
    val modelId: String,
    val messages: List<OwlettChatRequestMessage>,
    val maxCompletionTokens: Int = 8_192,
    val tools: List<OwlettToolDefinition> = emptyList(),
    val toolChoice: OwlettToolChoice = OwlettToolChoice(OwlettToolChoiceMode.NONE),
    val thinkingEnabled: Boolean = true
)

sealed interface OwlettStreamEvent {
    data class Thinking(val reasoningDelta: String = "") : OwlettStreamEvent
    data class TextDelta(val text: String) : OwlettStreamEvent
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argumentsDelta: String?
    ) : OwlettStreamEvent
    data class Completed(val modelId: String, val finishReason: String? = null) : OwlettStreamEvent
}

data class OwlettSettingsUiState(
    val hasApiKey: Boolean = false,
    val maskedApiKey: String = "",
    val selectedModelId: String = "deepseek-v4-flash",
    val availableModels: List<String> = listOf("deepseek-v4-flash", "deepseek-v4-pro"),
    val isTesting: Boolean = false,
    val connectionMessage: String? = null,
    val connectionError: String? = null,
    val automaticSkillsEnabled: Boolean = true,
    val hasXenoCantoApiKey: Boolean = false,
    val maskedXenoCantoApiKey: String = "",
    val isTestingXenoCanto: Boolean = false,
    val xenoCantoMessage: String? = null,
    val xenoCantoError: String? = null,
    val birdCallCacheBytes: Long = 0,
    val clearingBirdCallCache: Boolean = false
)

enum class PendingConversationAction {
    NEW_CHAT,
    SWITCH_CHAT
}

data class OwlettUiState(
    val isLoading: Boolean = true,
    val conversations: List<OwlettConversation> = emptyList(),
    val activeConversationId: Long? = null,
    val messages: List<OwlettMessage> = emptyList(),
    val skillRuns: List<OwlettSkillRun> = emptyList(),
    val draft: String = "",
    val draftSelectionStart: Int = 0,
    val draftSelectionEnd: Int = 0,
    val slashMenuDismissed: Boolean = false,
    val selectedManualSkillId: String? = null,
    val skills: List<OwlettSkillDescriptor> = emptyList(),
    val selectedPlan: OwlettPlanPickerItem? = null,
    val availablePlans: List<OwlettPlanPickerItem> = emptyList(),
    val selectedTrip: OwlettTripPickerItem? = null,
    val availableTrips: List<OwlettTripPickerItem> = emptyList(),
    val clipPlayback: OwlettClipPlayback = OwlettClipPlayback(),
    val showConversationList: Boolean = false,
    val showPlanPicker: Boolean = false,
    val showExternalConsent: Boolean = false,
    val isStreaming: Boolean = false,
    val pendingAction: PendingConversationAction? = null,
    val pendingConversationId: Long? = null,
    val bannerMessage: String? = null,
    val playingRecordingId: String? = null,
    val birdCallPlayback: BirdCallPlayback = BirdCallPlayback(),
    val planAnalysisProgress: Map<Long, OwlettAnalysisProgress> = emptyMap(),
    val settings: OwlettSettingsUiState = OwlettSettingsUiState()
)
