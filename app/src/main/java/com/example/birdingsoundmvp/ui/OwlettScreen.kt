package com.example.birdingsoundmvp.ui
import com.example.birdingsoundmvp.ui.OwlettButton as Button
import com.example.birdingsoundmvp.ui.OwlettOutlinedButton as OutlinedButton

import com.example.birdingsoundmvp.i18n.AppText
import com.example.birdingsoundmvp.owlett.OwlettErrorDisplay
import com.example.birdingsoundmvp.owlett.localizedSkillTitle

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.example.birdingsoundmvp.owlett.OwlettSlashCommands
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.birdingsoundmvp.owlett.OwlettConversation
import com.example.birdingsoundmvp.owlett.OwlettAnalysisProgress
import com.example.birdingsoundmvp.owlett.OwlettSkillCardCodec
import com.example.birdingsoundmvp.owlett.OwlettSkillCardPayload
import com.example.birdingsoundmvp.owlett.OwlettSkillDescriptor
import com.example.birdingsoundmvp.owlett.OwlettSkillOption
import com.example.birdingsoundmvp.owlett.OwlettSkillRun
import com.example.birdingsoundmvp.owlett.OwlettSkillRunStatus
import com.example.birdingsoundmvp.owlett.OwlettMessageRole
import com.example.birdingsoundmvp.owlett.OwlettMessageStatus
import com.example.birdingsoundmvp.owlett.OwlettPlanPickerItem
import com.example.birdingsoundmvp.owlett.OwlettUiState
import com.example.birdingsoundmvp.owlett.OwlettViewModel
import com.example.birdingsoundmvp.owlett.XenoCantoRecording
import com.pact.chatui.ChatConfig
import com.pact.chatui.ChatDefaults
import com.pact.chatui.ChatMessage
import com.pact.chatui.ChatScreen
import com.pact.chatui.LocalChatColors
import com.pact.chatui.MessageSender
import com.pact.chatui.StreamState
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun OwlettScreen(
    viewModel: OwlettViewModel,
    onOpenSettings: () -> Unit,
    onOpenPlan: (Long) -> Unit,
    onOpenSpecies: (String, String) -> Unit,
    onOpenClip: (com.example.birdingsoundmvp.owlett.OwlettAudioClip) -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    var renameTarget by remember { mutableStateOf<OwlettConversation?>(null) }
    var deleteTarget by remember { mutableStateOf<OwlettConversation?>(null) }
    var skillOptionPickerRunId by remember { mutableStateOf<Long?>(null) }

    val visibleMessages = remember(state.messages, state.skillRuns, state.isLoading) {
        val visibleStoredMessages = state.messages.filter { message ->
            message.role == OwlettMessageRole.USER ||
                (message.role == OwlettMessageRole.ASSISTANT &&
                    (message.content.isNotBlank() || message.toolCallsJson.isNullOrBlank()))
        }
        val lastStoredId = visibleStoredMessages.lastOrNull()?.id
        val stored = visibleStoredMessages.map { message ->
            val errorDisplay = message.errorMessage?.takeIf { message.status == OwlettMessageStatus.ERROR }?.let(OwlettErrorDisplay::from)
            message.createdAtMs to ChatMessage(
                    id = message.id.toString(),
                    sender = if (message.role == OwlettMessageRole.USER) MessageSender.User else MessageSender.Assistant,
                    text = message.content,
                    streamState = when {
                        message.status == OwlettMessageStatus.THINKING && message.content.isBlank() -> StreamState.Thinking
                        message.status == OwlettMessageStatus.STREAMING -> StreamState.Streaming
                        else -> StreamState.Complete
                    },
                    attachmentLabel = message.attachmentLabel,
                    metadata = when (message.status) {
                        OwlettMessageStatus.COMPLETE -> message.modelId
                        OwlettMessageStatus.STOPPED -> listOfNotNull(message.modelId, AppText.get("Stopped")).joinToString(" · ")
                        else -> null
                    },
                    error = errorDisplay?.message,
                    errorDetails = errorDisplay?.diagnostics,
                    canRetry = message.id == lastStoredId && message.status in setOf(
                        OwlettMessageStatus.ERROR,
                        OwlettMessageStatus.STOPPED
                    )
                )
        }
        val skillCards = state.skillRuns.mapNotNull { run ->
            val hasCard = !run.previewJson.isNullOrBlank() || !run.resultJson.isNullOrBlank()
            if (!hasCard) return@mapNotNull null
            (run.createdAtMs + 1L) to ChatMessage(
                id = "skill-${run.id}",
                sender = MessageSender.Assistant,
                text = "",
                extraKey = run.id.toString()
            )
        }
        val mapped = (stored + skillCards)
            .sortedWith(compareBy<Pair<Long, ChatMessage>> { it.first }.thenBy { it.second.id })
            .map { it.second }
        if (mapped.isEmpty() && !state.isLoading) {
            listOf(
                ChatMessage(
                    id = "welcome",
                    sender = MessageSender.Assistant,
                    text = AppText.get("你好，我是 Owlett。可以问我鸟类知识或观鸟建议，也可以附加一个 Plan 一起讨论。")
                )
            )
        } else mapped
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom))
    ) {
        val chatColors = ChatDefaults.colors().let { colors ->
            colors.copy(backgroundGradient = listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background))
        }
        ChatScreen(
            messages = visibleMessages,
            draft = state.draft,
            isStreaming = state.isStreaming,
            onDraftChange = viewModel::updateDraft,
            onSendClick = { viewModel.sendMessage() },
            onRetryClick = { id -> id.toLongOrNull()?.let(viewModel::retryMessage) },
            config = ChatConfig(
                assistantLabel = "Owlett",
                handleSystemBottomInsets = false,
                allowEmptyDraftSend = true
            ),
            colors = chatColors,
            messageExtras = { message ->
                val runId = message.extraKey?.toLongOrNull()
                val run = state.skillRuns.firstOrNull { it.id == runId }
                if (run != null) {
                    OwlettSkillCard(
                        run = run,
                        analysisProgress = OwlettSkillCardCodec.decode(run.resultJson ?: run.previewJson)?.planId
                            ?.let(state.planAnalysisProgress::get),
                        playingRecordingId = state.playingRecordingId,
                        birdCallPlayback = state.birdCallPlayback,
                        onOptionSelected = { optionId -> viewModel.selectSkillOption(run.id, optionId) },
                        onOpenOptionPicker = { skillOptionPickerRunId = run.id },
                        onConfirm = { viewModel.confirmSkillRun(run.id) },
                        onCancel = { viewModel.cancelSkillRun(run.id) },
                        onRetry = { viewModel.retrySkillRun(run.id) },
                        onOpenPlan = onOpenPlan,
                        onOrganizePlan = viewModel::organizePlan,
                        onOpenSpecies = onOpenSpecies,
                        onOpenSettings = onOpenSettings,
                        onToggleRecording = viewModel::toggleRecording
                        ,onCancelAnalysis = { planId -> viewModel.cancelPlanAnalysis(planId) }
                    )
                    OwlettSkillCardCodec.decode(run.resultJson)?.clips.orEmpty().takeIf { it.isNotEmpty() }?.let { clips ->
                        OwlettClipsPanel(clips, state.clipPlayback, viewModel, onOpenClip)
                    }
                }
            },
            topBar = { onHeightChanged ->
                OwlettTopBar(
                    title = state.conversations.firstOrNull { it.id == state.activeConversationId }?.title,
                    onMenu = viewModel::showConversationList,
                    onNewConversation = viewModel::requestNewConversation,
                    onHeightChanged = onHeightChanged
                )
            },
            composer = { draft, isStreaming, _, onSend ->
                Column {
                    viewModel.observationSources.all().forEach { source ->
                        val warning = source.largeQueryWarning?.collectAsState()?.value
                        if (warning != null) Column(Modifier.padding(horizontal = 12.dp)) { SourceQueryStatus(source) }
                    }
                    val queryRun = state.skillRuns.lastOrNull { it.skillId in setOf("observations_area_query", "regions_list", "locations_search") }
                    if (queryRun?.status in setOf(OwlettSkillRunStatus.EXECUTING, OwlettSkillRunStatus.ERROR, OwlettSkillRunStatus.INTERRUPTED)) {
                        val sourceId = runCatching { com.google.gson.JsonParser.parseString(queryRun!!.argumentsJson).asJsonObject.get("source_id")?.asString }.getOrNull()
                        val source = sourceId?.let(viewModel.observationSources::find)
                        val warning = source?.largeQueryWarning?.collectAsState()?.value
                        if (warning == null) Column(Modifier.padding(horizontal = 12.dp)) { SourceQueryStatus(source) }
                    }
                    OwlettComposer(
                        draft = draft,
                        selection = TextRange(state.draftSelectionStart.coerceIn(0, draft.length), state.draftSelectionEnd.coerceIn(0, draft.length)),
                        menuDismissed = state.slashMenuDismissed,
                        onEdit = { viewModel.updateDraftSelection(it.text, it.selection.start, it.selection.end) },
                        isStreaming = isStreaming,
                        hasApiKey = state.settings.hasApiKey,
                        selectedPlan = state.selectedPlan,
                        tripLabel = state.selectedTrip?.label,
                        skills = state.skills,
                        selectedSkillId = state.selectedManualSkillId,
                        onSelectSkill = viewModel::selectManualSkill,
                        onRemoveSkill = viewModel::clearManualSkill,
                        onOpenPlanPicker = viewModel::showPlanPicker,
                        onRemovePlan = viewModel::removeSelectedPlan,
                        onSend = onSend,
                        onStop = viewModel::stopResponse
                    )
                }
            }
        )

        state.bannerMessage?.let { message ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = 108.dp),
                action = {
                    if (!state.settings.hasApiKey) {
                        TextButton(onClick = {
                            viewModel.clearBanner()
                            onOpenSettings()
                        }) { Text(AppText.get("Settings")) }
                    }
                },
                dismissAction = {
                    IconButton(onClick = viewModel::clearBanner) {
                        Icon(Icons.Default.Close, contentDescription = AppText.get("Dismiss"))
                    }
                }
            ) { Text(message) }
        }
    }

    if (state.showExternalConsent) AlertDialog(onDismissRequest = viewModel::dismissExternalConsent,
        title = { Text("向 DeepSeek 发送消息") },
        text = { Text("当前消息、近期对话、附加计划或行程摘要会直接发送给 DeepSeek。工具可能按请求访问 eBird 或 xeno-canto。不会上传本地录音或文件路径；请勿在聊天中粘贴密钥。") },
        confirmButton = { TextButton(onClick = viewModel::acceptExternalConsent) { Text("同意并发送") } },
        dismissButton = { TextButton(onClick = viewModel::dismissExternalConsent) { Text("取消") } })
    if (state.showConversationList) {
        ConversationSheet(
            state = state,
            onDismiss = viewModel::hideConversationList,
            onSelect = viewModel::requestConversation,
            onRename = { renameTarget = it },
            onDelete = { deleteTarget = it },
            onNewConversation = viewModel::requestNewConversation
        )
    }
    if (state.showPlanPicker) {
        OwlettAttachmentPicker(state, viewModel)
    }
    skillOptionPickerRunId?.let { runId ->
        val run = state.skillRuns.firstOrNull { it.id == runId }
        val payload = run?.let { OwlettSkillCardCodec.decode(it.previewJson) }
        if (run != null && payload != null) {
            SkillOptionPickerSheet(
                title = payload.title,
                options = payload.options,
                onDismiss = { skillOptionPickerRunId = null },
                onSelect = { optionId ->
                    skillOptionPickerRunId = null
                    viewModel.selectSkillOption(run.id, optionId)
                }
            )
        }
    }
    if (state.pendingAction != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPendingAction,
            title = { Text(AppText.get("Stop this response?")) },
            text = { Text(AppText.get("The partial response will be kept before switching conversations.")) },
            confirmButton = {
                Button(onClick = viewModel::confirmPendingAction) { Text(AppText.get("Stop and switch")) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPendingAction) { Text(AppText.get("Keep waiting")) }
            }
        )
    }
    renameTarget?.let { conversation ->
        RenameConversationDialog(
            conversation = conversation,
            onDismiss = { renameTarget = null },
            onConfirm = { title ->
                viewModel.renameConversation(conversation.id, title)
                renameTarget = null
            }
        )
    }
    deleteTarget?.let { conversation ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(AppText.get("Delete conversation?")) },
            text = { Text(AppText.format("This permanently deletes the local chat history for “{0}”.", conversation.title)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteConversation(conversation.id)
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(AppText.get("Delete")) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(AppText.get("Cancel")) }
            }
        )
    }
}

@Composable
internal fun BoxScope.OwlettTopBar(
    title: String?,
    onMenu: () -> Unit,
    onNewConversation: () -> Unit,
    onHeightChanged: (Int) -> Unit
) {
    Surface(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .onSizeChanged { onHeightChanged(it.height) },
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onMenu) {
                Icon(Icons.Default.Menu, contentDescription = AppText.get("Conversations"))
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Owlett", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = title ?: AppText.get("New conversation"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onNewConversation) {
                Icon(Icons.Default.Add, contentDescription = AppText.get("New conversation"))
            }
        }
    }
}

@Composable
internal fun OwlettComposer(
    draft: String,
    selection: TextRange,
    menuDismissed: Boolean,
    onEdit: (TextFieldValue) -> Unit,
    isStreaming: Boolean,
    hasApiKey: Boolean,
    selectedPlan: OwlettPlanPickerItem?,
    skills: List<OwlettSkillDescriptor>,
    selectedSkillId: String?,
    onSelectSkill: (String) -> Unit,
    onRemoveSkill: () -> Unit,
    onOpenPlanPicker: () -> Unit,
    onRemovePlan: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    tripLabel: String? = null
) {
    val colors = LocalChatColors.current
    val selectedSkill = skills.firstOrNull { it.id == selectedSkillId }
    val focusRequester = remember { FocusRequester() }
    var fieldValue by remember { mutableStateOf(TextFieldValue(draft, selection)) }
    LaunchedEffect(draft, selection) {
        if (fieldValue.text != draft || fieldValue.selection != selection) {
            fieldValue = TextFieldValue(draft, selection)
        }
    }
    val token = if (selection.collapsed && !menuDismissed) OwlettSlashCommands.atCursor(draft, selection.end) else null
    val slashQuery = token?.text?.take(selection.end - token.start)?.removePrefix("/").orEmpty()
    val skillSuggestions = if (token != null) {
        skills.filter { skill ->
            skill.slashCommand.removePrefix("/").startsWith(slashQuery, ignoreCase = true)
        }
    } else emptyList()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("owlett-composer")
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (skillSuggestions.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("owlett-slash-menu"),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 2.dp
            ) {
                Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    skillSuggestions.forEachIndexed { index, skill ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectSkill(skill.id); focusRequester.requestFocus() }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(skill.slashCommand, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(72.dp))
                            Column(Modifier.weight(1f)) {
                                Text(skill.displayName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    skill.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (index != skillSuggestions.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
        selectedSkill?.let { skill ->
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${skill.slashCommand} · ${skill.displayName}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onRemoveSkill, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, contentDescription = AppText.get("Remove skill"), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        (selectedPlan?.name ?: tripLabel)?.let { label ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = onRemovePlan, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, contentDescription = AppText.get("Remove Plan"), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onOpenPlanPicker, enabled = !isStreaming) {
                Icon(Icons.Default.Add, contentDescription = AppText.get("Attach Plan"))
            }
            TextField(
                value = fieldValue,
                onValueChange = { fieldValue = it; onEdit(it) },
                modifier = Modifier.weight(1f).focusRequester(focusRequester).testTag("owlett-input"),
                enabled = !isStreaming,
                placeholder = {
                    Text(if (hasApiKey) AppText.get("Ask Owlett") else AppText.get("Set up DeepSeek in Settings"))
                },
                shape = RoundedCornerShape(8.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.composerSurface,
                    unfocusedContainerColor = colors.composerSurface,
                    disabledContainerColor = colors.composerSurface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                maxLines = 5
            )
            val sendEnabled = (draft.isNotBlank() || selectedSkill != null) && hasApiKey && !isStreaming
            Surface(
                onClick = {
                    if (isStreaming) onStop() else if (sendEnabled) onSend()
                },
                modifier = Modifier.size(48.dp).testTag("owlett-send"),
                shape = CircleShape,
                color = when {
                    isStreaming -> MaterialTheme.colorScheme.error
                    sendEnabled -> colors.sendButtonBackground
                    else -> colors.sendButtonDisabledBackground
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isStreaming) Icons.Default.Stop else Icons.Default.ArrowUpward,
                        contentDescription = if (isStreaming) AppText.get("Stop response") else AppText.get("Send"),
                        tint = if (isStreaming || sendEnabled) MaterialTheme.colorScheme.onPrimary else colors.sendButtonDisabledContent
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationSheet(
    state: OwlettUiState,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
    onRename: (OwlettConversation) -> Unit,
    onDelete: (OwlettConversation) -> Unit,
    onNewConversation: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(AppText.get("Conversations"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(onClick = onNewConversation) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(AppText.get("New"))
            }
        }
        if (state.conversations.isEmpty()) {
            Text(
                AppText.get("No saved conversations"),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                items(state.conversations, key = { it.id }) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        selected = conversation.id == state.activeConversationId,
                        actionsEnabled = !state.isStreaming,
                        onSelect = { onSelect(conversation.id) },
                        onRename = { onRename(conversation) },
                        onDelete = { onDelete(conversation) }
                    )
                }
            }
        }
        Spacer(Modifier.padding(bottom = 12.dp))
    }
}

@Composable
private fun ConversationRow(
    conversation: OwlettConversation,
    selected: Boolean,
    actionsEnabled: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(start = 20.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            conversation.title,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
        Box {
            IconButton(onClick = { menuExpanded = true }, enabled = actionsEnabled) {
                Icon(Icons.Default.MoreVert, contentDescription = AppText.get("Conversation actions"))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(AppText.get("Rename")) },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onRename()
                    }
                )
                DropdownMenuItem(
                    text = { Text(AppText.get("Delete")) },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    }
                )
            }
        }
    }
    HorizontalDivider()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanPickerSheet(
    plans: List<OwlettPlanPickerItem>,
    selectedPlanId: Long?,
    onDismiss: () -> Unit,
    onSelect: (OwlettPlanPickerItem) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(plans, query) {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) plans else plans.filter { plan ->
            plan.name.lowercase().contains(normalized) ||
                plan.hotspotName.lowercase().contains(normalized) ||
                plan.plannedDate.contains(normalized)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(AppText.get("Attach a Plan"), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(AppText.get("Search Plans")) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            if (filtered.isEmpty()) {
                Text(
                    if (plans.isEmpty()) AppText.get("No Plans available") else AppText.get("No matching Plans"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 20.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                    items(filtered, key = { it.id }) { plan ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (plan.id == selectedPlanId) MaterialTheme.colorScheme.secondaryContainer
                                    else Color.Transparent
                                )
                                .clickable { onSelect(plan) }
                                .padding(vertical = 10.dp, horizontal = 8.dp)
                        ) {
                            Text(plan.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                listOf(plan.plannedDate, plan.hotspotName).filter(String::isNotBlank).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.padding(bottom = 12.dp))
        }
    }
}

@Composable
private fun RenameConversationDialog(
    conversation: OwlettConversation,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var title by remember(conversation.id) { mutableStateOf(conversation.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppText.get("Rename conversation")) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text(AppText.get("Title")) }
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(title) }, enabled = title.isNotBlank()) { Text(AppText.get("Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(AppText.get("Cancel")) } }
    )
}

@Composable
private fun OwlettSkillCard(
    run: OwlettSkillRun,
    analysisProgress: OwlettAnalysisProgress?,
    playingRecordingId: String?,
    birdCallPlayback: com.example.birdingsoundmvp.owlett.BirdCallPlayback,
    onOptionSelected: (String) -> Unit,
    onOpenOptionPicker: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenPlan: (Long) -> Unit,
    onOrganizePlan: (Long) -> Unit,
    onOpenSpecies: (String, String) -> Unit,
    onOpenSettings: () -> Unit,
    onToggleRecording: (XenoCantoRecording) -> Unit,
    onCancelAnalysis: (Long) -> Unit
) {
    val payload = OwlettSkillCardCodec.decode(run.resultJson ?: run.previewJson) ?: return
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text(localizedSkillTitle(payload), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (payload.summary.isNotBlank()) {
                Text(
                    if (run.status == OwlettSkillRunStatus.ERROR) OwlettErrorDisplay.from(payload.summary).message else AppText.get(payload.summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (run.status == OwlettSkillRunStatus.EXECUTING) {
                val progress = analysisProgress?.takeIf { payload.kind in setOf("analysis_prompt", "activity_preview") }
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        progress.message.ifBlank { AppText.get("Organizing bird activity") },
                        style = MaterialTheme.typography.labelSmall
                    )
                    payload.planId?.let { planId ->
                        TextButton(onClick = { onCancelAnalysis(planId) }) { Text(AppText.get("取消鸟况整理")) }
                    }
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(AppText.get("Working…"), style = MaterialTheme.typography.labelSmall)
                }
            }

            if (payload.selectionField in setOf("plan", "bird_plan") && payload.options.isNotEmpty()) {
                Button(
                    onClick = onOpenOptionPicker,
                    enabled = run.status == OwlettSkillRunStatus.WAITING_INPUT,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(AppText.get("Choose Plan"))
                }
            } else {
                payload.options.forEach { option ->
                    OutlinedButton(
                        onClick = { onOptionSelected(option.id) },
                        enabled = run.status == OwlettSkillRunStatus.WAITING_INPUT,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                            Text(option.label)
                            if (option.supportingText.isNotBlank()) {
                                Text(
                                    option.supportingText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            SkillSpeciesDiff(AppText.get("Add"), payload.addedSpecies, MaterialTheme.colorScheme.primary)
            SkillSpeciesDiff(AppText.get("Remove"), payload.removedSpecies, MaterialTheme.colorScheme.error)
            SkillSpeciesDiff(AppText.get("Keep"), payload.keptSpecies, MaterialTheme.colorScheme.onSurfaceVariant, collapsed = true)
            if (payload.manualSpecies.isNotEmpty() && payload.kind != "manual_policy") {
                SkillSpeciesDiff(AppText.get("Manual entries affected"), payload.manualSpecies, MaterialTheme.colorScheme.tertiary)
            }

            payload.birdDetail?.let { detail ->
                detail.images.firstOrNull()?.let { image ->
                    OwlettRemoteOrAssetImage(
                        url = image.url,
                        isLocalAsset = image.isLocalAsset,
                        contentDescription = payload.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f)
                            .clip(RoundedCornerShape(6.dp))
                    )
                    Text(
                        listOf(image.author, image.license, image.source).filter(String::isNotBlank).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                detail.ibirding?.let { account ->
                    BirdDetailSummaryLine(AppText.get("Overview"), account.description)
                    BirdDetailSummaryLine(AppText.get("Range"), account.rangeText.ifBlank { account.chinaDistribution })
                    BirdDetailSummaryLine(AppText.get("Habits"), account.habits)
                    BirdDetailSummaryLine(AppText.get("Voice"), account.voice)
                }
                if (payload.recentObservations.isNotEmpty()) {
                    Text(
                        payload.recentObservationsNote.ifBlank { AppText.get("Recent observations") },
                        style = MaterialTheme.typography.labelLarge
                    )
                    payload.recentObservations.forEach { observation ->
                        Text(
                            listOfNotNull(
                                observation.observedAt.takeIf(String::isNotBlank),
                                observation.locationName.takeIf(String::isNotBlank),
                                observation.count?.let { AppText.format("count {0}", it) }
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (payload.recentObservationsNote.isNotBlank()) {
                    Text(
                        payload.recentObservationsNote,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (payload.recordings.isNotEmpty()) {
                payload.recordings.forEachIndexed { index, recording ->
                    if (index > 0) HorizontalDivider()
                    XenoCantoRecordingRow(
                        recording = recording,
                        isPlaying = recording.id == playingRecordingId,
                        playback = birdCallPlayback.takeIf { it.recordingId == recording.id },
                        onToggle = { onToggleRecording(recording) }
                    )
                }
            }

            when {
                run.status == OwlettSkillRunStatus.WAITING_CONFIRMATION -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(AppText.get("Cancel")) }
                    Button(onClick = onConfirm, modifier = Modifier.weight(1f)) {
                        Text(if (payload.action == "analyze") AppText.get("Organize") else AppText.get("Confirm"))
                    }
                }
                run.status == OwlettSkillRunStatus.WAITING_INPUT ->
                    TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text(AppText.get("Cancel")) }
                run.status in setOf(OwlettSkillRunStatus.INTERRUPTED, OwlettSkillRunStatus.ERROR) -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (run.status == OwlettSkillRunStatus.INTERRUPTED) {
                        TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(AppText.get("Cancel")) }
                    }
                    Button(onClick = onRetry, modifier = Modifier.weight(1f)) { Text(AppText.get("Retry")) }
                }
                payload.action == "open_plan" && payload.planId != null -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onOpenPlan(payload.planId) }, modifier = Modifier.weight(1f)) {
                            Text(AppText.get("Open Plan"))
                        }
                        if (payload.kind == "plan_complete") {
                            Button(onClick = { onOrganizePlan(payload.planId) }, modifier = Modifier.weight(1f)) {
                                Text(AppText.get("Organize activity"))
                            }
                        }
                    }
                    analysisProgress?.let { progress ->
                        if (progress.isRunning) {
                            LinearProgressIndicator(progress = { progress.progress }, modifier = Modifier.fillMaxWidth())
                            Text(progress.message, style = MaterialTheme.typography.labelSmall)
                        } else if (progress.error != null) {
                            Text(progress.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                payload.action == "open_species" && payload.birdDetail != null ->
                    OutlinedButton(
                        onClick = {
                            onOpenSpecies(payload.birdDetail.scientificName, payload.birdDetail.commonName)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(AppText.get("View full species details"))
                    }
                payload.action == "open_settings" ->
                    Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text(AppText.get("Open Settings")) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillOptionPickerSheet(
    title: String,
    options: List<OwlettSkillOption>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(options, query) {
        val normalized = query.trim()
        if (normalized.isBlank()) options else options.filter { option ->
            option.label.contains(normalized, ignoreCase = true) ||
                option.supportingText.contains(normalized, ignoreCase = true)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(AppText.get("Search Plans")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                items(filtered, key = { it.id }) { option ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option.id) }
                            .padding(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Text(option.label, fontWeight = FontWeight.SemiBold)
                        if (option.supportingText.isNotBlank()) {
                            Text(
                                option.supportingText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.padding(bottom = 12.dp))
        }
    }
}

@Composable
private fun BirdDetailSummaryLine(label: String, value: String) {
    if (value.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SkillSpeciesDiff(
    label: String,
    species: List<String>,
    color: Color,
    collapsed: Boolean = false
) {
    if (species.isEmpty()) return
    val shown = if (collapsed) species.take(6) else species.take(12)
    Text(
        "$label (${species.size}): ${shown.joinToString()}${if (shown.size < species.size) " …" else ""}",
        style = MaterialTheme.typography.bodySmall,
        color = color
    )
}

@Composable
internal fun XenoCantoRecordingRow(
    recording: XenoCantoRecording,
    isPlaying: Boolean,
    playback: com.example.birdingsoundmvp.owlett.BirdCallPlayback?,
    onToggle: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (recording.sonogramUrl.isNotBlank()) {
            OwlettRemoteOrAssetImage(
                url = recording.sonogramUrl,
                isLocalAsset = false,
                contentDescription = AppText.format("Sonogram for XC{0}", recording.id),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f)
                    .clip(RoundedCornerShape(4.dp))
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggle) {
                Icon(
                    if (isPlaying || playback?.wantsPlayback == true) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying || playback?.wantsPlayback == true) "暂停播放" else AppText.get("Play recording")
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    AppText.format("XC{0} · {1} · quality {2}", recording.id, recording.soundType.ifBlank { AppText.get("Recording") }, recording.quality.ifBlank { AppText.get("unrated") }),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    listOf(recording.recordist, recording.location, recording.country, recording.date)
                        .filter(String::isNotBlank)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    AppText.format("License: {0}", recording.license.ifBlank { AppText.get("not specified") }),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { uriHandler.openUri(recording.sourceUrl) }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = AppText.get("Open xeno-canto source"))
            }
        }
        if (playback?.loading == true) {
            Text("正在加载鸟鸣…", style = MaterialTheme.typography.bodySmall)
            val percent = playback.bufferedPercent
            if (percent == null || percent == 0) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun OwlettRemoteOrAssetImage(
    url: String,
    isLocalAsset: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bitmapState = remember(url, isLocalAsset) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(url, isLocalAsset) {
        bitmapState.value = withContext(Dispatchers.IO) {
            runCatching {
                if (isLocalAsset) {
                    context.assets.open(url).use(BitmapFactory::decodeStream)
                } else {
                    URL(url).openStream().use(BitmapFactory::decodeStream)
                }
            }.getOrNull()
        }
    }
    val bitmap = bitmapState.value
    if (bitmap != null) {
        Image(
            bitmap = requireNotNull(bitmap).asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OwlettSettingsCard(viewModel: OwlettViewModel, section: String = "all") {
    val state by viewModel.state.collectAsState()
    val settings = state.settings
    var apiKeyDraft by remember { mutableStateOf("") }
    var revealKey by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var xenoCantoKeyDraft by remember { mutableStateOf("") }
    var revealXenoCantoKey by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (section != "xeno") {
            Text(AppText.get("Owlett / DeepSeek"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (settings.hasApiKey) {
                Text(
                    AppText.format("Saved key: {0}", settings.maskedApiKey),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = apiKeyDraft,
                onValueChange = { apiKeyDraft = it },
                label = { Text(if (settings.hasApiKey) AppText.get("Replace API key") else AppText.get("DeepSeek API key")) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { revealKey = !revealKey }) {
                        Icon(
                            if (revealKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (revealKey) AppText.get("Hide API key") else AppText.get("Show API key")
                        )
                    }
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        viewModel.saveApiKey(apiKeyDraft)
                        apiKeyDraft = ""
                    },
                    enabled = apiKeyDraft.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(AppText.get("Save"))
                }
                OutlinedButton(
                    onClick = { viewModel.testConnection(apiKeyDraft) },
                    enabled = !settings.isTesting && (apiKeyDraft.isNotBlank() || settings.hasApiKey),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Wifi, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(AppText.get("Test"))
                }
            }
            if (settings.hasApiKey) {
                TextButton(onClick = viewModel::clearApiKey) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(AppText.get("Remove saved key"))
                }
            }
            if (settings.isTesting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            settings.connectionMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
            settings.connectionError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            ExposedDropdownMenuBox(
                expanded = modelMenuExpanded,
                onExpandedChange = { modelMenuExpanded = !modelMenuExpanded }
            ) {
                OutlinedTextField(
                    value = settings.selectedModelId,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(AppText.get("Model")) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = modelMenuExpanded,
                    onDismissRequest = { modelMenuExpanded = false }
                ) {
                    settings.availableModels.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model) },
                            onClick = {
                                viewModel.selectModel(model)
                                modelMenuExpanded = false
                            }
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setAutomaticSkillsEnabled(!settings.automaticSkillsEnabled) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(AppText.get("Allow Owlett to use skills automatically"), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        AppText.get("Read-only skills may run automatically. Changes to Plans always require confirmation."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.automaticSkillsEnabled,
                    onCheckedChange = viewModel::setAutomaticSkillsEnabled
                )
            }
            Text(
                AppText.get("Messages and attached Plan summaries are sent directly to DeepSeek when you chat."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            }
            if (section != "deepseek") {
            Text(AppText.get("Bird calls / xeno-canto"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (settings.hasXenoCantoApiKey) {
                Text(
                    AppText.format("Saved key: {0}", settings.maskedXenoCantoApiKey),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = xenoCantoKeyDraft,
                onValueChange = { xenoCantoKeyDraft = it },
                label = { Text(if (settings.hasXenoCantoApiKey) AppText.get("Replace API key") else AppText.get("xeno-canto API key")) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (revealXenoCantoKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { revealXenoCantoKey = !revealXenoCantoKey }) {
                        Icon(
                            if (revealXenoCantoKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (revealXenoCantoKey) AppText.get("Hide API key") else AppText.get("Show API key")
                        )
                    }
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        viewModel.saveXenoCantoApiKey(xenoCantoKeyDraft)
                        xenoCantoKeyDraft = ""
                    },
                    enabled = xenoCantoKeyDraft.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(AppText.get("Save"))
                }
                OutlinedButton(
                    onClick = { viewModel.testXenoCantoConnection(xenoCantoKeyDraft) },
                    enabled = !settings.isTestingXenoCanto &&
                        (xenoCantoKeyDraft.isNotBlank() || settings.hasXenoCantoApiKey),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Wifi, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(AppText.get("Test"))
                }
            }
            if (settings.hasXenoCantoApiKey) {
                TextButton(onClick = viewModel::clearXenoCantoApiKey) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(AppText.get("Remove saved key"))
                }
            }
            if (settings.isTestingXenoCanto) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            settings.xenoCantoMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
            settings.xenoCantoError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "鸟鸣按需播放并缓存已加载部分，最多256 MB；空间不足时自动清理旧缓存。保留录音者和许可证信息。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("鸟鸣音频缓存：${android.text.format.Formatter.formatShortFileSize(LocalContext.current, settings.birdCallCacheBytes)}",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = viewModel::clearBirdCallCache, enabled = !settings.clearingBirdCallCache && settings.birdCallCacheBytes > 0) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Text(if (settings.clearingBirdCallCache) "正在清空…" else "清空鸟鸣音频缓存")
            }
            if (settings.clearingBirdCallCache) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}
