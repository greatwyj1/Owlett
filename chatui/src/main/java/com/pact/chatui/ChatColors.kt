package com.pact.chatui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Color slots used by the chat UI. Decoupled from Material's `ColorScheme`
 * so a host app can override individual surfaces without forking the
 * Material theme. Default values are derived from the ambient
 * [MaterialTheme.colorScheme] via [ChatDefaults.colors].
 */
data class ChatColors(
    val userBubbleBackground: Color,
    val userBubbleText: Color,
    val assistantText: Color,
    val assistantLabel: Color,
    val thinkingDot: Color,
    val codeBlockBackground: Color,
    val codeBlockText: Color,
    val keywordHighlight: Color,
    val codeFenceLabel: Color,
    val fabBackground: Color,
    val fabContent: Color,
    val sendButtonBackground: Color,
    val sendButtonContent: Color,
    val sendButtonDisabledBackground: Color,
    val sendButtonDisabledContent: Color,
    val composerBackground: Color,
    val composerSurface: Color,
    val composerOnSurface: Color,
    val composerDivider: Color,
    val composerHintText: Color,
    val linkColor: Color,
    val backgroundGradient: List<Color>,
)

object ChatDefaults {
    /** Material-derived defaults for [ChatColors]. */
    @Composable
    fun colors(): ChatColors {
        val cs = MaterialTheme.colorScheme
        return ChatColors(
            userBubbleBackground = cs.primaryContainer,
            userBubbleText = cs.onPrimaryContainer,
            assistantText = cs.onSurface,
            assistantLabel = cs.onSurfaceVariant,
            thinkingDot = cs.onSurfaceVariant.copy(alpha = 0.5f),
            codeBlockBackground = cs.surfaceVariant.copy(alpha = 0.5f),
            codeBlockText = cs.onSurface,
            keywordHighlight = cs.primary,
            codeFenceLabel = cs.onSurfaceVariant,
            fabBackground = cs.primaryContainer,
            fabContent = cs.onPrimaryContainer,
            sendButtonBackground = cs.primary,
            sendButtonContent = cs.onPrimary,
            sendButtonDisabledBackground = cs.surfaceVariant,
            sendButtonDisabledContent = cs.outline,
            composerBackground = cs.background.copy(alpha = 0.98f),
            composerSurface = cs.surface,
            composerOnSurface = cs.onSurface,
            composerDivider = cs.outlineVariant.copy(alpha = 0.6f),
            composerHintText = cs.onSurfaceVariant,
            linkColor = cs.primary,
            backgroundGradient = listOf(
                cs.background,
                cs.surfaceVariant.copy(alpha = 0.55f),
            ),
        )
    }
}

/**
 * Composition-local handle on the active [ChatColors]. Errors if read
 * outside a `ChatScreen` (or any caller that explicitly provides one) —
 * this is intentional, the chat UI requires a colors palette to render.
 */
val LocalChatColors = compositionLocalOf<ChatColors> {
    error("ChatColors not provided. Wrap content in ChatScreen or provide via LocalChatColors.")
}
