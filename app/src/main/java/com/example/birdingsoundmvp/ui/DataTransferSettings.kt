package com.example.birdingsoundmvp.ui
import com.example.birdingsoundmvp.ui.OwlettButton as Button
import com.example.birdingsoundmvp.ui.OwlettOutlinedButton as OutlinedButton

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.birdingsoundmvp.MainActivity
import com.example.birdingsoundmvp.owlett.OwlettUiState
import com.example.birdingsoundmvp.transfer.*
import kotlinx.coroutines.*

@Composable
internal fun DataTransferSettings(state: MainUiState, chat: OwlettUiState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var message by remember { mutableStateOf("") }
    var imported by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf(false) }
    val idle = !state.isDataTransferBlocked && state.tripState == TripRecordingState.STOPPED && !state.preciseRecognition.isLoading &&
        !state.quickPrecise.isUploading && !state.shareUiState.isProcessing && !state.filteredSelectionClip.isLoading &&
        !chat.isStreaming && chat.planAnalysisProgress.values.none { it.isRunning } &&
        chat.skillRuns.none { it.status.databaseValue == "executing" }
    val currentIdle by rememberUpdatedState(idle)
    fun transfer(uri: android.net.Uri?, importing: Boolean) {
        if (uri == null) return
        if (!currentIdle || !DataTransferGate.begin()) { message = "请先停止录音，并等待识别、助手和鸟况整理完成。"; return }
        busy = true
        com.example.birdingsoundmvp.audio.PlaybackCoordinator.stop()
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val transfer = LocalDataTransfer(context.applicationContext)
                    val update: (Float, String) -> Unit = { p, text -> progress = p; message = text }
                    if (importing) transfer.import(uri, update) else transfer.export(uri, update)
                }
                imported = importing
            } catch (error: Exception) {
                message = error.message?.take(180) ?: "迁移失败，未覆盖现有数据。"
            } finally { busy = false; DataTransferGate.end() }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { transfer(it, false) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { transfer(it, true) }
    Text("本地数据迁移", style = MaterialTheme.typography.titleMedium)
    Text("迁移包不设密码，包含私人录音、地点与聊天。不会包含任何服务密钥。请只保存在可信位置，导入完成后妥善删除不需要的副本。")
    OutlinedButton(onClick = { export.launch("Owlett-private-backup.zip") }, enabled = idle && !busy) { Text("导出数据") }
    OutlinedButton(onClick = { confirmImport = true }, enabled = idle && !busy && !imported) { Text("导入数据") }
    if (!idle) Text("请先停止录音，并等待识别、助手及鸟况整理完成。", color = MaterialTheme.colorScheme.tertiary)
    if (message.isNotBlank()) Text(message)
    if (imported) Button(onClick = {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        (context as? Activity)?.finish()
    }) { Text("重新打开应用") }
    if (confirmImport) AlertDialog(onDismissRequest = { confirmImport = false }, title = { Text("导入至空白正式版") },
        text = { Text("仅支持导入尚无计划、行程和聊天的正式版。会先验证所有文件，失败则撤销导入。所有服务密钥需要重新填写。") },
        confirmButton = { TextButton(onClick = { confirmImport = false; import.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("选择迁移包") } },
        dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("取消") } })
    if (busy) AlertDialog(onDismissRequest = {}, title = { Text("正在迁移") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(message); LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()) }
    }, confirmButton = {})
}
