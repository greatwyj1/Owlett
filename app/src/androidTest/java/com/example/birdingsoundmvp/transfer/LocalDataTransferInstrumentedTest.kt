package com.example.birdingsoundmvp.transfer

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.birdingsoundmvp.owlett.*
import com.example.birdingsoundmvp.planning.*
import com.example.birdingsoundmvp.settings.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalDataTransferInstrumentedTest {
    private class Isolated(base: Context, val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName() = "io.github.greatwyj1.owlett"
        override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
        override fun getExternalFilesDir(type: String?) = File(root, "external").apply { mkdirs() }
        override fun getDatabasePath(name: String) = File(root, "db/$name").apply { parentFile!!.mkdirs() }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?) =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
    }

    @Test fun roundTripKeepsIdsLinksClipsAndExcludesKeys() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "transfer-test-" + UUID.randomUUID()).apply { mkdirs() }
        val source = Isolated(base, File(root, "source"))
        val target = Isolated(base, File(root, "target"))
        var restored = AppSettings()
        val options = AppSettings(owlettSystemPrompt = "fixture persona", ebirdApiKey = "fixture-secret", preciseRecognitionAuthToken = "fixture-token", owlettAutomationMode = "automatic")
        fun transfer(ctx: Context) = LocalDataTransfer(ctx, { options }, { restored = it }, {})
        try {
            val plans = PlansTripsRepository(source)
            val id = plans.createPlan("Fixture", "2026-09-06", "CN-11", EbirdHotspotMatch("L1", "Fixture", 40.0, 116.0, "CN", "CN-11", null))
            assertTrue(plans.addPlanTripIdempotent("op1", id, "trip1"))
            assertFalse(plans.addPlanTripIdempotent("op1", id, "trip1"))
            assertFalse(plans.addPlanTripIdempotent("op2", id, "trip1"))
            plans.close()
            OwlettChatDatabase(source).use { db ->
                val conversation = db.createConversation("Fixture", 1)
                val user = db.insertMessage(conversation, OwlettMessageRole.USER, "fixture", turnConfigJson = OwlettTurnConfig.from(options).toJson())
                val assistant = db.insertMessage(conversation, OwlettMessageRole.ASSISTANT, "", status = OwlettMessageStatus.STREAMING)
                db.insertSkillRun(conversation, assistant, "call1", "link_trip", "{}", status = OwlettSkillRunStatus.WAITING_CONFIRMATION)
                assertTrue(user > 0)
            }
            val trip = File(source.getExternalFilesDir(null), "trips/trip1").apply { mkdirs() }
            File(trip, "audio.wav").writeBytes(byteArrayOf(1, 2, 3, 4))
            File(trip, "metadata.json").writeText("""{"tripId":"trip1","segments":[{"filePath":"${File(trip, "audio.wav").absolutePath}"}]}""")
            val archive = File(root, "private.zip")
            transfer(source).export(Uri.fromFile(archive)) { _, _ -> }
            ZipFile(archive).use { zip ->
                val config = zip.getInputStream(zip.getEntry("settings.json")).bufferedReader().readText()
                assertFalse(config.contains("fixture-secret")); assertFalse(config.contains("fixture-token"))
                assertFalse(zip.entries().toList().any { it.name.contains("secure_settings") || it.name.endsWith(".tflite") })
            }
            transfer(target).import(Uri.fromFile(archive)) { _, _ -> }
            val restoredPlans = PlansTripsRepository(target)
            try {
                val repository = restoredPlans
                assertEquals(id, repository.listPlans().single().id)
                assertEquals("trip1", repository.listPlanTripLinks(id).single().tripId)
            } finally { restoredPlans.close() }
            OwlettChatDatabase(target).use { db ->
                val conversation = db.listConversations().single()
                assertEquals(OwlettMessageStatus.STOPPED, db.listMessages(conversation.id).last().status)
                assertEquals(OwlettSkillRunStatus.INTERRUPTED, db.listSkillRuns(conversation.id).single().status)
                assertFalse(OwlettTurnConfig.decode(db.listMessages(conversation.id).first().turnConfigJson).automatic)
            }
            assertEquals("fixture persona", restored.owlettSystemPrompt)
            assertEquals("confirm_writes", restored.owlettAutomationMode)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), File(target.getExternalFilesDir(null), "trips/trip1/audio.wav").readBytes())
            assertTrue(File(target.getExternalFilesDir(null), "trips/trip1/metadata.json").readText().contains(target.getExternalFilesDir(null).absolutePath))
            assertTrue(runCatching { transfer(target).import(Uri.fromFile(archive)) { _, _ -> } }.isFailure)
        } finally { root.deleteRecursively() }
    }

    @Test fun startupJournalRollsBackIncompleteImport() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "rollback-test-" + UUID.randomUUID())
        val context = Isolated(base, root)
        try {
            OwlettChatDatabase(context).use { it.createConversation("incomplete") }
            File(context.noBackupFilesDir, "transfer-importing").writeText("importing")
            File(context.getExternalFilesDir(null), "trips/partial").mkdirs()
            LocalDataTransfer(context, { AppSettings() }, {}, {}).let { try { it.recover() } finally { it.close() } }
            OwlettChatDatabase(context).use { assertTrue(it.listConversations().isEmpty()) }
            assertFalse(File(context.noBackupFilesDir, "transfer-importing").exists())
            assertFalse(File(context.getExternalFilesDir(null), "trips").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun damagedArchiveFailsBeforeWritingData() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "damaged-test-" + UUID.randomUUID()).apply { mkdirs() }
        val source = Isolated(base, File(root, "source"))
        val target = Isolated(base, File(root, "target"))
        try {
            val archive = File(root, "valid.zip")
            LocalDataTransfer(source, { AppSettings() }, {}, {}).export(Uri.fromFile(archive)) { _, _ -> }
            val damaged = File(root, "damaged.zip")
            ZipFile(archive).use { input ->
                ZipOutputStream(damaged.outputStream()).use { output ->
                    input.entries().toList().forEach { entry ->
                        output.putNextEntry(ZipEntry(entry.name))
                        if (entry.name == "settings.json") output.write("corrupt".toByteArray())
                        else input.getInputStream(entry).use { it.copyTo(output) }
                        output.closeEntry()
                    }
                }
            }
            val result = runCatching {
                LocalDataTransfer(target, { AppSettings() }, { fail("Must not write settings") }, { fail("Must not clear keys") })
                    .import(Uri.fromFile(damaged)) { _, _ -> }
            }
            assertTrue(result.isFailure)
            OwlettChatDatabase(target).use { assertTrue(it.listConversations().isEmpty()) }
            assertFalse(File(target.noBackupFilesDir, "transfer-importing").exists())
            assertFalse(File(target.noBackupFilesDir, "transfer-stage").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun settingsWriteFailureRollsBackRecordsAndOriginalSettings() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "settings-rollback-test-" + UUID.randomUUID()).apply { mkdirs() }
        val source = Isolated(base, File(root, "source"))
        val target = Isolated(base, File(root, "target"))
        val original = AppSettings(owlettSystemPrompt = "original", ebirdApiKey = "fixture-key")
        var restored: AppSettings? = null
        var writes = 0
        try {
            OwlettChatDatabase(source).use { it.createConversation("fixture") }
            val archive = File(root, "backup.zip")
            LocalDataTransfer(source, { AppSettings() }, {}, {}).export(Uri.fromFile(archive)) { _, _ -> }
            val result = runCatching {
                LocalDataTransfer(target, { original }, {
                    if (++writes == 1) error("Simulated settings failure")
                    restored = it
                }, { fail("Must not clear keys") }).import(Uri.fromFile(archive)) { _, _ -> }
            }
            assertTrue(result.isFailure)
            assertEquals(original, restored)
            OwlettChatDatabase(target).use { assertTrue(it.listConversations().isEmpty()) }
            assertFalse(File(target.noBackupFilesDir, "transfer-importing").exists())
        } finally { root.deleteRecursively() }
    }
}
