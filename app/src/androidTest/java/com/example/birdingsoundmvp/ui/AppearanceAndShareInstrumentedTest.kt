package com.example.birdingsoundmvp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.birdingsoundmvp.settings.AppSettings
import com.example.birdingsoundmvp.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearanceAndShareInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun themePersistsWithoutChangingDarkMode() = runBlocking {
        val repo = SettingsRepository(ApplicationProvider.getApplicationContext())
        val before = repo.settings.first()
        try {
            repo.update { it.copy(appearance = "dark", colorTheme = "gold") }
            val reloaded = SettingsRepository(ApplicationProvider.getApplicationContext()).settings.first()
            assertEquals("gold", reloaded.colorTheme)
            assertEquals("dark", reloaded.appearance)
        } finally { repo.update { before } }
    }
    @Test fun shareHasItsOwnAccessibleButtonAndHonorsAvailability() {
        var opened = 0
        compose.setContent {
            OwlettTheme("light", "feather") {
                PlaybackControlBar(false, SpectrogramSelection(0, 1000, true), SelectionPlaybackUiState(),
                    FilteredSelectionClipUiState(), PreciseRecognitionUiState(), 0, 4000, {}, {}, { opened++ }, true)
            }
        }
        compose.onNodeWithContentDescription("分享选区").assertIsEnabled().performClick()
        assertEquals(1, opened)
        compose.onNodeWithText("播放").assertExists()
    }
    @Test fun allThemesAndNavigationLabelsAreReachable() {
        compose.setContent { OwlettTheme("dark", "gold") { AppearanceSettings(AppSettings(), {}) } }
        listOf("清爽绿", "羽色·灰褐", "羽色·暖金", "跟随系统", "浅色", "深色").forEach {
            compose.onNodeWithText(it).assertExists()
        }
    }
    @Test fun unavailableShareCannotSubmit() {
        compose.setContent {
            OwlettTheme("dark", "green") {
                PlaybackControlBar(false, null, SelectionPlaybackUiState(), FilteredSelectionClipUiState(),
                    PreciseRecognitionUiState(), 0, 4000, {}, {}, { error("Must not submit") }, false)
            }
        }
        compose.onNodeWithContentDescription("分享选区").assertIsNotEnabled()
    }
    @Test fun missingThumbnailUsesPlaceholderAndDoesNotSelectParentRow() {
        var selected = 0
        var opened = 0
        compose.setContent {
            OwlettTheme("light", "green") {
                CompositionLocalProvider(LocalThumbnailLoader provides { _, _, _ -> null }) {
                    Box(Modifier.clickable { selected++ }) {
                        BirdThumbnail("Unmatched species", "测试鸟") { opened++ }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("暂无鸟种图片").performClick()
        assertEquals(1, opened)
        assertEquals(0, selected)
    }
}
