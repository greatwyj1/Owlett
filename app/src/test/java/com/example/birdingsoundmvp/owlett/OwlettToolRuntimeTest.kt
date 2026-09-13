package com.example.birdingsoundmvp.owlett

import com.example.birdingsoundmvp.settings.AppSettings
import com.example.birdingsoundmvp.planning.RecentObservationLabel
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OwlettToolRuntimeTest {
    @Test fun scenesHaveInstructionsAndNeverExposeAtomicCommands() {
        assertEquals(9, OwlettSceneSkills.all.size)
        assertEquals(setOf("/plan", "/activity", "/targets", "/bird", "/calls", "/link", "/clips", "/settle", "/settings"),
            OwlettSceneSkills.all.map { it.descriptor.slashCommand }.toSet())
        OwlettSceneSkills.all.forEach {
            assertTrue(it.instructions().lineSequence().first().endsWith("v${it.version}"))
        }
        assertTrue(OwlettSceneSkills.allowed(null, false)!!.isEmpty())
        assertNull(OwlettSceneSkills.allowed(null, true))
        assertTrue("plans_create" in OwlettSceneSkills.allowed("create_plan", false)!!)
        assertFalse("settings_update" in OwlettSceneSkills.allowed("create_plan", false)!!)
    }

    @Test fun settingsAllowlistDoesNotLeakOrModifyCredentialsAndPermissions() {
        val original = AppSettings(ebirdApiKey = "private", preciseRecognitionAuthToken = "private", owlettAutomationMode = "automatic")
        assertFalse(OwlettSettingsPolicy.publicValues(original).keys.any { it.contains("key", true) || it.contains("token", true) || it.contains("automation", true) })
        val updated = OwlettSettingsPolicy.apply(original, JsonParser.parseString("""{"appearance":"dark","minimumAudioConfidence":0.3}""").asJsonObject)
        assertEquals("dark", updated.appearance)
        assertEquals(0.3f, updated.minimumAudioConfidence)
        assertEquals("private", updated.ebirdApiKey)
        assertEquals("automatic", updated.owlettAutomationMode)
        for (patch in listOf("""{"ebirdApiKey":"x"}""", """{"owlettAutomationMode":"automatic"}""", """{"minimumAudioConfidence":3}""",
            """{"useMetaModel":"true"}""", """{"maxSelectionDurationSec":60}""", """{"owlettModelId":"unknown"}""")) {
            assertTrue(runCatching { OwlettSettingsPolicy.apply(original, JsonParser.parseString(patch).asJsonObject) }.isFailure)
        }
    }

    @Test fun nestedPrivateAndUnknownArgumentsCannotBeForged() {
        val schema = """{"type":"object","properties":{"changes":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}}},"required":["changes"]}"""
        OwlettToolParameters.validate("""{"changes":[{"name":"A"}]}""", schema)
        for (args in listOf("""{"changes":[{"name":"A","_location":{}}]}""", """{"changes":[{"name":3}]}""", "{}", """{"changes":[],"sql":"DELETE"}""")) {
            assertTrue(runCatching { OwlettToolParameters.validate(args, schema) }.isFailure)
        }
    }

    @Test fun allWriteToolsGoThroughCentralConfirmation() = runBlocking {
        val tool = FakeTool()
        val executor = OwlettToolExecutor(listOf(tool))
        val context = OwlettSkillContext("op", null, "", null)
        assertTrue(executor.prepare(tool, "{}", context) is OwlettSkillPreparation.WaitingConfirmation)
        assertTrue(runCatching { executor.execute(tool, "{}", context) }.isFailure)
        assertEquals(0, tool.executions)
        executor.execute(tool, "{}", context.copy(writeAuthorized = true))
        executor.execute(tool, "{}", context.copy(automatic = true))
        assertEquals(2, tool.executions)
        assertTrue(runCatching { executor.prepare(tool, "{}", context.copy(allowedToolIds = emptySet())) }.isFailure)
    }

    @Test fun repeatedFailuresStopButSuccessResetsStreak() {
        val guard = OwlettFailureGuard()
        assertFalse(guard.record("query", "timeout"))
        assertFalse(guard.record("query", "timeout"))
        assertTrue(guard.record("query", "timeout"))
        assertFalse(guard.record("query", null))
        assertFalse(guard.record("query", "timeout"))
        assertEquals(24, OwlettAgentRunner.DEFAULT_MAX_TOOL_CALLS)
    }

    @Test fun turnConfigurationFreezesModelPermissionsAndSkillInstructions() {
        val config = OwlettTurnConfig.from(AppSettings(owlettModelId = "deepseek-v4-pro", owlettAutomaticSkillsEnabled = false))
        val restored = OwlettTurnConfig.decode(config.toJson())
        assertEquals(config, restored)
        assertFalse(restored.automaticSkillsEnabled)
        assertEquals("deepseek-v4-pro", restored.modelId)
        assertEquals(9, restored.skillInstructions.size)
        assertFalse(OwlettTurnConfig.decode("{}").automatic)
    }

    @Test fun historicalLowFrequencyRequiresSufficientPositiveSamples() {
        assertEquals("历史样本不足", RecentObservationLabel.label(19, 0.01f))
        assertEquals("历史样本未记录", RecentObservationLabel.label(20, 0f))
        assertEquals("历史低频", RecentObservationLabel.label(20, 0.05f))
        assertEquals("近期记录", RecentObservationLabel.label(20, 0.051f))
    }

    private class FakeTool : OwlettTool {
        var executions = 0
        override val descriptor = OwlettSkillDescriptor("write", "", "修改", "测试", true)
        override val toolDefinition = OwlettToolDefinition("write", "test", """{"type":"object","properties":{}}""")
        override suspend fun prepare(argumentsJson: String, context: OwlettSkillContext) = OwlettSkillPreparation.Ready(argumentsJson)
        override suspend fun execute(argumentsJson: String, context: OwlettSkillContext): OwlettSkillExecution {
            executions++
            return OwlettSkillExecution("{}", OwlettSkillCardPayload("test", "test"))
        }
    }
}
