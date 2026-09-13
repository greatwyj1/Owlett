package com.example.birdingsoundmvp.owlett

import com.example.birdingsoundmvp.settings.AppSettings
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class OwlettSettingsConfirmationTest {
    private fun patch(json: String) = JsonParser.parseString(json).asJsonObject
    private val original = AppSettings()
    // Old stored proposals must work without requiring users to recreate the conversation.
    private fun storedBefore(settings: AppSettings = original) =
        patch(Gson().toJson(OwlettSettingsPolicy.publicValues(settings)))

    @Test fun defaultFloatSnapshotPreviouslyLookedLikeAConcurrentEdit() {
        val liveTree = Gson().toJsonTree(OwlettSettingsPolicy.publicValues(original))
        assertNotEquals(liveTree, storedBefore())
        val next = OwlettSettingsPolicy.applyConfirmed(original, patch("""{"appearance":"dark"}"""), storedBefore())
        assertEquals("dark", next!!.appearance)
    }

    @Test fun persistedDecimalAndBatchUpdateApplyOnFirstConfirmation() {
        val next = OwlettSettingsPolicy.applyConfirmed(original,
            patch("""{"minimumAudioConfidence":0.3,"showSpectrogram":false}"""), storedBefore())!!
        assertEquals(0.3f, next.minimumAudioConfidence)
        assertFalse(next.showSpectrogram)
    }

    @Test fun unrelatedEditsArePreserved() {
        val concurrent = original.copy(appearance = "dark", ebirdApiKey = "private")
        val next = OwlettSettingsPolicy.applyConfirmed(concurrent,
            patch("""{"minimumAudioConfidence":0.3}"""), storedBefore())!!
        assertEquals("dark", next.appearance)
        assertEquals("private", next.ebirdApiKey)
    }

    @Test fun changedApprovedFieldInvalidatesWholeBatch() {
        assertNull(OwlettSettingsPolicy.applyConfirmed(original.copy(minimumAudioConfidence = 0.7f),
            patch("""{"minimumAudioConfidence":0.3,"appearance":"dark"}"""), storedBefore()))
    }

    @Test fun alreadyAppliedAssignmentCanBeReplayed() {
        val changes = patch("""{"minimumAudioConfidence":0.3}""")
        val next = OwlettSettingsPolicy.applyConfirmed(original, changes, storedBefore())!!
        assertEquals(next, OwlettSettingsPolicy.applyConfirmed(next, changes, storedBefore()))
    }

    @Test fun incompleteApprovalCannotModifySettings() {
        val changes = patch("""{"appearance":"dark"}""")
        assertNull(OwlettSettingsPolicy.applyConfirmed(original, changes, null))
        assertNull(OwlettSettingsPolicy.applyConfirmed(original, changes, patch("{}")))
        assertTrue(runCatching { OwlettSettingsPolicy.applyConfirmed(original,
            patch("""{"owlettAutomationMode":"automatic"}"""), storedBefore()) }.isFailure)
    }
}
