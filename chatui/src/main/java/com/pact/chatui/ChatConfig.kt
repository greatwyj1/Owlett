package com.pact.chatui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tuning knobs for the chat UI's animations, layout, and timing. All values
 * have sensible defaults; a host app overrides any subset by constructing a
 * new [ChatConfig] and providing it via [LocalChatConfig].
 *
 * Reaches deeply nested composables (e.g. `MarkdownText`,
 * `AnimatedParagraph`) without prop-drilling — they read
 * [LocalChatConfig.current].
 */
data class ChatConfig(
    /** Name shown above assistant responses. */
    val assistantLabel: String = "Assistant",

    /** Whether the standalone chat surface should consume IME/navigation insets itself. */
    val handleSystemBottomInsets: Boolean = true,

    /** Whether a host-provided composer may send without visible draft text. */
    val allowEmptyDraftSend: Boolean = false,

    /** Characters per second the typewriter reveals during streaming. */
    val typewriterCharsPerSec: Int = 80,

    /** Minimum typewriter animation duration so very short text doesn't flash. */
    val typewriterMinMs: Int = 100,

    /** Number of characters in the leading-edge fade wave. */
    val waveLength: Int = 12,

    /** Inner vertical padding (top and bottom) inside each message bubble. */
    val bubbleInnerVerticalPadding: Dp = 14.dp,

    /** Inner horizontal padding (start and end) inside each message bubble. */
    val bubbleInnerHorizontalPadding: Dp = 18.dp,

    /** Vertical spacing between paragraphs inside a markdown message. */
    val paragraphSpacing: Dp = 10.dp,

    /** Trailing breathing-room spacer at the bottom of a markdown message. */
    val markdownTrailingSpacer: Dp = 8.dp,

    /**
     * Minimum amount of text the user must scroll past the visible bottom
     * before the jump-to-bottom FAB appears. Provides hysteresis so the FAB
     * doesn't flicker when the text bottom is grazing the fold.
     */
    val fabHiddenTextThreshold: Dp = 24.dp,

    /**
     * Delay between the user's send and the assistant transitioning from
     * Thinking to Streaming state.
     */
    val thinkingDelayMs: Long = 800L,

    /** Horizontal padding inside each markdown table cell. */
    val tableCellPaddingHorizontal: Dp = 6.dp,

    /** Vertical padding inside each markdown table cell. */
    val tableCellPaddingVertical: Dp = 4.dp,

    /**
     * Maximum width any single table column can claim. Caps a long cell so
     * one oversized entry can't blow the table out — content beyond this
     * wraps via the default `Text` soft-wrap.
     */
    val tableColumnMaxWidth: Dp = 200.dp,
)

/** Composition-local handle on the active [ChatConfig]. Defaults to [ChatConfig]. */
val LocalChatConfig = staticCompositionLocalOf { ChatConfig() }
