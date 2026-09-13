package com.example.birdingsoundmvp.owlett

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal suspend fun cachedObservation(context: OwlettSkillContext, kind: String, source: String,
    criteria: JsonObject, refresh: Boolean = false, fetch: suspend () -> JsonObject): ObservationCacheRead {
    val cache = context.observationCache
    val conversation = context.conversationId
    return if (cache != null && conversation != null) cache.load(conversation, kind, source, criteria, refresh, fetch)
    else ObservationCacheRead(CachedObservationQuery("", kind, source, criteria, System.currentTimeMillis(), fetch()), false, false)
}

data class CachedObservationQuery(val id: String, val kind: String, val source: String,
    val criteria: JsonObject, val fetchedAtMs: Long, val data: JsonObject)
data class ObservationCacheRead(val query: CachedObservationQuery, val reused: Boolean, val stored: Boolean = true)

/** Successful structured observations only. Keys never contain credentials, prompts or local paths. */
class OwlettObservationCache(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private val gson = Gson()
    private fun folder(conversationId: Long): File {
        require(conversationId > 0)
        return File(directory, conversationId.toString())
    }
    private fun sorted(value: JsonElement): JsonElement = when {
        value.isJsonObject -> JsonObject().apply { value.asJsonObject.entrySet().sortedBy { it.key }.forEach { add(it.key, sorted(it.value)) } }
        value.isJsonArray -> com.google.gson.JsonArray().apply { value.asJsonArray.forEach { add(sorted(it)) } }
        else -> value
    }
    private fun id(kind: String, source: String, criteria: JsonObject): String = MessageDigest.getInstance("SHA-256")
        .digest(gson.toJson(listOf(kind, source, sorted(criteria))).toByteArray()).joinToString("") { "%02x".format(it) }
    private fun read(file: File): CachedObservationQuery? = runCatching {
        if (!file.isFile || file.length() > MAX_ENTRY_BYTES) return null
        gson.fromJson(file.readText(), CachedObservationQuery::class.java).takeIf {
            it.id == file.nameWithoutExtension && now() - it.fetchedAtMs in 0..TTL_MS
        }
    }.getOrNull()

    suspend fun load(conversationId: Long, kind: String, source: String, criteria: JsonObject,
                     refresh: Boolean = false, fetch: suspend () -> JsonObject): ObservationCacheRead = mutex.withLock {
        prune()
        val key = id(kind, source, criteria)
        val target = File(folder(conversationId), "$key.json")
        if (!refresh) read(target)?.let { return@withLock ObservationCacheRead(it, true) }
        val data = fetch()
        // Partial responses remain visible to the user, but are never saved as complete snapshots.
        val complete = data.getAsJsonArray("failedDates")?.size()?.let { it == 0 } ?: true
        val entry = CachedObservationQuery(key, kind, source, criteria.deepCopy(), now(), data)
        val bytes = gson.toJson(entry).toByteArray()
        if (!complete || bytes.size > MAX_ENTRY_BYTES) return@withLock ObservationCacheRead(entry, false, false)
        target.parentFile!!.mkdirs()
        val stage = File(target.parentFile, "$key.tmp")
        try {
            stage.outputStream().use { it.write(bytes); it.fd.sync() }
            Files.move(stage.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { stage.delete() }
        ObservationCacheRead(entry, false)
    }

    suspend fun get(conversationId: Long, id: String): CachedObservationQuery? = mutex.withLock {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "查询快照编号无效" }
        read(File(folder(conversationId), "$id.json"))
    }
    suspend fun list(conversationId: Long): List<CachedObservationQuery> = mutex.withLock {
        folder(conversationId).listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull(::read).sortedByDescending { it.fetchedAtMs }
    }
    suspend fun delete(conversationId: Long) = mutex.withLock { folder(conversationId).deleteRecursively(); Unit }
    private fun prune() {
        val files = directory.listFiles().orEmpty().filter { it.isDirectory && it.name.toLongOrNull() != null }
            .flatMap { it.listFiles().orEmpty().toList() }
        files.filter { it.extension == "tmp" || now() - it.lastModified() > TTL_MS }.forEach { it.delete() }
        var total = files.filter { it.isFile }.sumOf { it.length() }
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_TOTAL_BYTES) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }
    companion object {
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_ENTRY_BYTES = 16 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 128L * 1024 * 1024
    }
}
