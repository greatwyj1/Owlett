package com.example.birdingsoundmvp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.google.gson.JsonParser

@Composable
internal fun LicenseSettings() {
    val context = LocalContext.current
    val handler = LocalUriHandler.current
    var selected by remember { mutableStateOf<Pair<String, String>?>(null) }
    val entries = remember {
        runCatching { context.assets.open("notices/components.json").bufferedReader().use { JsonParser.parseReader(it).asJsonArray.toList() } }.getOrDefault(emptyList())
    }
    Text("自研代码为非商业源码公开。第三方组件、模型和数据仍适用各自许可。", style = MaterialTheme.typography.bodyMedium)
    entries.forEach { item ->
        val data = item.asJsonObject
        Text(data["name"].asString, style = MaterialTheme.typography.titleSmall)
        Text(data["details"].asString, style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = { runCatching { handler.openUri(data["url"].asString) } }) { Text("来源") }
            data["licenseFile"]?.takeUnless { it.isJsonNull }?.asString?.let { path ->
                TextButton(onClick = {
                    val text = runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.getOrDefault("许可文件缺失，请查看项目来源。")
                    selected = data["name"].asString to text
                }) { Text("许可全文") }
            }
        }
        HorizontalDivider()
    }
    TextButton(onClick = {
        selected = "完整依赖清单" to runCatching { context.assets.open("notices/dependencies.txt").bufferedReader().use { it.readText() } }.getOrDefault("请查看源码中的构建依赖。")
    }) { Text("查看全部依赖版本") }
    selected?.let { (title, body) ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(title) }, text = {
            Text(body, Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
        }, confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } })
    }
}
