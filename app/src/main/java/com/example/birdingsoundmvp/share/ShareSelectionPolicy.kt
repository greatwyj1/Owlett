package com.example.birdingsoundmvp.share

import com.example.birdingsoundmvp.ui.MainUiState
import com.example.birdingsoundmvp.ui.TripRecordingState

object ShareSelectionPolicy {
    fun error(state: MainUiState, segments: List<ShareAudioSegment>, exists: (String) -> Boolean): String? {
        if (state.shareUiState.isProcessing) return "正在生成分享内容"
        if (state.tripState == TripRecordingState.RECORDING) return "请先暂停录音"
        val selection = state.selection?.takeIf { it.isValid } ?: return "请先选择一段音频"
        if (selection.durationMs > state.settings.maxSelectionDurationSec * 1000) return "选区超过时长上限，请重新选择"
        val segment = ShareContentPlanner.validateSingleSegmentRange(selection.startMs, selection.endMs, segments).getOrNull()
            ?: return "选区必须位于同一个录音分段内"
        if (!exists(segment.filePath)) return "该选区的原始音频已删除或不可用"
        if (selection.hasFrequencyRange && (!state.filteredSelectionClip.isReadyFor(selection) ||
                !exists(state.filteredSelectionClip.tempFilePath))) return "频谱选区音频尚未准备好，请等待滤波完成"
        return null
    }
}
