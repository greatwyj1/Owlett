package com.example.birdingsoundmvp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.birdingsoundmvp.owlett.BirdCallPlayback
import com.example.birdingsoundmvp.owlett.XenoCantoRecording
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BirdCallLoadingInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    @Test fun waitingForFirstAudioShowsProgressAndPauseControl() {
        var clicked = false
        val recording = XenoCantoRecording("1", "Turdus merula", "Common Blackbird", "录音者", "China", "Beijing",
            "2026-09-08", "song", "A", "0:30", "CC BY", "https://example.org/test.mp3", "", "https://xeno-canto.org/1")
        compose.setContent {
            MaterialTheme {
                XenoCantoRecordingRow(recording, false,
                    BirdCallPlayback("1", loading = true, wantsPlayback = true), onToggle = { clicked = true })
            }
        }
        compose.onNodeWithText("正在加载鸟鸣…").assertIsDisplayed()
        compose.onNodeWithContentDescription("暂停播放").performClick()
        compose.runOnIdle { assertTrue(clicked) }
    }
}
