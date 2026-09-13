package com.example.birdingsoundmvp.owlett

import com.example.birdingsoundmvp.i18n.AppText

import android.content.Context
import com.example.birdingsoundmvp.birdnet.DetectionResult
import com.example.birdingsoundmvp.birdnet.speciesKey
import com.example.birdingsoundmvp.planning.PlansTripsRepository
import com.example.birdingsoundmvp.trip.TripHistoryRepository
import com.google.gson.Gson

class OwlettPlanAttachmentBuilder(
    context: Context,
    private val plansRepository: PlansTripsRepository = PlansTripsRepository(context),
    private val tripHistoryRepository: TripHistoryRepository = TripHistoryRepository(context),
    private val gson: Gson = Gson()
) {
    fun listPlans(): List<OwlettPlanPickerItem> = plansRepository.listPlans().map { plan ->
        OwlettPlanPickerItem(
            id = plan.id,
            name = plan.name,
            plannedDate = plan.plannedDate,
            hotspotName = plan.hotspotName
        )
    }

    fun listTrips(): List<OwlettTripPickerItem> = tripHistoryRepository.listTrips().filter { it.isCompleted }.map {
        OwlettTripPickerItem(it.tripId, tripLabel(it), it.startedAtMs, it.durationMs, it.detectionCount)
    }

    fun buildTrip(tripId: String): String {
        val trip = tripHistoryRepository.listTrips().singleOrNull { it.tripId == tripId && it.isCompleted }
            ?: throw PlanAttachmentUnavailableException("录音行程已删除或尚未完成，请重新选择。")
        val species = OwlettPlanAttachmentLogic.aggregateRecognizedSpecies(listOf(tripId to tripHistoryRepository.loadDetections(tripId)))
        return gson.toJson(mapOf("kind" to "trip", "tripId" to tripId, "startedAtMs" to trip.startedAtMs,
            "endedAtMs" to trip.endedAtMs, "durationMs" to trip.durationMs, "recognizedSpecies" to species.take(100),
            "recognizedSpeciesTotal" to species.size, "recognizedSpeciesOmitted" to (species.size - 100).coerceAtLeast(0),
            "linkedPlans" to plansRepository.listPlans().filter { plan -> plansRepository.listPlanTripLinks(plan.id).any { it.tripId == tripId } }
                .map { mapOf("id" to it.id, "name" to it.name, "date" to it.plannedDate, "hotspot" to it.hotspotName) }))
    }

    fun build(planId: Long): PlanAttachmentSnapshot {
        val trips = tripHistoryRepository.listTrips()
        val detail = plansRepository.loadPlanDetail(planId, trips)
            ?: throw PlanAttachmentUnavailableException(AppText.get("The selected Plan no longer exists"))
        val completedTrips = detail.linkedTrips.filter { it.isCompleted }

        val likely = detail.speciesStats
            .sortedWith(compareByDescending<com.example.birdingsoundmvp.planning.PlanSpeciesStat> { it.combinedFrequency }
                .thenBy { it.displayName.lowercase() })
        val rare = detail.rareObservations
            .sortedWith(compareByDescending<com.example.birdingsoundmvp.planning.PlanRareObservation> { it.observedAt }
                .thenBy { it.displayName.lowercase() })
        val recognized = OwlettPlanAttachmentLogic.aggregateRecognizedSpecies(completedTrips.map { trip ->
            trip.tripId to tripHistoryRepository.loadDetections(trip.tripId)
        })

        return PlanAttachmentSnapshot(
            generatedAtMs = System.currentTimeMillis(),
            planId = detail.plan.id,
            planName = detail.plan.name,
            plannedDate = detail.plan.plannedDate,
            regionCode = detail.plan.regionCode,
            hotspot = PlanAttachmentHotspot(
                id = detail.plan.hotspotId,
                name = detail.plan.hotspotName,
                latitude = detail.plan.hotspotLatitude,
                longitude = detail.plan.hotspotLongitude
            ),
            expectedSpecies = detail.expectedSpecies.map { species ->
                PlanAttachmentSpecies(
                    speciesCode = species.speciesCode,
                    scientificName = species.scientificName,
                    commonName = species.commonName,
                    displayName = species.displayName
                )
            },
            likelySpecies = likely.take(MAX_LIKELY_SPECIES).map { species ->
                PlanAttachmentLikelySpecies(
                    speciesCode = species.speciesCode,
                    scientificName = species.scientificName,
                    commonName = species.commonName,
                    displayName = species.displayName,
                    historicalFrequency = species.historicalFrequency,
                    currentFrequency = species.currentFrequency,
                    combinedFrequency = species.combinedFrequency
                )
            },
            rareObservations = rare.take(MAX_RARE_OBSERVATIONS).map { observation ->
                PlanAttachmentRareObservation(
                    speciesCode = observation.speciesCode,
                    scientificName = observation.scientificName,
                    commonName = observation.commonName,
                    displayName = observation.displayName,
                    observedAt = observation.observedAt,
                    locationName = observation.locationName,
                    count = observation.count,
                    provisional = observation.provisional
                )
            },
            linkedCompletedTrips = completedTrips.map { trip ->
                PlanAttachmentTrip(
                    tripId = trip.tripId,
                    startedAtMs = trip.startedAtMs,
                    endedAtMs = trip.endedAtMs,
                    durationMs = trip.durationMs,
                    detectionCount = trip.detectionCount,
                    uniqueSpeciesCount = trip.uniqueSpeciesCount
                )
            },
            recognizedSpecies = recognized.take(MAX_RECOGNIZED_SPECIES),
            likelySpeciesTotal = likely.size,
            likelySpeciesOmitted = (likely.size - MAX_LIKELY_SPECIES).coerceAtLeast(0),
            rareObservationsTotal = rare.size,
            rareObservationsOmitted = (rare.size - MAX_RARE_OBSERVATIONS).coerceAtLeast(0),
            recognizedSpeciesTotal = recognized.size,
            recognizedSpeciesOmitted = (recognized.size - MAX_RECOGNIZED_SPECIES).coerceAtLeast(0),
            analysisAvailable = detail.plan.hasAnalysis,
            regionName = detail.plan.regionName,
            observationSource = detail.plan.sourceId,
            recentObservationKind = if (detail.plan.sourceId == "ebird") "notable" else "recent_observations_not_confirmed_rare"
        )
    }

    fun toJson(snapshot: PlanAttachmentSnapshot): String = gson.toJson(snapshot)

    fun close() = plansRepository.close()

    companion object {
        const val MAX_LIKELY_SPECIES = 50
        const val MAX_RARE_OBSERVATIONS = 20
        const val MAX_RECOGNIZED_SPECIES = 100
    }
}

internal object OwlettPlanAttachmentLogic {
    fun aggregateRecognizedSpecies(
        detectionsByTrip: List<Pair<String, List<DetectionResult>>>
    ): List<PlanAttachmentRecognizedSpecies> {
        val aggregates = linkedMapOf<String, RecognitionAggregate>()
        detectionsByTrip.forEach { (tripId, detections) ->
            detections.forEach { detection ->
                val key = detection.speciesKey()
                if (key.isBlank()) return@forEach
                val aggregate = aggregates.getOrPut(key) {
                    RecognitionAggregate(
                        scientificName = detection.scientificName,
                        commonName = detection.commonName,
                        displayName = detection.displayNameZh?.takeIf(String::isNotBlank)
                            ?: detection.commonName.ifBlank { detection.scientificName }
                    )
                }
                aggregate.maxConfidence = maxOf(aggregate.maxConfidence, detection.audioConfidence)
                aggregate.sources += detection.source?.takeIf(String::isNotBlank) ?: "realtime"
                aggregate.tripIds += tripId
            }
        }
        return aggregates.values
            .map { aggregate ->
                PlanAttachmentRecognizedSpecies(
                    scientificName = aggregate.scientificName,
                    commonName = aggregate.commonName,
                    displayName = aggregate.displayName,
                    maxConfidence = aggregate.maxConfidence,
                    sources = aggregate.sources,
                    tripCount = aggregate.tripIds.size
                )
            }
            .sortedWith(compareByDescending<PlanAttachmentRecognizedSpecies> { it.maxConfidence }
                .thenBy { it.displayName.lowercase() })
    }

    private data class RecognitionAggregate(
        val scientificName: String,
        val commonName: String,
        val displayName: String,
        var maxConfidence: Float = 0f,
        val sources: MutableSet<String> = linkedSetOf(),
        val tripIds: MutableSet<String> = linkedSetOf()
    )

}

class PlanAttachmentUnavailableException(message: String) : IllegalStateException(message)
