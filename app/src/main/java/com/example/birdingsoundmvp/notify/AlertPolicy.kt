package com.example.birdingsoundmvp.notify

data class AlertPolicy(
    val scientificName: String,
    val commonName: String,
    val displayNameZh: String? = null,
    val threshold: Float,
    val minHits: Int,
    val windowSec: Int,
    val cooldownSec: Int,
    val alert: String = "vibrate"
) {
    fun matches(scientificName: String, commonName: String): Boolean {
        val scientificMatches = this.scientificName.isNotBlank() &&
            this.scientificName.equals(scientificName, ignoreCase = true)
        val commonMatches = this.commonName.isNotBlank() &&
            this.commonName.equals(commonName, ignoreCase = true)
        return scientificMatches || commonMatches
    }

    fun stableKey(): String = when {
        scientificName.isNotBlank() -> scientificName.lowercase()
        else -> commonName.lowercase()
    }
}

data class TestChecklist(
    val species: List<AlertPolicy> = emptyList()
)
