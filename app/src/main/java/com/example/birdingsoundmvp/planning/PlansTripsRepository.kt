package com.example.birdingsoundmvp.planning

import com.example.birdingsoundmvp.i18n.AppText

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.example.birdingsoundmvp.trip.TripHistoryItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.LocalDate
import java.time.YearMonth

class PlansTripsRepository(
    context: Context,
    databaseName: String = BirdingDatabaseHelper.DEFAULT_DATABASE_NAME
) : PlanAnalysisStore {
    private val helper = BirdingDatabaseHelper(context.applicationContext, databaseName)
    private val gson = Gson()

    fun close() = helper.close()

    fun loadData(trips: List<TripHistoryItem>, storageBytes: Long): PlansTripsData {
        val plans = listPlans()
        val expectedCounts = listExpectedSpecies().groupingBy { it.planId }.eachCount()
        val tripIds = trips.map { it.tripId }.toSet()
        val links = listPlanTripLinks().filter { it.tripId in tripIds }
        val linkedTripCounts = links.groupingBy { it.planId }.eachCount()
        val settlements = listSettlements().associateBy { it.planId }
        val planById = plans.associateBy { it.id }
        val plansByTrip = links.groupBy { it.tripId }
        return PlansTripsData(
            plans = plans.map { plan ->
                PlanSummaryItem(
                    plan = plan,
                    expectedSpeciesCount = expectedCounts[plan.id] ?: 0,
                    linkedTripCount = linkedTripCounts[plan.id] ?: 0,
                    settlement = settlements[plan.id]
                )
            },
            trips = trips.map { trip ->
                ManagedTripItem(
                    trip = trip,
                    linkedPlans = plansByTrip[trip.tripId].orEmpty().mapNotNull { planById[it.planId] }
                )
            },
            storageBytes = storageBytes
        )
    }

    fun loadPlanDetail(planId: Long, trips: List<TripHistoryItem>): PlanDetailData? {
        val plan = getPlan(planId) ?: return null
        val linkedIds = listPlanTripLinks(planId).map { it.tripId }.toSet()
        return PlanDetailData(
            plan = plan,
            speciesStats = listSpeciesStats(planId),
            expectedSpecies = listExpectedSpecies(planId),
            rareObservations = listRareObservations(planId),
            linkedTrips = trips.filter { it.tripId in linkedIds },
            settlement = getSettlement(planId),
            settlementSpecies = listSettlementSpecies(planId),
            settlementOverrides = listSettlementOverrides(planId)
        )
    }

    fun listPlans(includeDeleted: Boolean = false): List<Plan> = helper.readableDatabase.rawQuery(
        """
        SELECT id, name, planned_date, region_code, hotspot_id, hotspot_name,
               hotspot_latitude, hotspot_longitude, created_at_ms, updated_at_ms,
               analysis_generated_at_ms, analysis_target_year, analysis_target_month,
               historical_requested_days, historical_successful_days, historical_active_days,
               current_requested_days, current_successful_days, current_active_days, region_name,
               source_id, source_location_json, statistic_kind, deleted_at_ms
        FROM plans ${if (includeDeleted) "" else "WHERE deleted_at_ms IS NULL"}
        ORDER BY planned_date DESC, updated_at_ms DESC
        """.trimIndent(),
        emptyArray()
    ).useRows(Cursor::toPlan)

    fun getPlan(planId: Long, includeDeleted: Boolean = false): Plan? = helper.readableDatabase.rawQuery(
        """
        SELECT id, name, planned_date, region_code, hotspot_id, hotspot_name,
               hotspot_latitude, hotspot_longitude, created_at_ms, updated_at_ms,
               analysis_generated_at_ms, analysis_target_year, analysis_target_month,
               historical_requested_days, historical_successful_days, historical_active_days,
               current_requested_days, current_successful_days, current_active_days, region_name,
               source_id, source_location_json, statistic_kind, deleted_at_ms
        FROM plans WHERE id = ? ${if (includeDeleted) "" else "AND deleted_at_ms IS NULL"} LIMIT 1
        """.trimIndent(),
        arrayOf(planId.toString())
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toPlan() else null }

    fun createPlan(
        name: String,
        plannedDate: String,
        regionCode: String,
        hotspot: EbirdHotspotMatch,
        regionName: String = regionCode
    ): Long {
        val now = System.currentTimeMillis()
        return helper.writableDatabase.insertOrThrow(
            "plans",
            null,
            ContentValues().apply {
                put("name", name.trim().ifBlank { AppText.get("Untitled plan") })
                put("planned_date", plannedDate)
                put("region_code", regionCode.trim().ifBlank { "CN" })
                put("region_name", regionName.trim().ifBlank { regionCode })
                put("hotspot_id", hotspot.locationId)
                put("hotspot_name", hotspot.name)
                put("source_id", hotspot.sourceId)
                put("source_location_json", hotspot.sourceLocationJson)
                putNullable("hotspot_latitude", hotspot.latitude)
                putNullable("hotspot_longitude", hotspot.longitude)
                put("created_at_ms", now)
                put("updated_at_ms", now)
            }
        )
    }

    fun updatePlan(
        planId: Long,
        name: String,
        plannedDate: String,
        regionCode: String,
        hotspot: EbirdHotspotMatch,
        regionName: String = regionCode
    ) {
        val previous = getPlan(planId) ?: return
        val analysisScopeChanged = previous.sourceId != hotspot.sourceId ||
            previous.sourceLocationJson != hotspot.sourceLocationJson || previous.hotspotId != hotspot.locationId ||
            targetMonth(previous.plannedDate) != targetMonth(plannedDate)
        val db = helper.writableDatabase
        db.inTransaction {
            update(
                "plans",
                ContentValues().apply {
                    put("name", name.trim().ifBlank { AppText.get("Untitled plan") })
                    put("planned_date", plannedDate)
                    put("region_code", regionCode.trim().ifBlank { "CN" })
                    put("region_name", regionName.trim().ifBlank { regionCode })
                    put("hotspot_id", hotspot.locationId)
                    put("hotspot_name", hotspot.name)
                    put("source_id", hotspot.sourceId)
                    put("source_location_json", hotspot.sourceLocationJson)
                    putNullable("hotspot_latitude", hotspot.latitude)
                    putNullable("hotspot_longitude", hotspot.longitude)
                    put("updated_at_ms", System.currentTimeMillis())
                    if (analysisScopeChanged) {
                        putNull("analysis_generated_at_ms")
                        putNull("analysis_target_year")
                        putNull("analysis_target_month")
                        put("historical_requested_days", 0)
                        put("historical_successful_days", 0)
                        put("historical_active_days", 0)
                        put("current_requested_days", 0)
                        put("current_successful_days", 0)
                        put("current_active_days", 0)
                    }
                },
                "id = ?",
                arrayOf(planId.toString())
            )
            if (analysisScopeChanged) {
                delete("plan_species_stats", "plan_id = ?", arrayOf(planId.toString()))
                delete("plan_rare_observations", "plan_id = ?", arrayOf(planId.toString()))
                delete(
                    "plan_expected_species",
                    "plan_id = ? AND source IN ('analysis', 'rare')",
                    arrayOf(planId.toString())
                )
                markSettlementStale(this, planId)
            }
        }
    }

    fun deletePlan(planId: Long) {
        val now = System.currentTimeMillis()
        helper.writableDatabase.update("plans", ContentValues().apply {
            put("deleted_at_ms", now)
            put("updated_at_ms", now)
        }, "id = ? AND deleted_at_ms IS NULL", arrayOf(planId.toString()))
    }

    fun restorePlan(planId: Long, now: Long = System.currentTimeMillis()) {
        val plan = getPlan(planId, includeDeleted = true) ?: error("计划已不存在")
        val deleted = plan.deletedAtMs ?: return
        check(now - deleted < TRASH_RETENTION_MS) { "计划已超过30天恢复期限" }
        helper.writableDatabase.inTransaction {
            update("plans", ContentValues().apply { putNull("deleted_at_ms"); put("updated_at_ms", now) },
                "id = ?", arrayOf(planId.toString()))
            markSettlementStale(this, planId)
        }
    }

    fun purgeExpiredPlans(now: Long = System.currentTimeMillis()): Int =
        helper.writableDatabase.delete("plans", "deleted_at_ms IS NOT NULL AND deleted_at_ms <= ?",
            arrayOf((now - TRASH_RETENTION_MS).toString()))

    fun planFingerprint(planId: Long): String = gson.toJson(mapOf(
        "plan" to getPlan(planId, includeDeleted = true), "targets" to listExpectedSpecies(planId),
        "links" to listPlanTripLinks(planId).sortedBy { it.tripId },
        "overrides" to listSettlementOverrides(planId), "settlement" to getSettlement(planId)
    )).let { input -> java.security.MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray()).joinToString("") { "%02x".format(it) } }

    fun agentWrite(operationId: String, toolId: String, planIds: List<Long>, fingerprints: Map<Long, String>,
                   block: () -> String): String = helper.writableDatabase.inTransaction {
        findAgentActionReceipt(this, operationId)?.let { return@inTransaction it.resultJson }
        fingerprints.forEach { (id, expected) ->
            check(planFingerprint(id) == expected) { "计划内容已变化，请重新准备操作" }
        }
        val result = block()
        insertAgentReceipt(this, operationId, toolId, planIds.firstOrNull(), result, System.currentTimeMillis())
        result
    }

    fun listSpeciesStats(planId: Long): List<PlanSpeciesStat> = helper.readableDatabase.rawQuery(
        """
        SELECT plan_id, species_key, species_code, scientific_name, common_name,
               display_name_zh, historical_frequency, current_frequency,
               combined_frequency, historical_observed_days, current_observed_days,
               last_seen_date
        FROM plan_species_stats WHERE plan_id = ?
        ORDER BY combined_frequency DESC, common_name COLLATE NOCASE ASC
        """.trimIndent(),
        arrayOf(planId.toString())
    ).useRows(Cursor::toPlanSpeciesStat)

    fun listExpectedSpecies(planId: Long? = null): List<PlanExpectedSpecies> {
        val where = if (planId == null) "" else " WHERE plan_id = ?"
        val args = planId?.let { arrayOf(it.toString()) } ?: emptyArray()
        return helper.readableDatabase.rawQuery(
            """
            SELECT plan_id, species_key, species_code, scientific_name, common_name,
                   display_name_zh, source, selected_at_ms
            FROM plan_expected_species$where
            ORDER BY selected_at_ms ASC
            """.trimIndent(),
            args
        ).useRows(Cursor::toPlanExpectedSpecies)
    }

    fun listRareObservations(planId: Long): List<PlanRareObservation> =
        helper.readableDatabase.rawQuery(
            """
            SELECT plan_id, species_key, species_code, scientific_name, common_name,
                   display_name_zh, observed_at, location_id, location_name, latitude,
                   longitude, count, report_count, provisional, source_url
            FROM plan_rare_observations WHERE plan_id = ?
            ORDER BY observed_at DESC, report_count DESC
            """.trimIndent(),
            arrayOf(planId.toString())
        ).useRows(Cursor::toPlanRareObservation)

    override fun replaceAnalysis(planId: Long, snapshot: PlanAnalysisSnapshot) {
        val previous = getPlan(planId) ?: return
        val allExpected = listExpectedSpecies(planId)
        val generatedExpected = allExpected
            .filter { it.source == "analysis" || it.source == "rare" }
        val generatedKeysAfterRefresh = PlanExpectedSelectionPolicy.generatedKeysAfterRefresh(
            existingGeneratedKeys = generatedExpected.map { it.speciesKey }.toSet(),
            rankedStats = snapshot.speciesStats,
            rareObservations = snapshot.rareObservations,
            isFirstAnalysis = !previous.hasAnalysis
        )
        val validKeys = (snapshot.speciesStats.map { it.speciesKey } +
            snapshot.rareObservations.map { it.speciesKey }).toSet()
        val db = helper.writableDatabase
        db.inTransaction {
            delete("plan_species_stats", "plan_id = ?", arrayOf(planId.toString()))
            delete("plan_rare_observations", "plan_id = ?", arrayOf(planId.toString()))
            snapshot.speciesStats.forEach { stat -> insertSpeciesStat(this, stat) }
            snapshot.rareObservations.forEach { rare -> insertRareObservation(this, rare) }

            generatedExpected.filter { it.speciesKey !in validKeys }.forEach { expected ->
                delete(
                    "plan_expected_species",
                    "plan_id = ? AND species_key = ?",
                    arrayOf(planId.toString(), expected.speciesKey)
                )
            }
            if (!previous.hasAnalysis) {
                val selectedAliases = allExpected.flatMap { expected ->
                    speciesIdentityAliases(
                        expected.speciesKey,
                        expected.speciesCode,
                        expected.scientificName,
                        expected.commonName
                    )
                }.toMutableSet()
                snapshot.speciesStats.filter { it.speciesKey in generatedKeysAfterRefresh }.forEach { stat ->
                    val aliases = speciesIdentityAliases(
                        stat.speciesKey,
                        stat.speciesCode,
                        stat.scientificName,
                        stat.commonName
                    )
                    if (aliases.any(selectedAliases::contains)) return@forEach
                    insertExpectedSpecies(
                        db = this,
                        planId = planId,
                        speciesKey = stat.speciesKey,
                        speciesCode = stat.speciesCode,
                        scientificName = stat.scientificName,
                        commonName = stat.commonName,
                        displayNameZh = stat.displayNameZh,
                        source = "analysis",
                        replace = false
                    )
                    selectedAliases += aliases
                }
            }
            update(
                "plans",
                ContentValues().apply {
                    put("analysis_generated_at_ms", snapshot.generatedAtMs)
                    put("analysis_target_year", snapshot.targetYear)
                    put("analysis_target_month", snapshot.targetMonth)
                    put("historical_requested_days", snapshot.coverage.historicalRequestedDays)
                    put("historical_successful_days", snapshot.coverage.historicalSuccessfulDays)
                    put("historical_active_days", snapshot.coverage.historicalActiveDays)
                    put("current_requested_days", snapshot.coverage.currentRequestedDays)
                    put("current_successful_days", snapshot.coverage.currentSuccessfulDays)
                    put("current_active_days", snapshot.coverage.currentActiveDays)
                    put("updated_at_ms", System.currentTimeMillis())
                },
                "id = ?",
                arrayOf(planId.toString())
            )
            markSettlementStale(this, planId)
        }
    }

    fun setExpectedSpecies(
        planId: Long,
        speciesKey: String,
        speciesCode: String,
        scientificName: String,
        commonName: String,
        displayNameZh: String?,
        source: String,
        selected: Boolean
    ) {
        val aliases = speciesIdentityAliases(speciesKey, speciesCode, scientificName, commonName)
        val matching = listExpectedSpecies(planId).filter { expected ->
            speciesIdentityAliases(
                expected.speciesKey,
                expected.speciesCode,
                expected.scientificName,
                expected.commonName
            ).any(aliases::contains)
        }
        val db = helper.writableDatabase
        db.inTransaction {
            if (selected) {
                if (matching.isEmpty()) {
                    insertExpectedSpecies(
                        db = this,
                        planId = planId,
                        speciesKey = speciesKey,
                        speciesCode = speciesCode,
                        scientificName = scientificName,
                        commonName = commonName,
                        displayNameZh = displayNameZh,
                        source = source,
                        replace = true
                    )
                }
            } else {
                (matching.map { it.speciesKey } + speciesKey).distinct().forEach { key ->
                    delete(
                        "plan_expected_species",
                        "plan_id = ? AND species_key = ?",
                        arrayOf(planId.toString(), key)
                    )
                }
            }
            markSettlementStale(this, planId)
        }
    }

    fun createPlanIdempotent(
        operationId: String,
        name: String,
        plannedDate: String,
        regionCode: String,
        regionName: String,
        hotspot: EbirdHotspotMatch
    ): Long {
        findAgentActionReceipt(operationId)?.planId?.let { return it }
        return helper.writableDatabase.inTransaction {
            findAgentActionReceipt(this, operationId)?.planId?.let { return@inTransaction it }
            val now = System.currentTimeMillis()
            val planId = insertOrThrow(
                "plans",
                null,
                ContentValues().apply {
                    put("name", name.trim().ifBlank { AppText.get("Untitled plan") })
                    put("planned_date", plannedDate)
                    put("region_code", regionCode.trim().ifBlank { "CN" })
                    put("region_name", regionName.trim().ifBlank { regionCode })
                    put("hotspot_id", hotspot.locationId)
                    put("hotspot_name", hotspot.name)
                    put("source_id", hotspot.sourceId)
                    put("source_location_json", hotspot.sourceLocationJson)
                    putNullable("hotspot_latitude", hotspot.latitude)
                    putNullable("hotspot_longitude", hotspot.longitude)
                    put("created_at_ms", now)
                    put("updated_at_ms", now)
                }
            )
            insertAgentReceipt(this, operationId, "create_plan", planId, "{\"planId\":$planId}", now)
            planId
        }
    }

    fun replaceExpectedSpeciesIdempotent(
        operationId: String,
        planId: Long,
        species: List<PlanExpectedSpecies>
    ): Boolean {
        if (findAgentActionReceipt(operationId) != null) return false
        return helper.writableDatabase.inTransaction {
            if (findAgentActionReceipt(this, operationId) != null) return@inTransaction false
            delete("plan_expected_species", "plan_id = ?", arrayOf(planId.toString()))
            species.distinctBy { it.speciesKey }.forEach { expected ->
                insertExpectedSpecies(
                    db = this,
                    planId = planId,
                    speciesKey = expected.speciesKey,
                    speciesCode = expected.speciesCode,
                    scientificName = expected.scientificName,
                    commonName = expected.commonName,
                    displayNameZh = expected.displayNameZh,
                    source = expected.source,
                    replace = true
                )
            }
            markSettlementStale(this, planId)
            val now = System.currentTimeMillis()
            insertAgentReceipt(
                this,
                operationId,
                "update_targets",
                planId,
                "{\"planId\":$planId,\"speciesCount\":${species.size}}",
                now
            )
            true
        }
    }

    fun setExpectedSpeciesBatch(planId: Long, candidates: List<PlanExpectedSpecies>, selected: Boolean) {
        helper.writableDatabase.inTransaction {
            check(getPlan(planId) != null) { AppText.get("计划已不存在") }
            val current = listExpectedSpecies(planId)
            val desired = PlanSpeciesBatchSelection.apply(current, candidates, selected)
            if (current.toSet() == desired.toSet()) return@inTransaction
            val retainedKeys = desired.map { it.speciesKey }.toSet()
            current.filterNot { it.speciesKey in retainedKeys }.forEach {
                delete("plan_expected_species", "plan_id = ? AND species_key = ?", arrayOf(planId.toString(), it.speciesKey))
            }
            val existingKeys = current.map { it.speciesKey }.toSet()
            desired.filterNot { it.speciesKey in existingKeys }.forEach { species ->
                insertOrThrow("plan_expected_species", null, ContentValues().apply {
                    put("plan_id", planId)
                    put("species_key", species.speciesKey)
                    put("species_code", species.speciesCode)
                    put("scientific_name", species.scientificName)
                    put("common_name", species.commonName)
                    putNullable("display_name_zh", species.displayNameZh)
                    put("source", species.source)
                    put("selected_at_ms", species.selectedAtMs)
                })
            }
            markSettlementStale(this, planId)
        }
    }

    fun findAgentActionReceipt(operationId: String): AgentActionReceipt? =
        findAgentActionReceipt(helper.readableDatabase, operationId)

    fun listPlanTripLinks(planId: Long? = null): List<PlanTripLink> {
        val where = if (planId == null) "" else " WHERE plan_id = ?"
        val args = planId?.let { arrayOf(it.toString()) } ?: emptyArray()
        return helper.readableDatabase.rawQuery(
            "SELECT plan_id, trip_id, linked_at_ms FROM plan_trip_links$where",
            args
        ).useRows { cursor ->
            PlanTripLink(cursor.getLong(0), cursor.getStringOrBlank(1), cursor.getLong(2))
        }
    }

    fun setPlanTrips(planId: Long, tripIds: Set<String>) {
        val previous = listPlanTripLinks(planId).map { it.tripId }.toSet()
        if (previous == tripIds) return
        val db = helper.writableDatabase
        db.inTransaction {
            delete("plan_trip_links", "plan_id = ?", arrayOf(planId.toString()))
            val now = System.currentTimeMillis()
            tripIds.sorted().forEach { tripId ->
                insertOrThrow(
                    "plan_trip_links",
                    null,
                    ContentValues().apply {
                        put("plan_id", planId)
                        put("trip_id", tripId)
                        put("linked_at_ms", now)
                    }
                )
            }
            markSettlementStale(this, planId)
        }
    }

    fun addPlanTripIdempotent(operationId: String, planId: Long, tripId: String): Boolean = helper.writableDatabase.inTransaction {
        check(getPlan(planId) != null) { "计划已不存在" }
        if (findAgentActionReceipt(this, operationId) != null) return@inTransaction false
        val exists = listPlanTripLinks(planId).any { it.tripId == tripId }
        val now = System.currentTimeMillis()
        if (!exists) {
            insertOrThrow("plan_trip_links", null, ContentValues().apply { put("plan_id", planId); put("trip_id", tripId); put("linked_at_ms", now) })
            markSettlementStale(this, planId)
        }
        insertAgentReceipt(this, operationId, "link_trip", planId, gson.toJson(mapOf("planId" to planId, "tripId" to tripId)), now)
        !exists
    }

    fun removeTripReferences(tripId: String): Set<Long> {
        val planIds = listPlanTripLinks().filter { it.tripId == tripId }.map { it.planId }.toSet()
        val db = helper.writableDatabase
        db.inTransaction {
            delete("plan_trip_links", "trip_id = ?", arrayOf(tripId))
            planIds.forEach { markSettlementStale(this, it) }
        }
        return planIds
    }

    fun markSettlementsStaleForTrip(tripId: String) {
        val planIds = listPlanTripLinks().filter { it.tripId == tripId }.map { it.planId }.toSet()
        val db = helper.writableDatabase
        db.inTransaction {
            planIds.forEach { markSettlementStale(this, it) }
        }
    }

    fun getSettlement(planId: Long): PlanSettlement? = helper.readableDatabase.rawQuery(
        """
        SELECT plan_id, generated_at_ms, is_stale, analysis_generated_at_ms,
               linked_trip_signature
        FROM plan_settlement WHERE plan_id = ? LIMIT 1
        """.trimIndent(),
        arrayOf(planId.toString())
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toPlanSettlement() else null }

    fun listSettlements(): List<PlanSettlement> = helper.readableDatabase.rawQuery(
        """
        SELECT plan_id, generated_at_ms, is_stale, analysis_generated_at_ms,
               linked_trip_signature FROM plan_settlement
        """.trimIndent(),
        emptyArray()
    ).useRows(Cursor::toPlanSettlement)

    fun listSettlementSpecies(planId: Long): List<PlanSettlementSpecies> =
        helper.readableDatabase.rawQuery(
            """
            SELECT plan_id, species_key, species_code, scientific_name, common_name,
                   display_name_zh, category, origin, max_confidence, trip_count,
                   detection_sources
            FROM plan_settlement_species WHERE plan_id = ?
            ORDER BY CASE category WHEN 'hit' THEN 0 WHEN 'missed' THEN 1 ELSE 2 END,
                     max_confidence DESC, common_name COLLATE NOCASE ASC
            """.trimIndent(),
            arrayOf(planId.toString())
        ).useRows(Cursor::toPlanSettlementSpecies)

    fun listSettlementOverrides(planId: Long): List<PlanSettlementOverride> =
        helper.readableDatabase.rawQuery(
            """
            SELECT plan_id, species_key, species_code, scientific_name, common_name,
                   display_name_zh, action, updated_at_ms
            FROM plan_settlement_overrides WHERE plan_id = ?
            """.trimIndent(),
            arrayOf(planId.toString())
        ).useRows(Cursor::toPlanSettlementOverride)

    fun replaceSettlement(
        planId: Long,
        species: List<PlanSettlementSpecies>,
        analysisGeneratedAtMs: Long?,
        linkedTripSignature: String
    ) {
        val now = System.currentTimeMillis()
        val db = helper.writableDatabase
        db.inTransaction {
            delete("plan_settlement_species", "plan_id = ?", arrayOf(planId.toString()))
            species.forEach { item ->
                insertOrThrow(
                    "plan_settlement_species",
                    null,
                    ContentValues().apply {
                        put("plan_id", planId)
                        put("species_key", item.speciesKey)
                        put("species_code", item.speciesCode)
                        put("scientific_name", item.scientificName)
                        put("common_name", item.commonName)
                        putNullable("display_name_zh", item.displayNameZh)
                        put("category", item.category.databaseValue)
                        put("origin", item.origin)
                        put("max_confidence", item.maxConfidence)
                        put("trip_count", item.tripCount)
                        put("detection_sources", item.detectionSources.sorted().joinToString(","))
                    }
                )
            }
            insertWithOnConflict(
                "plan_settlement",
                null,
                ContentValues().apply {
                    put("plan_id", planId)
                    put("generated_at_ms", now)
                    put("is_stale", 0)
                    putNullable("analysis_generated_at_ms", analysisGeneratedAtMs)
                    put("linked_trip_signature", linkedTripSignature)
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
    }

    fun upsertSettlementOverride(override: PlanSettlementOverride) {
        helper.writableDatabase.insertWithOnConflict(
            "plan_settlement_overrides",
            null,
            ContentValues().apply {
                put("plan_id", override.planId)
                put("species_key", override.speciesKey)
                put("species_code", override.speciesCode)
                put("scientific_name", override.scientificName)
                put("common_name", override.commonName)
                putNullable("display_name_zh", override.displayNameZh)
                put("action", override.action.databaseValue)
                put("updated_at_ms", override.updatedAtMs)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun deleteSettlementOverride(planId: Long, speciesKey: String) {
        helper.writableDatabase.delete(
            "plan_settlement_overrides",
            "plan_id = ? AND species_key = ?",
            arrayOf(planId.toString(), speciesKey)
        )
    }

    fun clearSettlementOverrides(planId: Long) {
        helper.writableDatabase.delete(
            "plan_settlement_overrides",
            "plan_id = ?",
            arrayOf(planId.toString())
        )
    }

    override fun getCachedObservationDay(
        hotspotId: String,
        observationDate: LocalDate,
        minFetchedAtMs: Long
    ): EbirdDailyCacheEntry? = helper.readableDatabase.rawQuery(
        """
        SELECT payload_json, fetched_at_ms FROM ebird_daily_cache
        WHERE hotspot_id = ? AND observation_date = ? AND fetched_at_ms >= ?
        LIMIT 1
        """.trimIndent(),
        arrayOf(hotspotId, observationDate.toString(), minFetchedAtMs.toString())
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val observations = runCatching {
            gson.fromJson<List<EbirdRecentObservation>>(
                cursor.getStringOrBlank(0),
                object : TypeToken<List<EbirdRecentObservation>>() {}.type
            )
        }.getOrNull() ?: return@use null
        EbirdDailyCacheEntry(observations, cursor.getLong(1))
    }

    override fun putCachedObservationDay(
        hotspotId: String,
        observationDate: LocalDate,
        observations: List<EbirdRecentObservation>,
        fetchedAtMs: Long
    ) {
        helper.writableDatabase.insertWithOnConflict(
            "ebird_daily_cache",
            null,
            ContentValues().apply {
                put("hotspot_id", hotspotId)
                put("observation_date", observationDate.toString())
                put("payload_json", gson.toJson(observations))
                put("fetched_at_ms", fetchedAtMs)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun insertSpeciesStat(db: SQLiteDatabase, stat: PlanSpeciesStat) {
        db.insertOrThrow(
            "plan_species_stats",
            null,
            ContentValues().apply {
                put("plan_id", stat.planId)
                put("species_key", stat.speciesKey)
                put("species_code", stat.speciesCode)
                put("scientific_name", stat.scientificName)
                put("common_name", stat.commonName)
                putNullable("display_name_zh", stat.displayNameZh)
                putNullable("historical_frequency", stat.historicalFrequency)
                putNullable("current_frequency", stat.currentFrequency)
                put("combined_frequency", stat.combinedFrequency)
                put("historical_observed_days", stat.historicalObservedDays)
                put("current_observed_days", stat.currentObservedDays)
                putNullable("last_seen_date", stat.lastSeenDate)
            }
        )
    }

    private fun insertRareObservation(db: SQLiteDatabase, rare: PlanRareObservation) {
        db.insertOrThrow(
            "plan_rare_observations",
            null,
            ContentValues().apply {
                put("plan_id", rare.planId)
                put("species_key", rare.speciesKey)
                put("species_code", rare.speciesCode)
                put("scientific_name", rare.scientificName)
                put("common_name", rare.commonName)
                putNullable("display_name_zh", rare.displayNameZh)
                put("observed_at", rare.observedAt)
                put("location_id", rare.locationId)
                put("location_name", rare.locationName)
                putNullable("latitude", rare.latitude)
                putNullable("longitude", rare.longitude)
                putNullable("count", rare.count)
                put("report_count", rare.reportCount)
                put("provisional", if (rare.provisional) 1 else 0)
                putNullable("source_url", rare.sourceUrl)
            }
        )
    }

    private fun insertExpectedSpecies(
        db: SQLiteDatabase,
        planId: Long,
        speciesKey: String,
        speciesCode: String,
        scientificName: String,
        commonName: String,
        displayNameZh: String?,
        source: String,
        replace: Boolean
    ) {
        db.insertWithOnConflict(
            "plan_expected_species",
            null,
            ContentValues().apply {
                put("plan_id", planId)
                put("species_key", speciesKey)
                put("species_code", speciesCode)
                put("scientific_name", scientificName)
                put("common_name", commonName)
                putNullable("display_name_zh", displayNameZh)
                put("source", source)
                put("selected_at_ms", System.currentTimeMillis())
            },
            if (replace) SQLiteDatabase.CONFLICT_REPLACE else SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    private fun markSettlementStale(db: SQLiteDatabase, planId: Long) {
        db.update(
            "plan_settlement",
            ContentValues().apply { put("is_stale", 1) },
            "plan_id = ?",
            arrayOf(planId.toString())
        )
    }

    private fun findAgentActionReceipt(db: SQLiteDatabase, operationId: String): AgentActionReceipt? =
        db.rawQuery(
            "SELECT operation_id, skill_id, plan_id, result_json, created_at_ms FROM agent_action_receipts WHERE operation_id = ? LIMIT 1",
            arrayOf(operationId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            AgentActionReceipt(
                operationId = cursor.getStringOrBlank(0),
                skillId = cursor.getStringOrBlank(1),
                planId = cursor.getNullableLong(2),
                resultJson = cursor.getStringOrBlank(3),
                createdAtMs = cursor.getLong(4)
            )
        }

    private fun insertAgentReceipt(
        db: SQLiteDatabase,
        operationId: String,
        skillId: String,
        planId: Long?,
        resultJson: String,
        now: Long
    ) {
        db.insertOrThrow(
            "agent_action_receipts",
            null,
            ContentValues().apply {
                put("operation_id", operationId)
                put("skill_id", skillId)
                if (planId == null) putNull("plan_id") else put("plan_id", planId)
                put("result_json", resultJson)
                put("created_at_ms", now)
            }
        )
    }

    private fun targetMonth(date: String): YearMonth? = runCatching {
        YearMonth.from(LocalDate.parse(date))
    }.getOrNull()

    companion object {
        const val TRASH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }

}

private fun Cursor.toPlan() = Plan(
    id = getLong(0),
    name = getStringOrBlank(1),
    plannedDate = getStringOrBlank(2),
    regionCode = getStringOrBlank(3),
    hotspotId = getStringOrBlank(4),
    hotspotName = getStringOrBlank(5),
    hotspotLatitude = getNullableDouble(6),
    hotspotLongitude = getNullableDouble(7),
    createdAtMs = getLong(8),
    updatedAtMs = getLong(9),
    analysisGeneratedAtMs = getNullableLong(10),
    analysisTargetYear = getNullableInt(11),
    analysisTargetMonth = getNullableInt(12),
    historicalRequestedDays = getInt(13),
    historicalSuccessfulDays = getInt(14),
    historicalActiveDays = getInt(15),
    currentRequestedDays = getInt(16),
    currentSuccessfulDays = getInt(17),
    currentActiveDays = getInt(18),
    regionName = getStringOrBlank(19).ifBlank { getStringOrBlank(3) },
    sourceId = getStringOrBlank(20), sourceLocationJson = getStringOrBlank(21),
    statisticKind = getStringOrBlank(22), deletedAtMs = getNullableLong(23)
)

private fun Cursor.toPlanSpeciesStat() = PlanSpeciesStat(
    planId = getLong(0),
    speciesKey = getStringOrBlank(1),
    speciesCode = getStringOrBlank(2),
    scientificName = getStringOrBlank(3),
    commonName = getStringOrBlank(4),
    displayNameZh = getNullableString(5),
    historicalFrequency = getNullableFloat(6),
    currentFrequency = getNullableFloat(7),
    combinedFrequency = getFloat(8),
    historicalObservedDays = getInt(9),
    currentObservedDays = getInt(10),
    lastSeenDate = getNullableString(11)
)

private fun Cursor.toPlanExpectedSpecies() = PlanExpectedSpecies(
    planId = getLong(0),
    speciesKey = getStringOrBlank(1),
    speciesCode = getStringOrBlank(2),
    scientificName = getStringOrBlank(3),
    commonName = getStringOrBlank(4),
    displayNameZh = getNullableString(5),
    source = getStringOrBlank(6),
    selectedAtMs = getLong(7)
)

private fun Cursor.toPlanRareObservation() = PlanRareObservation(
    planId = getLong(0),
    speciesKey = getStringOrBlank(1),
    speciesCode = getStringOrBlank(2),
    scientificName = getStringOrBlank(3),
    commonName = getStringOrBlank(4),
    displayNameZh = getNullableString(5),
    observedAt = getStringOrBlank(6),
    locationId = getStringOrBlank(7),
    locationName = getStringOrBlank(8),
    latitude = getNullableDouble(9),
    longitude = getNullableDouble(10),
    count = getNullableInt(11),
    reportCount = getInt(12),
    provisional = getInt(13) == 1,
    sourceUrl = getNullableString(14)
)

private fun Cursor.toPlanSettlement() = PlanSettlement(
    planId = getLong(0),
    generatedAtMs = getLong(1),
    isStale = getInt(2) == 1,
    analysisGeneratedAtMs = getNullableLong(3),
    linkedTripSignature = getStringOrBlank(4)
)

private fun Cursor.toPlanSettlementSpecies() = PlanSettlementSpecies(
    planId = getLong(0),
    speciesKey = getStringOrBlank(1),
    speciesCode = getStringOrBlank(2),
    scientificName = getStringOrBlank(3),
    commonName = getStringOrBlank(4),
    displayNameZh = getNullableString(5),
    category = PlanSettlementCategory.fromDatabase(getStringOrBlank(6)),
    origin = getStringOrBlank(7),
    maxConfidence = getFloat(8),
    tripCount = getInt(9),
    detectionSources = getStringOrBlank(10).split(',').filter { it.isNotBlank() }.toSet()
)

private fun Cursor.toPlanSettlementOverride() = PlanSettlementOverride(
    planId = getLong(0),
    speciesKey = getStringOrBlank(1),
    speciesCode = getStringOrBlank(2),
    scientificName = getStringOrBlank(3),
    commonName = getStringOrBlank(4),
    displayNameZh = getNullableString(5),
    action = PlanSettlementOverrideAction.fromDatabase(getStringOrBlank(6)),
    updatedAtMs = getLong(7)
)

private inline fun <T> Cursor.useRows(mapper: (Cursor) -> T): List<T> = use { cursor ->
    buildList {
        while (cursor.moveToNext()) add(mapper(cursor))
    }
}

private inline fun <T> SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    return try {
        val result = block()
        setTransactionSuccessful()
        result
    } finally {
        endTransaction()
    }
}

private fun Cursor.getStringOrBlank(index: Int): String = if (isNull(index)) "" else getString(index).orEmpty()
private fun Cursor.getNullableString(index: Int): String? = if (isNull(index)) null else getString(index)
private fun Cursor.getNullableLong(index: Int): Long? = if (isNull(index)) null else getLong(index)
private fun Cursor.getNullableInt(index: Int): Int? = if (isNull(index)) null else getInt(index)
private fun Cursor.getNullableDouble(index: Int): Double? = if (isNull(index)) null else getDouble(index)
private fun Cursor.getNullableFloat(index: Int): Float? = if (isNull(index)) null else getFloat(index)

private fun ContentValues.putNullable(key: String, value: String?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun ContentValues.putNullable(key: String, value: Double?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun ContentValues.putNullable(key: String, value: Float?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun ContentValues.putNullable(key: String, value: Int?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun ContentValues.putNullable(key: String, value: Long?) {
    if (value == null) putNull(key) else put(key, value)
}
