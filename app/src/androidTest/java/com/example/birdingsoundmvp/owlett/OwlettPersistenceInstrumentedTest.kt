package com.example.birdingsoundmvp.owlett

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwlettPersistenceInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun databasePersistsRenamesRepairsStreamsAndCascadesMessages() {
        val databaseName = "owlett_test_${UUID.randomUUID()}.db"
        val database = OwlettChatDatabase(context, databaseName)
        try {
            val conversationId = database.createConversation("First title", now = 10)
            database.insertMessage(
                conversationId = conversationId,
                role = OwlettMessageRole.USER,
                content = "hello",
                attachmentJson = "{\"planId\":42}",
                attachmentLabel = "Plan: Marsh",
                now = 11
            )
            database.insertMessage(
                conversationId = conversationId,
                role = OwlettMessageRole.ASSISTANT,
                content = "partial",
                status = OwlettMessageStatus.STREAMING,
                now = 12
            )

            database.renameConversation(conversationId, "Birding advice")
            database.repairInterruptedMessages()

            assertEquals("Birding advice", database.listConversations().single().title)
            assertTrue(database.listConversations().single().manuallyRenamed)
            val messages = database.listMessages(conversationId)
            assertEquals("{\"planId\":42}", messages.first().attachmentJson)
            assertEquals("Plan: Marsh", messages.first().attachmentLabel)
            assertEquals(OwlettMessageStatus.STOPPED, messages.last().status)

            database.deleteConversation(conversationId)
            assertTrue(database.listConversations().isEmpty())
            assertTrue(database.listMessages(conversationId).isEmpty())
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun apiKeyUsesEncryptedRoundTripAndCanBeCleared() {
        val suffix = UUID.randomUUID().toString()
        val preferencesName = "owlett_secure_test_$suffix"
        val store = OwlettApiKeyStore(context, preferencesName, "owlett_test_key_$suffix")
        try {
            assertNull(store.load())
            store.save("sk-test-secret")
            assertEquals("sk-test-secret", store.load())
            assertTrue(store.masked().endsWith("cret"))
            assertFalse(store.masked().contains("sk-test"))
            store.clear()
            assertNull(store.load())
        } finally {
            store.clear()
            context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun v1DatabaseMigratesToolColumnsAndRecoversExecutingSkillRuns() {
        val databaseName = "owlett_v1_${UUID.randomUUID()}.db"
        LegacyV1ChatHelper(context, databaseName).use { it.writableDatabase }
        val database = OwlettChatDatabase(context, databaseName)
        try {
            assertEquals(4, database.writableDatabase.version)
            database.repairInterruptedMessages()
            assertEquals(OwlettMessageStatus.STOPPED, database.listMessages(1).single().status)

            val assistantId = database.insertMessage(
                conversationId = 1,
                role = OwlettMessageRole.ASSISTANT,
                content = "",
                status = OwlettMessageStatus.COMPLETE,
                toolCallsJson = """[{"id":"call-1","name":"lookup_bird","argumentsJson":"{}"}]"""
            )
            database.insertSkillRun(
                conversationId = 1,
                assistantMessageId = assistantId,
                toolCallId = "call-1",
                skillId = "lookup_bird",
                argumentsJson = "{}",
                status = OwlettSkillRunStatus.EXECUTING
            )
            database.repairInterruptedMessages()

            val run = database.listSkillRuns(1).single()
            assertEquals(OwlettSkillRunStatus.INTERRUPTED, run.status)
            assertTrue(run.resultJson.orEmpty().contains("Skill interrupted"))

            database.finishSkillRun(
                runId = run.id,
                conversationId = 1,
                toolCallId = "call-1",
                argumentsJson = "{}",
                resultJson = "{\"kind\":\"complete\"}",
                status = OwlettSkillRunStatus.COMPLETE,
                errorMessage = null,
                toolContent = "{\"status\":\"ok\"}"
            )
            database.finishSkillRun(
                runId = run.id,
                conversationId = 1,
                toolCallId = "call-1",
                argumentsJson = "{}",
                resultJson = "{\"kind\":\"complete\"}",
                status = OwlettSkillRunStatus.COMPLETE,
                errorMessage = null,
                toolContent = "{\"status\":\"duplicate\"}"
            )
            assertEquals(
                1,
                database.listMessages(1).count {
                    it.role == OwlettMessageRole.TOOL && it.toolCallId == "call-1"
                }
            )
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun xenoCantoKeyUsesAnIndependentEncryptedStore() {
        val suffix = UUID.randomUUID().toString()
        val deepSeekPrefs = "deepseek_independent_$suffix"
        val xenoPrefs = "xeno_independent_$suffix"
        val deepSeek = OwlettApiKeyStore(context, deepSeekPrefs, "deepseek_independent_key_$suffix")
        val xenoCanto = XenoCantoApiKeyStore(context, xenoPrefs, "xeno_independent_key_$suffix")
        try {
            deepSeek.clear()
            xenoCanto.clear()
            deepSeek.save("deepseek-secret")
            xenoCanto.save("xeno-secret")

            assertEquals("deepseek-secret", deepSeek.load())
            assertEquals("xeno-secret", xenoCanto.load())
            xenoCanto.clear()
            assertEquals("deepseek-secret", deepSeek.load())
            assertNull(xenoCanto.load())
        } finally {
            deepSeek.clear()
            xenoCanto.clear()
            context.getSharedPreferences(deepSeekPrefs, Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences(xenoPrefs, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun v3PendingOperationsNeedNewPreparationAndTasksCascade() {
        val name = "owlett_v3_${UUID.randomUUID()}.db"
        try {
            OwlettChatDatabase(context, name).use { database ->
                val conversation = database.createConversation("待确认")
                val user = database.insertMessage(conversation, OwlettMessageRole.USER, "修改计划")
                val assistant = database.insertMessage(conversation, OwlettMessageRole.ASSISTANT, "预览")
                database.startAgentTask(user, "{}")
                database.insertSkillRun(conversation, assistant, "tool-1", "plans_update", "{}",
                    status = OwlettSkillRunStatus.WAITING_CONFIRMATION, taskMessageId = user)
                database.writableDatabase.version = 3
            }
            OwlettChatDatabase(context, name).use { database ->
                assertEquals(4, database.writableDatabase.version)
                val conversation = database.listConversations().single().id
                assertEquals(OwlettSkillRunStatus.INTERRUPTED, database.listSkillRuns(conversation).single().status)
                database.repairInterruptedMessages()
                database.readableDatabase.rawQuery("SELECT status FROM agent_tasks", null).use {
                    assertTrue(it.moveToFirst()); assertEquals("interrupted", it.getString(0))
                }
                database.deleteConversation(conversation)
                assertTrue(database.listSkillRuns(conversation).isEmpty())
                database.readableDatabase.rawQuery("SELECT COUNT(*) FROM agent_tasks", null).use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    private class LegacyV1ChatHelper(
        context: Context,
        databaseName: String
    ) : SQLiteOpenHelper(context, databaseName, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE conversations (id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,manually_renamed INTEGER NOT NULL DEFAULT 0,created_at_ms INTEGER NOT NULL,updated_at_ms INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT,conversation_id INTEGER NOT NULL,role TEXT NOT NULL,content TEXT NOT NULL,attachment_json TEXT,attachment_label TEXT,model_id TEXT,status TEXT NOT NULL,error_message TEXT,created_at_ms INTEGER NOT NULL,updated_at_ms INTEGER NOT NULL,FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE)"
            )
            db.execSQL("CREATE INDEX messages_conversation_time ON messages(conversation_id, created_at_ms, id)")
            db.execSQL("CREATE INDEX conversations_updated_time ON conversations(updated_at_ms DESC)")
            db.execSQL("INSERT INTO conversations VALUES (1,'Legacy',0,1,1)")
            db.execSQL("INSERT INTO messages VALUES (1,1,'assistant','partial',NULL,NULL,NULL,'streaming',NULL,1,1)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
