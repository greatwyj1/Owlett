package com.example.birdingsoundmvp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.example.birdingsoundmvp.owlett.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OwlettAttachmentPicker(state: OwlettUiState, viewModel: OwlettViewModel) {
    var trips by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = viewModel::hidePlanPicker) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("添加附件", style = MaterialTheme.typography.titleLarge)
            TabRow(selectedTabIndex = if (trips) 1 else 0) {
                Tab(selected = !trips, onClick = { trips = false }, text = { Text("观鸟计划") })
                Tab(selected = trips, onClick = { trips = true }, text = { Text("已完成行程") })
            }
            OutlinedTextField(query, { query = it }, label = { Text("搜索名称或日期") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                if (trips) {
                    val items = state.availableTrips.filter { it.label.contains(query, true) }
                    if (items.isEmpty()) Text("没有匹配的已完成行程", Modifier.padding(12.dp))
                    items.forEach { trip ->
                        ListItem(headlineContent = { Text(trip.label) }, supportingContent = { Text("${trip.detectionCount} 条识别记录") },
                            modifier = Modifier.clickable { viewModel.selectTrip(trip) })
                    }
                } else {
                    val items = state.availablePlans.filter { (it.name + it.plannedDate + it.hotspotName).contains(query, true) }
                    if (items.isEmpty()) Text("没有匹配的观鸟计划", Modifier.padding(12.dp))
                    items.forEach { plan ->
                        ListItem(headlineContent = { Text(plan.name) }, supportingContent = { Text("${plan.plannedDate} · ${plan.hotspotName.orEmpty()}") },
                            modifier = Modifier.clickable { viewModel.selectPlan(plan) })
                    }
                }
            }
        }
    }
}

@Composable
internal fun OwlettClipsPanel(clips: List<OwlettAudioClip>, state: OwlettClipPlayback, viewModel: OwlettViewModel, onOpen: (OwlettAudioClip) -> Unit) {
    var page by remember(clips) { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("连续播放", Modifier.weight(1f))
            Switch(state.continuous, viewModel::setContinuousPlayback)
        }
        clips.drop(page * 20).take(20).forEachIndexed { offset, clip ->
            val selected = clip.id == state.clipId
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { viewModel.toggleClip(clips, clip) }) {
                    Icon(if (selected && state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "播放或暂停")
                }
                Column(Modifier.weight(1f)) {
                    Text("${page * 20 + offset + 1}. ${clipTime(clip.startMs)} – ${clipTime(clip.endMs)}", style = MaterialTheme.typography.bodyMedium)
                    Text("置信度 ${(clip.confidence * 100).toInt()}% · ${clip.sources.orEmpty().joinToString("、")}", style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { viewModel.stopAudio(); onOpen(clip) }) { Icon(Icons.Default.GraphicEq, "查看原频谱") }
            }
            if (selected) {
                Slider(state.positionMs.coerceAtMost(state.durationMs).toFloat(), { viewModel.seekClip(it.toLong()) },
                    valueRange = 0f..state.durationMs.coerceAtLeast(1).toFloat())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.moveClip(-1) }) { Icon(Icons.Default.SkipPrevious, "上一段") }
                    Text("${clipTime(state.positionMs)} / ${clipTime(state.durationMs)}")
                    IconButton(onClick = { viewModel.moveClip(1) }) { Icon(Icons.Default.SkipNext, "下一段") }
                }
            }
            HorizontalDivider()
        }
        if (clips.size > 20) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { page-- }, enabled = page > 0) { Icon(Icons.Default.ChevronLeft, "上一页") }
            Text("${page + 1} / ${(clips.size + 19) / 20}")
            IconButton(onClick = { page++ }, enabled = (page + 1) * 20 < clips.size) { Icon(Icons.Default.ChevronRight, "下一页") }
        }
    }
}
private fun clipTime(ms: Long): String = "%d:%02d.%01d".format(ms / 60000, ms / 1000 % 60, ms / 100 % 10)
