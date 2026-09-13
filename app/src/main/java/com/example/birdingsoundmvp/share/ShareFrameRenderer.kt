package com.example.birdingsoundmvp.share

import com.example.birdingsoundmvp.i18n.AppText

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.example.birdingsoundmvp.audio.SpectrogramColumn
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ShareFrameRenderer {
    const val WIDTH = 1080
    const val HEIGHT = 1920
    private const val MAX_FREQUENCY_HZ = 15_000f

    data class FrameData(
        val selectionStartMs: Long,
        val selectionEndMs: Long,
        val spectrogramStartMs: Long,
        val spectrogramEndMs: Long,
        val recordingTimeMs: Long,
        val columns: List<SpectrogramColumn>,
        val detections: List<ShareDetectionLine>,
        val selectionLowFrequencyHz: Float? = null,
        val selectionHighFrequencyHz: Float? = null
    ) {
        val durationMs: Long
            get() = (selectionEndMs - selectionStartMs).coerceAtLeast(1L)

        val spectrogramDurationMs: Long
            get() = (spectrogramEndMs - spectrogramStartMs).coerceAtLeast(1L)
    }

    fun writePreviewPng(
        outputFile: File,
        frameData: FrameData,
        brandIcon: Bitmap? = null
    ): Result<File> = runCatching {
        outputFile.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawFrame(canvas, WIDTH, HEIGHT, frameData, progress = 0.42f, brandIcon = brandIcon)
        FileOutputStream(outputFile).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 95, stream)
        }
        bitmap.recycle()
        outputFile
    }

    fun drawFrame(
        canvas: Canvas,
        width: Int,
        height: Int,
        frameData: FrameData,
        progress: Float,
        brandIcon: Bitmap? = null
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(244, 250, 252))

        val spectrogramTop = 104f
        val axisWidth = 112f
        val rightPadding = 42f
        val spectrogramHeight = height * 0.44f
        val specRect = RectF(axisWidth, spectrogramTop, width - rightPadding, spectrogramTop + spectrogramHeight)
        val resultsTop = specRect.bottom + 112f
        val footerTop = height - 168f

        drawRecordingTime(canvas, width, frameData.recordingTimeMs, paint)
        drawSpectrogram(canvas, specRect, frameData, paint)
        drawFrequencyAxis(canvas, specRect, paint)
        drawTimeAxis(canvas, specRect, paint)
        drawSelectionBox(canvas, specRect, frameData, paint)
        drawPlaybackLine(canvas, specRect, frameData, progress, paint)
        drawResults(canvas, width, resultsTop, footerTop, frameData, paint)
        drawFooter(canvas, width, height, brandIcon, paint)
    }

    private fun drawRecordingTime(canvas: Canvas, width: Int, recordingTimeMs: Long, paint: Paint) {
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.rgb(20, 34, 42)
        paint.textSize = 34f
        paint.isFakeBoldText = true
        canvas.drawText(AppText.format("录音时间 {0}", formatDateTime(recordingTimeMs)), width / 2f, 66f, paint)
        paint.isFakeBoldText = false
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawSpectrogram(canvas: Canvas, rect: RectF, frameData: FrameData, paint: Paint) {
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        canvas.drawRect(rect, paint)
        val visible = frameData.columns
            .filter { it.tripAudioTimeMs in frameData.spectrogramStartMs..frameData.spectrogramEndMs }
            .sortedBy { it.tripAudioTimeMs }
        if (visible.isEmpty()) return

        val bins = visible.first().values.size.coerceAtLeast(1)
        val binHeight = rect.height() / bins
        val fallbackColumnMs = estimateColumnDurationMs(visible).coerceAtLeast(1L)
        visible.forEachIndexed { index, column ->
            val x1 = timeToX(column.tripAudioTimeMs, rect, frameData)
            val nextTimeMs = visible.getOrNull(index + 1)?.tripAudioTimeMs
                ?: column.tripAudioTimeMs + fallbackColumnMs
            val x2 = timeToX(nextTimeMs, rect, frameData)
            val left = x1.coerceIn(rect.left, rect.right)
            val right = x2.coerceIn(rect.left, rect.right)
            if (right <= rect.left || left >= rect.right || right <= left) return@forEachIndexed
            column.values.forEachIndexed { y, value ->
                val v = value.coerceIn(0f, 1f)
                paint.color = com.example.birdingsoundmvp.audio.SpectrogramPalette.argb(v)
                canvas.drawRect(
                    left,
                    rect.bottom - (y + 1) * binHeight,
                    right + 1f,
                    rect.bottom - y * binHeight + 1f,
                    paint
                )
            }
        }
    }

    private fun drawFrequencyAxis(canvas: Canvas, rect: RectF, paint: Paint) {
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.RIGHT
        paint.textSize = 24f
        paint.color = Color.rgb(84, 104, 112)
        listOf(15_000, 12_000, 9_000, 6_000, 3_000, 0).forEach { frequency ->
            val y = frequencyToY(frequency.toFloat(), rect)
            canvas.drawText(frequencyLabel(frequency), rect.left - 14f, y + 8f, paint)
        }
        paint.color = Color.rgb(116, 137, 145)
        paint.strokeWidth = 2f
        canvas.drawLine(rect.left, rect.top, rect.left, rect.bottom, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawTimeAxis(canvas: Canvas, rect: RectF, paint: Paint) {
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 24f
        paint.color = Color.rgb(84, 104, 112)
        for (second in 0..4) {
            val x = rect.left + rect.width() * second / 4f
            canvas.drawText("${second}s", x, rect.bottom + 42f, paint)
        }
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawSelectionBox(canvas: Canvas, rect: RectF, frameData: FrameData, paint: Paint) {
        val left = timeToX(frameData.selectionStartMs, rect, frameData).coerceIn(rect.left, rect.right)
        val right = timeToX(frameData.selectionEndMs, rect, frameData).coerceIn(rect.left, rect.right)
        val top = frameData.selectionHighFrequencyHz?.let { frequencyToY(it, rect) } ?: rect.top
        val bottom = frameData.selectionLowFrequencyHz?.let { frequencyToY(it, rect) } ?: rect.bottom
        val box = RectF(
            minOf(left, right),
            minOf(top, bottom).coerceIn(rect.top, rect.bottom),
            maxOf(left, right),
            maxOf(top, bottom).coerceIn(rect.top, rect.bottom)
        )
        if (box.width() <= 1f || box.height() <= 1f) return
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(42, 255, 40, 40)
        canvas.drawRect(box, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        paint.color = Color.rgb(220, 0, 0)
        canvas.drawRect(box, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawPlaybackLine(
        canvas: Canvas,
        rect: RectF,
        frameData: FrameData,
        progress: Float,
        paint: Paint
    ) {
        val playTimeMs = frameData.selectionStartMs +
            ((frameData.selectionEndMs - frameData.selectionStartMs) * progress.coerceIn(0f, 1f)).toLong()
        val playX = timeToX(playTimeMs, rect, frameData).coerceIn(rect.left, rect.right)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(210, 0, 0)
        canvas.drawRect(playX - 4f, rect.top, playX + 4f, rect.bottom, paint)
    }

    private fun drawResults(
        canvas: Canvas,
        width: Int,
        top: Float,
        footerTop: Float,
        frameData: FrameData,
        paint: Paint
    ) {
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        paint.color = Color.rgb(20, 34, 42)
        paint.textSize = 34f
        paint.isFakeBoldText = true
        canvas.drawText(AppText.get("识别结果"), 42f, top, paint)
        paint.isFakeBoldText = false

        val resultLines = frameData.detections.ifEmpty {
            listOf(ShareDetectionLine(ShareDetectionSource.EMPTY, ShareContentPlanner.NO_RESULT_TEXT))
        }
        var y = top + 32f
        resultLines.take(10).forEach { line ->
            if (y + DETECTION_CARD_HEIGHT > footerTop) return@forEach
            y = drawDetectionCard(canvas, width, y, line, paint)
        }
    }

    private fun drawDetectionCard(
        canvas: Canvas,
        width: Int,
        top: Float,
        line: ShareDetectionLine,
        paint: Paint
    ): Float {
        val card = RectF(42f, top, width - 42f, top + DETECTION_CARD_HEIGHT)
        paint.style = Paint.Style.FILL
        paint.color = when (line.source) {
            ShareDetectionSource.PRECISE -> Color.rgb(223, 243, 227)
            ShareDetectionSource.REALTIME -> Color.rgb(232, 235, 236)
            ShareDetectionSource.EMPTY -> Color.rgb(238, 240, 241)
        }
        canvas.drawRoundRect(card, 18f, 18f, paint)
        paint.color = Color.rgb(24, 38, 44)
        paint.textSize = 30f
        paint.isFakeBoldText = true
        drawFittedText(canvas, line.displayName, card.left + 28f, card.top + 42f, card.width() - 56f, paint)
        paint.isFakeBoldText = false
        paint.textSize = 24f
        val detail = detectionDetail(line)
        paint.color = Color.rgb(92, 111, 119)
        drawFittedText(canvas, detail, card.left + 28f, card.top + 78f, card.width() - 56f, paint)
        return card.bottom + 14f
    }

    private fun drawFooter(canvas: Canvas, width: Int, height: Int, brandIcon: Bitmap?, paint: Paint) {
        val centerX = width / 2f
        val iconSize = 58f
        val iconTop = height - 128f
        val iconRect = RectF(centerX - iconSize / 2f, iconTop, centerX + iconSize / 2f, iconTop + iconSize)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 28f
        paint.color = Color.rgb(62, 80, 88)
        canvas.drawText(AppText.get("由"), iconRect.left - 34f, iconTop + 38f, paint)
        canvas.drawText(AppText.get("生成"), iconRect.right + 46f, iconTop + 38f, paint)
        if (brandIcon != null && !brandIcon.isRecycled) {
            canvas.drawBitmap(brandIcon, null as Rect?, iconRect, paint)
        } else {
            paint.color = Color.rgb(227, 248, 255)
            canvas.drawOval(iconRect, paint)
            paint.color = Color.rgb(29, 82, 96)
            canvas.drawText("O", centerX, iconTop + 40f, paint)
        }
        paint.textSize = 23f
        paint.color = Color.rgb(62, 80, 88)
        canvas.drawText("Owlett", centerX, iconRect.bottom + 30f, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun detectionDetail(line: ShareDetectionLine): String {
        if (line.source == ShareDetectionSource.EMPTY) return ShareContentPlanner.NO_RESULT_TEXT
        val sourceText = when (line.source) {
            ShareDetectionSource.PRECISE -> AppText.get("精准识别")
            ShareDetectionSource.REALTIME -> AppText.get("实时识别")
            ShareDetectionSource.EMPTY -> ""
        }
        val parts = mutableListOf(sourceText)
        if (line.modelName.isNotBlank()) parts += AppText.format("model: {0}", line.modelName)
        line.confidence?.let { parts += AppText.get("confidence: %.2f").format(it) }
        return parts.filter { it.isNotBlank() }.joinToString("  ")
    }

    private fun drawFittedText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        paint: Paint
    ) {
        if (paint.measureText(text) <= maxWidth) {
            canvas.drawText(text, x, y, paint)
            return
        }
        var candidate = text
        while (candidate.length > 1 && paint.measureText("$candidate...") > maxWidth) {
            candidate = candidate.dropLast(1)
        }
        canvas.drawText("$candidate...", x, y, paint)
    }

    private fun timeToX(timeMs: Long, rect: RectF, frameData: FrameData): Float {
        val ratio = (timeMs - frameData.spectrogramStartMs).toFloat() / frameData.spectrogramDurationMs
        return rect.left + rect.width() * ratio
    }

    private fun frequencyToY(frequencyHz: Float, rect: RectF): Float {
        val ratio = frequencyHz.coerceIn(0f, MAX_FREQUENCY_HZ) / MAX_FREQUENCY_HZ
        return rect.bottom - rect.height() * ratio
    }

    private fun estimateColumnDurationMs(columns: List<SpectrogramColumn>): Long {
        val gaps = columns.zipWithNext { left, right -> right.tripAudioTimeMs - left.tripAudioTimeMs }
            .filter { it > 0L }
        return gaps.average().takeIf { !it.isNaN() }?.toLong() ?: 40L
    }

    private fun frequencyLabel(frequencyHz: Int): String {
        return if (frequencyHz == 0) "0 Hz" else "${frequencyHz / 1000} kHz"
    }

    private fun formatDateTime(ms: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
    }

    private const val DETECTION_CARD_HEIGHT = 102f
}
