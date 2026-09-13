package com.example.birdingsoundmvp.share

import com.example.birdingsoundmvp.i18n.AppText

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

object ShareVideoRenderer {
    private const val FRAME_RATE = 30
    private const val VIDEO_BIT_RATE = 4_000_000
    private const val AUDIO_BIT_RATE = 96_000
    private const val TIMEOUT_US = 10_000L

    fun render(
        audioFile: File,
        outputFile: File,
        frameData: ShareFrameRenderer.FrameData,
        brandIcon: Bitmap? = null,
        onProgress: (Float) -> Unit = {}
    ): Result<File> = runCatching {
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()
        val videoTrack = encodeVideo(frameData, brandIcon) { onProgress(it * 0.75f) }
        val audioTrack = encodeAudio(audioFile) { onProgress(0.75f + it * 0.2f) }
        mux(outputFile, videoTrack, audioTrack)
        onProgress(1f)
        outputFile
    }

    private fun encodeVideo(
        frameData: ShareFrameRenderer.FrameData,
        brandIcon: Bitmap?,
        onProgress: (Float) -> Unit
    ): EncodedTrack {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val encoderInfo = selectEncoder(mime) ?: error(AppText.get("no H.264 encoder available"))
        val colorFormat = selectColorFormat(encoderInfo, mime)
        val format = MediaFormat.createVideoFormat(mime, ShareFrameRenderer.WIDTH, ShareFrameRenderer.HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createByCodecName(encoderInfo.name)
        val bitmap = Bitmap.createBitmap(ShareFrameRenderer.WIDTH, ShareFrameRenderer.HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val pixels = IntArray(ShareFrameRenderer.WIDTH * ShareFrameRenderer.HEIGHT)
        var outputFormat: MediaFormat? = null
        val samples = mutableListOf<EncodedSample>()
        val durationUs = frameData.durationMs * 1000L
        val frameCount = ceil(frameData.durationMs / 1000.0 * FRAME_RATE).toInt().coerceAtLeast(1)
        var frameIndex = 0
        var inputDone = false
        var outputDone = false
        val info = MediaCodec.BufferInfo()

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex) ?: error(AppText.get("missing video input buffer"))
                        input.clear()
                        if (frameIndex >= frameCount) {
                            codec.queueInputBuffer(inputIndex, 0, 0, durationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val progress = if (frameCount <= 1) 1f else frameIndex.toFloat() / (frameCount - 1).toFloat()
                            ShareFrameRenderer.drawFrame(
                                canvas,
                                ShareFrameRenderer.WIDTH,
                                ShareFrameRenderer.HEIGHT,
                                frameData,
                                progress,
                                brandIcon
                            )
                            bitmap.getPixels(pixels, 0, ShareFrameRenderer.WIDTH, 0, 0, ShareFrameRenderer.WIDTH, ShareFrameRenderer.HEIGHT)
                            val bytes = argbToYuv(pixels, ShareFrameRenderer.WIDTH, ShareFrameRenderer.HEIGHT, colorFormat)
                            input.put(bytes)
                            val ptsUs = frameIndex * 1_000_000L / FRAME_RATE
                            codec.queueInputBuffer(inputIndex, 0, bytes.size, ptsUs, 0)
                            frameIndex += 1
                            onProgress(frameIndex.toFloat() / frameCount.toFloat())
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        collectSample(codec, outputIndex, info, samples)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            bitmap.recycle()
        }
        return EncodedTrack(outputFormat ?: error(AppText.get("missing video output format")), samples)
    }

    private fun encodeAudio(audioFile: File, onProgress: (Float) -> Unit): EncodedTrack {
        val wav = readWav(audioFile)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, wav.sampleRate, wav.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var outputFormat: MediaFormat? = null
        val samples = mutableListOf<EncodedSample>()
        val info = MediaCodec.BufferInfo()
        var offset = 0
        var samplesSubmitted = 0L
        var inputDone = false
        var outputDone = false
        val bytesPerFrame = wav.channels * 2
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex) ?: error(AppText.get("missing audio input buffer"))
                        input.clear()
                        if (offset >= wav.pcm.size) {
                            val ptsUs = samplesSubmitted * 1_000_000L / wav.sampleRate
                            codec.queueInputBuffer(inputIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val chunkSize = minOf(input.remaining(), wav.pcm.size - offset)
                            input.put(wav.pcm, offset, chunkSize)
                            val ptsUs = samplesSubmitted * 1_000_000L / wav.sampleRate
                            codec.queueInputBuffer(inputIndex, 0, chunkSize, ptsUs, 0)
                            offset += chunkSize
                            samplesSubmitted += chunkSize / bytesPerFrame
                            onProgress(offset.toFloat() / wav.pcm.size.coerceAtLeast(1).toFloat())
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        collectSample(codec, outputIndex, info, samples)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }
        return EncodedTrack(outputFormat ?: error(AppText.get("missing audio output format")), samples)
    }

    private fun mux(outputFile: File, videoTrack: EncodedTrack, audioTrack: EncodedTrack) {
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val videoIndex = muxer.addTrack(videoTrack.format)
            val audioIndex = muxer.addTrack(audioTrack.format)
            muxer.start()
            val merged = videoTrack.samples.map { videoIndex to it } + audioTrack.samples.map { audioIndex to it }
            merged.sortedBy { it.second.presentationTimeUs }.forEach { (trackIndex, sample) ->
                if (sample.bytes.isEmpty() || sample.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return@forEach
                val info = MediaCodec.BufferInfo().apply {
                    set(0, sample.bytes.size, sample.presentationTimeUs, sample.flags)
                }
                muxer.writeSampleData(trackIndex, ByteBuffer.wrap(sample.bytes), info)
            }
        } finally {
            muxer.stop()
            muxer.release()
        }
    }

    private fun collectSample(
        codec: MediaCodec,
        outputIndex: Int,
        info: MediaCodec.BufferInfo,
        samples: MutableList<EncodedSample>
    ) {
        val output = codec.getOutputBuffer(outputIndex)
        if (output != null && info.size > 0) {
            output.position(info.offset)
            output.limit(info.offset + info.size)
            val bytes = ByteArray(info.size)
            output.get(bytes)
            samples += EncodedSample(bytes, info.presentationTimeUs, info.flags)
        }
        codec.releaseOutputBuffer(outputIndex, false)
    }

    private fun selectEncoder(mime: String): MediaCodecInfo? {
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    }

    private fun selectColorFormat(info: MediaCodecInfo, mime: String): Int {
        val supported = info.getCapabilitiesForType(mime).colorFormats.toSet()
        return listOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        ).firstOrNull { it in supported } ?: error(AppText.get("no supported YUV420 color format"))
    }

    private fun argbToYuv(pixels: IntArray, width: Int, height: Int, colorFormat: Int): ByteArray {
        val frameSize = width * height
        val yuv = ByteArray(frameSize * 3 / 2)
        var yIndex = 0
        var uIndex = frameSize
        var vIndex = frameSize + frameSize / 4
        var uvIndex = frameSize
        for (j in 0 until height) {
            for (i in 0 until width) {
                val color = pixels[j * width + i]
                val r = color shr 16 and 0xff
                val g = color shr 8 and 0xff
                val b = color and 0xff
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                yuv[yIndex++] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                        yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                        yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                    } else {
                        yuv[uIndex++] = u.coerceIn(0, 255).toByte()
                        yuv[vIndex++] = v.coerceIn(0, 255).toByte()
                    }
                }
            }
        }
        return yuv
    }

    private fun readWav(file: File): WavData {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(22L)
            val channels = raf.readShortLE()
            raf.seek(24L)
            val sampleRate = raf.readIntLE()
            raf.seek(44L)
            val pcm = ByteArray((raf.length() - 44L).coerceAtLeast(0L).toInt())
            raf.readFully(pcm)
            return WavData(sampleRate = sampleRate, channels = channels, pcm = pcm)
        }
    }

    private fun RandomAccessFile.readShortLE(): Int {
        val bytes = ByteArray(2)
        readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
    }

    private fun RandomAccessFile.readIntLE(): Int {
        val bytes = ByteArray(4)
        readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private data class WavData(val sampleRate: Int, val channels: Int, val pcm: ByteArray)
    private data class EncodedTrack(val format: MediaFormat, val samples: List<EncodedSample>)
    private data class EncodedSample(val bytes: ByteArray, val presentationTimeUs: Long, val flags: Int)
}
