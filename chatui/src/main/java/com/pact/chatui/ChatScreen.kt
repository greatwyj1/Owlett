package com.pact.chatui


import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pact.chatui.ChatMessage
import com.pact.chatui.MessageSender
import com.pact.chatui.StreamState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch


// ---------------------------------------------------------------------------
// Composable: Thinking indicator (three bouncing dots)
// ---------------------------------------------------------------------------

@Composable
private fun ThinkingIndicator(horizontalPadding: Dp) {
    // The thinking dots are always shown for the assistant, so the caller
    // passes the same `bubbleHorizontalPadding` it uses for the assistant's
    // bubble text — that keeps the dots' left edge aligned with the
    // "Assistant" label and the eventual streamed message text.
    val config = LocalChatConfig.current
    val colors = LocalChatColors.current
    val transition = rememberInfiniteTransition(label = "thinking")
    Row(
        modifier = Modifier.padding(
            horizontal = horizontalPadding,
            vertical = config.bubbleInnerVerticalPadding,
        ),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val offsetY by transition.animateFloat(
                initialValue = 0f,
                targetValue = -8f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 400,
                        delayMillis = index * 150,
                        easing = LinearOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$index"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer { translationY = offsetY }
                    .clip(CircleShape)
                    .background(colors.thinkingDot)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Composable: Paragraph-level markdown rendering with entry animation
// ---------------------------------------------------------------------------

/**
 * Renders markdown text as a column of [MarkdownBlock]s. Paragraphs animate
 * in via the typewriter [AnimatedParagraph]; tables pop in atomically through
 * [MarkdownTable]. Block reveal is sequential — each block advances the queue
 * via `onRevealComplete` so the next one starts only after the current one
 * finishes (or pops, for tables).
 */
@Composable
private fun MarkdownText(
    fullText: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier
) {
    val chatColors = LocalChatColors.current
    val markdownColors = remember(chatColors) {
        MarkdownColors(
            codeBg = chatColors.codeBlockBackground,
            codeText = chatColors.codeBlockText,
            keywordColor = chatColors.keywordHighlight,
            fenceColor = chatColors.codeFenceLabel,
            linkColor = chatColors.linkColor,
        )
    }

    val blocks = remember(fullText, isStreaming, markdownColors) {
        parseMarkdownBlocks(text = fullText, isComplete = !isStreaming, colors = markdownColors)
    }

    // Sequential reveal queue, indexed over blocks (paragraphs + tables).
    // rememberSaveable so it survives LazyColumn item disposal — without this,
    // scrolling the bubble off-screen and back would reset to 0 and re-reveal.
    var revealedUpTo by rememberSaveable { mutableIntStateOf(0) }

    // hasComposedBefore distinguishes "first composition ever" from
    // "re-composition after the LazyColumn item was disposed and restored".
    // On re-composition during streaming, fast-forward the queue past
    // everything that arrived while we were disposed — see longer note on
    // the same pattern preserved from the paragraph-only version.
    var hasComposedBefore by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (hasComposedBefore && isStreaming && revealedUpTo < blocks.size) {
            revealedUpTo = blocks.size
        }
        hasComposedBefore = true
    }

    val config = LocalChatConfig.current
    val textColor = chatColors.assistantText
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(config.paragraphSpacing)) {
        blocks.forEachIndexed { index, block ->
            // During streaming, skip blocks that haven't reached their turn yet
            if (isStreaming && index > revealedUpTo) return@forEachIndexed

            val shouldReveal = isStreaming && index == revealedUpTo
            val onRevealComplete: () -> Unit = { revealedUpTo = index + 1 }

            key(index) {
                when (block) {
                    is MarkdownBlock.Paragraph -> AnimatedParagraph(
                        parsed = block.text,
                        isStreaming = isStreaming,
                        shouldReveal = shouldReveal,
                        onRevealComplete = onRevealComplete,
                        textColor = textColor,
                    )
                    is MarkdownBlock.Table -> {
                        MarkdownTable(table = block, textColor = textColor)
                        // Tables don't typewriter — advance the reveal queue
                        // immediately so the next block can start.
                        if (shouldReveal) {
                            LaunchedEffect(Unit) { onRevealComplete() }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(config.markdownTrailingSpacer))
    }
}

/**
 * Typewriter-style reveal for a single pre-parsed paragraph.
 *
 * Only typewriters when [shouldReveal] is true (its turn in the queue).
 * Calls [onRevealComplete] when the animation finishes so the next block
 * can start. When not streaming, shows full text immediately.
 *
 * Parsing happens once up in [MarkdownText] — this composable receives the
 * resulting [AnnotatedString] so the reveal loop doesn't re-parse on every
 * tick of the fade wave.
 */
@Composable
private fun AnimatedParagraph(
    parsed: AnnotatedString,
    isStreaming: Boolean,
    shouldReveal: Boolean,
    onRevealComplete: () -> Unit,
    textColor: Color,
) {
    val config = LocalChatConfig.current
    val totalChars = parsed.length

    // Persist the typewriter progress so LazyColumn item disposal
    // (when the bubble scrolls off-screen) doesn't restart the animation
    // from zero on the way back in.
    var savedProgress by rememberSaveable { mutableFloatStateOf(0f) }
    val revealProgress = remember { Animatable(savedProgress) }
    val durationMs = (totalChars * 1000 / config.typewriterCharsPerSec).coerceAtLeast(config.typewriterMinMs)

    LaunchedEffect(Unit) {
        snapshotFlow { revealProgress.value }.collect { savedProgress = it }
    }

    LaunchedEffect(shouldReveal) {
        if (shouldReveal && revealProgress.value < 1f) {
            revealProgress.animateTo(1f, tween(durationMs, easing = LinearEasing))
            onRevealComplete()
        }
    }

    val visibleChars = when {
        !isStreaming -> totalChars           // not streaming: full text
        shouldReveal -> (revealProgress.value * totalChars).toInt().coerceAtMost(totalChars)
        else -> totalChars                   // past paragraph: already revealed
    }

    val displayText = if (visibleChars >= totalChars) {
        parsed
    } else {
        val sub = parsed.subSequence(0, visibleChars) as AnnotatedString
        buildAnnotatedString {
            append(sub)
            // Apply fading alpha based on distance from the leading edge.
            for (i in 0 until visibleChars) {
                val distFromFront = visibleChars - 1 - i
                if (distFromFront < config.waveLength) {
                    val progress = distFromFront.toFloat() / config.waveLength
                    val alpha = progress * progress // quadratic ease-in
                    addStyle(SpanStyle(color = textColor.copy(alpha = alpha)), i, i + 1)
                }
            }
        }
    }

    Text(
        text = displayText,
        style = MaterialTheme.typography.bodyLarge,
        color = textColor
    )
}

// ---------------------------------------------------------------------------
// Composable: Markdown table rendering
// ---------------------------------------------------------------------------

/**
 * Render a [MarkdownBlock.Table] with per-column widths derived from the
 * widest cell text and capped at [ChatConfig.tableColumnMaxWidth]. Wraps the
 * whole grid in `horizontalScroll` so wide tables swipe sideways instead of
 * cramping into the bubble.
 *
 * Why a [TextMeasurer] instead of `IntrinsicSize.Max`: intrinsic widths
 * don't propagate across siblings in different `Row`s, so they can't align
 * "all cells in column N share width X". A single measurement pass over all
 * cells gives a stable column width that every row uses.
 */
@Composable
private fun MarkdownTable(table: MarkdownBlock.Table, textColor: Color) {
    val density = LocalDensity.current
    val config = LocalChatConfig.current
    val textMeasurer = rememberTextMeasurer()

    val regularStyle = MaterialTheme.typography.bodyLarge
    val headerStyle = remember(regularStyle) { regularStyle.copy(fontWeight = FontWeight.Bold) }
    val cellHorizontalPaddingPx = with(density) {
        (config.tableCellPaddingHorizontal * 2).roundToPx()
    }
    val maxColumnWidthPx = with(density) { config.tableColumnMaxWidth.roundToPx() }

    val columnWidthsPx = remember(table, density, regularStyle, headerStyle, cellHorizontalPaddingPx, maxColumnWidthPx) {
        val numCols = table.header.size
        if (numCols == 0) return@remember emptyList<Int>()
        (0 until numCols).map { col ->
            val headerWidth = table.header.getOrNull(col)?.takeIf { it.isNotEmpty() }
                ?.let { textMeasurer.measure(it, style = headerStyle).size.width } ?: 0
            val dataMaxWidth = table.rows.maxOfOrNull { row ->
                row.getOrNull(col)?.takeIf { it.isNotEmpty() }
                    ?.let { textMeasurer.measure(it, style = regularStyle).size.width } ?: 0
            } ?: 0
            (maxOf(headerWidth, dataMaxWidth) + cellHorizontalPaddingPx)
                .coerceAtMost(maxColumnWidthPx)
        }
    }
    val columnWidths: List<Dp> = with(density) { columnWidthsPx.map { it.toDp() } }
    val totalWidth: Dp = with(density) { columnWidthsPx.sum().toDp() }

    val scrollState = rememberScrollState()
    Column(modifier = Modifier.horizontalScroll(scrollState)) {
        TableRow(
            cells = table.header,
            alignments = table.alignments,
            columnWidths = columnWidths,
            textColor = textColor,
            isHeader = true,
        )
        HorizontalDivider(
            modifier = Modifier.width(totalWidth),
            color = textColor.copy(alpha = 0.2f),
        )
        table.rows.forEach { row ->
            TableRow(
                cells = row,
                alignments = table.alignments,
                columnWidths = columnWidths,
                textColor = textColor,
                isHeader = false,
            )
        }
    }
}

@Composable
private fun TableRow(
    cells: List<AnnotatedString>,
    alignments: List<TableAlignment>,
    columnWidths: List<Dp>,
    textColor: Color,
    isHeader: Boolean,
) {
    val config = LocalChatConfig.current
    Row {
        cells.forEachIndexed { idx, cell ->
            val textAlign = when (alignments.getOrNull(idx) ?: TableAlignment.START) {
                TableAlignment.START -> TextAlign.Start
                TableAlignment.CENTER -> TextAlign.Center
                TableAlignment.END -> TextAlign.End
            }
            Text(
                text = cell,
                color = textColor,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                textAlign = textAlign,
                modifier = Modifier
                    .width(columnWidths.getOrNull(idx) ?: 0.dp)
                    .padding(
                        horizontal = config.tableCellPaddingHorizontal,
                        vertical = config.tableCellPaddingVertical,
                    ),
            )
        }
    }
}

/** Single blinking cursor rendered below the last paragraph during streaming. */
@Composable
private fun BlinkingCursor(cursorColor: Color) {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorBlink"
    )
    Text(
        text = "▌",
        style = MaterialTheme.typography.bodyLarge,
        color = cursorColor.copy(alpha = alpha),
        fontWeight = FontWeight.Bold
    )
}

// ---------------------------------------------------------------------------
// Composable: Jump to bottom FAB
// ---------------------------------------------------------------------------

@Composable
private fun JumpToBottomButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalChatColors.current
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = colors.fabBackground,
        shadowElevation = 4.dp
    ) {
        Icon(
            imageVector = Icons.Rounded.KeyboardArrowDown,
            contentDescription = stringResource(R.string.chat_jump_bottom),
            modifier = Modifier.padding(8.dp),
            tint = colors.fabContent,
        )
    }
}

// ---------------------------------------------------------------------------
// Composable: Message bubble with entry animation
// ---------------------------------------------------------------------------

@Composable
private fun MessageBubble(
    message: ChatMessage,
    onRetryClick: (String) -> Unit,
    messageExtras: ChatMessageExtrasSlot
) {
    val config = LocalChatConfig.current
    val colors = LocalChatColors.current
    // History is immediately readable when switching conversations or restoring a list item.
    val alpha = remember { Animatable(if (message.streamState == StreamState.Complete) 1f else 0f) }

    LaunchedEffect(Unit) {
        alpha.animateTo(1f, tween(350, easing = FastOutSlowInEasing))
    }

    val isUser = message.sender == MessageSender.User
    val arrangement = if (isUser) Arrangement.End else Arrangement.Start

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha.value },
        horizontalArrangement = arrangement
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(if (isUser) 0.86f else 0.94f),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            if (!isUser) {
                Text(
                    text = config.assistantLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.assistantLabel,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )
            }
            // Horizontal inner padding differs by sender:
            // - User: full `bubbleInnerHorizontalPadding` so text breathes
            //   inside the rounded Surface bubble (the surface has visible
            //   edges that need a margin around the text).
            // - Assistant: matches the "Assistant" label's 4dp start inset,
            //   so the message text and the label share the same left
            //   edge. The assistant has no Surface — extra horizontal
            //   padding here is just text indentation, not bubble chrome.
            val assistantBubbleStartInset = 4.dp
            val bubbleHorizontalPadding =
                if (isUser) config.bubbleInnerHorizontalPadding else assistantBubbleStartInset
            val bubbleContent: @Composable () -> Unit = {
                when (message.streamState) {
                    StreamState.Thinking -> {
                        ThinkingIndicator(horizontalPadding = bubbleHorizontalPadding)
                    }
                    StreamState.Streaming -> {
                        MarkdownText(
                            fullText = message.text,
                            isStreaming = false,
                            modifier = Modifier.padding(horizontal = bubbleHorizontalPadding, vertical = config.bubbleInnerVerticalPadding)
                        )
                    }
                    StreamState.Complete -> {
                        if (isUser) {
                            Column(
                                modifier = Modifier.padding(
                                    horizontal = bubbleHorizontalPadding,
                                    vertical = config.bubbleInnerVerticalPadding
                                ),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = message.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.userBubbleText
                                )
                                message.attachmentLabel?.let { label ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = colors.userBubbleText.copy(alpha = 0.10f)
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = colors.userBubbleText,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            MarkdownText(
                                fullText = message.text,
                                isStreaming = false,
                                modifier = Modifier.padding(horizontal = bubbleHorizontalPadding, vertical = config.bubbleInnerVerticalPadding)
                            )
                        }
                    }
                }
            }

            if (isUser) {
                Surface(
                    shape = RoundedCornerShape(
                        topStart = 8.dp,
                        topEnd = 8.dp,
                        bottomStart = 8.dp,
                        bottomEnd = 8.dp
                    ),
                    color = colors.userBubbleBackground,
                    content = bubbleContent
                )
            } else {
                Box(modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                    bubbleContent()
                }
            }
            if (!isUser && (message.metadata != null || message.error != null)) {
                Row(
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = message.error ?: message.metadata.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message.error != null) MaterialTheme.colorScheme.error else colors.assistantLabel,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (message.canRetry) {
                        TextButton(onClick = { onRetryClick(message.id) }) {
                            Text(stringResource(R.string.chat_retry))
                        }
                    }
                }
            }
            if (message.errorDetails != null) {
                var showDetails by remember(message.id) { mutableStateOf(false) }
                TextButton(onClick = { showDetails = !showDetails }) {
                    Text(stringResource(if (showDetails) R.string.chat_diagnostics_hide else R.string.chat_diagnostics_show))
                }
                if (showDetails) Text(message.errorDetails, style = MaterialTheme.typography.bodySmall)
            }
            if (message.extraKey != null) {
                messageExtras(message)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Top bar slot — pluggable header composable
// ---------------------------------------------------------------------------

/**
 * Signature of the top bar slot accepted by [ChatScreen]. The slot owns
 * the bar fully — positioning (via [BoxScope.align]), background, padding,
 * status-bar handling, and chrome. The slot is invoked inside the chat
 * frame's inner [Box], so it has [BoxScope] access for alignment.
 *
 * The library reserves space for the bar above the message list via
 * [LazyColumn.contentPadding.top], driven by the bar's measured height.
 * Slot implementations report their height by attaching `onSizeChanged`
 * (or any equivalent measurement) to their outermost element and calling
 * [onHeightChanged] with the value. This keeps the library agnostic about
 * the bar's exact layout while still letting it size content correctly.
 */
typealias ChatTopBarSlot = @Composable BoxScope.(onHeightChanged: (Int) -> Unit) -> Unit

/**
 * The library's stock top bar — Menu / title / new-chat icons. Apps can
 * use this directly or pass an entirely different composable as
 * [ChatScreen]'s `topBar` slot. Applies `statusBarsPadding` internally
 * so the chrome sits below the system status bar.
 */
@Composable
fun DefaultChatTopBar(
    modifier: Modifier = Modifier,
    title: String = "Stream Chat",
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = {}) {
            Icon(
                imageVector = Icons.Rounded.Menu,
                contentDescription = stringResource(R.string.chat_menu)
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        IconButton(onClick = {}) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.chat_new)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Composer slot — pluggable footer composable
// ---------------------------------------------------------------------------

/**
 * Signature of the composer slot accepted by [ChatScreen]. Receives the
 * current `draft`, `isStreaming` flag, an `onDraftChange` callback for the
 * input field, and an `onSendClick` callback that should be invoked when
 * the user presses send. The library handles trim/empty validation,
 * keyboard dismissal, and the eventual call to the host's `onSendClick`
 * — slot implementations only need to render UI and signal intent.
 */
typealias ChatComposerSlot = @Composable (
    draft: String,
    isStreaming: Boolean,
    onDraftChange: (String) -> Unit,
    onSendClick: () -> Unit,
) -> Unit

/** Optional app-owned content rendered directly beneath a message. */
typealias ChatMessageExtrasSlot = @Composable (message: ChatMessage) -> Unit

/**
 * The library's stock composer bar — rounded text input plus a circular
 * send button. Apps can use this directly, wrap it, or pass an entirely
 * different composable as [ChatScreen]'s `composer` slot.
 *
 * The slot itself is transparent — the library wrapper around the slot
 * paints a glassy gradient backdrop that obscures scrolling messages
 * behind the composer. Reads colors from [LocalChatColors].
 */
@Composable
fun DefaultComposerBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    isStreaming: Boolean,
    onSendClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Message",
) {
    val sendEnabled = draft.isNotBlank() && !isStreaming
    val colors = LocalChatColors.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(placeholder) },
            shape = RoundedCornerShape(26.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = colors.composerSurface,
                unfocusedContainerColor = colors.composerSurface,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        Surface(
            onClick = { if (sendEnabled) onSendClick() },
            shape = CircleShape,
            color = if (sendEnabled) colors.sendButtonBackground else colors.sendButtonDisabledBackground,
            modifier = Modifier.size(54.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.ArrowUpward,
                    contentDescription = stringResource(R.string.chat_send),
                    tint = if (sendEnabled) colors.sendButtonContent else colors.sendButtonDisabledContent,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Composable: Main chat screen
// ---------------------------------------------------------------------------

/**
 * Public entry point for the chat UI. Wraps the rendering body in
 * [CompositionLocalProvider] so the supplied [config] and [colors] are
 * visible to every nested composable that reads [LocalChatConfig] /
 * [LocalChatColors].
 */
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    draft: String,
    isStreaming: Boolean = false,
    onDraftChange: (String) -> Unit = {},
    onSendClick: (String) -> Unit = {},
    onRetryClick: (String) -> Unit = {},
    config: ChatConfig = ChatConfig(),
    colors: ChatColors = ChatDefaults.colors(),
    messageExtras: ChatMessageExtrasSlot = {},
    topBar: ChatTopBarSlot = { onHeightChanged ->
        // Default top-bar slot — same visual the library used to wrap. Hosts
        // can pass their own slot to fully replace this (positioning,
        // backdrop, chrome, all of it).
        val backdrop = LocalChatColors.current.backgroundGradient.first().copy(alpha = 1f)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        0.0f to backdrop.copy(alpha = 0.95f),
                        0.15f to backdrop.copy(alpha = 0.9f),
                        0.5f to backdrop.copy(alpha = 0.55f),
                        1.0f to backdrop.copy(alpha = 0f),
                    )
                )
                .onSizeChanged { onHeightChanged(it.height) },
        ) {
            DefaultChatTopBar()
        }
    },
    composer: ChatComposerSlot = { d, s, c, o ->
        DefaultComposerBar(draft = d, onDraftChange = c, isStreaming = s, onSendClick = o)
    },
) {
    CompositionLocalProvider(
        LocalChatConfig provides config,
        LocalChatColors provides colors,
    ) {
        ChatScreenInternal(
            messages = messages,
            draft = draft,
            isStreaming = isStreaming,
            onDraftChange = onDraftChange,
            onSendClick = onSendClick,
            onRetryClick = onRetryClick,
            messageExtras = messageExtras,
            topBar = topBar,
            composer = composer,
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChatScreenInternal(
    messages: List<ChatMessage>,
    draft: String,
    isStreaming: Boolean,
    onDraftChange: (String) -> Unit,
    onSendClick: (String) -> Unit,
    onRetryClick: (String) -> Unit,
    messageExtras: ChatMessageExtrasSlot,
    topBar: ChatTopBarSlot,
    composer: ChatComposerSlot,
) {
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val config = LocalChatConfig.current

    val topBarHeightPx = remember { mutableIntStateOf(0) }
    val composerHeightPx = remember { mutableIntStateOf(0) }
    val boxHeightPx = remember { mutableIntStateOf(0) }

    val topBarPadding = with(density) {
        if (topBarHeightPx.intValue == 0) 0.dp else topBarHeightPx.intValue.toDp()
    }
    val composerPadding = with(density) {
        if (composerHeightPx.intValue == 0) 0.dp else composerHeightPx.intValue.toDp()
    }

    // Bottom inset (IME + nav bar), driven by our own clean tween.
    //
    // We do NOT follow `WindowInsets.ime` directly: on this device the
    // system IME animation curve overshoots — `ime.bottom` briefly goes
    // past the keyboard's final height and then settles back, which made
    // the composer visibly bounce above the keyboard.
    //
    // Instead we read `WindowInsets.imeAnimationTarget` (which snaps to
    // the *final* IME inset value the moment an animation begins, no
    // overshoot) and run our own monotonic tween from current → target.
    // `union` with `navigationBars` keeps the layout above the gesture
    // handle when the keyboard is closed.
    val targetBottomInsetPx = if (config.handleSystemBottomInsets) {
        with(density) {
            maxOf(
                WindowInsets.navigationBars.getBottom(this),
                WindowInsets.imeAnimationTarget.getBottom(this),
            )
        }
    } else {
        0
    }
    val animatedBottomInsetPx by animateIntAsState(
        targetValue = targetBottomInsetPx,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "bottomInset"
    )
    val animatedBottomInsetDp = with(density) { animatedBottomInsetPx.toDp() }

    // Extra bottom padding during streaming: fills remaining viewport below
    // the last user message + assistant content, so the user message can be
    // pinned at the top. Shrinks as the assistant response grows.
    val spacingPx = with(density) { 14.dp.roundToPx() }
    // Height of the spacer item at the end of the LazyColumn, used to pin
    // the user message at the top. Shrinks as the assistant response grows.
    val spacerHeightPx by remember(isStreaming) {
        derivedStateOf {
            if (!isStreaming) return@derivedStateOf 0

            val fixedVerticalPaddingPx = with(density) { 24.dp.roundToPx() }
            val availableHeightPx = boxHeightPx.intValue -
                topBarHeightPx.intValue - composerHeightPx.intValue - fixedVerticalPaddingPx
            if (availableHeightPx <= 0) return@derivedStateOf 0

            // Only measure real message items (exclude the spacer itself)
            val lastUserIdx = messages.indexOfLast { it.sender == MessageSender.User }
            val relevantItems = listState.layoutInfo.visibleItemsInfo
                .filter { it.index >= lastUserIdx && it.index < messages.size }
            if (relevantItems.isEmpty()) return@derivedStateOf availableHeightPx

            val totalItemsHeight = relevantItems.sumOf { it.size }
            val totalSpacing = (relevantItems.size - 1).coerceAtLeast(0) * spacingPx

            (availableHeightPx - totalItemsHeight - totalSpacing).coerceAtLeast(0) //  theSpacerHeight
        }
    }
    val spacerHeight = with(density) { spacerHeightPx.toDp() }

    // Follow mode: controls whether the viewport auto-scrolls to follow
    // new streaming content. OFF by default — user opts in via FAB or
    // by scrolling to the bottom manually.
    var followMode by remember { mutableStateOf(false) }

    // True only while a real finger gesture is driving the list — set by a
    // NestedScrollConnection that observes scroll events with source =
    // UserInput (covers drag and fling), cleared when the scroll fully
    // settles. Declared early so the auto-scroll loop below can pause while
    // the user is gesturing.
    var userScrollActive by remember { mutableStateOf(false) }
    val userScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    userScrollActive = true
                }
                return Offset.Zero
            }
        }
    }

    // Show FAB when the last message's text bottom sits below the user-visible
    // area (i.e. below the LazyColumn bottom minus the contentPadding strip
    // that the composer overlay covers). Subtract the bubble's inner bottom
    // padding so the FAB doesn't trigger when only decorative padding is
    // hidden. Targeting the message item directly avoids the canScrollForward
    // flicker caused by the streaming spacer's frame-by-frame resize.
    // Suppressed during streaming follow mode, since auto-scroll keeps the
    // bottom in view.
    val messagesState = rememberUpdatedState(messages)
    // Decorative trailing space below the last text line in an assistant
    // bubble: bubble bottom padding + Column spacedBy gap + trailing Spacer.
    val trailingDecorationPx = with(density) {
        (config.bubbleInnerVerticalPadding + config.paragraphSpacing + config.markdownTrailingSpacer).roundToPx()
    }
    // Hysteresis: require at least one line of text to be hidden before the
    // FAB shows — avoids popping the FAB on a grazing scroll.
    val hiddenTextThresholdPx = with(density) { config.fabHiddenTextThreshold.roundToPx() }
    val showJumpToBottom by remember(isStreaming, trailingDecorationPx, hiddenTextThresholdPx, followMode) {
        derivedStateOf {
            if (isStreaming && followMode) return@derivedStateOf false
            val msgs = messagesState.value
            if (msgs.isEmpty()) return@derivedStateOf false
            val info = listState.layoutInfo
            val lastMsg = info.visibleItemsInfo.find { it.index == msgs.lastIndex }
            val visibleBottom = info.viewportEndOffset - info.afterContentPadding
            val textBottom = lastMsg?.let { it.offset + it.size - trailingDecorationPx }
            textBottom == null || textBottom > visibleBottom + hiddenTextThresholdPx
        }
    }

    // Pin user message to top when a new conversation round starts
    LaunchedEffect(messages.size) {
        val lastUserIdx = messages.indexOfLast { it.sender == MessageSender.User }
        if (lastUserIdx >= 0) {
            listState.animateScrollToItem(lastUserIdx)
        }
    }

    // Reset follow mode when a new streaming session starts.
    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            followMode = false
        }
    }

    // During streaming, auto-scroll to the bottom only when followMode
    // is active and the spacer is fully consumed. Also pause while the user
    // is gesturing — otherwise the loop's repeated scrollBy makes
    // isScrollInProgress flip true→false every 32 ms, and the
    // isScrollInProgress LaunchedEffect below clears userScrollActive on
    // every "false" transition, even while the user's finger is still on
    // the screen. That eats the scroll-up detection.
    LaunchedEffect(isStreaming) {
        if (!isStreaming) return@LaunchedEffect
        while (isActive) {
            delay(32L)
            if (followMode && !userScrollActive && spacerHeightPx <= 0 && listState.canScrollForward) {
                val viewport = listState.layoutInfo.viewportSize.height
                if (viewport > 0) listState.scrollBy(viewport.toFloat())
            }
        }
    }

    // When streaming ends with followMode on, snap to the absolute bottom.
    // Loop in viewport-sized chunks driven by canScrollForward: each chunk
    // gives the LazyColumn time to lay out the next batch of items, so the
    // loop reliably reaches the end no matter how much content is below the
    // fold. canScrollForward is the source of truth for "we're at the bottom".
    var prevStreaming by remember { mutableStateOf(isStreaming) }
    LaunchedEffect(isStreaming) {
        if (prevStreaming && !isStreaming && followMode) {
            while (listState.canScrollForward) {
                val viewport = listState.layoutInfo.viewportSize.height
                if (viewport <= 0) break
                listState.scrollBy(viewport.toFloat())
            }
        }
        prevStreaming = isStreaming
    }

    // Programmatic scrollBy/animateScrollBy never set userScrollActive, so
    // layout-induced offset changes and programmatic scrolls can't be misread
    // as "user scrolled up". Clear the flag once the scroll fully settles
    // (covers fling release).
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { inProgress ->
            if (!inProgress) userScrollActive = false
        }
    }

    // Detect user scroll gestures to toggle follow mode. Both transitions are
    // gated on userScrollActive so only a real finger gesture flips follow
    // mode — programmatic scrolls (FAB-tap loop, end-of-stream snap, etc.)
    // can't accidentally turn it on or off.
    // - User scroll up → deactivate
    // - User-driven scroll to bottom → activate (user wants to follow content)
    val currentIsStreaming by rememberUpdatedState(isStreaming)
    LaunchedEffect(Unit) {
        var prev = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            if (followMode) {
                val scrolledUp = index < prev.first ||
                    (index == prev.first && offset < prev.second)
                if (scrolledUp && userScrollActive) {
                    followMode = false
                }
            } else if (currentIsStreaming && userScrollActive && !listState.canScrollForward) {
                followMode = true
            }
            prev = index to offset
        }
    }

    // Composer wrapper backdrop color. The composer fades INTO the chat
    // from the bottom; we coerce the gradient's last color to full opacity
    // in case the theme's gradient end-color is intentionally translucent
    // — otherwise scrolling messages would show through the "opaque" region.
    // (The top-bar backdrop now lives entirely inside the slot — see the
    // `topBar` parameter on [ChatScreen].)
    val backgroundGradient = LocalChatColors.current.backgroundGradient
    val composerBackdropColor = backgroundGradient.last().copy(alpha = 1f)

    /**
     * Two layers:
     * - The OUTER Box paints the gradient at full screen, unconditionally.
     *   Keeping the background and the inset-padding on different layers
     *   guarantees no black gap appears below the children area mid-animation
     *   (Compose's `padding` modifier shrinks the drawn area when chained
     *   after `background`, which would expose the system backdrop).
     * - The INNER Box owns the IME/nav-bar bottom inset and lays out the
     *   message list, FAB, and composer slot above the keyboard.
     */
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = backgroundGradient,
                )
            )
    ) {
      Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = animatedBottomInsetDp)
            .onSizeChanged { boxHeightPx.intValue = it.height }
    ) {
        // Message list — NO weight, NO SpaceBetween.
        // Few messages sit at the top with empty space below.
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(userScrollConnection),
            contentPadding = PaddingValues(
                // Horizontal start/end picked to match both the top bar's
                // menu-icon left edge and the composer pill's left edge in
                // the bundled demos: outer Row/Box padding + the IconButton's
                // 8dp icon-centering inset land the visible icon at 16dp,
                // and the composer's 16dp horizontal padding lands the pill
                // at 16dp. Keeping the LazyColumn at the same 16dp gives a
                // clean vertical alignment across the whole screen.
                start = 16.dp,
                top = topBarPadding + 12.dp,
                end = 16.dp,
                bottom = composerPadding + 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                MessageBubble(
                    message = message,
                    onRetryClick = onRetryClick,
                    messageExtras = messageExtras
                )
            }
            // Spacer item to pin the user message at the top during streaming.
            // Shrinks as the assistant response grows; reaches 0 when content
            // fills the viewport, then auto-scroll takes over.
            if (spacerHeight > 0.dp) {
                item(key = "bottom-spacer") {
                    Spacer(modifier = Modifier.height(spacerHeight))
                }
            }
        }

        // Top bar slot — fully owned by the host. Library invokes it
        // directly inside this Box (so the slot has BoxScope access for
        // `align(...)`) and subscribes to its measured-height callback,
        // which feeds `LazyColumn.contentPadding.top`. Positioning,
        // backdrop, padding, status-bar handling, and chrome are all the
        // slot's responsibility — see [ChatTopBarSlot] for the contract.
        topBar { topBarHeightPx.intValue = it }

        // Jump to bottom FAB
        if (showJumpToBottom) {
            JumpToBottomButton(
                onClick = {
                    followMode = true
                    coroutineScope.launch {
                        // Instant jump: chunked non-animated scrollBy until
                        // the list can no longer scroll forward. The loop is
                        // still required because LazyColumn lays out items
                        // lazily — each scrollBy gives layout time to
                        // materialize the next batch below the fold.
                        while (listState.canScrollForward) {
                            val viewport = listState.layoutInfo.viewportSize.height
                            if (viewport <= 0) break
                            listState.scrollBy(viewport.toFloat())
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = composerPadding + 16.dp)
            )
        }

        // Composer slot overlaid at bottom. Library wraps it in a Box that
        // (a) tracks the slot's measured height (used by LazyColumn's
        // contentPadding.bottom and by the spacer-pin logic), (b) paints a
        // glassy backdrop fading from transparent at the top to opaque
        // chat-bg at the bottom — symmetric mirror of the top bar — so
        // scrolling messages dissolve into the chat color instead of peeking
        // through the slot's transparent paddings, and (c) signals "user
        // pressed send" via onSendClick: the library handles the
        // trim/empty/streaming check, dismisses the keyboard, then invokes
        // the host's onSendClick(content).
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        0.0f to composerBackdropColor.copy(alpha = 0f),
                        0.5f to composerBackdropColor,
                        1.0f to composerBackdropColor,
                    )
                )
                .onSizeChanged { composerHeightPx.intValue = it.height },
        ) {
            composer(draft, isStreaming, onDraftChange) {
                val content = draft.trim()
                if ((content.isEmpty() && !config.allowEmptyDraftSend) || isStreaming) return@composer
                keyboardController?.hide()
                focusManager.clearFocus(force = true)
                onSendClick(content)
            }
        }
      }
    }
}

// ---------------------------------------------------------------------------
// Preview
// ---------------------------------------------------------------------------

/**
 * Design-time preview of [ChatScreen] with a sample conversation that
 * exercises every renderer in the chat: a plain user bubble, a completed
 * assistant reply with markdown (bold / italic / inline code / fenced code
 * block), and a Thinking placeholder for the next turn. Uses the default
 * [ChatConfig] and [ChatColors]; wraps in a bare [MaterialTheme] so
 * [ChatDefaults.colors] resolves against Material defaults.
 */
@Preview(showBackground = true, heightDp = 720)
@Composable
private fun ChatScreenPreview() {
    MaterialTheme {
        ChatScreen(
            messages = listOf(
                ChatMessage(
                    id = "user-0",
                    sender = MessageSender.User,
                    text = "Can you show me a small Kotlin coroutine example?",
                ),
                ChatMessage(
                    id = "assistant-0",
                    sender = MessageSender.Assistant,
                    text = """
                        Here's a small **Kotlin coroutine** example:

                        ```kotlin
                        suspend fun fetchUser(id: String): User {
                            return withContext(Dispatchers.IO) {
                                api.getUser(id)
                            }
                        }
                        ```

                        The `withContext` call ensures network work happens on a *background thread* without blocking the caller.
                    """.trimIndent(),
                    streamState = StreamState.Complete,
                ),
                ChatMessage(
                    id = "user-1",
                    sender = MessageSender.User,
                    text = "Thanks!",
                ),
                ChatMessage(
                    id = "assistant-1",
                    sender = MessageSender.Assistant,
                    text = "",
                    streamState = StreamState.Thinking,
                ),
            ),
            draft = "",
        )
    }
}
