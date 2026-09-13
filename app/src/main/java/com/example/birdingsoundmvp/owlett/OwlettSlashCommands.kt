package com.example.birdingsoundmvp.owlett

data class OwlettCommandToken(val start: Int, val end: Int, val text: String)
data class OwlettDraftEdit(val text: String, val cursor: Int)

object OwlettSlashCommands {
    private val tokenPattern = Regex("/[a-zA-Z]*")

    fun tokens(text: String): List<OwlettCommandToken> = tokenPattern.findAll(text).mapNotNull { match ->
        val start = match.range.first
        val end = match.range.last + 1
        val wordStart = text.take(start).indexOfLast(Char::isWhitespace) + 1
        val prefix = text.substring(wordStart, start)
        val next = text.getOrNull(end)
        val previous = text.getOrNull(start - 1)
        val isPath = prefix.contains('/') || prefix.contains(':') || prefix.contains('\\') ||
            previous?.let { it.isDigit() || it in 'a'..'z' || it in 'A'..'Z' || it in "._~" } == true ||
            next?.let { it == '/' || it == '.' || it == '\\' } == true
        if (isPath) null else OwlettCommandToken(start, end, match.value)
    }.toList()

    fun atCursor(text: String, cursor: Int): OwlettCommandToken? =
        tokens(text).lastOrNull { cursor > it.start && cursor <= it.end }

    fun selected(text: String, skills: List<OwlettSkillDescriptor>): Pair<OwlettCommandToken, OwlettSkillDescriptor>? =
        tokens(text).mapNotNull { token ->
            skills.firstOrNull { it.slashCommand.equals(token.text, true) }?.let { token to it }
        }.lastOrNull()

    fun complete(text: String, cursor: Int, command: String, skills: List<OwlettSkillDescriptor>): OwlettDraftEdit {
        val active = atCursor(text, cursor)
        val previous = selected(text, skills)?.first
        val range = active ?: previous ?: OwlettCommandToken(cursor, cursor, "")
        var output = text.replaceRange(range.start, range.end, "$command ")
        var newCursor = range.start + command.length + 1
        if (previous != null && previous.start != range.start) {
            val offset = if (previous.start > range.start) command.length + 1 - (range.end - range.start) else 0
            output = output.removeRange(previous.start + offset, previous.end + offset)
            if (previous.start < range.start) newCursor -= previous.end - previous.start
        }
        return OwlettDraftEdit(output, newCursor)
    }

    fun withoutCommands(text: String, skills: List<OwlettSkillDescriptor>): String {
        var output = text
        tokens(text).asReversed().filter { token -> skills.any { it.slashCommand.equals(token.text, true) } }
            .forEach { output = output.removeRange(it.start, it.end) }
        return output.trim()
    }
}
