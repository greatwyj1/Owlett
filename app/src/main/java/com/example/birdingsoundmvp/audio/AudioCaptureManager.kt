package com.example.birdingsoundmvp.audio

import com.example.birdingsoundmvp.i18n.AppText

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class AudioCaptureManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sampleRate: Int = AudioChunker.DEFAULT_SAMPLE_RATE
) {
    private var recordJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var wavWriter: WavFileWriter? = null
    private val running = AtomicBoolean(false)

    val isRunning: Boolean
        get() = running.get()

    fun start(
        wavFile: File,
        chunkChannel: SendChannel<AudioChunk>,
        spectrumChannel: SendChannel<ShortArray>? = null,
        hopDurationSec: Double = AudioChunker.DEFAULT_CHUNK_DURATION_SEC.toDouble(),
        startOffsetSec: Double = 0.0,
        onStatus: (String) -> Unit,
        onBytesWritten: (Long) -> Unit
    ) {
        if (running.get()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onStatus(AppText.get("Missing RECORD_AUDIO permission"))
            return
        }

        val minBufferBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferBytes == AudioRecord.ERROR || minBufferBytes == AudioRecord.ERROR_BAD_VALUE) {
            onStatus(AppText.format("AudioRecord min buffer failed: {0}", minBufferBytes))
            return
        }

        val bufferSamples = maxOf(minBufferBytes / 2, sampleRate / 10)
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSamples * 2)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onStatus(AppText.get("AudioRecord failed to initialize"))
            return
        }

        val writer = WavFileWriter(wavFile, sampleRate)
        runCatching { writer.open() }
            .onFailure {
                record.release()
                onStatus(AppText.format("Failed to create WAV: {0}", it.message))
                return
            }

        val chunker = AudioChunker(
            sampleRate = sampleRate,
            chunkDurationSec = AudioChunker.DEFAULT_CHUNK_DURATION_SEC.toDouble(),
            hopDurationSec = hopDurationSec,
            startOffsetSec = startOffsetSec
        )
        audioRecord = record
        wavWriter = writer
        running.set(true)

        recordJob = scope.launch(Dispatchers.IO) {
            val readBuffer = ShortArray(bufferSamples)
            try {
                record.startRecording()
                onStatus(AppText.get("Recording"))

                while (isActive && running.get()) {
                    val readCount = record.read(readBuffer, 0, readBuffer.size)
                    if (readCount > 0) {
                        writer.writePcm(readBuffer, readCount)
                        onBytesWritten(writer.bytesWritten())
                        spectrumChannel?.trySend(readBuffer.copyOf(readCount))

                        val chunks = chunker.append(readBuffer, readCount)
                        chunks.forEach { chunk ->
                            val result = chunkChannel.trySend(chunk)
                            if (result.isFailure) {
                                Log.w(TAG, "Inference channel full; dropping chunk ${chunk.startSec}-${chunk.endSec}")
                            }
                        }
                    } else {
                        Log.w(TAG, "AudioRecord read returned $readCount")
                    }
                }
            } catch (throwable: Throwable) {
                Log.e(TAG, AppText.get("Recording loop failed"), throwable)
                onStatus(AppText.format("Recording error: {0}", throwable.message))
            } finally {
                running.set(false)
                runCatching { record.stop() }
                record.release()
                runCatching { writer.close() }
                audioRecord = null
                wavWriter = null
                onStatus(AppText.get("Stopped"))
            }
        }
    }

    suspend fun stop() {
        running.set(false)
        recordJob?.join()
        recordJob = null
    }

    companion object {
        private const val TAG = "AudioCaptureManager"
    }
}
