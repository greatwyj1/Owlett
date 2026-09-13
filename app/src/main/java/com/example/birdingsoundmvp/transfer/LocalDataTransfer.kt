package com.example.birdingsoundmvp.transfer

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.example.birdingsoundmvp.owlett.OwlettChatDatabase
import com.example.birdingsoundmvp.planning.BirdingDatabaseHelper
import com.example.birdingsoundmvp.settings.AppSettings
import com.example.birdingsoundmvp.settings.SettingsRepository
import com.google.gson.*
import java.io.*
import java.security.MessageDigest
import java.util.zip.*
import kotlinx.coroutines.flow.first

object DataTransferGate {
    @Volatile var active = false
        private set
    @Synchronized fun begin(): Boolean { if (active) return false; active = true; return true }
    @Synchronized fun end() { active = false }
}

data class TransferFile(val path: String, val size: Long, val sha256: String)
data class TransferManifest(val format: Int = 1, val files: List<TransferFile>, val observationSources: List<String>? = null)

object TransferValidation {
    const val MAX_BYTES = 20L * 1024 * 1024 * 1024
    fun safePath(path: String): Boolean = path.isNotBlank() && !path.startsWith("/") &&
        !path.contains('\\') && path.split('/').all { it.isNotBlank() && it != "." && it != ".." && !it.contains(':') } &&
        (path == "birding.json" || path == "chat.json" || path == "settings.json" ||
            (path.startsWith("trips/") && path.split('/').size >= 3 && path.substringAfterLast('.').lowercase() in setOf("wav", "json", "jsonl")))
    fun sanitized(settings: AppSettings) = settings.copy(ebirdApiKey = "", preciseRecognitionAuthToken = "",
        preciseRecognitionServerUrl = "", owlettAutomationMode = "confirm_writes")
    fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Archives contain records, never executable SQL or a raw database from the sender. */
class LocalDataTransfer(
    private val context: Context,
    private val readSettings: suspend () -> AppSettings = { SettingsRepository(context).settings.first() },
    private val writeSettings: suspend (AppSettings) -> Unit = { value -> SettingsRepository(context).update { value } },
    private val clearCredentials: () -> Unit = {
        com.example.birdingsoundmvp.owlett.OwlettApiKeyStore(context).clear()
        com.example.birdingsoundmvp.owlett.XenoCantoApiKeyStore(context).clear()
    }
) {
    private val gson = Gson()
    private val trips = File(context.getExternalFilesDir(null), "trips")
    private val stage = File(context.noBackupFilesDir, "transfer-stage")
    private val journal = File(context.noBackupFilesDir, "transfer-importing")
    private val bird = BirdingDatabaseHelper(context)
    private val chat = OwlettChatDatabase(context)
    private val excludedTables = setOf("android_metadata", "sqlite_sequence", "ebird_daily_cache")

    private fun tables(db: SQLiteDatabase): List<String> = db.rawQuery(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null
    ).use { cursor -> buildList { while (cursor.moveToNext()) { val name = cursor.getString(0); if (name !in excludedTables) add(name) } } }

    private fun empty(db: SQLiteDatabase): Boolean = tables(db).all { table ->
        db.rawQuery("SELECT COUNT(*) FROM \"$table\"", null).use { it.moveToFirst(); it.getLong(0) == 0L }
    }

    private fun exportDb(db: SQLiteDatabase): JsonObject {
        val result = JsonObject()
        result.addProperty("version", db.version)
        val records = JsonObject()
        tables(db).forEach { table ->
            val rows = JsonArray()
            db.rawQuery("SELECT * FROM \"$table\"", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val row = JsonObject()
                    cursor.columnNames.forEachIndexed { index, column ->
                        when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> row.add(column, JsonNull.INSTANCE)
                            Cursor.FIELD_TYPE_INTEGER -> row.addProperty(column, cursor.getLong(index))
                            Cursor.FIELD_TYPE_FLOAT -> row.addProperty(column, cursor.getDouble(index))
                            Cursor.FIELD_TYPE_STRING -> row.addProperty(column, cursor.getString(index))
                            else -> error("不支持的数据字段：$table.$column")
                        }
                    }
                    rows.add(row)
                }
            }
            records.add(table, rows)
        }
        result.add("tables", records)
        return result
    }

    suspend fun export(uri: Uri, progress: (Float, String) -> Unit) {
        try {
            stage.deleteRecursively(); check(stage.mkdirs())
            progress(0.03f, "整理计划与聊天")
            File(stage, "birding.json").writeText(gson.toJson(exportDb(bird.readableDatabase)))
            File(stage, "chat.json").writeText(gson.toJson(exportDb(chat.readableDatabase)))
            File(stage, "settings.json").writeText(gson.toJson(TransferValidation.sanitized(readSettings())))
            val originals = trips.walkTopDown().filter { file ->
                file.isFile && file.extension.lowercase() in setOf("wav", "json", "jsonl") &&
                    !file.name.startsWith("selected_playback") && !file.name.startsWith("share_")
            }.toList()
            val entries = stage.listFiles()!!.map { it.name to it } + originals.map { file ->
                require(file.canonicalFile.toPath().startsWith(trips.canonicalFile.toPath()))
                "trips/" + file.relativeTo(trips).invariantSeparatorsPath to file
            }
            val total = entries.sumOf { it.second.length() }.coerceAtLeast(1)
            require(total <= TransferValidation.MAX_BYTES) { "迁移数据超过 20 GB，请先分批清理录音。" }
            val sourceIds = bird.readableDatabase.rawQuery("SELECT DISTINCT source_id FROM plans", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            val manifest = TransferManifest(observationSources = sourceIds, files = entries.mapIndexed { index, (path, file) ->
                require(TransferValidation.safePath(path))
                progress(0.05f + 0.25f * index / entries.size, "校验文件 ${index + 1}/${entries.size}")
                TransferFile(path, file.length(), TransferValidation.hash(file))
            })
            context.contentResolver.openOutputStream(uri, "wt")!!.use { raw ->
                ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                    zip.putNextEntry(ZipEntry("manifest.json")); zip.write(gson.toJson(manifest).toByteArray()); zip.closeEntry()
                    var written = 0L
                    entries.forEach { (path, file) ->
                        zip.putNextEntry(ZipEntry(path))
                        file.inputStream().buffered().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) { val n = input.read(buffer); if (n < 0) break; zip.write(buffer, 0, n); written += n
                                progress(0.3f + 0.7f * written / total, "写入迁移包") }
                        }
                        zip.closeEntry()
                    }
                }
            }
            progress(1f, "导出完成。迁移包含私人录音与聊天，请妥善保存。")
        } finally { stage.deleteRecursively(); close() }
    }

    suspend fun import(uri: Uri, progress: (Float, String) -> Unit) {
        var committing = false
        try {
            require(context.packageName == "io.github.greatwyj1.owlett") { "请在正式版中导入数据。" }
            require(empty(bird.writableDatabase) && empty(chat.writableDatabase) && trips.listFiles().isNullOrEmpty()) { "只能导入空白正式版；当前已有数据，已阻止覆盖。" }
            stage.deleteRecursively(); check(stage.mkdirs())
            progress(0.01f, "读取与校验迁移包")
            context.contentResolver.openInputStream(uri)!!.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    require(zip.nextEntry?.name == "manifest.json") { "不是有效的 Owlett 迁移包。" }
                    val text = readLimited(zip, 4 * 1024 * 1024).toString(Charsets.UTF_8)
                    val manifest = gson.fromJson(text, TransferManifest::class.java)
                    require(manifest.format == 1 && manifest.files.size <= 100000) { "迁移包版本不支持。" }
                    val supported = com.example.birdingsoundmvp.planning.BirdObservationSources.get(context.applicationContext as android.app.Application).all().map { it.id }.toSet()
                    require(manifest.observationSources.orEmpty().all { it in supported }) { "迁移包包含此版本不支持的鸟况来源，请使用对应内部版本。" }
                    val files = manifest.files.associateBy { it.path }
                    require(files.size == manifest.files.size && files.keys.containsAll(listOf("birding.json", "chat.json", "settings.json")))
                    require(files.values.all { TransferValidation.safePath(it.path) && it.size >= 0 && it.size <= TransferValidation.MAX_BYTES && it.sha256.matches(Regex("[0-9a-f]{64}")) })
                    val total = files.values.fold(0L) { sum, file -> Math.addExact(sum, file.size) }
                    require(total <= TransferValidation.MAX_BYTES && stage.usableSpace > total * 2 + 64 * 1024 * 1024) { "空间不足，请至少预留迁移包解压大小的两倍。" }
                    require(trips.parentFile!!.usableSpace > total + 64 * 1024 * 1024) { "录音存储空间不足。" }
                    val seen = mutableSetOf<String>()
                    var read = 0L
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val expected = files[entry.name] ?: error("迁移包包含未声明的文件。")
                        require(!entry.isDirectory && seen.add(entry.name))
                        val output = File(stage, entry.name)
                        output.parentFile!!.mkdirs()
                        var size = 0L
                        output.outputStream().buffered().use { stream ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) { val n = zip.read(buffer); if (n < 0) break; size += n; read += n
                                require(size <= expected.size) { "文件大小与校验清单不符。" }
                                stream.write(buffer, 0, n)
                                progress(0.02f + 0.55f * read / total.coerceAtLeast(1), "验证录音与记录") }
                        }
                        require(size == expected.size && TransferValidation.hash(output) == expected.sha256) { "迁移包损坏，文件校验失败。" }
                    }
                    require(seen == files.keys) { "迁移包缺少文件。" }
                }
            }
            val birdRecords = readJson("birding.json").asJsonObject
            val supportedSources = com.example.birdingsoundmvp.planning.BirdObservationSources
                .get(context.applicationContext as android.app.Application).all().map { it.id }.toSet()
            val usedSources = birdRecords.getAsJsonObject("tables").getAsJsonArray("plans")?.map {
                it.asJsonObject.get("source_id")?.asString ?: "ebird"
            }.orEmpty().toSet()
            require(supportedSources.containsAll(usedSources)) { "迁移包使用了当前版本不支持的鸟况来源，请使用对应版本导入。" }
            val chatRecords = readJson("chat.json").asJsonObject
            val importedSettings = safeSettings(readJson("settings.json").asJsonObject)
            validateWithTemporaryDatabase(birdRecords, "birding", bird.writableDatabase)
            validateWithTemporaryDatabase(chatRecords, "chat", chat.writableDatabase)
            progress(0.65f, "准备本地录音路径")
            File(stage, "trips").walkTopDown().filter { it.isFile && it.extension in setOf("json", "jsonl") }.forEach { file ->
                if (file.extension == "jsonl") {
                    val lines = file.readLines().map { gson.toJson(rewritePaths(JsonParser.parseString(it))) }
                    file.writeText(lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n"))
                } else file.writeText(gson.toJson(rewritePaths(JsonParser.parseString(file.readText()))))
            }
            // A startup journal rolls an incomplete import back to the original empty database.
            val rollbackSettings = gson.toJson(readSettings()).toByteArray()
            FileOutputStream(journal).use { it.write(rollbackSettings); it.fd.sync() }
            committing = true
            insertRecords(bird.writableDatabase, birdRecords)
            insertRecords(chat.writableDatabase, chatRecords)
            chat.writableDatabase.execSQL("UPDATE messages SET status='stopped' WHERE status IN ('streaming','thinking')")
            chat.writableDatabase.rawQuery("SELECT id,turn_config_json FROM messages WHERE turn_config_json IS NOT NULL", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val config = com.example.birdingsoundmvp.owlett.OwlettTurnConfig.decode(cursor.getString(1)).copy(automationMode = "confirm_writes")
                    chat.writableDatabase.execSQL("UPDATE messages SET turn_config_json=? WHERE id=?", arrayOf(config.toJson(), cursor.getLong(0)))
                }
            }
            chat.writableDatabase.execSQL("UPDATE skill_runs SET status='interrupted' WHERE status IN ('executing','waiting_confirmation','waiting_input')")
            chat.writableDatabase.execSQL("UPDATE agent_tasks SET status='interrupted' WHERE status IN ('running','waiting')")
            val stagedTrips = File(stage, "trips")
            if (stagedTrips.exists()) {
                trips.mkdirs()
                stagedTrips.copyRecursively(trips, overwrite = false)
            }
            progress(0.95f, "恢复普通设置")
            writeSettings(importedSettings)
            clearCredentials()
            check(journal.delete())
            committing = false
            progress(1f, "导入完成。请重新打开应用，并重新填写服务密钥。")
        } catch (error: Throwable) {
            if (committing) recover()
            throw error
        } finally { stage.deleteRecursively(); close() }
    }

    private fun readJson(name: String): JsonElement {
        val file = File(stage, name)
        require(file.length() <= 64L * 1024 * 1024) { "记录文件过大。" }
        return file.reader().use { JsonParser.parseReader(it) }
    }

    private fun safeSettings(value: JsonObject): AppSettings {
        val defaults = gson.toJsonTree(AppSettings()).asJsonObject
        value.entrySet().forEach { (key, data) -> if (defaults.has(key)) defaults.add(key, data) }
        val result = gson.fromJson(defaults, AppSettings::class.java)
        require(result.appearance in setOf("system", "light", "dark") && result.owlettSystemPrompt.length <= 10000)
        require(result.chunkOverlapSec in 0f..2.5f && result.maxSelectionDurationSec in 1f..15f &&
            result.minimumAudioConfidence in 0f..1f && result.minimumMetaConfidence in 0f..1f)
        return TransferValidation.sanitized(result.copy(colorTheme = com.example.birdingsoundmvp.settings.ColorTheme.normalize(result.colorTheme)))
    }

    private fun validateWithTemporaryDatabase(records: JsonObject, label: String, source: SQLiteDatabase) {
        require(records.get("version").asInt <= source.version)
        val file = File(stage, "validate-$label.db")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            source.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND sql IS NOT NULL AND name NOT LIKE 'sqlite_%'", null).use { cursor ->
                while (cursor.moveToNext()) db.execSQL(cursor.getString(0))
            }
            insertRecords(db, records)
            db.rawQuery("PRAGMA foreign_key_check", null).use { require(!it.moveToFirst()) { "关联记录不完整。" } }
        }
    }

    private fun insertRecords(db: SQLiteDatabase, records: JsonObject) {
        val allowed = tables(db).toSet()
        val data = records.getAsJsonObject("tables")
        require(data.keySet().all { it in allowed }) { "迁移包包含不支持的数据表。" }
        db.beginTransaction()
        try {
            db.execSQL("PRAGMA defer_foreign_keys=ON")
            data.entrySet().forEach { (table, rows) ->
                val columns = db.rawQuery("SELECT * FROM \"$table\" LIMIT 0", null).use { it.columnNames.toSet() }
                rows.asJsonArray.forEach { item ->
                    val row = item.asJsonObject
                    require(row.keySet().all { it in columns })
                    val values = ContentValues()
                    row.entrySet().forEach { (key, value) ->
                        when {
                            value.isJsonNull -> values.putNull(key)
                            value.asJsonPrimitive.isNumber -> values.put(key, value.asString)
                            value.asJsonPrimitive.isBoolean -> values.put(key, if (value.asBoolean) 1 else 0)
                            else -> values.put(key, value.asString)
                        }
                    }
                    db.insertOrThrow(table, null, values)
                }
            }
            db.rawQuery("PRAGMA foreign_key_check", null).use { require(!it.moveToFirst()) { "迁移关联记录校验失败。" } }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun rewritePaths(element: JsonElement): JsonElement {
        if (element.isJsonArray) return JsonArray().apply { element.asJsonArray.forEach { add(rewritePaths(it)) } }
        if (element.isJsonObject) return JsonObject().apply { element.asJsonObject.entrySet().forEach { (key, value) -> add(key, rewritePaths(value)) } }
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            val text = element.asString
            val marker = "/trips/"
            if (text.startsWith("/")) {
                require(text.contains(marker)) { "录音记录含外部文件路径。" }
                val relative = "trips/" + text.substringAfter(marker)
                require(TransferValidation.safePath(relative)) { "录音记录含不安全路径。" }
                return JsonPrimitive(File(trips.parentFile, relative).absolutePath)
            }
        }
        return element
    }

    fun recover() {
        if (!journal.exists()) return
        listOf(chat.writableDatabase, bird.writableDatabase).forEach { db ->
            db.beginTransaction()
            try { db.execSQL("PRAGMA defer_foreign_keys=ON"); tables(db).reversed().forEach { db.delete(it, null, null) }; db.setTransactionSuccessful() }
            finally { db.endTransaction() }
        }
        trips.deleteRecursively()
        stage.deleteRecursively()
        val originalSettings = runCatching { gson.fromJson(journal.readText(), AppSettings::class.java) }.getOrNull()
        if (originalSettings != null) kotlinx.coroutines.runBlocking { writeSettings(originalSettings) }
        check(journal.delete())
    }
    fun close() { bird.close(); chat.close() }
    private fun readLimited(input: InputStream, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= maximum) { "迁移包清单过大。" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
