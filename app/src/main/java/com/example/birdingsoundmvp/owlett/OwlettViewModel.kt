package com.example.birdingsoundmvp.owlett

import com.example.birdingsoundmvp.i18n.AppText

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aallam.openai.api.exception.AuthenticationException
import com.aallam.openai.api.exception.GenericIOException
import com.aallam.openai.api.exception.OpenAIAPIException
import com.aallam.openai.api.exception.OpenAIHttpException
import com.aallam.openai.api.exception.OpenAIServerException
import com.aallam.openai.api.exception.OpenAITimeoutException
import com.aallam.openai.api.exception.RateLimitException
import com.example.birdingsoundmvp.planning.EbirdRecentObservationsClient
import com.example.birdingsoundmvp.planning.PlanAnalysisManager
import com.example.birdingsoundmvp.planning.PlanAnalysisResult
import com.example.birdingsoundmvp.planning.PlansTripsRepository
import com.example.birdingsoundmvp.settings.AppSettings
import com.example.birdingsoundmvp.settings.SettingsRepository
import com.example.birdingsoundmvp.taxonomy.BirdTaxonomyRepository
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OwlettViewModel(application: Application) : AndroidViewModel(application) {
    private val database = OwlettChatDatabase(application)
    private val apiKeyStore = OwlettApiKeyStore(application)
    private val xenoCantoApiKeyStore = XenoCantoApiKeyStore(application)
    private val settingsRepository = SettingsRepository(application)
    private val attachmentBuilder = OwlettPlanAttachmentBuilder(application)
    private val tripRepository = com.example.birdingsoundmvp.trip.TripHistoryRepository(application)
    private val plansRepository = PlansTripsRepository(application)
    private val taxonomy = BirdTaxonomyRepository(application)
    private val ebirdClient = EbirdRecentObservationsClient(cacheDirectory = java.io.File(application.cacheDir, "ebird-reference"))
    private val xenoCantoClient = XenoCantoClient()
    private val birdCallLocationResolver = BirdCallLocationResolver(application)
    private val observationCache = OwlettObservationCache(java.io.File(application.cacheDir, "owlett-observations"))
    private val analysisManager = PlanAnalysisManager.get(application)
    val observationSources = com.example.birdingsoundmvp.planning.BirdObservationSources.get(application)
    private val skillRegistry = OwlettSkillRegistry.create(plansRepository, ebirdClient, taxonomy, xenoCantoClient, tripRepository,
        observationSources, analysisManager::analyzeAndAwait)
    private val toolExecutor by lazy { OwlettToolExecutor(skillRegistry.tools + OwlettBusinessTools(
        plansRepository, tripRepository, taxonomy,
        observationSources, settingsRepository, ::executePlaybackTool).create()) }
    private val chatEngine: OwlettChatEngine = DeepSeekChatEngine()
    private val agentRunner = OwlettAgentRunner(chatEngine)
    private val gson = Gson()
    private val recordingPlayer = OwlettRecordingPlayer(
        application,
        onPlayingChanged = { recordingId ->
            _state.update { it.copy(playingRecordingId = recordingId) }
        },
        onError = { message ->
            _state.update { it.copy(bannerMessage = message) }
        }
    )

    private val _state = MutableStateFlow(OwlettUiState(skills = OwlettSceneSkills.all.map { it.descriptor }))
    val state: StateFlow<OwlettUiState> = _state.asStateFlow()

    @Volatile private var appSettings = AppSettings()
    @Volatile private var activeAssistantMessageId: Long? = null
    private var streamJob: Job? = null
    private val resumingToolBatches = Collections.synchronizedSet(mutableSetOf<Long>())

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                appSettings = settings
                _state.update { current ->
                    current.copy(
                        settings = current.settings.copy(
                            selectedModelId = settings.owlettModelId,
                            availableModels = settings.owlettAvailableModels,
                            automaticSkillsEnabled = settings.owlettAutomaticSkillsEnabled
                        )
                    )
                }
            }
        }
        viewModelScope.launch {
            analysisManager.tasks.collect { tasks ->
                _state.update { current ->
                    current.copy(
                        planAnalysisProgress = tasks.mapValues { (_, task) ->
                            OwlettAnalysisProgress(task.isRunning, task.progress, task.message, task.error)
                        }
                    )
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                _state.update { it.copy(clipPlayback = recordingPlayer.snapshot(), birdCallPlayback = recordingPlayer.birdCallSnapshot(),
                    settings = it.settings.copy(birdCallCacheBytes = recordingPlayer.cacheBytes())) }
                kotlinx.coroutines.delay(200)
            }
        }
        loadInitialState()
    }

    fun updateDraft(value: String) {
        updateDraftSelection(value, value.length, value.length)
    }

    fun updateDraftSelection(value: String, start: Int, end: Int) {
        _state.update {
            it.copy(draft = value, draftSelectionStart = start, draftSelectionEnd = end,
                selectedManualSkillId = OwlettSlashCommands.selected(value, it.skills)?.second?.id,
                slashMenuDismissed = false, bannerMessage = null)
        }
    }

    fun selectManualSkill(skillId: String) {
        val skill = OwlettSceneSkills.find(skillId) ?: return
        _state.update { state ->
            val edit = OwlettSlashCommands.complete(state.draft, state.draftSelectionEnd,
                skill.descriptor.slashCommand, state.skills)
            state.copy(selectedManualSkillId = skillId, draft = edit.text,
                draftSelectionStart = edit.cursor, draftSelectionEnd = edit.cursor,
                slashMenuDismissed = true, bannerMessage = null)
        }
    }

    fun clearManualSkill() = _state.update {
        val text = OwlettSlashCommands.withoutCommands(it.draft, it.skills)
        it.copy(selectedManualSkillId = null, draft = text, draftSelectionStart = text.length,
            draftSelectionEnd = text.length, slashMenuDismissed = true)
    }
    fun showConversationList() = _state.update { it.copy(showConversationList = true) }
    fun hideConversationList() = _state.update { it.copy(showConversationList = false) }

    fun showPlanPicker() {
        _state.update { it.copy(showPlanPicker = true, bannerMessage = null) }
        refreshPlans()
    }

    fun hidePlanPicker() = _state.update { it.copy(showPlanPicker = false) }
    fun selectPlan(plan: OwlettPlanPickerItem) =
        _state.update { it.copy(selectedPlan = plan, selectedTrip = null, showPlanPicker = false, bannerMessage = null) }
    fun selectTrip(trip: OwlettTripPickerItem) = _state.update { it.copy(selectedTrip = trip, selectedPlan = null, showPlanPicker = false) }
    fun removeSelectedPlan() = _state.update { it.copy(selectedPlan = null, selectedTrip = null) }
    fun clearBanner() = _state.update { it.copy(bannerMessage = null) }

    fun requestNewConversation() {
        if (_state.value.isStreaming) {
            _state.update {
                it.copy(
                    pendingAction = PendingConversationAction.NEW_CHAT,
                    pendingConversationId = null,
                    showConversationList = false
                )
            }
        } else openBlankConversation()
    }

    fun requestConversation(conversationId: Long) {
        if (conversationId == _state.value.activeConversationId) {
            hideConversationList()
            return
        }
        if (_state.value.isStreaming) {
            _state.update {
                it.copy(
                    pendingAction = PendingConversationAction.SWITCH_CHAT,
                    pendingConversationId = conversationId,
                    showConversationList = false
                )
            }
        } else openConversation(conversationId)
    }

    fun dismissPendingAction() = _state.update { it.copy(pendingAction = null, pendingConversationId = null) }

    fun confirmPendingAction() {
        val action = _state.value.pendingAction ?: return
        val conversationId = _state.value.pendingConversationId
        _state.update { it.copy(pendingAction = null, pendingConversationId = null) }
        viewModelScope.launch {
            streamJob?.cancelAndJoin()
            recordingPlayer.stop()
            when (action) {
                PendingConversationAction.NEW_CHAT -> openBlankConversation()
                PendingConversationAction.SWITCH_CHAT -> conversationId?.let(::openConversation)
            }
        }
    }

    fun renameConversation(conversationId: Long, title: String) {
        val normalized = title.trim()
        if (normalized.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            database.renameConversation(conversationId, normalized)
            reloadConversationList()
        }
    }

    fun deleteConversation(conversationId: Long) {
        if (_state.value.isStreaming) return
        if (_state.value.activeConversationId == conversationId) recordingPlayer.stop()
        viewModelScope.launch(Dispatchers.IO) {
            database.deleteConversation(conversationId)
            observationCache.delete(conversationId)
            val conversations = database.listConversations()
            val activeId = if (_state.value.activeConversationId == conversationId) {
                conversations.firstOrNull()?.id
            } else {
                _state.value.activeConversationId?.takeIf { id -> conversations.any { it.id == id } }
            }
            _state.update {
                it.copy(
                    conversations = conversations,
                    activeConversationId = activeId,
                    messages = activeId?.let(database::listMessages).orEmpty(),
                    skillRuns = activeId?.let(database::listSkillRuns).orEmpty(),
                    selectedPlan = null, selectedTrip = null,
                    selectedManualSkillId = null,
                    showConversationList = false
                )
            }
        }
    }

    fun acceptExternalConsent() {
        com.example.birdingsoundmvp.ui.OwlettHelp.mark(getApplication(), "deepseek-send")
        _state.update { it.copy(showExternalConsent = false) }
        sendMessage()
    }
    fun dismissExternalConsent() = _state.update { it.copy(showExternalConsent = false) }
    fun sendMessage() {
        if (com.example.birdingsoundmvp.transfer.DataTransferGate.active) return
        val current = _state.value
        if (!com.example.birdingsoundmvp.ui.OwlettHelp.seen(getApplication(), "deepseek-send")) {
            _state.update { it.copy(showExternalConsent = true) }; return
        }
        val turnConfig = OwlettTurnConfig.from(appSettings)
        if (current.isStreaming) return
        val parsedCommand = parseSlashCommand(current.draft)
        val forcedSkillId = current.selectedManualSkillId ?: parsedCommand?.first
        val content = (parsedCommand?.second ?: current.draft).trim()
        if (content.isBlank() && forcedSkillId == null) return
        val userVisibleContent = if (content.isBlank()) {
            OwlettSceneSkills.find(forcedSkillId)?.descriptor?.let { "${it.slashCommand} ${it.displayName}" } ?: return
        } else content
        _state.update { it.copy(isStreaming = true, bannerMessage = null) }
        streamJob = viewModelScope.launch {
            var assistantMessageId: Long? = null
            try {
                val apiKey = withContext(Dispatchers.IO) { apiKeyStore.load() }
                if (apiKey.isNullOrBlank()) {
                    _state.update { it.copy(bannerMessage = AppText.get("Add a DeepSeek API key in Settings")) }
                    return@launch
                }
                current.activeConversationId?.let { cancelPendingRuns(it) }
                val selectedPlan = current.selectedPlan
                val attachment = selectedPlan?.let { plan -> withContext(Dispatchers.IO) { attachmentBuilder.build(plan.id) } }
                val tripAttachment = current.selectedTrip?.let { withContext(Dispatchers.IO) { attachmentBuilder.buildTrip(it.tripId) } }
                val prepared = withContext(Dispatchers.IO) {
                    val conversationId = current.activeConversationId
                        ?: database.createConversation(OwlettTitleGenerator.fromFirstMessage(userVisibleContent))
                    database.insertMessage(
                        conversationId = conversationId,
                        role = OwlettMessageRole.USER,
                        content = userVisibleContent,
                        attachmentJson = tripAttachment ?: attachment?.let(attachmentBuilder::toJson),
                        attachmentLabel = current.selectedTrip?.let { "录音行程：${it.label}" } ?: selectedPlan?.let { AppText.format("Plan: {0}", it.name) },
                        manualSkillId = forcedSkillId,
                        turnConfigJson = turnConfig.toJson()
                    )
                    val id = insertAssistantPlaceholder(conversationId)
                    PreparedRequest(
                        conversationId,
                        id,
                        attachment?.planId,
                        database.listConversations(),
                        database.listMessages(conversationId),
                        database.listSkillRuns(conversationId)
                    )
                }
                assistantMessageId = prepared.assistantMessageId
                activeAssistantMessageId = assistantMessageId
                _state.update {
                    it.copy(
                        activeConversationId = prepared.conversationId,
                        conversations = prepared.conversations,
                        messages = prepared.messages,
                        skillRuns = prepared.skillRuns,
                        draft = "",
                        selectedPlan = null, selectedTrip = null,
                        selectedManualSkillId = null
                    )
                }
                runAgent(apiKey, prepared.conversationId, prepared.assistantMessageId, forcedSkillId, prepared.attachedPlanId)
            } catch (cancelled: CancellationException) {
                (activeAssistantMessageId ?: assistantMessageId)?.let { finishInterruptedMessage(it) }
            } catch (error: Throwable) {
                val id = activeAssistantMessageId ?: assistantMessageId
                if (id == null) {
                    _state.update {
                        it.copy(
                            bannerMessage = userFacingError(error),
                            selectedPlan = if (error is PlanAttachmentUnavailableException) null else it.selectedPlan
                        )
                    }
                } else finishFailedMessage(id, userFacingError(error))
            } finally {
                activeAssistantMessageId = null
                _state.update { it.copy(isStreaming = false) }
                streamJob = null
            }
        }
    }

    fun stopResponse() {
        streamJob?.cancel()
    }

    fun retryMessage(messageId: Long) {
        val current = _state.value
        if (current.isStreaming) return
        val assistantIndex = current.messages.indexOfFirst { it.id == messageId }
        val assistant = current.messages.getOrNull(assistantIndex) ?: return
        if (assistant.role != OwlettMessageRole.ASSISTANT ||
            assistant.status !in setOf(OwlettMessageStatus.ERROR, OwlettMessageStatus.STOPPED)
        ) return
        val user = current.messages.take(assistantIndex).lastOrNull { it.role == OwlettMessageRole.USER } ?: return
        _state.update { it.copy(isStreaming = true, bannerMessage = null) }
        streamJob = viewModelScope.launch {
            activeAssistantMessageId = messageId
            try {
                val apiKey = withContext(Dispatchers.IO) { apiKeyStore.load() }
                if (apiKey.isNullOrBlank()) {
                    finishFailedMessage(messageId, AppText.get("Add a DeepSeek API key in Settings"))
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    database.updateMessage(
                        messageId, "", appSettings.owlettModelId, OwlettMessageStatus.THINKING, null,
                        reasoningContent = null, toolCallsJson = null
                    )
                }
                reloadActiveConversation(assistant.conversationId)
                runAgent(apiKey, assistant.conversationId, messageId, user.manualSkillId, attachedPlanIdFrom(user))
            } catch (cancelled: CancellationException) {
                finishInterruptedMessage(activeAssistantMessageId ?: messageId)
            } catch (error: Throwable) {
                finishFailedMessage(activeAssistantMessageId ?: messageId, userFacingError(error))
            } finally {
                activeAssistantMessageId = null
                _state.update { it.copy(isStreaming = false) }
                streamJob = null
            }
        }
    }

    fun selectSkillOption(runId: Long, optionId: String) {
        val run = _state.value.skillRuns.firstOrNull { it.id == runId } ?: return
        if (run.status != OwlettSkillRunStatus.WAITING_INPUT || _state.value.isStreaming) return
        val card = OwlettSkillCardCodec.decode(run.previewJson) ?: return
        val option = card.options.firstOrNull { it.id == optionId } ?: return
        val skill = toolExecutor.find(run.skillId) ?: return
        launchPrepareExistingRun(run, skill.applySelection(run.argumentsJson, card.selectionField, option.valueJson))
    }

    fun confirmSkillRun(runId: Long) {
        if (com.example.birdingsoundmvp.transfer.DataTransferGate.active) return
        val run = _state.value.skillRuns.firstOrNull { it.id == runId } ?: return
        if (run.status != OwlettSkillRunStatus.WAITING_CONFIRMATION || _state.value.isStreaming) return
        val card = OwlettSkillCardCodec.decode(run.previewJson) ?: return
        if (card.action == "analyze") {
            val planId = card.planId ?: return
            _state.update { it.copy(isStreaming = true) }
            streamJob = viewModelScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        database.updateSkillRun(
                            run.id, run.argumentsJson, run.previewJson, status = OwlettSkillRunStatus.EXECUTING
                        )
                    }
                    reloadActiveConversation(run.conversationId)
                    when (val result = analysisManager.analyzeAndAwait(planId, appSettings.ebirdApiKey)) {
                        is PlanAnalysisResult.Success -> {
                            val args = JsonParser.parseString(run.argumentsJson).asJsonObject.apply {
                                addProperty("_analysis_refreshed", true)
                            }
                            prepareExistingRun(run, gson.toJson(args))
                        }
                        is PlanAnalysisResult.Failure -> completeRunWithError(run, result.message)
                    }
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) { markRunInterrupted(run) }
                } catch (error: Throwable) {
                    completeRunWithError(run, userFacingError(error))
                } finally {
                    _state.update { it.copy(isStreaming = false) }
                    streamJob = null
                }
            }
        } else executePendingRun(run)
    }

    fun cancelSkillRun(runId: Long) {
        val run = _state.value.skillRuns.firstOrNull { it.id == runId } ?: return
        if (run.status !in setOf(
                OwlettSkillRunStatus.WAITING_INPUT,
                OwlettSkillRunStatus.WAITING_CONFIRMATION,
                OwlettSkillRunStatus.INTERRUPTED
            ) || _state.value.isStreaming
        ) return
        _state.update { it.copy(isStreaming = true) }
        streamJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { cancelRun(run) }
                reloadActiveConversation(run.conversationId)
                resumeToolBatchIfReady(run)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { markRunInterrupted(run) }
            } catch (error: Throwable) {
                completeRunWithError(run, userFacingError(error))
            } finally {
                activeAssistantMessageId = null
                _state.update { it.copy(isStreaming = false) }
                streamJob = null
            }
        }
    }

    fun retrySkillRun(runId: Long) {
        val run = _state.value.skillRuns.firstOrNull { it.id == runId } ?: return
        if (run.status !in setOf(OwlettSkillRunStatus.INTERRUPTED, OwlettSkillRunStatus.ERROR) || _state.value.isStreaming) return
        if (run.status == OwlettSkillRunStatus.ERROR) {
            beginRetryTurn(run)
            return
        }
        launchPrepareExistingRun(run, run.argumentsJson)
    }

    private fun beginRetryTurn(previous: OwlettSkillRun) {
        _state.update { it.copy(isStreaming = true) }
        streamJob = viewModelScope.launch {
            var pending: OwlettSkillRun? = null
            try {
                val fresh = withContext(Dispatchers.IO) {
                    cancelPendingRuns(previous.conversationId)
                    val messages = database.listMessages(previous.conversationId)
                    val user = messages.takeWhile { it.id != previous.assistantMessageId }
                        .lastOrNull { it.role == OwlettMessageRole.USER }
                    val args = runCatching { JsonParser.parseString(previous.argumentsJson).asJsonObject }.getOrNull()
                        ?: com.google.gson.JsonObject()
                    args.addProperty("_retry_of_run", previous.id)
                    val argsJson = gson.toJson(args)
                    database.insertMessage(previous.conversationId, OwlettMessageRole.USER,
                        AppText.get("Retry"), attachmentJson = user?.attachmentJson, attachmentLabel = user?.attachmentLabel,
                        manualSkillId = user?.manualSkillId, turnConfigJson = user?.turnConfigJson)
                    val assistantId = insertAssistantPlaceholder(previous.conversationId)
                    val callId = "retry-" + java.util.UUID.randomUUID()
                    database.updateMessage(assistantId, "", appSettings.owlettModelId, OwlettMessageStatus.COMPLETE, null,
                        toolCallsJson = gson.toJson(listOf(OwlettToolCall(callId, previous.skillId, argsJson))))
                    val id = database.insertSkillRun(previous.conversationId, assistantId, callId, previous.skillId,
                        argsJson, status = OwlettSkillRunStatus.EXECUTING)
                    database.listSkillRuns(previous.conversationId).first { it.id == id }
                }
                pending = fresh
                prepareExistingRun(fresh, fresh.argumentsJson)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { pending?.let { markRunInterrupted(it) } }
            } catch (error: Throwable) {
                if (pending != null) completeRunWithError(pending, userFacingError(error))
                else _state.update { it.copy(bannerMessage = userFacingError(error)) }
            }
            finally { _state.update { it.copy(isStreaming = false) }; streamJob = null }
        }
    }

    fun organizePlan(planId: Long) {
        if (_state.value.isStreaming) return
        _state.update { it.copy(isStreaming = true) }
        streamJob = viewModelScope.launch {
            var pending: OwlettSkillRun? = null
            try {
                val conversationId = _state.value.activeConversationId ?: return@launch
                val run = withContext(Dispatchers.IO) {
                    cancelPendingRuns(conversationId)
                    database.insertMessage(conversationId, OwlettMessageRole.USER, AppText.get("整理这个计划的近期鸟况"), manualSkillId = "organize_activity", turnConfigJson = OwlettTurnConfig.from(appSettings).toJson())
                    val assistantId = insertAssistantPlaceholder(conversationId)
                    val callId = "activity-${java.util.UUID.randomUUID()}"
                    val args = gson.toJson(mapOf("_plan_id" to planId))
                    database.updateMessage(assistantId, "", appSettings.owlettModelId, OwlettMessageStatus.COMPLETE, null,
                        toolCallsJson = gson.toJson(listOf(OwlettToolCall(callId, "organize_activity", args))))
                    val id = database.insertSkillRun(conversationId, assistantId, callId, "organize_activity", args, status = OwlettSkillRunStatus.EXECUTING)
                    database.listSkillRuns(conversationId).first { it.id == id }
                }
                pending = run
                prepareExistingRun(run, run.argumentsJson)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { pending?.let { markRunInterrupted(it) } }
            } catch (error: Throwable) {
                if (pending != null) completeRunWithError(pending, userFacingError(error))
                else _state.update { it.copy(bannerMessage = userFacingError(error)) }
            }
            finally { _state.update { it.copy(isStreaming = false) }; streamJob = null }
        }
    }

    fun cancelPlanAnalysis(planId: Long) { analysisManager.cancel(planId) }
    fun toggleRecording(recording: XenoCantoRecording) = recordingPlayer.toggle(recording)

    fun clearBirdCallCache() {
        if (_state.value.settings.clearingBirdCallCache) return
        _state.update { it.copy(settings = it.settings.copy(clearingBirdCallCache = true)) }
        viewModelScope.launch {
            try {
                recordingPlayer.clearAudioCache()
                _state.update { it.copy(settings = it.settings.copy(xenoCantoMessage = "鸟鸣音频缓存已清空", xenoCantoError = null)) }
            } catch (cancel: CancellationException) { throw cancel }
              catch (_: Exception) { _state.update { it.copy(settings = it.settings.copy(xenoCantoError = "缓存未能完全清空，请重试")) } }
            finally { _state.update { it.copy(settings = it.settings.copy(clearingBirdCallCache = false, birdCallCacheBytes = recordingPlayer.cacheBytes())) } }
        }
    }
    fun toggleClip(clips: List<OwlettAudioClip>, clip: OwlettAudioClip) = recordingPlayer.toggleClip(clips, clip)
    fun moveClip(delta: Int) = recordingPlayer.move(delta)
    fun seekClip(positionMs: Long) = recordingPlayer.seek(positionMs)
    fun setContinuousPlayback(value: Boolean) { recordingPlayer.continuous = value }
    fun stopAudio() = recordingPlayer.stop()

    fun saveApiKey(apiKey: String) {
        val normalized = apiKey.trim()
        if (normalized.isBlank()) {
            _state.update { it.copy(settings = it.settings.copy(connectionError = AppText.get("Enter an API key first"), connectionMessage = null)) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { apiKeyStore.save(normalized) }
                .onSuccess { updateSecureSettings(AppText.get("API key saved"), null) }
                .onFailure { updateSecureSettings(null, AppText.get("Could not store the API key securely")) }
        }
    }

    fun clearApiKey() {
        viewModelScope.launch(Dispatchers.IO) {
            apiKeyStore.clear()
            updateSecureSettings(AppText.get("API key removed"), null)
        }
    }

    fun testConnection(draftApiKey: String) {
        if (_state.value.settings.isTesting) return
        _state.update { it.copy(settings = it.settings.copy(isTesting = true, connectionMessage = null, connectionError = null)) }
        viewModelScope.launch {
            val apiKey = draftApiKey.trim().takeIf(String::isNotBlank) ?: withContext(Dispatchers.IO) { apiKeyStore.load() }
            if (apiKey.isNullOrBlank()) {
                _state.update { it.copy(settings = it.settings.copy(isTesting = false, connectionError = AppText.get("Enter or save an API key first"))) }
                return@launch
            }
            try {
                val models = chatEngine.listModels(apiKey).ifEmpty { AppSettings.DEFAULT_OWLETT_MODELS }
                settingsRepository.update { settings ->
                    settings.copy(
                        owlettAvailableModels = models,
                        owlettModelId = settings.owlettModelId.takeIf { it in models } ?: models.first()
                    )
                }
                _state.update {
                    val suffix = if (draftApiKey.isNotBlank() && !it.settings.hasApiKey) AppText.get("; save the key to start chatting") else ""
                    it.copy(settings = it.settings.copy(isTesting = false, connectionMessage = AppText.format("Connected to DeepSeek{0}", suffix)))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.update { it.copy(settings = it.settings.copy(isTesting = false, connectionError = userFacingError(error))) }
            }
        }
    }

    fun saveXenoCantoApiKey(apiKey: String) {
        val normalized = apiKey.trim()
        if (normalized.isBlank()) {
            _state.update { it.copy(settings = it.settings.copy(xenoCantoError = AppText.get("Enter an API key first"), xenoCantoMessage = null)) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { xenoCantoApiKeyStore.save(normalized) }
                .onSuccess { updateXenoCantoSettings(AppText.get("xeno-canto API key saved"), null) }
                .onFailure { updateXenoCantoSettings(null, AppText.get("Could not store the xeno-canto key securely")) }
        }
    }

    fun clearXenoCantoApiKey() {
        viewModelScope.launch(Dispatchers.IO) {
            xenoCantoApiKeyStore.clear()
            updateXenoCantoSettings(AppText.get("xeno-canto API key removed"), null)
        }
    }

    fun testXenoCantoConnection(draftApiKey: String) {
        if (_state.value.settings.isTestingXenoCanto) return
        _state.update {
            it.copy(settings = it.settings.copy(isTestingXenoCanto = true, xenoCantoMessage = null, xenoCantoError = null))
        }
        viewModelScope.launch(Dispatchers.IO) {
            val apiKey = draftApiKey.trim().takeIf(String::isNotBlank) ?: xenoCantoApiKeyStore.load()
            val result = if (apiKey.isNullOrBlank()) {
                XenoCantoResponse.Failure(AppText.get("Enter or save a xeno-canto API key first."))
            } else xenoCantoClient.test(apiKey)
            _state.update { state ->
                state.copy(
                    settings = state.settings.copy(
                        isTestingXenoCanto = false,
                        xenoCantoMessage = if (result is XenoCantoResponse.Success) AppText.get("Connected to xeno-canto") else null,
                        xenoCantoError = (result as? XenoCantoResponse.Failure)?.message
                    )
                )
            }
        }
    }

    fun setAutomaticSkillsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.update { it.copy(owlettAutomaticSkillsEnabled = enabled) } }
    }

    fun selectModel(modelId: String) {
        if (modelId !in _state.value.settings.availableModels) return
        viewModelScope.launch { settingsRepository.update { it.copy(owlettModelId = modelId) } }
    }

    private suspend fun runAgent(
        apiKey: String,
        conversationId: Long,
        firstAssistantMessageId: Long,
        forcedSkillId: String?,
        attachedPlanId: Long?
    ) {
        val turnUser = withContext(Dispatchers.IO) { database.listMessages(conversationId).takeWhile { it.id != firstAssistantMessageId }.lastOrNull { it.role == OwlettMessageRole.USER } }
        val turnConfig = OwlettTurnConfig.decode(turnUser?.turnConfigJson)
        var assistantMessageId = firstAssistantMessageId
        val manualId = turnUser?.manualSkillId ?: forcedSkillId
        val allowed = allowedTools(manualId, turnConfig)
        turnUser?.let { database.startAgentTask(it.id, turnConfig.toJson()); database.setAgentTaskStatus(it.id, "running") }
        var toolCallCount = withContext(Dispatchers.IO) {
            val messages = database.listMessages(conversationId)
            val userId = messages.lastOrNull { it.id < firstAssistantMessageId && it.role == OwlettMessageRole.USER }?.id ?: 0L
            database.listSkillRuns(conversationId).count { it.assistantMessageId > userId }
        }
        val invalidArgumentSkills = mutableSetOf<String>()
        val manualTurn = withContext(Dispatchers.IO) {
            database.listMessages(conversationId).lastOrNull { it.role == OwlettMessageRole.USER }?.manualSkillId != null
        }
        while (true) {
            val guard = OwlettFailureGuard()
            val previousSteps = database.listSkillRuns(conversationId).filter { it.assistantMessageId > (turnUser?.id ?: 0L) }
            var repeatedFailure = false
            previousSteps.forEach { run -> repeatedFailure = guard.record(run.skillId, run.errorMessage.takeIf { run.status == OwlettSkillRunStatus.ERROR }) }
            if (repeatedFailure) {
                finishFailedMessage(assistantMessageId, "相同操作连续失败3次，已停止。请检查提示后再试。")
                turnUser?.let { database.setAgentTaskStatus(it.id, "error") }
                return
            }
            activeAssistantMessageId = assistantMessageId
            var visibleContent = ""
            var reasoningContent = ""
            var lastPersistedAt = 0L
            val tools = toolExecutor.definitions(allowed)
            val choice = OwlettToolChoice(if (tools.isNotEmpty()) OwlettToolChoiceMode.AUTO else OwlettToolChoiceMode.NONE)
            val request = withContext(Dispatchers.IO) {
                val messages = OwlettContextBuilder.build(database.listMessages(conversationId), thinkingEnabled = !manualTurn, turnConfig = turnConfig).toMutableList()
                val guide = OwlettSceneSkills.find(manualId)?.let { turnConfig.skillInstructions[it.descriptor.id] ?: it.instructions() }
                messages[0] = messages[0].copy(content = messages[0].content + "\n场景技能目录：\n" + OwlettSceneSkills.catalog() +
                    (guide?.let { "\n用户主动选择的操作指南：\n$it" } ?: ""))
                OwlettChatRequest(
                    modelId = turnConfig.modelId,
                    messages = messages,
                    tools = tools,
                    toolChoice = choice,
                    thinkingEnabled = !manualTurn
                )
            }
            val step = agentRunner.runStep(
                apiKey,
                request,
                onThinking = { delta ->
                    reasoningContent += delta
                    if (visibleContent.isBlank()) {
                        updateAssistantInState(
                            assistantMessageId, visibleContent, turnConfig.modelId,
                            OwlettMessageStatus.THINKING, null, reasoningContent
                        )
                    }
                },
                onTextDelta = { delta ->
                    visibleContent += delta
                    updateAssistantInState(
                        assistantMessageId, visibleContent, turnConfig.modelId,
                        OwlettMessageStatus.STREAMING, null, reasoningContent
                    )
                    val now = System.currentTimeMillis()
                    if (now - lastPersistedAt >= STREAM_PERSIST_INTERVAL_MS) {
                        lastPersistedAt = now
                        withContext(Dispatchers.IO) {
                            database.updateMessage(
                                assistantMessageId, visibleContent, turnConfig.modelId,
                                OwlettMessageStatus.STREAMING, null,
                                reasoningContent = reasoningContent, now = now
                            )
                        }
                    }
                }
            )
            visibleContent = step.content
            reasoningContent = step.reasoningContent
            if (step.toolCalls.isEmpty()) {
                if (visibleContent.isBlank()) finishFailedMessage(assistantMessageId, AppText.get("DeepSeek returned an empty response"))
                else finishCompletedMessage(assistantMessageId, visibleContent, step.modelId, reasoningContent)
                turnUser?.let { database.setAgentTaskStatus(it.id, if (visibleContent.isBlank()) "error" else "complete") }
                return
            }
            if (toolCallCount + step.toolCalls.size > agentRunner.maxToolCallsPerTurn) {
                finishFailedMessage(assistantMessageId, "已达到本轮24次工具调用上限，请查看已完成步骤后再继续。")
                turnUser?.let { database.setAgentTaskStatus(it.id, "limit") }
                return
            }
            toolCallCount += step.toolCalls.size
            withContext(NonCancellable + Dispatchers.IO) {
                database.updateMessage(
                    assistantMessageId, visibleContent, step.modelId, OwlettMessageStatus.COMPLETE, null,
                    reasoningContent = reasoningContent, toolCallsJson = gson.toJson(step.toolCalls)
                )
            }
            withContext(NonCancellable) { reloadActiveConversation(conversationId) }
            val baseContext = skillContext("tool-$conversationId-$assistantMessageId", attachedPlanId, conversationId).copy(
                automatic = turnConfig.automatic, attachedTripId = attachedTripIdFrom(turnUser), allowedToolIds = allowed,
                instructionsBySkill = turnConfig.skillInstructions)
            var hasPending = false
            var argumentCorrectionExhausted = false
            for (call in step.toolCalls) {
                // Private preparation/receipt fields can only come from app confirmations.
                val safeArguments = runCatching {
                    JsonParser.parseString(call.argumentsJson).asJsonObject.apply {
                        keySet().filter { it.startsWith("_") }.toList().forEach(::remove)
                    }.toString()
                }.getOrDefault(call.argumentsJson)
                val runId = withContext(NonCancellable + Dispatchers.IO) {
                    database.insertSkillRun(
                        conversationId, assistantMessageId, call.id, call.name, safeArguments,
                        status = OwlettSkillRunStatus.EXECUTING, taskMessageId = turnUser?.id
                    )
                }
                val run = withContext(NonCancellable + Dispatchers.IO) {
                    database.listSkillRuns(conversationId).first { it.id == runId }
                }
                val argumentsAreObject = runCatching {
                    JsonParser.parseString(call.argumentsJson).isJsonObject
                }.getOrDefault(false)
                val correctionAlreadyUsed = !argumentsAreObject && !invalidArgumentSkills.add(call.name)
                val terminal = try {
                    // Publish the running read before it waits on a website or a batch limit.
                    reloadActiveConversation(conversationId)
                    toolExecutor.find(call.name)?.let { OwlettToolParameters.validate(safeArguments, it.toolDefinition.parametersJsonSchema) }
                    if (correctionAlreadyUsed) {
                        argumentCorrectionExhausted = true
                        completeRunWithError(
                            run,
                            AppText.get("The skill arguments were still invalid after one correction."),
                            resumeBatch = false
                        )
                        true
                    } else {
                        withContext(Dispatchers.IO) {
                            prepareNewRun(run, baseContext.copy(operationId = operationId(run)))
                        }
                    }
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) { markRunInterrupted(run) }
                    return
                } catch (error: Throwable) {
                    completeRunWithError(run, userFacingError(error), resumeBatch = false)
                    true
                }
                hasPending = hasPending || !terminal
            }
            reloadActiveConversation(conversationId)
            if (hasPending) { turnUser?.let { database.setAgentTaskStatus(it.id, "waiting") }; return }
            if (argumentCorrectionExhausted) {
                assistantMessageId = withContext(NonCancellable + Dispatchers.IO) {
                    insertAssistantPlaceholder(conversationId)
                }
                activeAssistantMessageId = assistantMessageId
                withContext(NonCancellable) { reloadActiveConversation(conversationId) }
                finishFailedMessage(
                    assistantMessageId,
                    AppText.get("Owlett stopped because a skill request remained invalid after one correction.")
                )
                return
            }
            assistantMessageId = withContext(NonCancellable + Dispatchers.IO) {
                insertAssistantPlaceholder(conversationId)
            }
            activeAssistantMessageId = assistantMessageId
            withContext(NonCancellable) { reloadActiveConversation(conversationId) }
        }
    }

    private suspend fun prepareNewRun(run: OwlettSkillRun, context: OwlettSkillContext): Boolean {
        val skill = toolExecutor.find(run.skillId)
        if (skill == null) {
            completeRunWithError(
                run,
                AppText.format("Owlett does not recognize the requested skill: {0}", run.skillId),
                resumeBatch = false
            )
            return true
        }
        return applyPreparation(run, skill, toolExecutor.prepare(skill, run.argumentsJson, context), context)
    }

    private suspend fun prepareExistingRun(run: OwlettSkillRun, argumentsJson: String) {
        val skill = toolExecutor.find(run.skillId) ?: run {
            completeRunWithError(run, AppText.get("This skill is no longer available."))
            return
        }
        val refreshed = run.copy(argumentsJson = argumentsJson)
        withContext(Dispatchers.IO) {
            database.updateSkillRun(run.id, argumentsJson, status = OwlettSkillRunStatus.EXECUTING)
        }
        val context = contextForRun(refreshed)
        val terminal = withContext(Dispatchers.IO) {
            applyPreparation(refreshed, skill, toolExecutor.prepare(skill, argumentsJson, context), context)
        }
        reloadActiveConversation(run.conversationId)
        if (terminal) resumeToolBatchIfReady(refreshed)
    }

    private suspend fun applyPreparation(
        run: OwlettSkillRun,
        skill: OwlettSkill,
        preparation: OwlettSkillPreparation,
        context: OwlettSkillContext,
        depth: Int = 0
    ): Boolean = when (preparation) {
        is OwlettSkillPreparation.Ready -> {
            finishRun(run, preparation.normalizedArgumentsJson, toolExecutor.execute(skill, preparation.normalizedArgumentsJson, context))
            true
        }
        is OwlettSkillPreparation.Immediate -> {
            withContext(Dispatchers.IO) {
                database.finishSkillRun(
                    runId = run.id,
                    conversationId = run.conversationId,
                    toolCallId = run.toolCallId,
                    argumentsJson = run.argumentsJson,
                    resultJson = preparation.card?.let(OwlettSkillCardCodec::encode),
                    status = if (preparation.isError) OwlettSkillRunStatus.ERROR else OwlettSkillRunStatus.COMPLETE,
                    errorMessage = if (preparation.isError) preparation.card?.summary else null,
                    toolContent = preparation.toolResponse
                )
            }
            true
        }
        is OwlettSkillPreparation.WaitingInput -> {
            withContext(Dispatchers.IO) {
                database.updateSkillRun(
                    run.id,
                    preparation.normalizedArgumentsJson,
                    previewJson = OwlettSkillCardCodec.encode(preparation.card),
                    status = OwlettSkillRunStatus.WAITING_INPUT
                )
            }
            false
        }
        is OwlettSkillPreparation.WaitingConfirmation -> {
            if (context.automatic && depth < 3) {
                database.updateSkillRun(run.id, preparation.normalizedArgumentsJson, previewJson = OwlettSkillCardCodec.encode(preparation.card), status = OwlettSkillRunStatus.EXECUTING)
                reloadActiveConversation(run.conversationId)
                if (preparation.card.action == "analyze") {
                    when (val result = analysisManager.analyzeAndAwait(requireNotNull(preparation.card.planId), context.ebirdApiKey)) {
                        is PlanAnalysisResult.Failure -> { completeRunWithError(run, result.message, false); true }
                        is PlanAnalysisResult.Success -> {
                            val args = JsonParser.parseString(preparation.normalizedArgumentsJson).asJsonObject.apply { addProperty("_analysis_refreshed", true) }.toString()
                            applyPreparation(run, skill, toolExecutor.prepare(skill, args, context), context, depth + 1)
                        }
                    }
                } else {
                    val execution = toolExecutor.execute(skill, preparation.normalizedArgumentsJson, context)
                    if (execution.reprepareArgumentsJson != null) applyPreparation(run, skill, toolExecutor.prepare(skill, execution.reprepareArgumentsJson, context), context, depth + 1)
                    else { finishRun(run, preparation.normalizedArgumentsJson, execution); true }
                }
            } else {
            withContext(Dispatchers.IO) {
                database.updateSkillRun(
                    run.id,
                    preparation.normalizedArgumentsJson,
                    previewJson = OwlettSkillCardCodec.encode(preparation.card),
                    status = OwlettSkillRunStatus.WAITING_CONFIRMATION
                )
            }
            false
            }
        }
    }

    private fun executePendingRun(run: OwlettSkillRun) {
        val skill = toolExecutor.find(run.skillId) ?: return
        _state.update { it.copy(isStreaming = true) }
        streamJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    database.updateSkillRun(run.id, run.argumentsJson, run.previewJson, status = OwlettSkillRunStatus.EXECUTING)
                }
                reloadActiveConversation(run.conversationId)
                val execution = withContext(Dispatchers.IO) {
                    toolExecutor.execute(skill, run.argumentsJson, contextForRun(run).copy(writeAuthorized = true))
                }
                if (execution.reprepareArgumentsJson != null) {
                    prepareExistingRun(run, execution.reprepareArgumentsJson)
                } else {
                    finishRun(run, run.argumentsJson, execution)
                    reloadActiveConversation(run.conversationId)
                    resumeToolBatchIfReady(run)
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { markRunInterrupted(run) }
            } catch (error: Throwable) {
                completeRunWithError(run, userFacingError(error))
            } finally {
                _state.update { it.copy(isStreaming = false) }
                streamJob = null
            }
        }
    }

    private fun launchPrepareExistingRun(run: OwlettSkillRun, argumentsJson: String) {
        _state.update { it.copy(isStreaming = true) }
        streamJob = viewModelScope.launch {
            try {
                prepareExistingRun(run, argumentsJson)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { markRunInterrupted(run) }
            } catch (error: Throwable) {
                completeRunWithError(run, userFacingError(error))
            } finally {
                _state.update { it.copy(isStreaming = false) }
                streamJob = null
            }
        }
    }

    private suspend fun finishRun(run: OwlettSkillRun, argumentsJson: String, execution: OwlettSkillExecution) {
        withContext(Dispatchers.IO) {
            database.finishSkillRun(
                runId = run.id,
                conversationId = run.conversationId,
                toolCallId = run.toolCallId,
                argumentsJson = argumentsJson,
                resultJson = OwlettSkillCardCodec.encode(execution.card),
                status = if (execution.isError) OwlettSkillRunStatus.ERROR else OwlettSkillRunStatus.COMPLETE,
                errorMessage = if (execution.isError) execution.card.summary else null,
                toolContent = gson.toJson(mapOf("runId" to run.id, "result" to runCatching { JsonParser.parseString(execution.toolResponse) }.getOrElse { execution.toolResponse }))
            )
        }
    }

    private suspend fun completeRunWithError(
        run: OwlettSkillRun,
        message: String,
        resumeBatch: Boolean = true
    ) {
        val card = OwlettSkillCardPayload("error", AppText.get("Skill failed"), message)
        withContext(NonCancellable + Dispatchers.IO) {
            database.finishSkillRun(
                runId = run.id,
                conversationId = run.conversationId,
                toolCallId = run.toolCallId,
                argumentsJson = run.argumentsJson,
                resultJson = OwlettSkillCardCodec.encode(card),
                status = OwlettSkillRunStatus.ERROR,
                errorMessage = message,
                toolContent = gson.toJson(mapOf("error" to message))
            )
        }
        reloadActiveConversation(run.conversationId)
        if (resumeBatch) resumeToolBatchIfReady(run)
    }

    private suspend fun markRunInterrupted(run: OwlettSkillRun) {
        withContext(Dispatchers.IO) {
            val persisted = database.listSkillRuns(run.conversationId).firstOrNull { it.id == run.id }
            if (persisted?.status != OwlettSkillRunStatus.EXECUTING) return@withContext
            val card = OwlettSkillCardPayload(
                kind = "interrupted",
                title = AppText.get("Skill interrupted"),
                summary = AppText.get("The operation stopped before it completed. You can retry it safely.")
            )
            database.updateSkillRun(
                run.id, persisted.argumentsJson, persisted.previewJson, OwlettSkillCardCodec.encode(card),
                OwlettSkillRunStatus.INTERRUPTED, AppText.get("Skill execution was interrupted")
            )
        }
        reloadActiveConversation(run.conversationId)
    }

    private suspend fun cancelPendingRuns(conversationId: Long) {
        withContext(Dispatchers.IO) {
            database.listSkillRuns(conversationId).filter {
                it.status == OwlettSkillRunStatus.WAITING_INPUT ||
                    it.status == OwlettSkillRunStatus.WAITING_CONFIRMATION ||
                    it.status == OwlettSkillRunStatus.INTERRUPTED
            }.forEach { cancelRun(it) }
        }
    }

    private suspend fun cancelRun(run: OwlettSkillRun) {
        val card = OwlettSkillCardPayload("cancelled", AppText.get("Skill cancelled"), AppText.get("No App data was changed."))
        database.finishSkillRun(
            runId = run.id,
            conversationId = run.conversationId,
            toolCallId = run.toolCallId,
            argumentsJson = run.argumentsJson,
            resultJson = OwlettSkillCardCodec.encode(card),
            status = OwlettSkillRunStatus.CANCELLED,
            errorMessage = null,
            toolContent = "{\"status\":\"cancelled_by_user\"}"
        )
    }

    private suspend fun resumeToolBatchIfReady(run: OwlettSkillRun) {
        val siblings = withContext(Dispatchers.IO) {
            database.listSkillRuns(run.conversationId).filter { it.assistantMessageId == run.assistantMessageId }
        }
        if (siblings.isEmpty() || siblings.any { it.status in PENDING_SKILL_STATES }) return
        if (!resumingToolBatches.add(run.assistantMessageId)) return
        val apiKey = withContext(Dispatchers.IO) { apiKeyStore.load() }
        if (apiKey.isNullOrBlank()) {
            _state.update { it.copy(bannerMessage = AppText.get("Add a DeepSeek API key in Settings")) }
            resumingToolBatches.remove(run.assistantMessageId)
            return
        }
        val assistantId = withContext(NonCancellable + Dispatchers.IO) {
            insertAssistantPlaceholder(run.conversationId)
        }
        activeAssistantMessageId = assistantId
        withContext(NonCancellable) { reloadActiveConversation(run.conversationId) }
        try {
            runAgent(apiKey, run.conversationId, assistantId, null, attachedPlanIdForRun(run))
        } catch (cancelled: CancellationException) {
            finishInterruptedMessage(activeAssistantMessageId ?: assistantId)
        } catch (error: Throwable) {
            finishFailedMessage(activeAssistantMessageId ?: assistantId, userFacingError(error))
        } finally {
            resumingToolBatches.remove(run.assistantMessageId)
            activeAssistantMessageId = null
        }
    }

    private fun loadInitialState() {
        viewModelScope.launch(Dispatchers.IO) {
            database.repairInterruptedMessages()
            val conversations = database.listConversations()
            val activeId = conversations.firstOrNull()?.id
            val maskedKey = apiKeyStore.masked()
            val maskedXenoKey = xenoCantoApiKeyStore.masked()
            _state.update {
                it.copy(
                    isLoading = false,
                    conversations = conversations,
                    activeConversationId = activeId,
                    messages = activeId?.let(database::listMessages).orEmpty(),
                    skillRuns = activeId?.let(database::listSkillRuns).orEmpty(),
                    availablePlans = runCatching(attachmentBuilder::listPlans).getOrDefault(emptyList()),
                    settings = it.settings.copy(
                        hasApiKey = maskedKey.isNotBlank(),
                        maskedApiKey = maskedKey,
                        hasXenoCantoApiKey = maskedXenoKey.isNotBlank(),
                        maskedXenoCantoApiKey = maskedXenoKey
                    )
                )
            }
        }
    }

    private fun refreshPlans() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching(attachmentBuilder::listPlans)
                .onSuccess { plans -> _state.update { it.copy(availablePlans = plans, availableTrips = attachmentBuilder.listTrips()) } }
                .onFailure { _state.update { it.copy(bannerMessage = AppText.get("Could not load Plans")) } }
        }
    }

    private fun openBlankConversation() {
        recordingPlayer.stop()
        _state.update {
            it.copy(
                activeConversationId = null,
                messages = emptyList(),
                skillRuns = emptyList(),
                draft = "",
                selectedPlan = null, selectedTrip = null,
                selectedManualSkillId = null,
                showConversationList = false,
                bannerMessage = null
            )
        }
    }

    private fun openConversation(conversationId: Long) {
        recordingPlayer.stop()
        viewModelScope.launch(Dispatchers.IO) {
            val conversations = database.listConversations()
            val resolvedId = conversationId.takeIf { id -> conversations.any { it.id == id } }
            _state.update {
                it.copy(
                    conversations = conversations,
                    activeConversationId = resolvedId,
                    messages = resolvedId?.let(database::listMessages).orEmpty(),
                    skillRuns = resolvedId?.let(database::listSkillRuns).orEmpty(),
                    draft = "",
                    selectedPlan = null, selectedTrip = null,
                    selectedManualSkillId = null,
                    showConversationList = false,
                    bannerMessage = null
                )
            }
        }
    }

    private fun insertAssistantPlaceholder(conversationId: Long): Long = database.insertMessage(
        conversationId = conversationId,
        role = OwlettMessageRole.ASSISTANT,
        content = "",
        modelId = appSettings.owlettModelId,
        status = OwlettMessageStatus.THINKING
    )

    private suspend fun finishCompletedMessage(
        messageId: Long,
        content: String,
        modelId: String,
        reasoningContent: String?
    ) {
        withContext(Dispatchers.IO) {
            database.updateMessage(
                messageId, content, modelId, OwlettMessageStatus.COMPLETE, null,
                reasoningContent = reasoningContent
            )
        }
        updateAssistantInState(messageId, content, modelId, OwlettMessageStatus.COMPLETE, null, reasoningContent)
        reloadConversationList()
    }

    private suspend fun finishInterruptedMessage(messageId: Long) {
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) { database.finishAgentTaskForAssistant(messageId, "interrupted") }
            val currentMessage = _state.value.messages.firstOrNull { it.id == messageId }
            if (currentMessage?.status == OwlettMessageStatus.COMPLETE) return@withContext
            val content = currentMessage?.content.orEmpty()
            val modelId = currentMessage?.modelId ?: appSettings.owlettModelId
            withContext(Dispatchers.IO) {
                database.updateMessage(
                    messageId, content, modelId, OwlettMessageStatus.STOPPED, null,
                    reasoningContent = currentMessage?.reasoningContent
                )
            }
            updateAssistantInState(messageId, content, modelId, OwlettMessageStatus.STOPPED, null)
            reloadConversationList()
        }
    }

    private suspend fun finishFailedMessage(messageId: Long, error: String) {
        withContext(NonCancellable + Dispatchers.IO) { database.finishAgentTaskForAssistant(messageId, "error") }
        val currentMessage = _state.value.messages.firstOrNull { it.id == messageId }
        if (currentMessage?.status == OwlettMessageStatus.COMPLETE) {
            _state.update { it.copy(bannerMessage = error) }
            return
        }
        val content = currentMessage?.content.orEmpty()
        val modelId = currentMessage?.modelId ?: appSettings.owlettModelId
        withContext(NonCancellable + Dispatchers.IO) {
            database.updateMessage(
                messageId, content, modelId, OwlettMessageStatus.ERROR, error,
                reasoningContent = currentMessage?.reasoningContent
            )
        }
        updateAssistantInState(messageId, content, modelId, OwlettMessageStatus.ERROR, error)
        withContext(NonCancellable) { reloadConversationList() }
    }

    private fun updateAssistantInState(
        messageId: Long,
        content: String,
        modelId: String,
        status: OwlettMessageStatus,
        error: String?,
        reasoningContent: String? = null
    ) {
        _state.update { current ->
            current.copy(
                messages = current.messages.map { message ->
                    if (message.id == messageId) {
                        message.copy(
                            content = content,
                            modelId = modelId,
                            status = status,
                            errorMessage = error,
                            reasoningContent = reasoningContent ?: message.reasoningContent,
                            updatedAtMs = System.currentTimeMillis()
                        )
                    } else message
                }
            )
        }
    }

    private suspend fun reloadActiveConversation(conversationId: Long) {
        val messages = withContext(Dispatchers.IO) { database.listMessages(conversationId) }
        val runs = withContext(Dispatchers.IO) { database.listSkillRuns(conversationId) }
        _state.update { current ->
            if (current.activeConversationId == conversationId) current.copy(messages = messages, skillRuns = runs)
            else current
        }
    }

    private suspend fun reloadConversationList() {
        val conversations = withContext(Dispatchers.IO) { database.listConversations() }
        _state.update { it.copy(conversations = conversations) }
    }

    private suspend fun skillContext(
        operationId: String,
        attachedPlanId: Long?,
        conversationId: Long
    ): OwlettSkillContext = withContext(Dispatchers.IO) {
        OwlettSkillContext(
            operationId,
            attachedPlanId,
            appSettings.ebirdApiKey,
            xenoCantoApiKeyStore.load(),
            conversationId.let { id ->
                database.listSkillRuns(id).asReversed().firstNotNullOfOrNull { run ->
                    if (run.status != OwlettSkillRunStatus.COMPLETE) null else OwlettSkillCardCodec.decode(run.resultJson)?.planId
                }
            },
            conversationId = conversationId,
            observationCache = observationCache,
            attachedBirdCallRegion = attachedPlanId?.let(plansRepository::getPlan)?.location?.countryCode
                ?.let(BirdCallRegions::countryName)?.let { BirdCallRegion(it, origin = "对话指定地区") },
            resolveBirdCallRegion = { birdCallLocationResolver.resolve(appSettings.useLocation) }
        )
    }

    private fun operationId(run: OwlettSkillRun): String {
        var origin = run
        val runs = database.listSkillRuns(run.conversationId).associateBy { it.id }
        while (true) {
            val retryId = runCatching {
                JsonParser.parseString(origin.argumentsJson).asJsonObject.get("_retry_of_run")?.asLong
            }.getOrNull() ?: break
            val previous = runs[retryId]?.takeIf { it.id < origin.id && it.skillId == run.skillId } ?: break
            origin = previous
        }
        return "owlett-${origin.conversationId}-${origin.toolCallId}"
    }

    private suspend fun attachedPlanIdForRun(run: OwlettSkillRun): Long? = withContext(Dispatchers.IO) {
        val messages = database.listMessages(run.conversationId)
        val anchorIndex = messages.indexOfFirst { it.id == run.assistantMessageId }
        messages.take(anchorIndex.coerceAtLeast(0)).lastOrNull { it.role == OwlettMessageRole.USER }
            ?.let(::attachedPlanIdFrom)
    }

    private fun attachedPlanIdFrom(message: OwlettMessage): Long? = message.attachmentJson
        ?.let { json -> runCatching { JsonParser.parseString(json).asJsonObject.get("planId")?.asLong }.getOrNull() }

    private fun attachedTripIdFrom(message: OwlettMessage?): String? = message?.attachmentJson?.let {
        runCatching { JsonParser.parseString(it).asJsonObject.get("tripId")?.asString }.getOrNull()
    }

    private suspend fun contextForRun(run: OwlettSkillRun): OwlettSkillContext {
        val user = withContext(Dispatchers.IO) { database.listMessages(run.conversationId).takeWhile { it.id != run.assistantMessageId }.lastOrNull { it.role == OwlettMessageRole.USER } }
        val config = OwlettTurnConfig.decode(user?.turnConfigJson)
        return skillContext(operationId(run), user?.let(::attachedPlanIdFrom), run.conversationId).copy(automatic = config.automatic,
            attachedTripId = attachedTripIdFrom(user), allowedToolIds = allowedTools(user?.manualSkillId, config),
            instructionsBySkill = config.skillInstructions)
    }

    private fun parseSlashCommand(draft: String): Pair<String, String>? {
        val scenes = OwlettSceneSkills.all.map { it.descriptor }
        val selected = OwlettSlashCommands.selected(draft, scenes) ?: return null
        return selected.second.id to OwlettSlashCommands.withoutCommands(draft, scenes)
    }

    private fun allowedTools(manualId: String?, config: OwlettTurnConfig): Set<String> =
        toolExecutor.definitions(OwlettSceneSkills.allowed(manualId, config.automaticSkillsEnabled))
            .map { it.name }.filterNot { it == "create_plan" }.toSet()

    private suspend fun executePlaybackTool(args: com.google.gson.JsonObject): OwlettSkillExecution = withContext(Dispatchers.Main) {
        val action = args.text("action")
        when (action) {
            "play" -> {
                val conversation = _state.value.activeConversationId ?: error("请先选择对话")
                val run = withContext(Dispatchers.IO) { database.listSkillRuns(conversation).firstOrNull { it.id == args.number("run_id") } }
                    ?: error("没有找到本对话中的音频结果卡")
                val card = OwlettSkillCardCodec.decode(run.resultJson) ?: error("结果卡已失效")
                val id = args.text("item_id")
                val clip = card.clips.firstOrNull { it.id == id }
                val recording = card.recordings.firstOrNull { it.id == id }
                check(!com.example.birdingsoundmvp.audio.PlaybackCoordinator.recording) { "请先暂停录音" }
                if (clip != null) recordingPlayer.play(card.clips, clip)
                else if (recording != null) recordingPlayer.play(recording)
                else error("未找到指定音频，请使用结果卡中的片段ID")
            }
            "pause" -> recordingPlayer.pause()
            "resume" -> recordingPlayer.resume()
            "stop" -> recordingPlayer.stop()
            "next" -> recordingPlayer.move(1)
            "previous" -> recordingPlayer.move(-1)
            "seek" -> recordingPlayer.seek(args.number("position_ms") ?: error("请指定位置"))
        }
        OwlettSkillExecution(gson.toJson(mapOf("action" to action, "playback" to recordingPlayer.snapshot())),
            OwlettSkillCardPayload("playback", "播放控制", "已处理播放请求，请查看播放器状态"))
    }

    private fun updateSecureSettings(message: String?, error: String?) {
        val masked = apiKeyStore.masked()
        _state.update {
            it.copy(
                settings = it.settings.copy(
                    hasApiKey = masked.isNotBlank(), maskedApiKey = masked,
                    connectionMessage = message, connectionError = error
                )
            )
        }
    }

    private fun updateXenoCantoSettings(message: String?, error: String?) {
        val masked = xenoCantoApiKeyStore.masked()
        _state.update {
            it.copy(
                settings = it.settings.copy(
                    hasXenoCantoApiKey = masked.isNotBlank(), maskedXenoCantoApiKey = masked,
                    xenoCantoMessage = message, xenoCantoError = error
                )
            )
        }
    }

    private fun userFacingError(error: Throwable): String = when (error) {
        is com.example.birdingsoundmvp.planning.ObservationQueryNeedsRefinement -> error.message.orEmpty()
        is DeepSeekRequestException -> "${error.message}\n\n[诊断] HTTP ${error.statusCode}\n${error.detail}"
        is PlanAttachmentUnavailableException -> error.message ?: AppText.get("The selected Plan is unavailable")
        is AuthenticationException -> AppText.get("DeepSeek rejected the API key")
        is RateLimitException -> AppText.get("DeepSeek is rate limiting requests. Try again shortly.")
        is OpenAIServerException -> AppText.get("DeepSeek is temporarily unavailable")
        is OpenAITimeoutException -> AppText.get("DeepSeek did not respond in time")
        is GenericIOException, is OpenAIHttpException -> AppText.get("Network connection failed")
        is OpenAIAPIException -> if (error.statusCode in 500..599) AppText.get("DeepSeek is temporarily unavailable")
            else AppText.format("DeepSeek request failed ({0})", error.statusCode)
        is IOException -> AppText.get("Network connection failed")
        else -> AppText.get("请求未能完成，请重试。")
    }

    override fun onCleared() {
        val activeStream = streamJob
        if (activeStream == null) closeResources()
        else {
            activeStream.invokeOnCompletion { closeResources() }
            activeStream.cancel()
        }
        super.onCleared()
    }

    private fun closeResources() {
        recordingPlayer.release()
        database.close()
        attachmentBuilder.close()
        plansRepository.close()
    }

    private data class PreparedRequest(
        val conversationId: Long,
        val assistantMessageId: Long,
        val attachedPlanId: Long?,
        val conversations: List<OwlettConversation>,
        val messages: List<OwlettMessage>,
        val skillRuns: List<OwlettSkillRun>
    )

    private companion object {
        const val STREAM_PERSIST_INTERVAL_MS = 300L
        val PENDING_SKILL_STATES = setOf(
            OwlettSkillRunStatus.WAITING_INPUT,
            OwlettSkillRunStatus.WAITING_CONFIRMATION,
            OwlettSkillRunStatus.EXECUTING,
            OwlettSkillRunStatus.INTERRUPTED
        )
    }
}
