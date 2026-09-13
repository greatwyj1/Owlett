package com.example.birdingsoundmvp.trip

import com.example.birdingsoundmvp.birdnet.DetectionResult
import com.google.gson.GsonBuilder
import java.io.BufferedWriter
import java.io.FileWriter

class DetectionLogger(
    private val session: TripSession
) : AutoCloseable {
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val writer: BufferedWriter = BufferedWriter(FileWriter(session.detectionsFile, true))

    @Synchronized
    fun logDetection(result: DetectionResult) {
        writer.write(gson.toJson(result))
        writer.newLine()
        writer.flush()
    }

    @Synchronized
    fun writeSummary(summary: TripSummary) {
        session.summaryFile.writeText(gson.toJson(summary))
    }

    override fun close() {
        writer.flush()
        writer.close()
    }
}
