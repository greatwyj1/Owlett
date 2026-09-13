package com.example.birdingsoundmvp.notify

import com.example.birdingsoundmvp.i18n.AppText

import android.content.Context
import android.util.Log
import com.example.birdingsoundmvp.birdnet.SpeciesPrediction
import com.google.gson.Gson
import java.util.ArrayDeque

class AlertPolicyEngine(
    private val policies: List<AlertPolicy>
) {
    private val hitsBySpecies = mutableMapOf<String, ArrayDeque<Double>>()
    private val lastAlertSecBySpecies = mutableMapOf<String, Double>()

    fun findPolicy(prediction: SpeciesPrediction): AlertPolicy? {
        return policies.firstOrNull {
            it.matches(prediction.scientificName, prediction.commonName)
        }
    }

    fun shouldAlert(
        prediction: SpeciesPrediction,
        audioEndSec: Double
    ): Boolean {
        val policy = findPolicy(prediction) ?: return false
        if (prediction.audioConfidence < policy.threshold) return false

        val key = policy.stableKey()
        val hits = hitsBySpecies.getOrPut(key) { ArrayDeque() }
        hits.addLast(audioEndSec)

        val windowStart = audioEndSec - policy.windowSec
        while (hits.isNotEmpty() && hits.first() < windowStart) {
            hits.removeFirst()
        }

        if (hits.size < policy.minHits) return false

        val lastAlertSec = lastAlertSecBySpecies[key]
        if (lastAlertSec != null && audioEndSec - lastAlertSec < policy.cooldownSec) {
            return false
        }

        lastAlertSecBySpecies[key] = audioEndSec
        return true
    }

    fun reset() {
        hitsBySpecies.clear()
        lastAlertSecBySpecies.clear()
    }

    companion object {
        private const val TAG = "AlertPolicyEngine"
        private const val CHECKLIST_ASSET = "test_checklist.json"

        fun fromAssets(context: Context): Pair<AlertPolicyEngine, String> {
            return runCatching {
                val json = context.assets.open(CHECKLIST_ASSET).bufferedReader().use { it.readText() }
                val checklist = Gson().fromJson(json, TestChecklist::class.java) ?: TestChecklist()
                AlertPolicyEngine(checklist.species) to AppText.format("Checklist loaded: {0} species", checklist.species.size)
            }.getOrElse { throwable ->
                Log.e(TAG, AppText.get("Failed to load checklist"), throwable)
                AlertPolicyEngine(emptyList()) to AppText.format("Checklist load failed: {0}", throwable.message)
            }
        }
    }
}
