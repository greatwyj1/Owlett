package com.example.birdingsoundmvp.owlett

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.example.birdingsoundmvp.audio.PlaybackCoordinator
import com.example.birdingsoundmvp.trip.TripSession
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OwlettRecordingPlayer(
    private val context: Context,
    private val onPlayingChanged: (String?) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    private var currentRecordingId: String? = null
    private var clips: List<OwlettAudioClip> = emptyList()
    private var clipIndex = 0
    var continuous = false
    private val audioCache = BirdCallAudioCache.get(context)
    private var clearingCache = false
    private var disposed = false
    private var player = newPlayer()
    private fun newPlayer() = ExoPlayer.Builder(context.applicationContext)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(audioCache.dataSourceFactory))
        .build().apply {
        setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onPlayingChanged(if (isPlaying) currentRecordingId else null)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    onPlayingChanged(null)
                    if (continuous && clipIndex < clips.lastIndex) move(1)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                onPlayingChanged(null)
                onError("音频无法播放，请检查录音是否已删除，或网络是否可用。")
            }
        })
    }

    fun play(recording: XenoCantoRecording) {
        if (currentRecordingId == recording.id) resume() else toggle(recording)
    }
    fun play(list: List<OwlettAudioClip>, clip: OwlettAudioClip) {
        if (clips.getOrNull(clipIndex)?.id == clip.id) resume() else toggleClip(list, clip)
    }
    fun toggle(recording: XenoCantoRecording) {
        if (clearingCache) return
        if (!recording.audioUrl.startsWith("https://")) return onError("鸟鸣链接无效，请重新查询。")
        if (PlaybackCoordinator.recording) return onError("请先暂停录音。")
        if (currentRecordingId == recording.id) {
            if (player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE) player.pause() else resume()
            return
        }
        if (!PlaybackCoordinator.acquire(this, ::stop)) return
        clips = emptyList()
        currentRecordingId = recording.id
        player.setMediaItem(MediaItem.Builder().setUri(recording.audioUrl).setCustomCacheKey("xc:${recording.id}").build())
        player.prepare()
        player.play()
    }

    fun toggleClip(list: List<OwlettAudioClip>, clip: OwlettAudioClip) {
        if (clearingCache) return
        if (PlaybackCoordinator.recording) return onError("请先暂停录音。")
        if (clips.getOrNull(clipIndex)?.id == clip.id) {
            if (player.isPlaying) player.pause() else {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                player.play()
            }
            return
        }
        if (!PlaybackCoordinator.acquire(this, ::stop)) return
        clips = list
        clipIndex = list.indexOfFirst { it.id == clip.id }.coerceAtLeast(0)
        playClip()
    }

    private fun playClip() {
        val clip = clips.getOrNull(clipIndex) ?: return
        runCatching {
            val root = File(context.getExternalFilesDir(null), "trips").canonicalFile
            val dir = File(root, clip.tripId).canonicalFile
            require(dir.parentFile == root)
            val session = TripSession.load(dir) ?: error("录音已删除")
            val segment = session.segments.singleOrNull { it.index == clip.segmentIndex } ?: error("音频分段缺失")
            val file = File(segment.filePath).canonicalFile
            require(file.toPath().startsWith(dir.toPath()) && file.isFile)
            require(clip.startMs >= segment.tripAudioStartMs && clip.endMs <= segment.tripAudioStartMs + segment.durationMs && clip.endMs > clip.startMs)
            currentRecordingId = null
            player.setMediaItem(MediaItem.Builder().setUri(Uri.fromFile(file))
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.startMs - segment.tripAudioStartMs)
                    .setEndPositionMs(clip.endMs - segment.tripAudioStartMs).build()).build())
            player.prepare()
            player.play()
        }.onFailure { stop(); onError("该片段的原始音频缺失或范围已失效，无法播放。") }
    }

    fun move(delta: Int) {
        if (PlaybackCoordinator.recording || clips.isEmpty()) return
        clipIndex = (clipIndex + delta).coerceIn(0, clips.lastIndex)
        playClip()
    }
    fun seek(positionMs: Long) = player.seekTo(positionMs.coerceAtLeast(0))
    fun pause() = player.pause()
    fun resume() {
        check(!clearingCache) { "正在清理鸟鸣缓存，请稍后播放" }
        check(!PlaybackCoordinator.recording) { "请先暂停录音" }
        check(player.mediaItemCount > 0) { "请先选择要播放的片段" }
        if (!PlaybackCoordinator.acquire(this, ::stop)) error("当前无法播放")
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }
    fun birdCallSnapshot() = BirdCallPlayback(currentRecordingId, player.isPlaying,
        currentRecordingId != null && player.playWhenReady && player.playbackState == Player.STATE_BUFFERING,
        player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE,
        if (player.duration > 0) player.bufferedPercentage.coerceIn(0, 100) else null)
    fun cacheBytes() = audioCache.bytes
    suspend fun clearAudioCache() {
        if (clearingCache) return
        clearingCache = true
        stop()
        player.release()
        try { withContext(NonCancellable + Dispatchers.IO) { audioCache.clear() } }
        finally { if (!disposed) player = newPlayer(); clearingCache = false }
    }
    fun snapshot(): OwlettClipPlayback {
        val clip = clips.getOrNull(clipIndex) ?: return OwlettClipPlayback(continuous = continuous)
        return OwlettClipPlayback(clip.id, player.isPlaying, player.currentPosition.coerceAtLeast(0), clip.endMs - clip.startMs, continuous)
    }
    fun stop() {
        PlaybackCoordinator.release(this)
        player.stop()
        player.clearMediaItems()
        clips = emptyList()
        currentRecordingId = null
        onPlayingChanged(null)
    }
    fun release() { disposed = true; stop(); player.release() }
}
