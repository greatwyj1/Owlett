package com.example.birdingsoundmvp.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

object OwlettHelp {
    val chapters = linkedMapOf(
        "recording" to ("录音与回顾" to listOf("点击麦克风开始录音，识别结果会自动保存在录音行程中。", "暂停后可滑动频谱、点选播放起点，或切换时间选区与频谱选区。", "录音结束后，到“计划与行程”中的行程列表回顾或删除。")),
        "plans" to ("计划与行程" to listOf("观鸟计划用于选择鸟点、整理鸟况与设定目标；录音行程保存实际录音。", "先在设置填写 eBird 密钥，再创建计划并点击“整理近期鸟况”。报告日频率不是目击概率。", "完成录音后，可把一个或多个行程关联到计划，再结算和修正清单。")),
        "owlett" to ("Owlett 助手" to listOf("在设置填写 DeepSeek 密钥后开始聊天。对话和附件快照保存在本机。", "输入 / 可选择技能；加号可附加一个计划或已完成行程。", "默认修改数据前询问。可以停止回答；查看片段列表不会自动播放录音。")),
        "precise" to ("精准识别" to listOf("目前不提供公共精准识别服务，需要自行部署后端并填写地址和令牌。", "选择时间窗或频段后提交。频谱选区使用带通滤波音频；识别分数仅供参考。", "提交会把选中音频与识别参数发送给你配置的服务器。")),
        "share" to ("分享选区" to listOf("暂停后选中时间窗或频段，长按频谱 1.5 秒，再点击“分享”。", "选择纯音频或视频；生成在本地进行，完成后从系统分享面板选择目标应用。", "分享文件可能包含录音日期、鸟况与私人声音，请先检查内容。"))
    )
    fun seen(context: Context, key: String) = context.getSharedPreferences("owlett_first_use", Context.MODE_PRIVATE).getBoolean(key, false)
    fun mark(context: Context, key: String) { context.getSharedPreferences("owlett_first_use", Context.MODE_PRIVATE).edit().putBoolean(key, true).apply() }
    fun audioConsentKey(url: String) = "audio:" + java.security.MessageDigest.getInstance("SHA-256").digest(url.trim().toByteArray()).joinToString("") { "%02x".format(it) }
    fun requestAudio(activity: Activity, url: String, onSettings: () -> Unit, action: () -> Unit) {
        if (url.isBlank()) {
            AlertDialog.Builder(activity).setTitle("尚未配置精准识别")
                .setMessage("目前没有公共后端服务。请先自行部署，再在设置填写服务地址与令牌。")
                .setPositiveButton("前往设置") { _, _ -> onSettings() }.setNegativeButton("取消", null).show()
            return
        }
        val key = audioConsentKey(url)
        if (seen(activity, key)) { action(); return }
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: "配置的服务器"
        AlertDialog.Builder(activity).setTitle("发送选中音频")
            .setMessage("选中录音、时间范围、识别设置及已启用的定位信息会发送至 $host。请确认这是你信任的服务。\n\n目前不提供公共精准识别后端，需自行部署。")
            .setPositiveButton("同意并继续") { _, _ -> mark(activity, key); mark(activity, "precise"); action() }
            .setNegativeButton("取消", null).show()
    }
    fun showChapter(activity: Activity, key: String, action: () -> Unit) {
        if (seen(activity, key)) { action(); return }
        val chapter = chapters.getValue(key)
        AlertDialog.Builder(activity).setTitle(chapter.first).setMessage(chapter.second.mapIndexed { index, text -> "${index + 1}. $text" }.joinToString("\n\n"))
            .setPositiveButton("继续") { _, _ -> mark(activity, key); action() }
            .setNegativeButton("跳过") { _, _ -> mark(activity, key); action() }.show()
    }
}

@Composable
internal fun FirstUseGuide(chapterKey: String) {
    val context = LocalContext.current
    var show by remember(chapterKey) { mutableStateOf(!OwlettHelp.seen(context, chapterKey)) }
    var step by remember(chapterKey) { mutableIntStateOf(0) }
    val chapter = OwlettHelp.chapters[chapterKey] ?: return
    fun close() { OwlettHelp.mark(context, chapterKey); show = false }
    if (show) AlertDialog(onDismissRequest = { close() }, title = { Text("${chapter.first} · ${step + 1}/${chapter.second.size}") },
        text = { Text(chapter.second[step]) }, confirmButton = {
            TextButton(onClick = { if (step == chapter.second.lastIndex) close() else step++ }) { Text(if (step == chapter.second.lastIndex) "开始使用" else "下一步") }
        }, dismissButton = { TextButton(onClick = { close() }) { Text("跳过") } })
}

@Composable
internal fun HelpSettings() {
    var selected by remember { mutableStateOf<String?>(null) }
    OwlettHelp.chapters.forEach { (key, chapter) -> TextButton(onClick = { selected = key }) { Text(chapter.first) } }
    selected?.let { key ->
        val chapter = OwlettHelp.chapters.getValue(key)
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(chapter.first) },
            text = { Text(chapter.second.mapIndexed { i, text -> "${i + 1}. $text" }.joinToString("\n\n")) },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("知道了") } })
    }
}
