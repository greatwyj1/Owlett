package com.example.birdingsoundmvp.ui
import com.example.birdingsoundmvp.ui.OwlettButton as Button
import com.example.birdingsoundmvp.ui.OwlettOutlinedButton as OutlinedButton

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.birdingsoundmvp.owlett.OwlettContextBuilder
import com.example.birdingsoundmvp.settings.AppSettings

@Composable
fun OwlettBehaviorSettings(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    var draft by remember(settings.owlettSystemPrompt) { mutableStateOf(settings.owlettSystemPrompt.ifBlank { OwlettContextBuilder.SYSTEM_PROMPT }) }
    var confirmAuto by remember { mutableStateOf(false) }
    Text("初始提示词", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(draft, { if (it.length <= 10_000) draft = it }, modifier = Modifier.fillMaxWidth(), minLines = 5, maxLines = 12,
        supportingText = { Text("${draft.length} / 10000 · 保存后用于下一轮对话") })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onChange(settings.copy(owlettSystemPrompt = draft.trim())) }) { Text("保存") }
        TextButton(onClick = { draft = OwlettContextBuilder.SYSTEM_PROMPT; onChange(settings.copy(owlettSystemPrompt = "")) }) { Text("恢复默认") }
    }
    Text("操作确认方式", style = MaterialTheme.typography.titleMedium)
    listOf("confirm_writes" to "修改数据前询问", "automatic" to "全部自动执行").forEach { (id, title) ->
        val select = { if (id == "automatic") confirmAuto = true else onChange(settings.copy(owlettAutomationMode = id)) }
        Row(Modifier.fillMaxWidth().clickable(onClick = select), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(settings.owlettAutomationMode == id, onClick = select); Text(title)
        }
    }
    if (confirmAuto) AlertDialog(onDismissRequest = { confirmAuto = false }, title = { Text("允许自动修改数据？") },
        text = { Text("Owlett 可按你的指令创建计划、整理鸟况、修改目标鸟种和关联行程，不再逐项确认。信息不明确时仍会询问，不会删除行程或修改密钥。") },
        confirmButton = { TextButton(onClick = { onChange(settings.copy(owlettAutomationMode = "automatic")); confirmAuto = false }) { Text("允许") } },
        dismissButton = { TextButton(onClick = { confirmAuto = false }) { Text("取消") } })
}
