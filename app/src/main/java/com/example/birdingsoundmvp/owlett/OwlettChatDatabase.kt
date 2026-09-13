package com.example.birdingsoundmvp.owlett

import com.example.birdingsoundmvp.i18n.AppText

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class OwlettChatDatabase(
    context: Context,
    databaseName: String = DATABASE_NAME
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE conversations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                manually_renamed INTEGER NOT NULL DEFAULT 0,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id INTEGER NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                attachment_json TEXT,
                attachment_label TEXT,
                model_id TEXT,
                status TEXT NOT NULL,
                error_message TEXT,
                reasoning_content TEXT,
                tool_calls_json TEXT,
                tool_call_id TEXT,
                manual_skill_id TEXT,
                turn_config_json TEXT,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        createSkillRunsTable(db)
        createAgentV4(db)
        db.execSQL("CREATE INDEX messages_conversation_time ON messages(conversation_id, created_at_ms, id)")
        db.execSQL("CREATE INDEX conversations_updated_time ON conversations(updated_at_ms DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) addColumnIfMissing(db, "messages", "turn_config_json", "TEXT")
        if (oldVersion < 2) {
            addColumnIfMissing(db, "messages", "reasoning_content", "TEXT")
            addColumnIfMissing(db, "messages", "tool_calls_json", "TEXT")
            addColumnIfMissing(db, "messages", "tool_call_id", "TEXT")
            addColumnIfMissing(db, "messages", "manual_skill_id", "TEXT")
            createSkillRunsTable(db)
        }
        if (oldVersion < 4) {
            createAgentV4(db)
            db.execSQL("UPDATE skill_runs SET status = 'interrupted', error_message = '工具已升级，请重新准备操作' WHERE status IN ('waiting_input', 'waiting_confirmation', 'executing')")
        }
    }

    private fun createAgentV4(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS agent_tasks (
            user_message_id INTEGER PRIMARY KEY, config_json TEXT NOT NULL, status TEXT NOT NULL,
            updated_at_ms INTEGER NOT NULL, FOREIGN KEY(user_message_id) REFERENCES messages(id) ON DELETE CASCADE)""")
        addColumnIfMissing(db, "skill_runs", "task_message_id", "INTEGER REFERENCES agent_tasks(user_message_id) ON DELETE CASCADE")
        addColumnIfMissing(db, "skill_runs", "skill_version", "INTEGER NOT NULL DEFAULT 1")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tool_steps_task ON skill_runs(task_message_id, id)")
    }

    @Synchronized
    fun startAgentTask(userMessageId: Long, configJson: String) {
        writableDatabase.insertWithOnConflict("agent_tasks", null, ContentValues().apply {
            put("user_message_id", userMessageId); put("config_json", configJson)
            put("status", "running"); put("updated_at_ms", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    @Synchronized
    fun setAgentTaskStatus(userMessageId: Long, status: String) {
        writableDatabase.update("agent_tasks", ContentValues().apply {
            put("status", status); put("updated_at_ms", System.currentTimeMillis())
        }, "user_message_id = ?", arrayOf(userMessageId.toString()))
    }

    @Synchronized
    fun finishAgentTaskForAssistant(messageId: Long, status: String) {
        writableDatabase.execSQL("""UPDATE agent_tasks SET status=?,updated_at_ms=? WHERE user_message_id=(
            SELECT MAX(id) FROM messages WHERE role='user' AND id<? AND conversation_id=(
                SELECT conversation_id FROM messages WHERE id=?))""", arrayOf(status, System.currentTimeMillis(), messageId, messageId))
    }

    @Synchronized
    fun repairInterruptedMessages() {
        val now = System.currentTimeMillis()
        writableDatabase.execSQL("UPDATE agent_tasks SET status = 'interrupted' WHERE status = 'running'")
        writableDatabase.update(
            "messages",
            ContentValues().apply {
                put("status", OwlettMessageStatus.STOPPED.databaseValue)
                put("error_message", AppText.get("Response was interrupted"))
                put("updated_at_ms", now)
            },
            "status IN (?, ?)",
            arrayOf(
                OwlettMessageStatus.THINKING.databaseValue,
                OwlettMessageStatus.STREAMING.databaseValue
            )
        )
        writableDatabase.update(
            "skill_runs",
            ContentValues().apply {
                put("status", OwlettSkillRunStatus.INTERRUPTED.databaseValue)
                put("error_message", AppText.get("Skill execution was interrupted"))
                put(
                    "result_json",
                    OwlettSkillCardCodec.encode(
                        OwlettSkillCardPayload(
                            kind = "interrupted",
                            title = AppText.get("Skill interrupted"),
                            summary = AppText.get("The operation stopped before it completed. You can retry it safely.")
                        )
                    )
                )
                put("updated_at_ms", now)
            },
            "status = ?",
            arrayOf(OwlettSkillRunStatus.EXECUTING.databaseValue)
        )
    }

    @Synchronized
    fun listConversations(): List<OwlettConversation> = readableDatabase.rawQuery(
        """
        SELECT id, title, manually_renamed, created_at_ms, updated_at_ms
        FROM conversations
        ORDER BY updated_at_ms DESC, id DESC
        """.trimIndent(),
        emptyArray()
    ).useRows { cursor ->
        OwlettConversation(
            id = cursor.getLong(0),
            title = cursor.getString(1),
            manuallyRenamed = cursor.getInt(2) != 0,
            createdAtMs = cursor.getLong(3),
            updatedAtMs = cursor.getLong(4)
        )
    }

    @Synchronized
    fun listMessages(conversationId: Long): List<OwlettMessage> = readableDatabase.rawQuery(
        """
        SELECT id, conversation_id, role, content, attachment_json, attachment_label,
               model_id, status, error_message, created_at_ms, updated_at_ms,
               reasoning_content, tool_calls_json, tool_call_id, manual_skill_id, turn_config_json
        FROM messages
        WHERE conversation_id = ?
        ORDER BY created_at_ms ASC, id ASC
        """.trimIndent(),
        arrayOf(conversationId.toString())
    ).useRows { cursor ->
        OwlettMessage(
            id = cursor.getLong(0),
            conversationId = cursor.getLong(1),
            role = OwlettMessageRole.fromDatabase(cursor.getString(2)),
            content = cursor.getString(3),
            attachmentJson = cursor.stringOrNull(4),
            attachmentLabel = cursor.stringOrNull(5),
            modelId = cursor.stringOrNull(6),
            status = OwlettMessageStatus.fromDatabase(cursor.getString(7)),
            errorMessage = cursor.stringOrNull(8),
            createdAtMs = cursor.getLong(9),
            updatedAtMs = cursor.getLong(10),
            reasoningContent = cursor.stringOrNull(11),
            toolCallsJson = cursor.stringOrNull(12),
            toolCallId = cursor.stringOrNull(13),
            manualSkillId = cursor.stringOrNull(14),
            turnConfigJson = cursor.stringOrNull(15)
        )
    }

    @Synchronized
    fun listSkillRuns(conversationId: Long): List<OwlettSkillRun> = readableDatabase.rawQuery(
        """
        SELECT id, conversation_id, assistant_message_id, tool_call_id, skill_id,
               arguments_json, preview_json, result_json, status, error_message,
               created_at_ms, updated_at_ms
        FROM skill_runs
        WHERE conversation_id = ?
        ORDER BY created_at_ms ASC, id ASC
        """.trimIndent(),
        arrayOf(conversationId.toString())
    ).useRows { cursor ->
        OwlettSkillRun(
            id = cursor.getLong(0),
            conversationId = cursor.getLong(1),
            assistantMessageId = cursor.getLong(2),
            toolCallId = cursor.getString(3),
            skillId = cursor.getString(4),
            argumentsJson = cursor.getString(5),
            previewJson = cursor.stringOrNull(6),
            resultJson = cursor.stringOrNull(7),
            status = OwlettSkillRunStatus.fromDatabase(cursor.getString(8)),
            errorMessage = cursor.stringOrNull(9),
            createdAtMs = cursor.getLong(10),
            updatedAtMs = cursor.getLong(11)
        )
    }

    @Synchronized
    fun createConversation(title: String, now: Long = System.currentTimeMillis()): Long {
        return writableDatabase.insertOrThrow(
            "conversations",
            null,
            ContentValues().apply {
                put("title", title)
                put("manually_renamed", 0)
                put("created_at_ms", now)
                put("updated_at_ms", now)
            }
        )
    }

    @Synchronized
    fun insertMessage(
        conversationId: Long,
        role: OwlettMessageRole,
        content: String,
        attachmentJson: String? = null,
        attachmentLabel: String? = null,
        modelId: String? = null,
        status: OwlettMessageStatus = OwlettMessageStatus.COMPLETE,
        errorMessage: String? = null,
        reasoningContent: String? = null,
        toolCallsJson: String? = null,
        toolCallId: String? = null,
        manualSkillId: String? = null,
        turnConfigJson: String? = null,
        now: Long = System.currentTimeMillis()
    ): Long {
        val database = writableDatabase
        val id = database.insertOrThrow(
            "messages",
            null,
            ContentValues().apply {
                put("conversation_id", conversationId)
                put("role", role.databaseValue)
                put("content", content)
                putNullable("attachment_json", attachmentJson)
                putNullable("attachment_label", attachmentLabel)
                putNullable("model_id", modelId)
                put("status", status.databaseValue)
                putNullable("error_message", errorMessage)
                putNullable("reasoning_content", reasoningContent)
                putNullable("tool_calls_json", toolCallsJson)
                putNullable("tool_call_id", toolCallId)
                putNullable("manual_skill_id", manualSkillId)
                putNullable("turn_config_json", turnConfigJson)
                put("created_at_ms", now)
                put("updated_at_ms", now)
            }
        )
        touchConversation(database, conversationId, now)
        return id
    }

    @Synchronized
    fun updateMessage(
        messageId: Long,
        content: String,
        modelId: String?,
        status: OwlettMessageStatus,
        errorMessage: String?,
        reasoningContent: String? = null,
        toolCallsJson: String? = null,
        now: Long = System.currentTimeMillis()
    ) {
        val database = writableDatabase
        database.update(
            "messages",
            ContentValues().apply {
                put("content", content)
                putNullable("model_id", modelId)
                put("status", status.databaseValue)
                putNullable("error_message", errorMessage)
                putNullable("reasoning_content", reasoningContent)
                putNullable("tool_calls_json", toolCallsJson)
                put("updated_at_ms", now)
            },
            "id = ?",
            arrayOf(messageId.toString())
        )
        database.rawQuery("SELECT conversation_id FROM messages WHERE id = ?", arrayOf(messageId.toString())).use { cursor ->
            if (cursor.moveToFirst()) touchConversation(database, cursor.getLong(0), now)
        }
    }

    @Synchronized
    fun insertSkillRun(
        conversationId: Long,
        assistantMessageId: Long,
        toolCallId: String,
        skillId: String,
        argumentsJson: String,
        previewJson: String? = null,
        resultJson: String? = null,
        status: OwlettSkillRunStatus,
        errorMessage: String? = null,
        taskMessageId: Long? = null,
        skillVersion: Int = 1,
        now: Long = System.currentTimeMillis()
    ): Long = writableDatabase.insertOrThrow(
        "skill_runs",
        null,
        ContentValues().apply {
            put("conversation_id", conversationId)
            put("assistant_message_id", assistantMessageId)
            put("tool_call_id", toolCallId)
            put("skill_id", skillId)
            if (taskMessageId == null) putNull("task_message_id") else put("task_message_id", taskMessageId)
            put("skill_version", skillVersion)
            put("arguments_json", argumentsJson)
            putNullable("preview_json", previewJson)
            putNullable("result_json", resultJson)
            put("status", status.databaseValue)
            putNullable("error_message", errorMessage)
            put("created_at_ms", now)
            put("updated_at_ms", now)
        }
    )

    @Synchronized
    fun updateSkillRun(
        runId: Long,
        argumentsJson: String? = null,
        previewJson: String? = null,
        resultJson: String? = null,
        status: OwlettSkillRunStatus,
        errorMessage: String? = null,
        now: Long = System.currentTimeMillis()
    ) {
        writableDatabase.update(
            "skill_runs",
            ContentValues().apply {
                argumentsJson?.let { put("arguments_json", it) }
                if (previewJson == null) putNull("preview_json") else put("preview_json", previewJson)
                if (resultJson == null) putNull("result_json") else put("result_json", resultJson)
                put("status", status.databaseValue)
                putNullable("error_message", errorMessage)
                put("updated_at_ms", now)
            },
            "id = ?",
            arrayOf(runId.toString())
        )
    }

    @Synchronized
    fun finishSkillRun(
        runId: Long,
        conversationId: Long,
        toolCallId: String,
        argumentsJson: String,
        resultJson: String?,
        status: OwlettSkillRunStatus,
        errorMessage: String?,
        toolContent: String,
        now: Long = System.currentTimeMillis()
    ) {
        val database = writableDatabase
        database.beginTransaction()
        try {
            database.update(
                "skill_runs",
                ContentValues().apply {
                    put("arguments_json", argumentsJson)
                    putNull("preview_json")
                    putNullable("result_json", resultJson)
                    put("status", status.databaseValue)
                    putNullable("error_message", errorMessage)
                    put("updated_at_ms", now)
                },
                "id = ?",
                arrayOf(runId.toString())
            )
            val hasToolResult = database.rawQuery(
                "SELECT 1 FROM messages WHERE conversation_id = ? AND role = ? AND tool_call_id = ? LIMIT 1",
                arrayOf(conversationId.toString(), OwlettMessageRole.TOOL.databaseValue, toolCallId)
            ).use { it.moveToFirst() }
            if (!hasToolResult) {
                database.insertOrThrow(
                    "messages",
                    null,
                    ContentValues().apply {
                        put("conversation_id", conversationId)
                        put("role", OwlettMessageRole.TOOL.databaseValue)
                        put("content", toolContent)
                        put("status", OwlettMessageStatus.COMPLETE.databaseValue)
                        put("tool_call_id", toolCallId)
                        put("created_at_ms", now)
                        put("updated_at_ms", now)
                    }
                )
            }
            touchConversation(database, conversationId, now)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    @Synchronized
    fun renameConversation(conversationId: Long, title: String) {
        writableDatabase.update(
            "conversations",
            ContentValues().apply {
                put("title", title.trim())
                put("manually_renamed", 1)
                put("updated_at_ms", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(conversationId.toString())
        )
    }

    @Synchronized
    fun deleteConversation(conversationId: Long) {
        writableDatabase.delete("conversations", "id = ?", arrayOf(conversationId.toString()))
    }

    private fun touchConversation(database: SQLiteDatabase, conversationId: Long, now: Long) {
        database.update(
            "conversations",
            ContentValues().apply { put("updated_at_ms", now) },
            "id = ?",
            arrayOf(conversationId.toString())
        )
    }

    private inline fun <T> Cursor.useRows(mapper: (Cursor) -> T): List<T> = use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(mapper(cursor))
        }
    }

    private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private fun ContentValues.putNullable(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun createSkillRunsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS skill_runs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id INTEGER NOT NULL,
                assistant_message_id INTEGER NOT NULL,
                tool_call_id TEXT NOT NULL,
                skill_id TEXT NOT NULL,
                arguments_json TEXT NOT NULL,
                preview_json TEXT,
                result_json TEXT,
                status TEXT NOT NULL,
                error_message TEXT,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE,
                FOREIGN KEY(assistant_message_id) REFERENCES messages(id) ON DELETE CASCADE,
                UNIQUE(conversation_id, tool_call_id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS skill_runs_conversation_time ON skill_runs(conversation_id, created_at_ms, id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS skill_runs_assistant ON skill_runs(assistant_message_id)")
    }

    private fun addColumnIfMissing(
        db: SQLiteDatabase,
        table: String,
        column: String,
        declaration: String
    ) {
        val exists = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) {
                    found = true
                    break
                }
            }
            found
        }
        if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $column $declaration")
    }

    companion object {
        const val DATABASE_NAME = "owlett_chat.db"
        private const val DATABASE_VERSION = 4
    }
}
