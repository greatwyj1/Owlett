package com.example.birdingsoundmvp.ui
import com.example.birdingsoundmvp.ui.OwlettButton as Button
import com.example.birdingsoundmvp.ui.OwlettOutlinedButton as OutlinedButton

import com.example.birdingsoundmvp.i18n.AppText

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.birdingsoundmvp.R
import com.example.birdingsoundmvp.owlett.OwlettViewModel
import com.example.birdingsoundmvp.planning.EbirdRecentObservationsClient
import com.example.birdingsoundmvp.planning.EbirdRegionsResponse
import com.example.birdingsoundmvp.settings.AppSettings
import com.example.birdingsoundmvp.settings.ColorTheme
import com.example.birdingsoundmvp.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import com.example.birdingsoundmvp.trip.TripHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OwlettAppSettingsScreen(
    state: MainUiState,
    owlett: OwlettViewModel,
    onLocationPermission: () -> Unit,
    onChange: (AppSettings) -> Unit,
    onTestNotification: () -> Unit,
    onManageStorage: () -> Unit,
    diagnostics: @Composable () -> Unit
) {
    var page by rememberSaveable { mutableStateOf(AppText.get("设置")) }
    val chat by owlett.state.collectAsState()
    val settings = state.settings
    BackHandler(page != AppText.get("设置")) { page = AppText.get("设置") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page != AppText.get("设置")) IconButton(onClick = { page = AppText.get("设置") }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, AppText.get("返回设置")) }
            Text(page, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(4.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (page == AppText.get("设置")) 0.dp else 14.dp)) {
            when (page) {
                AppText.get("设置") -> {
                    SettingsSectionTitle(AppText.get("录音与识别"))
                    SettingsLink(AppText.get("录音设置"), Icons.Default.Mic) { page = it }
                    SettingsLink(AppText.get("识别设置"), Icons.Default.GraphicEq) { page = it }
                    SettingsLink(AppText.get("精准识别"), Icons.Default.Tune) { page = it }
                    SettingsLink(AppText.get("频谱显示"), Icons.Default.Equalizer) { page = it }
                    SettingsLink(AppText.get("存储管理"), Icons.Default.FolderOpen, AppText.get("本机录音与分享文件")) { page = it }
                    SettingsSectionTitle(AppText.get("Owlett 助手"))
                    SettingsLink("DeepSeek", Icons.Default.ChatBubbleOutline, if (chat.settings.hasApiKey) AppText.get("已配置") else AppText.get("未配置")) { page = it }
                    SettingsLink(AppText.get("默认模型"), Icons.Default.Memory, settings.owlettModelId) { page = "DeepSeek" }
                    SwitchRow(AppText.get("自动使用技能"), settings.owlettAutomaticSkillsEnabled, owlett::setAutomaticSkillsEnabled)
                    SettingsLink("助手行为", Icons.Default.SmartToy) { page = it }
                    SettingsSectionTitle(AppText.get("鸟类数据服务"))
                    SettingsLink("eBird", Icons.Default.Public, if (settings.ebirdApiKey.isBlank()) AppText.get("未配置") else AppText.get("已配置")) { page = it }
                    SettingsLink("xeno-canto", Icons.Default.MusicNote, if (chat.settings.hasXenoCantoApiKey) AppText.get("已配置") else AppText.get("未配置")) { page = it }
                    SettingsSectionTitle(AppText.get("外观与关于"))
                    SettingsLink(AppText.get("外观"), Icons.Default.Palette,
                        "${ColorTheme.label(settings.colorTheme)} · ${appearanceLabel(settings.appearance)}") { page = it }
                    SettingsLink(AppText.get("详细诊断"), Icons.Default.Info) { page = it }
                    SettingsLink("使用帮助", Icons.Default.HelpOutline) { page = it }
                    SettingsLink(AppText.get("关于 Owlett"), Icons.Default.FavoriteBorder) { page = it }
                }
                AppText.get("录音设置") -> {
                    SettingsSlider(AppText.get("分段重叠时长"), settings.chunkOverlapSec, 0f..2.5f, AppText.get("秒")) { onChange(settings.copy(chunkOverlapSec = it)) }
                    SwitchRow(AppText.get("使用定位辅助识别"), settings.useLocation) { if (it) onLocationPermission(); onChange(settings.copy(useLocation = it)) }
                    OutlinedButton(onClick = onTestNotification) { Icon(Icons.Default.NotificationsNone, null); Spacer(Modifier.width(8.dp)); Text(AppText.get("测试通知")) }
                    Text(AppText.format("定位权限：{0}", state.locationPermissionStatus), style = MaterialTheme.typography.bodySmall)
                }
                AppText.get("识别设置") -> {
                    SettingsSlider(AppText.get("最低音频置信度"), settings.minimumAudioConfidence, 0f..1f) { onChange(settings.copy(minimumAudioConfidence = it)) }
                    SwitchRow(AppText.get("启用时空先验模型"), settings.useMetaModel) { onChange(settings.copy(useMetaModel = it)) }
                    SettingsSlider(AppText.get("最低先验置信度"), settings.minimumMetaConfidence, 0f..1f) { onChange(settings.copy(minimumMetaConfidence = it)) }
                    SwitchRow(AppText.get("按综合置信度排序"), settings.sortByAdjustedConfidence) { onChange(settings.copy(sortByAdjustedConfidence = it)) }
                }
                AppText.get("频谱显示") -> {
                    SwitchRow(AppText.get("显示频谱图"), settings.showSpectrogram) { onChange(settings.copy(showSpectrogram = it)) }
                    SettingsSlider(AppText.get("最长选区"), settings.maxSelectionDurationSec, 1f..15f, AppText.get("秒")) { onChange(settings.copy(maxSelectionDurationSec = it)) }
                }
                AppText.get("精准识别") -> {
                    OutlinedTextField(settings.preciseRecognitionServerUrl, { onChange(settings.copy(preciseRecognitionServerUrl = it)) }, label = { Text(AppText.get("服务地址")) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(settings.preciseRecognitionAuthToken, { onChange(settings.copy(preciseRecognitionAuthToken = it)) }, label = { Text(AppText.get("访问令牌")) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Text(AppText.get("声学模型"), style = MaterialTheme.typography.titleSmall)
                    listOf("perch_v2" to "Perch v2", "birdnet_v2_4" to "BirdNET 2.4").forEach { (id, name) ->
                        Row(Modifier.fillMaxWidth().clickable { onChange(settings.copy(preciseAcousticModel = id)) }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(settings.preciseAcousticModel == id, { onChange(settings.copy(preciseAcousticModel = id)) }); Text(name)
                        }
                    }
                    SettingsSlider(AppText.get("分析窗口重叠"), settings.defaultPreciseOverlapSec, 0f..3f, AppText.get("秒")) { onChange(settings.copy(defaultPreciseOverlapSec = it)) }
                    SettingsSlider(AppText.get("最低置信度"), settings.defaultPreciseMinConfidence, 0f..1f) { onChange(settings.copy(defaultPreciseMinConfidence = it)) }
                    SettingsSlider(AppText.get("最多返回鸟种数"), settings.defaultPreciseTopK.toFloat(), 1f..20f, AppText.get("种")) { onChange(settings.copy(defaultPreciseTopK = it.toInt())) }
                }
                "使用帮助" -> HelpSettings()
                "助手行为" -> OwlettBehaviorSettings(settings, onChange)
                "DeepSeek" -> OwlettSettingsCard(owlett, "deepseek")
                "xeno-canto" -> OwlettSettingsCard(owlett, "xeno")
                "eBird" -> EbirdSettings(settings.ebirdApiKey) { onChange(settings.copy(ebirdApiKey = it)) }
                AppText.get("外观") -> AppearanceSettings(settings, onChange)
                AppText.get("存储管理") -> {
                    val context = LocalContext.current
                    val summary by produceState(AppText.get("正在统计…")) {
                        value = withContext(Dispatchers.IO) {
                            val repository = TripHistoryRepository(context)
                            AppText.format("{0} 个录音行程 · %.1f MB", repository.listTrips().size).format(repository.storageBytes() / 1_048_576.0)
                        }
                    }
                    DataTransferSettings(state, chat)
                    HorizontalDivider()
                    Text(summary, style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onManageStorage) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text(AppText.get("管理录音行程")) }
                }
                AppText.get("详细诊断") -> diagnostics()
                AppText.get("关于 Owlett") -> {
                    Image(painterResource(R.drawable.owlett_icon), null, modifier = Modifier.size(64.dp).align(Alignment.CenterHorizontally))
                    Text("Owlett", style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Text("观鸟录音助手 · ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", modifier = Modifier.align(Alignment.CenterHorizontally), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SettingsLink("更新记录", Icons.Default.History) { page = it }
                    SettingsLink(AppText.get("第三方许可与数据来源"), Icons.Default.Description) { page = it }
                }
                AppText.get("第三方许可与数据来源") -> LicenseSettings()
                "更新记录" -> {
                    val context = LocalContext.current
                    val changes by produceState("") {
                        value = withContext(Dispatchers.IO) { context.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() } }
                    }
                    Text(changes, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
internal fun AppearanceSettings(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    Text("主题配色", style = MaterialTheme.typography.titleSmall)
    ColorTheme.ids.forEach { id ->
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onChange(settings.copy(colorTheme = id)) },
            verticalAlignment = Alignment.CenterVertically) {
            RadioButton(ColorTheme.normalize(settings.colorTheme) == id, { onChange(settings.copy(colorTheme = id)) })
            Box(Modifier.size(22.dp).background(owlettColors(id, false).primary, CircleShape))
            Spacer(Modifier.width(12.dp))
            Text(ColorTheme.label(id), Modifier.weight(1f))
        }
    }
    HorizontalDivider()
    Text("明暗模式", style = MaterialTheme.typography.titleSmall)
    listOf("system", "light", "dark").forEach { id ->
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onChange(settings.copy(appearance = id)) }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(settings.appearance == id, { onChange(settings.copy(appearance = id)) }); Text(appearanceLabel(id))
        }
    }
}

private fun appearanceLabel(value: String) = when (value) { "light" -> AppText.get("浅色"); "dark" -> AppText.get("深色"); else -> AppText.get("跟随系统") }

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(text, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SettingsLink(title: String, icon: ImageVector, value: String = "", onClick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onClick(title) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (value.isNotBlank()) Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}

@Composable
private fun SettingsSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String = "", onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) { Text(label, Modifier.weight(1f)); Text("%.2f $unit".format(value), color = MaterialTheme.colorScheme.primary) }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun EbirdSettings(savedKey: String, onSave: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    OutlinedTextField(draft, { draft = it; result = null }, label = { Text(if (savedKey.isBlank()) AppText.get("eBird 密钥") else AppText.get("替换 eBird 密钥")) },
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
    if (savedKey.isNotBlank()) Text(AppText.format("已保存密钥 · ••••{0}", savedKey.takeLast(4)), style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onSave(draft.trim()); draft = ""; result = AppText.get("密钥已保存") }, enabled = draft.isNotBlank()) { Icon(Icons.Default.Save, null); Text(AppText.get("保存")) }
        OutlinedButton(onClick = {
            testing = true
            scope.launch {
                try {
                    result = when (val response = EbirdRecentObservationsClient().testConnection(draft.ifBlank { savedKey })) {
                        is EbirdRegionsResponse.Success -> AppText.get("已连接 eBird")
                        is EbirdRegionsResponse.Failure -> response.message
                    }
                } finally { testing = false }
            }
        }, enabled = !testing && (draft.isNotBlank() || savedKey.isNotBlank())) { Icon(Icons.Default.Wifi, null); Text(AppText.get("测试连接")) }
    }
    if (savedKey.isNotBlank()) TextButton(onClick = { onSave(""); result = AppText.get("密钥已清除") }) { Text(AppText.get("清除密钥")) }
    if (testing) LinearProgressIndicator(Modifier.fillMaxWidth())
    result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}
