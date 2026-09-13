package com.pact.chatui


import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pact.chatui.ChatMessage
import com.pact.chatui.MessageSender
import com.pact.chatui.StreamState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Tuning constants (typewriter speed, padding, FAB threshold, etc.) live on
// ChatConfig now and are read via LocalChatConfig.current inside composables.

// ---------------------------------------------------------------------------
// Markdown colors — passed to pure functions so they can use theme colors
// ---------------------------------------------------------------------------

data class MarkdownColors(
    val codeBg: Color,
    val codeText: Color,
    val keywordColor: Color,
    val fenceColor: Color,
    val linkColor: Color,
)

// ---------------------------------------------------------------------------
// Character-scanning inline markdown parser — NO regex
// ---------------------------------------------------------------------------

/** Find closing multi-char delimiter (e.g. "**") starting from [startIndex]. Returns -1 if not found. */
private fun findClosing(text: String, startIndex: Int, delimiter: String): Int {
    val delimLen = delimiter.length
    var i = startIndex
    while (i <= text.length - delimLen) {
        if (text.regionMatches(i, delimiter, 0, delimLen)) return i
        i++
    }
    return -1
}

/** Find closing single-char delimiter starting from [startIndex]. Returns -1 if not found. */
private fun findClosingSingle(text: String, startIndex: Int, delimiter: Char): Int {
    var i = startIndex
    while (i < text.length) {
        if (text[i] == delimiter) return i
        i++
    }
    return -1
}

/**
 * Append [line] with inline markdown styles (bold, italic, inline code).
 * Character-scanning approach — no regex in the hot path.
 */
fun AnnotatedString.Builder.appendInlineMarkdown(line: String, colors: MarkdownColors) {
    var i = 0
    val len = line.length

    while (i < len) {
        when {
            // Bold: **...**
            i + 1 < len && line[i] == '*' && line[i + 1] == '*' -> {
                val closeIdx = findClosing(line, i + 2, "**")
                if (closeIdx != -1) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    // Recursive parse for nested italic inside bold
                    appendInlineMarkdown(line.substring(i + 2, closeIdx), colors)
                    pop()
                    i = closeIdx + 2
                } else {
                    append(line[i])
                    i++
                }
            }
            // Italic: *...* (single asterisk, not **)
            line[i] == '*' && (i + 1 >= len || line[i + 1] != '*') -> {
                val closeIdx = findClosingSingle(line, i + 1, '*')
                if (closeIdx != -1) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(line.substring(i + 1, closeIdx))
                    pop()
                    i = closeIdx + 1
                } else {
                    append(line[i])
                    i++
                }
            }
            // Inline code: `...`
            line[i] == '`' -> {
                val closeIdx = findClosingSingle(line, i + 1, '`')
                if (closeIdx != -1) {
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = colors.codeBg,
                            color = colors.codeText
                        )
                    )
                    append(line.substring(i + 1, closeIdx))
                    pop()
                    i = closeIdx + 1
                } else {
                    append(line[i])
                    i++
                }
            }
            // Markdown link: [text](url)
            // Only matches when ALL FOUR delimiters are present (`[`, `]`,
            // `(`, `)`); a partial `[text` mid-stream falls through and
            // renders as plain text until the closing paren arrives.
            line[i] == '[' -> {
                val closeBracket = findClosingSingle(line, i + 1, ']')
                val opensParen = closeBracket != -1 &&
                    closeBracket + 1 < len &&
                    line[closeBracket + 1] == '('
                val closeParen = if (opensParen) {
                    findClosingSingle(line, closeBracket + 2, ')')
                } else -1

                if (closeBracket != -1 && opensParen && closeParen != -1) {
                    val linkText = line.substring(i + 1, closeBracket)
                    val url = line.substring(closeBracket + 2, closeParen)
                    withLink(
                        LinkAnnotation.Url(
                            url = url,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = colors.linkColor,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ),
                        )
                    ) {
                        // Recurse so links can contain other inline styles
                        // (e.g. **bold**, *italic*, `code` inside the link
                        // text). The recursion is bounded by linkText being
                        // strictly shorter than `line`.
                        appendInlineMarkdown(linkText, colors)
                    }
                    i = closeParen + 1
                } else {
                    append(line[i])
                    i++
                }
            }
            else -> {
                append(line[i])
                i++
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Code block rendering with keyword coloring
// ---------------------------------------------------------------------------

private val KOTLIN_KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "if", "else", "when",
    "return", "import", "package", "interface", "data", "sealed",
    "private", "internal", "override", "suspend", "launch", "true", "false"
)

/** Append a single code line with monospace font and keyword highlighting. */
fun AnnotatedString.Builder.appendStyledCodeLine(line: String, colors: MarkdownColors) {
    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBg, fontSize = 14.sp))
    var i = 0
    while (i < line.length) {
        if (line[i].isLetter() || line[i] == '_') {
            val wordStart = i
            while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '_')) i++
            val word = line.substring(wordStart, i)
            if (word in KOTLIN_KEYWORDS) {
                pushStyle(SpanStyle(color = colors.keywordColor, fontWeight = FontWeight.SemiBold))
                append(word)
                pop()
            } else {
                append(word)
            }
        } else {
            append(line[i])
            i++
        }
    }
    pop()
}

// ---------------------------------------------------------------------------
// Document-level parser: handles code fences + inline markdown per line
// ---------------------------------------------------------------------------

/**
 * Parse a span of markdown text (typically a `\n\n`-delimited paragraph or
 * table cell) into an [AnnotatedString].
 *
 * Strategy:
 * - Split by `\n` and track code-block state across lines.
 * - Pre-scan for closed code blocks (matching ``` pairs) when [isComplete].
 * - Closed code blocks get styled background + keyword coloring.
 * - Unclosed code blocks (mid-stream) render as plain monospace.
 * - Normal lines get inline markdown parsing.
 */
internal fun parseDocument(fullText: String, isComplete: Boolean, colors: MarkdownColors): AnnotatedString {
    if (fullText.isEmpty()) return AnnotatedString("")

    val lines = fullText.split("\n")

    // Pre-scan: identify line-index ranges of CLOSED code blocks
    val closedBlockRanges = mutableListOf<IntRange>()
    var openLine = -1
    lines.forEachIndexed { i, line ->
        if (line.trimStart().startsWith("```")) {
            if (openLine == -1) {
                openLine = i
            } else {
                closedBlockRanges.add(openLine..i)
                openLine = -1
            }
        }
    }
    // If isComplete and there's an unclosed block, it stays plain monospace (openLine != -1)

    return buildAnnotatedString {
        var inCodeBlock = false

        lines.forEachIndexed { lineIndex, line ->
            if (lineIndex > 0) append("\n")

            val isFence = line.trimStart().startsWith("```")

            if (isFence) {
                inCodeBlock = !inCodeBlock
                // Render fence line as dim monospace label
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = colors.fenceColor, fontSize = 13.sp))
                append(line)
                pop()
                return@forEachIndexed
            }

            if (inCodeBlock) {
                val isInClosedBlock = closedBlockRanges.any { lineIndex in it }
                if (isInClosedBlock || isComplete) {
                    // Fully closed code block: styled with keyword coloring
                    appendStyledCodeLine(line, colors)
                } else {
                    // Unclosed block during streaming: plain monospace, no background
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp))
                    append(line)
                    pop()
                }
                return@forEachIndexed
            }

            // Headers: # , ## , ###
            if (line.startsWith("### ")) {
                pushStyle(SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold))
                appendInlineMarkdown(line.substring(4), colors)
                pop()
                return@forEachIndexed
            }
            if (line.startsWith("## ")) {
                pushStyle(SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold))
                appendInlineMarkdown(line.substring(3), colors)
                pop()
                return@forEachIndexed
            }
            if (line.startsWith("# ")) {
                pushStyle(SpanStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold))
                appendInlineMarkdown(line.substring(2), colors)
                pop()
                return@forEachIndexed
            }

            // Bullet points: - or *  (only at line start, not mid-sentence asterisks)
            if (line.startsWith("- ") || line.startsWith("* ")) {
                append("  • ")
                appendInlineMarkdown(line.substring(2), colors)
                return@forEachIndexed
            }

            // Block quote
            if (line.startsWith("> ")) {
                pushStyle(SpanStyle(color = colors.fenceColor, fontStyle = FontStyle.Italic))
                appendInlineMarkdown(line.substring(2), colors)
                pop()
                return@forEachIndexed
            }

            // Normal line: inline markdown
            appendInlineMarkdown(line, colors)
        }
    }
}

// ---------------------------------------------------------------------------
// Block model: paragraphs + pipe-tables
// ---------------------------------------------------------------------------

/**
 * A top-level renderable unit. The renderer dispatches on subtype: paragraphs
 * flow through the typewriter; tables pop in atomically (an `AnnotatedString`
 * can hold inline spans but not a grid, so tables need real Compose layout).
 */
sealed class MarkdownBlock {
    /** Plain markdown text — already parsed inline (bold/italic/code/links/headers/quotes). */
    data class Paragraph(val text: AnnotatedString) : MarkdownBlock()

    /**
     * A pipe-table. [header] holds the first row's cells, [alignments] one
     * entry per column derived from the `:---` row, [rows] the data rows.
     * Cell content is pre-parsed `AnnotatedString` so inline markdown
     * (`**bold**`, `*italic*`, `` `code` ``, links) works inside cells.
     */
    data class Table(
        val header: List<AnnotatedString>,
        val alignments: List<TableAlignment>,
        val rows: List<List<AnnotatedString>>,
    ) : MarkdownBlock()
}

/** Per-column text alignment, derived from the markdown alignment row. */
enum class TableAlignment { START, CENTER, END }

/**
 * Parse [text] into a list of [MarkdownBlock]. Splits on `\n\n` and tries to
 * recognise each chunk as a table first; anything that doesn't match falls
 * through to [MarkdownBlock.Paragraph] via [parseDocument].
 *
 * Tables must be **standalone** chunks — a pipe row inline with surrounding
 * prose (no blank line above) won't be detected. Mid-stream partial tables
 * render as paragraphs with literal `|` until the alignment row arrives, at
 * which point the chunk snaps into a styled table.
 */
fun parseMarkdownBlocks(
    text: String,
    isComplete: Boolean,
    colors: MarkdownColors,
): List<MarkdownBlock> {
    if (text.isBlank()) return emptyList()
    val chunks = text.split("\n\n").filter { it.isNotBlank() }
    return chunks.map { chunk ->
        parseTableChunk(chunk, colors)
            ?: MarkdownBlock.Paragraph(parseDocument(chunk, isComplete = isComplete, colors = colors))
    }
}

/**
 * Try to parse [chunk] as a pipe-table. Strict — both the header line and
 * the alignment line must start with `|`, and the alignment line must be a
 * valid `:---|---:` row. Returns null otherwise so the caller can render the
 * chunk as a normal paragraph.
 */
private fun parseTableChunk(chunk: String, colors: MarkdownColors): MarkdownBlock.Table? {
    val lines = chunk.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.size < 2) return null

    val headerLine = lines[0]
    val alignmentLine = lines[1]
    if (!headerLine.startsWith("|") || !alignmentLine.startsWith("|")) return null

    val alignments = parseAlignmentRow(alignmentLine) ?: return null
    val header = splitTableRow(headerLine).map { cell ->
        parseDocument(cell, isComplete = true, colors = colors)
    }
    val rows = lines.drop(2).map { rowLine ->
        splitTableRow(rowLine).map { cell ->
            parseDocument(cell, isComplete = true, colors = colors)
        }
    }
    return MarkdownBlock.Table(header = header, alignments = alignments, rows = rows)
}

/** Split a row like `| a | b | c |` into `["a", "b", "c"]`. */
private fun splitTableRow(line: String): List<String> {
    val trimmed = line.trim().trim('|')
    return trimmed.split("|").map { it.trim() }
}

/**
 * Parse an alignment row like `| :--- | :---: | ---: |`. Returns null if any
 * cell isn't a valid dash run with optional leading/trailing colons.
 */
private fun parseAlignmentRow(line: String): List<TableAlignment>? {
    val cells = splitTableRow(line)
    if (cells.isEmpty()) return null
    return cells.map { cell ->
        val token = cell.trim()
        val startColon = token.startsWith(":")
        val endColon = token.endsWith(":")
        val core = token.removePrefix(":").removeSuffix(":")
        if (core.isEmpty() || core.any { it != '-' }) return null
        when {
            startColon && endColon -> TableAlignment.CENTER
            endColon -> TableAlignment.END
            else -> TableAlignment.START
        }
    }
}