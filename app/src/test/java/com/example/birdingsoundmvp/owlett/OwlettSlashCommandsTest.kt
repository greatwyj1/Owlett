package com.example.birdingsoundmvp.owlett

import org.junit.Assert.*
import org.junit.Test

class OwlettSlashCommandsTest {
    private val skills = listOf("bird", "plan", "activity", "calls").map { OwlettSkillDescriptor(it, "/$it", it, "", false) }
    @Test fun `completion preserves surrounding text and moves cursor`() {
        val text = "帮我查 /bi 这只鸟"
        val edit = OwlettSlashCommands.complete(text, 7, "/bird", skills)
        assertEquals("帮我查 /bird  这只鸟", edit.text)
        assertEquals(10, edit.cursor)
    }
    @Test fun `empty slash completes with trailing space`() {
        assertEquals(OwlettDraftEdit("/bird ", 6), OwlettSlashCommands.complete("/", 1, "/bird", skills))
    }
    @Test fun `newlines and chinese prefixes allow commands`() {
        assertNotNull(OwlettSlashCommands.atCursor("请查询\n/b", 6))
        assertNotNull(OwlettSlashCommands.atCursor("请查询/b", 5))
    }
    @Test fun `urls and file paths do not trigger skills`() {
        listOf("https://example.com/bird", "/Users/test/bird", "C:/bird", "foo/bird", "3/4").forEach {
            assertNull(it, OwlettSlashCommands.atCursor(it, it.length))
        }
    }
    @Test fun `new skill replaces existing skill without losing user text`() {
        val text = "/plan 蓝歌鸲 /"
        val edit = OwlettSlashCommands.complete(text, text.length, "/bird", skills)
        assertEquals(" 蓝歌鸲 /bird ", edit.text)
        assertEquals("bird", OwlettSlashCommands.selected(edit.text, skills)?.second?.id)
        assertEquals("蓝歌鸲", OwlettSlashCommands.withoutCommands(edit.text, skills))
    }
    @Test fun `deleting command clears selected skill`() {
        assertNull(OwlettSlashCommands.selected("蓝歌鸲", skills))
    }
}
