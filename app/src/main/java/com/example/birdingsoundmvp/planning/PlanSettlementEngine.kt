package com.example.birdingsoundmvp.planning

import com.example.birdingsoundmvp.birdnet.DetectionResult

data class TripDetectionSet(
    val tripId: String,
    val detections: List<DetectionResult>
)

object PlanSettlementEngine {
    fun build(
        planId: Long,
        expectedSpecies: List<PlanExpectedSpecies>,
        tripDetections: List<TripDetectionSet>,
        overrides: List<PlanSettlementOverride>
    ): List<PlanSettlementSpecies> {
        val expectedByKey = normalizedExpectedSpecies(expectedSpecies)
        val identityByAlias = mutableMapOf<String, SpeciesIdentity>()
        expectedByKey.values.forEach { identity ->
            identity.aliases.forEach { identityByAlias.putIfAbsent(it, identity) }
        }
        overrides.forEach { override ->
            val identity = SpeciesIdentity.from(override)
            identity.aliases.forEach { identityByAlias.putIfAbsent(it, identity) }
        }

        val detectedByKey = mutableMapOf<String, DetectedAggregate>()
        tripDetections.forEach { trip ->
            trip.detections.forEach { detection ->
                val aliases = speciesAliases(
                    speciesKey = "",
                    speciesCode = "",
                    scientificName = detection.scientificName,
                    commonName = detection.commonName
                )
                val knownIdentity = aliases.firstNotNullOfOrNull(identityByAlias::get)
                val key = knownIdentity?.speciesKey
                    ?: canonicalSpeciesKey("", detection.scientificName, detection.commonName)
                if (key.isBlank()) return@forEach
                val aggregate = detectedByKey.getOrPut(key) {
                    DetectedAggregate(
                        identity = knownIdentity ?: SpeciesIdentity(
                            speciesKey = key,
                            speciesCode = "",
                            scientificName = detection.scientificName,
                            commonName = detection.commonName,
                            displayNameZh = detection.displayNameZh
                        )
                    )
                }
                aggregate.maxConfidence = maxOf(aggregate.maxConfidence, detection.audioConfidence)
                aggregate.tripIds += trip.tripId
                aggregate.sources += detection.source?.takeIf { it.isNotBlank() } ?: "realtime"
                if (aggregate.identity.displayNameZh.isNullOrBlank() && !detection.displayNameZh.isNullOrBlank()) {
                    aggregate.identity = aggregate.identity.copy(displayNameZh = detection.displayNameZh)
                }
            }
        }

        val excludedKeys = overrides
            .filter { it.action == PlanSettlementOverrideAction.EXCLUDE }
            .map { override ->
                SpeciesIdentity.from(override).aliases
                    .firstNotNullOfOrNull(identityByAlias::get)
                    ?.speciesKey
                    ?: override.speciesKey
            }
            .toSet()
        excludedKeys.forEach(detectedByKey::remove)

        val manualByKey = overrides
            .filter { it.action == PlanSettlementOverrideAction.MANUAL_ADD }
            .associate { override ->
                val overrideIdentity = SpeciesIdentity.from(override)
                val expectedIdentity = overrideIdentity.aliases
                    .firstNotNullOfOrNull(identityByAlias::get)
                    ?.takeIf { it.speciesKey in expectedByKey }
                val identity = expectedIdentity?.let { expected ->
                    expected.copy(displayNameZh = overrideIdentity.displayNameZh ?: expected.displayNameZh)
                } ?: overrideIdentity
                identity.speciesKey to identity
            }
        val observedKeys = detectedByKey.keys + manualByKey.keys
        val allKeys = expectedByKey.keys + observedKeys

        return allKeys.mapNotNull { key ->
            val expected = expectedByKey[key]
            val detected = detectedByKey[key]
            val manual = manualByKey[key]
            val identity = detected?.identity
                ?: manual
                ?: expected
                ?: return@mapNotNull null
            val observed = detected != null || manual != null
            val category = when {
                expected != null && observed -> PlanSettlementCategory.HIT
                expected != null -> PlanSettlementCategory.MISSED
                else -> PlanSettlementCategory.EXTRA
            }
            val origin = when {
                detected != null && manual != null -> "app+manual"
                detected != null -> "app"
                manual != null -> "manual"
                else -> "expected"
            }
            PlanSettlementSpecies(
                planId = planId,
                speciesKey = key,
                speciesCode = identity.speciesCode,
                scientificName = identity.scientificName,
                commonName = identity.commonName,
                displayNameZh = identity.displayNameZh,
                category = category,
                origin = origin,
                maxConfidence = detected?.maxConfidence ?: 0f,
                tripCount = detected?.tripIds?.size ?: 0,
                detectionSources = detected?.sources.orEmpty()
            )
        }.sortedWith(
            compareBy<PlanSettlementSpecies> { it.category.ordinal }
                .thenByDescending { it.maxConfidence }
                .thenBy { it.displayName.lowercase() }
        )
    }

    private data class SpeciesIdentity(
        val speciesKey: String,
        val speciesCode: String,
        val scientificName: String,
        val commonName: String,
        val displayNameZh: String?
    ) {
        val aliases: Set<String>
            get() = speciesAliases(speciesKey, speciesCode, scientificName, commonName)

        companion object {
            fun from(species: PlanExpectedSpecies) = SpeciesIdentity(
                speciesKey = species.speciesKey,
                speciesCode = species.speciesCode,
                scientificName = species.scientificName,
                commonName = species.commonName,
                displayNameZh = species.displayNameZh
            )

            fun from(override: PlanSettlementOverride) = SpeciesIdentity(
                speciesKey = override.speciesKey,
                speciesCode = override.speciesCode,
                scientificName = override.scientificName,
                commonName = override.commonName,
                displayNameZh = override.displayNameZh
            )
        }
    }

    private data class DetectedAggregate(
        var identity: SpeciesIdentity,
        var maxConfidence: Float = 0f,
        val tripIds: MutableSet<String> = linkedSetOf(),
        val sources: MutableSet<String> = linkedSetOf()
    )

    private fun speciesAliases(
        speciesKey: String,
        speciesCode: String,
        scientificName: String,
        commonName: String
    ): Set<String> = speciesIdentityAliases(speciesKey, speciesCode, scientificName, commonName)

    private fun normalizedExpectedSpecies(
        expectedSpecies: List<PlanExpectedSpecies>
    ): Map<String, SpeciesIdentity> {
        val identities = linkedMapOf<String, SpeciesIdentity>()
        val keyByAlias = mutableMapOf<String, String>()
        expectedSpecies.sortedByDescending { if (it.speciesCode.isNotBlank()) 1 else 0 }.forEach { expected ->
            val incoming = SpeciesIdentity.from(expected)
            val existingKey = incoming.aliases.firstNotNullOfOrNull(keyByAlias::get)
            if (existingKey == null) {
                identities[incoming.speciesKey] = incoming
                incoming.aliases.forEach { keyByAlias[it] = incoming.speciesKey }
            } else {
                val existing = identities.getValue(existingKey)
                val merged = existing.copy(
                    speciesCode = existing.speciesCode.ifBlank { incoming.speciesCode },
                    scientificName = existing.scientificName.ifBlank { incoming.scientificName },
                    commonName = existing.commonName.ifBlank { incoming.commonName },
                    displayNameZh = existing.displayNameZh ?: incoming.displayNameZh
                )
                identities[existingKey] = merged
                merged.aliases.forEach { keyByAlias[it] = existingKey }
            }
        }
        return identities
    }
}
