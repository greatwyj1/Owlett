package com.example.birdingsoundmvp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.birdingsoundmvp.owlett.*
import com.pact.chatui.ChatDefaults
import com.pact.chatui.LocalChatColors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OwlettComposerInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val skills = listOf(OwlettSkillDescriptor("lookup_bird", "/bird", "鸟种资料", "查看本地图鉴", false))

    private fun show(appearance: String, initial: String, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                OwlettTheme(appearance) {
                    CompositionLocalProvider(LocalChatColors provides ChatDefaults.colors()) {
                        var draft by remember { mutableStateOf(initial) }
                        var selection by remember { mutableStateOf(TextRange(initial.length)) }
                        var dismissed by remember { mutableStateOf(false) }
                        val selected = OwlettSlashCommands.selected(draft, skills)?.second?.id
                        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime), contentAlignment = Alignment.BottomCenter) {
                            Box(Modifier.width(320.dp)) {
                                OwlettComposer(draft, selection, dismissed,
                                    onEdit = { draft = it.text; selection = it.selection; dismissed = false },
                                    isStreaming = false, hasApiKey = true,
                                    selectedPlan = OwlettPlanPickerItem(1, "北京野鸭湖秋季观鸟计划", "2026-09-06", "野鸭湖"),
                                    skills = skills, selectedSkillId = selected,
                                    onSelectSkill = {
                                        val edit = OwlettSlashCommands.complete(draft, selection.end, "/bird", skills)
                                        draft = edit.text; selection = TextRange(edit.cursor); dismissed = true
                                    }, onRemoveSkill = { draft = OwlettSlashCommands.withoutCommands(draft, skills); selection = TextRange(draft.length) },
                                    onOpenPlanPicker = {}, onRemovePlan = {}, onSend = {}, onStop = {})
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun slashCompletionKeepsDraftAttachmentAndCursor() {
        show("light", "请查询\n/b")
        compose.onNodeWithTag("owlett-slash-menu").assertIsDisplayed()
        compose.onNodeWithText("鸟种资料").performClick()
        compose.onNodeWithTag("owlett-slash-menu").assertDoesNotExist()
        val config = compose.onNodeWithTag("owlett-input").fetchSemanticsNode().config
        assertEquals("请查询\n/bird ", config[SemanticsProperties.EditableText].text)
        assertEquals(TextRange(10), config[SemanticsProperties.TextSelectionRange])
        compose.onNodeWithText("北京野鸭湖秋季观鸟计划").assertIsDisplayed()
        compose.onNodeWithTag("owlett-send").assertIsDisplayed()
    }

    @Test fun darkNarrowLargeFontComposerDoesNotOverlapSend() {
        show("dark", "/bird 请帮我查一下蓝歌鸲的栖息地和鸣声特点", 1.3f)
        val input = compose.onNodeWithTag("owlett-input").fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithTag("owlett-send").fetchSemanticsNode().boundsInRoot
        val composer = compose.onNodeWithTag("owlett-composer").fetchSemanticsNode().boundsInRoot
        assertTrue(input.right <= send.left)
        assertTrue(input.bottom <= composer.bottom)
        assertTrue(send.right <= composer.right)
    }
}
