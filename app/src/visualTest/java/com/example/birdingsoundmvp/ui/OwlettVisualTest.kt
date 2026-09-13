package com.example.birdingsoundmvp.ui

import android.graphics.BitmapFactory
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.example.birdingsoundmvp.audio.SpectrogramColumn
import com.example.birdingsoundmvp.birdnet.*
import com.example.birdingsoundmvp.owlett.OwlettPlanPickerItem
import com.example.birdingsoundmvp.owlett.BirdCallPlayback
import com.example.birdingsoundmvp.owlett.XenoCantoRecording
import com.example.birdingsoundmvp.planning.*
import com.example.birdingsoundmvp.settings.AppSettings
import com.pact.chatui.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.*

/** Real app components, synthetic records and the app's licensed local illustrations. No services. */
@RunWith(Parameterized::class)
class OwlettVisualTest(private val theme: String, private val appearance: String,
                       private val width: Int, private val fontScale: Float) {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(
        screenWidth = width * 2, screenHeight = 840 * 2, density = Density.XHIGH,
        fontScale = fontScale, softButtons = false), useDeviceResolution = true)
    private fun capture(content: @Composable () -> Unit) {
        val view = androidx.compose.ui.platform.ComposeView(paparazzi.context)
        view.setContent(content)
        paparazzi.snapshot(view, offsetMillis = 1000)
    }
    private val settings = AppSettings(colorTheme = theme, appearance = appearance)
    private val birds = listOf(
        Triple("Pycnonotus sinensis", "Light-vented Bulbul", "白头鹎"),
        Triple("Turdus merula", "Common Blackbird", "乌鸫"),
        Triple("Pica pica", "Eurasian Magpie", "喜鹊")
    )
    private val images by lazy {
        listOf("b0898", "b0707", "b0636").mapIndexed { index, id ->
            birds[index].first to paparazzi.context.assets.open("ibirding_cn/assets/plates/$id.jpg").use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })!!.asImageBitmap()
            }
        }.toMap()
    }
    @Composable private fun Shell(tab: AppTab, content: @Composable ColumnScope.() -> Unit) {
        val loader: suspend (android.content.Context, String, String) -> androidx.compose.ui.graphics.ImageBitmap? = remember { { _, scientific, _ -> images[scientific] } }
        CompositionLocalProvider(LocalThumbnailLoader provides loader) {
            OwlettTheme(appearance, theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column {
                        Column(Modifier.weight(1f), content = content)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        OwlettBottomNavigation(tab, {})
                    }
                }
            }
        }
    }

    @Test fun recording() {
        val selected = SpectrogramSelection(9200, 10800, true)
        val state = MainUiState(settings = settings, elapsedSec = 756, wallElapsedSec = 756,
            tripState = TripRecordingState.PAUSED, canStop = true, selection = selected, shareAudioAvailable = true,
            viewportStartMs = 8000, spectrogramDurationMs = 756000,
            recentDetections = birds.mapIndexed { i, bird -> MergedDetectionCard(DetectionResult("preview", 1788919200000 + i * 1000L,
                9.2, 10.8, bird.first, bird.second, bird.third, .96f - i * .04f, null, .96f, false, false, false, false, 90, "BirdNET")) },
            spectrogramColumns = (0..319).map { x -> SpectrogramColumn(8000L + x * 12L,
                FloatArray(96) { y ->
                    val chirp = exp(-((y - (24 + 13 * sin(x * .07))).pow(2)) / 8) * max(0.0, sin(x * .10))
                    (0.05 + .13 * abs(sin(x * 6.13 + y * 3.24)) + chirp * .8).toFloat()
                }) })
        capture {
            Shell(AppTab.RECORDING) {
                Box(Modifier.weight(1f)) { RecordingScreen(state, {}, {}, { _, _ -> }, {}, {}, { _, _ -> }, {}, {}, { _, _ -> }, { _, _ -> }, {}) }
                RecordingBottomControls(state, {}, {}, {}, {}, {})
            }
        }
    }

    private fun detail(): PlanDetailData {
        val plan = Plan(1, "奥森秋日观鸟", "2026-09-12", "CN-BJ", "preview", "奥林匹克森林公园",
            40.01, 116.39, 1788919200000, 1788919200000, 1788919200000, 2026, 9, 60, 56, 38, 8, 8, 6, "北京")
        val stats = birds.mapIndexed { i, b -> PlanSpeciesStat(1, b.first, "", b.first, b.second, b.third,
            .82f - i * .1f, .76f - i * .12f, .79f - i * .1f, 30, 5, "2026-09-08") }
        return PlanDetailData(plan, stats,
            stats.take(2).map { PlanExpectedSpecies(1, it.speciesKey, "", it.scientificName, it.commonName, it.displayNameZh, "analysis", 0) },
            listOf(PlanRareObservation(1, "Pica pica", "", "Pica pica", "Eurasian Magpie", "喜鹊", "2026-09-08",
                "preview", "奥林匹克森林公园", null, null, 2, 1, false)), emptyList(), null, emptyList(), emptyList())
    }
    @Test fun plan() {
        val actions = PlanDetailActions({}, {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }, {}, {}, {}, { _, _ -> }, {}, {}, {})
        val detail = detail()
        val source = EbirdObservationSource(EbirdRecentObservationsClient())
        capture {
            Shell(AppTab.TRIPS) {
                SimpleHeader(detail.plan.name, {})
                PlanDetailContent(PlansTripsUiState(), detail, actions, source)
            }
        }
    }

    @Test fun chat() {
        val draft = if (fontScale > 1f) "请结合刚才的鸟况，帮我比较周末适合去的观鸟地点，并列出要注意的事项。" else "周末去这里能看到哪些鸟？"
        capture {
            Shell(AppTab.OWLETT) {
                val colors = ChatDefaults.colors().copy(backgroundGradient = listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background))
                ChatScreen(listOf(ChatMessage("1", MessageSender.User, "帮我看看这次观鸟计划。", attachmentLabel = "奥森秋日观鸟"),
                    ChatMessage("2", MessageSender.Assistant, "可以先沿林缘和水边慢慢观察。清晨通常更容易听到鸣声。\n\n我会优先使用本次查询保存的鸟况记录；如果需要更新数据，会明确说明。", metadata = "DeepSeek")),
                    draft = draft, config = ChatConfig(assistantLabel = "Owlett", handleSystemBottomInsets = false), colors = colors,
                    topBar = { height -> OwlettTopBar("周末观鸟安排", {}, {}, height) },
                    composer = { _, _, _, _ ->
                        OwlettComposer(draft, TextRange(draft.length), false, {}, false, true,
                            OwlettPlanPickerItem(1, "奥森秋日观鸟", "2026-09-12", "奥林匹克森林公园"), emptyList(), null,
                            {}, {}, {}, {}, {}, {})
                    })
            }
        }
    }

    @Test fun settings() {
        capture {
            Shell(AppTab.SETTINGS) {
                SimpleHeader("外观", {})
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppearanceSettings(settings, {})
                }
            }
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}-{1}-{2}dp-font{3}") fun cases(): List<Array<Any>> =
            listOf("green", "feather", "gold").flatMap { color -> listOf("light", "dark").flatMap { mode ->
                listOf(arrayOf<Any>(color, mode, 360, 1f), arrayOf<Any>(color, mode, 411, 1f), arrayOf<Any>(color, mode, 360, 1.5f))
            } }
    }
}
