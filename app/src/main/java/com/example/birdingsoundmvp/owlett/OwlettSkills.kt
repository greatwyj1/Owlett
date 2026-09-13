package com.example.birdingsoundmvp.owlett

import com.example.birdingsoundmvp.i18n.AppText

import com.example.birdingsoundmvp.planning.EbirdHotspotMatch
import com.example.birdingsoundmvp.planning.EbirdHotspotSearchResponse
import com.example.birdingsoundmvp.planning.EbirdRecentObservationsClient
import com.example.birdingsoundmvp.planning.EbirdRecentObservationsResponse
import com.example.birdingsoundmvp.planning.EbirdRegionOption
import com.example.birdingsoundmvp.planning.EbirdRegionsResponse
import com.example.birdingsoundmvp.planning.PlanExpectedSpecies
import com.example.birdingsoundmvp.planning.PlanRareObservation
import com.example.birdingsoundmvp.planning.PlanSpeciesStat
import com.example.birdingsoundmvp.planning.PlansTripsRepository
import com.example.birdingsoundmvp.planning.PlanAnalysisResult
import com.example.birdingsoundmvp.planning.EbirdRegionNames
import com.example.birdingsoundmvp.planning.canonicalSpeciesKey
import com.example.birdingsoundmvp.taxonomy.BirdSpeciesDetail
import com.example.birdingsoundmvp.taxonomy.BirdTaxonomyRepository
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.time.LocalDate

data class OwlettSkillContext(
    val operationId: String,
    val attachedPlanId: Long?,
    val ebirdApiKey: String,
    val xenoCantoApiKey: String?,
    val recentPlanId: Long? = null,
    val attachedTripId: String? = null,
    val automatic: Boolean = false,
    val writeAuthorized: Boolean = false,
    val allowedToolIds: Set<String>? = null,
    val instructionsBySkill: Map<String, String> = emptyMap(),
    val conversationId: Long? = null,
    val observationCache: OwlettObservationCache? = null,
    val attachedBirdCallRegion: BirdCallRegion? = null,
    val resolveBirdCallRegion: suspend () -> BirdCallRegion? = { null }
)

data class OwlettSkillOption(
    val id: String,
    val label: String,
    val supportingText: String = "",
    val valueJson: String
)

data class OwlettBirdObservationSummary(
    val observedAt: String,
    val locationName: String,
    val count: Int?
)

data class OwlettSkillCardPayload(
    val kind: String,
    val title: String,
    val summary: String = "",
    val action: String = "",
    val selectionField: String = "",
    val planId: Long? = null,
    val options: List<OwlettSkillOption> = emptyList(),
    val addedSpecies: List<String> = emptyList(),
    val removedSpecies: List<String> = emptyList(),
    val keptSpecies: List<String> = emptyList(),
    val manualSpecies: List<String> = emptyList(),
    val birdDetail: BirdSpeciesDetail? = null,
    val recentObservations: List<OwlettBirdObservationSummary> = emptyList(),
    val recentObservationsNote: String = "",
    val recordings: List<XenoCantoRecording> = emptyList(),
    val tripId: String? = null,
    val clips: List<OwlettAudioClip> = emptyList()
)

object OwlettSkillCardCodec {
    private val gson = Gson()

    fun encode(payload: OwlettSkillCardPayload): String = gson.toJson(payload)

    fun decode(json: String?): OwlettSkillCardPayload? = json
        ?.takeIf(String::isNotBlank)
        ?.let { runCatching {
            val defaults = gson.toJsonTree(OwlettSkillCardPayload("unknown", "技能结果")).asJsonObject
            JsonParser.parseString(it).asJsonObject.entrySet().forEach { (key, value) ->
                if (!value.isJsonNull) defaults.add(key, value)
            }
            gson.fromJson(defaults, OwlettSkillCardPayload::class.java)
        }.getOrNull() }
}

sealed interface OwlettSkillPreparation {
    data class Ready(val normalizedArgumentsJson: String) : OwlettSkillPreparation
    data class WaitingInput(
        val normalizedArgumentsJson: String,
        val card: OwlettSkillCardPayload
    ) : OwlettSkillPreparation
    data class WaitingConfirmation(
        val normalizedArgumentsJson: String,
        val card: OwlettSkillCardPayload
    ) : OwlettSkillPreparation
    data class Immediate(
        val toolResponse: String,
        val card: OwlettSkillCardPayload? = null,
        val isError: Boolean = false,
        val awaitsUserInput: Boolean = false
    ) : OwlettSkillPreparation
}

data class OwlettSkillExecution(
    val toolResponse: String,
    val card: OwlettSkillCardPayload,
    val isError: Boolean = false,
    val reprepareArgumentsJson: String? = null
)

interface OwlettTool {
    val descriptor: OwlettSkillDescriptor
    val toolDefinition: OwlettToolDefinition

    suspend fun prepare(argumentsJson: String, context: OwlettSkillContext): OwlettSkillPreparation
    suspend fun execute(argumentsJson: String, context: OwlettSkillContext): OwlettSkillExecution
    fun applySelection(argumentsJson: String, field: String, valueJson: String): String = argumentsJson
}

// Legacy implementations remain adapters while scene skills become instruction-only.
typealias OwlettSkill = OwlettTool

class OwlettSkillRegistry(skills: List<OwlettSkill>) {
    private val allSkills = skills.toList()
    private val byId = skills.associateBy { it.descriptor.id }
    val descriptors: List<OwlettSkillDescriptor> = skills.map(OwlettSkill::descriptor)
    val toolDefinitions: List<OwlettToolDefinition> = skills.map(OwlettSkill::toolDefinition)
    val tools: List<OwlettTool> get() = allSkills

    fun find(id: String): OwlettSkill? = byId[id]

    fun findBySlash(command: String): OwlettSkill? = allSkills.firstOrNull {
        it.descriptor.slashCommand.equals(command.trim(), ignoreCase = true)
    }

    companion object {
        fun create(
            repository: PlansTripsRepository,
            ebird: EbirdRecentObservationsClient,
            taxonomy: BirdTaxonomyRepository,
            xenoCanto: XenoCantoClient,
            trips: com.example.birdingsoundmvp.trip.TripHistoryRepository? = null,
            sources: com.example.birdingsoundmvp.planning.BirdObservationSources? = null,
            analyzePlan: suspend (Long, String) -> PlanAnalysisResult
        ): OwlettSkillRegistry = OwlettSkillRegistry(
            listOf(
                CreatePlanSkill(repository, ebird),
                OrganizeActivitySkill(repository, analyzePlan, sources),
                UpdateTargetsSkill(repository, taxonomy),
                BirdLookupSkill(repository, taxonomy, ebird, sources),
                BirdCallsSkill(taxonomy, xenoCanto)
            ) + if (trips == null) emptyList() else listOf(OwlettTripSkill(true, trips, repository, taxonomy), OwlettTripSkill(false, trips, repository, taxonomy))
        )
    }
}

private class OrganizeActivitySkill(
    private val repository: PlansTripsRepository,
    private val analyze: suspend (Long, String) -> PlanAnalysisResult,
    private val sources: com.example.birdingsoundmvp.planning.BirdObservationSources?
) : OwlettSkill {
    override val descriptor = OwlettSkillDescriptor("organize_activity", "/activity", AppText.get("整理鸟况"), AppText.get("整理计划鸟点的往年、当年与近期稀有鸟报告"), true)
    override val toolDefinition = OwlettToolDefinition(descriptor.id,
        "Organize bird activity using the Plan's actual source. Shows confirmation/progress and returns coverage. Use real plan_id from search or attached/recent Plan.",
        """{"type":"object","properties":{"plan_id":{"type":"integer"},"use_recent_plan":{"type":"boolean","description":"True only when the user refers to the plan just created or used in this conversation"}},"required":[]}""")

    override suspend fun prepare(argumentsJson: String, context: OwlettSkillContext): OwlettSkillPreparation {
        val args = parseObject(argumentsJson) ?: return invalidArguments(AppText.get("鸟况参数无法读取。"))
        val planId = args.long("_plan_id") ?: args.long("plan_id") ?: context.attachedPlanId ?: context.recentPlanId.takeIf { args.boolean("use_recent_plan") }
        if (planId == null) {
            val plans = repository.listPlans()
            if (plans.isEmpty()) return skillError(AppText.get("请先创建观鸟计划。"))
            return OwlettSkillPreparation.WaitingInput(gson.toJson(args), OwlettSkillCardPayload(
                "selection", AppText.get("选择要整理的计划"), selectionField = "plan",
                options = plans.map { OwlettSkillOption(it.id.toString(), it.name, "${it.plannedDate} · ${it.hotspotName}", it.id.toString()) }
            ))
        }
        val plan = repository.getPlan(planId) ?: return skillError(AppText.get("该计划已不存在，请重新选择。"))
        if (plan.needsHotspot) return skillError("请先为计划选择具体鸟点。")
        val source = sources?.require(plan.sourceId)
        if ((source?.capabilities?.requiresApiKey ?: true) && context.ebirdApiKey.isBlank()) return skillError(AppText.get("请在设置中填写并测试 eBird 密钥。"))
        args.addProperty("_plan_id", planId)
        return OwlettSkillPreparation.WaitingConfirmation(gson.toJson(args), OwlettSkillCardPayload(
            "activity_preview", AppText.get("整理近期鸟况"), "${plan.name}\n${plan.plannedDate} · ${plan.hotspotName}", action = "execute", planId = planId
        ))
    }

    override suspend fun execute(argumentsJson: String, context: OwlettSkillContext): OwlettSkillExecution {
        val planId = requireNotNull(parseObject(argumentsJson)?.long("_plan_id"))
        return when (val result = analyze(planId, context.ebirdApiKey)) {
            is PlanAnalysisResult.Failure -> executionError(result.message)
            is PlanAnalysisResult.Success -> {
                val snapshot = result.snapshot
                val plan = repository.getPlan(planId) ?: return executionError("计划已删除")
                val notable = sources?.find(plan.sourceId)?.capabilities?.supportsNotable ?: true
                val stats = repository.listSpeciesStats(planId).sortedByDescending { it.combinedFrequency }
                val rare = repository.listRareObservations(planId)
                val coverage = snapshot.coverage
                val completed = coverage.historicalSuccessfulDays + coverage.currentSuccessfulDays
                val total = coverage.historicalRequestedDays + coverage.currentRequestedDays
                val summary = "已整理 ${stats.size} 种可能鸟种、${rare.size} 种${if (notable) "近期稀有鸟" else "近期记录鸟种"}。\n成功查询 $completed/$total 个日期。" +
                    result.snapshot.warning?.let { "\n$it" }.orEmpty()
                OwlettSkillExecution(gson.toJson(mapOf("status" to "completed", "planId" to planId,
                    "source" to plan.sourceId, "recentKind" to if (notable) "notable" else "recent_observations", "summary" to summary,
                    "likelySpecies" to stats.take(30).map { mapOf("name" to it.displayName, "frequency" to it.combinedFrequency) },
                    "recentSpecies" to rare.take(20).map { row -> mapOf("name" to row.displayName, "observedAt" to row.observedAt,
                        "label" to if (notable) "近期稀有鸟" else com.example.birdingsoundmvp.planning.RecentObservationLabel.label(plan.historicalActiveDays,
                            stats.firstOrNull { it.speciesKey == row.speciesKey }?.historicalFrequency)) })),
                    OwlettSkillCardPayload("activity_complete", AppText.get("鸟况整理完成"), summary, action = "open_plan", planId = planId))
            }
        }
    }

    override fun applySelection(argumentsJson: String, field: String, valueJson: String): String =
        gson.toJson((parseObject(argumentsJson) ?: JsonObject()).apply { if (field == "plan") addProperty("_plan_id", valueJson.toLong()) })
}

private class CreatePlanSkill(
    private val repository: PlansTripsRepository,
    private val ebird: EbirdRecentObservationsClient
) : OwlettSkill {
    override val descriptor = OwlettSkillDescriptor(
        id = ID,
        slashCommand = "/plan",
        displayName = AppText.get("Create Plan"),
        description = AppText.get("Create a Plan from a date, region, and eBird hotspot"),
        writesAppData = true
    )
    override val toolDefinition = OwlettToolDefinition(
        name = ID,
        description = "Prepare a new birding Plan. This never writes until the user confirms the preview in the app.",
        parametersJsonSchema = """
            {"type":"object","properties":{
              "name":{"type":"string","description":"Optional custom Plan name"},
              "date":{"type":"string","description":"Required ISO date YYYY-MM-DD"},
              "country_code":{"type":"string","description":"Optional eBird country code, defaults to CN"},
              "country_name":{"type":"string"},
              "region_code":{"type":"string","description":"Optional eBird country or subnational1 code"},
              "region_name":{"type":"string"},
              "hotspot_keyword":{"type":"string","description":"Required hotspot search text"}
            },"required":["date","hotspot_keyword"]}
        """.trimIndent()
    )

    override suspend fun prepare(argumentsJson: String, context: OwlettSkillContext): OwlettSkillPreparation {
        val args = parseObject(argumentsJson) ?: return invalidArguments(AppText.get("Plan parameters were not valid JSON."))
        val date = args.string("date")
        if (date.isBlank() || runCatching { LocalDate.parse(date) }.isFailure) {
            return clarification(AppText.get("Please provide the Plan date in YYYY-MM-DD format."))
        }
        val hotspotKeyword = args.string("hotspot_keyword")
        if (hotspotKeyword.isBlank() && args.string("_hotspot_id").isBlank()) {
            return clarification(AppText.get("Please provide a hotspot name or search keyword."))
        }
        if (context.ebirdApiKey.isBlank()) {
            return skillError(AppText.get("Add an eBird API key in Settings before creating a Plan."))
        }

        var cacheNotice: String? = null
        val countries = when (val response = ebird.listCountries(context.ebirdApiKey)) {
            is EbirdRegionsResponse.Failure -> return skillError(response.message)
            is EbirdRegionsResponse.Success -> {
                cacheNotice = response.cacheNotice
                response.regions
            }
        }
        val countryQuery = args.string("country_code").ifBlank { args.string("country_name").ifBlank { "CN" } }
        val countryMatches = matchingRegions(countries, countryQuery)
        if (countryMatches.size != 1) return regionOptions(args, "country", AppText.get("Choose a country"), countryMatches)
        val country = countryMatches.single()
        val countryCode = country.code
        val countryName = country.name
        args.addProperty("country_code", countryCode)
        args.addProperty("country_name", countryName)

        val regionQuery = args.string("region_code").ifBlank { args.string("region_name").ifBlank { countryCode } }
        val region = if (EbirdRegionNames.matches(country, regionQuery)) country else {
            val regions = when (val response = ebird.listSubnationalRegions(context.ebirdApiKey, countryCode)) {
                is EbirdRegionsResponse.Failure -> return skillError(response.message)
                is EbirdRegionsResponse.Success -> {
                    cacheNotice = response.cacheNotice ?: cacheNotice
                    response.regions
                }
            }
            val matches = matchingRegions(regions, regionQuery)
            if (matches.size != 1) return regionOptions(args, "region", AppText.get("Choose a state or province"), matches)
            matches.single()
        }
        val regionCode = region.code
        val regionName = region.name
        args.addProperty("region_code", regionCode)
        args.addProperty("region_name", regionName)

        val selectedHotspot = args.get("_hotspot")?.takeIf { it.isJsonObject }?.let { element ->
            runCatching { gson.fromJson(element, EbirdHotspotMatch::class.java) }.getOrNull()
        }
        val hotspot = selectedHotspot ?: when (
            val response = ebird.searchHotspots(context.ebirdApiKey, regionCode, hotspotKeyword, 12)
        ) {
            is EbirdHotspotSearchResponse.Failure -> return skillError(response.message)
            is EbirdHotspotSearchResponse.Success -> {
                cacheNotice = response.cacheNotice ?: cacheNotice
                if (response.hotspots.isEmpty()) return skillError(AppText.get("No matching eBird hotspots were found."))
                if (response.hotspots.size > 1) {
                    val options = response.hotspots.map { item ->
                        OwlettSkillOption(
                            id = item.locationId,
                            label = item.name,
                            supportingText = listOfNotNull(
                                item.locationId,
                                item.latitude?.let { latitude ->
                                    item.longitude?.let { longitude -> "%.4f, %.4f".format(latitude, longitude) }
                                }
                            ).joinToString(" · "),
                            valueJson = gson.toJson(item)
                        )
                    }
                    return OwlettSkillPreparation.WaitingInput(
                        gson.toJson(args),
                        OwlettSkillCardPayload(
                            kind = "selection",
                            title = AppText.get("Choose an eBird hotspot"),
                            summary = AppText.format("Several hotspots match “{0}”.", hotspotKeyword) + cacheNotice?.let { "\n$it" }.orEmpty(),
                            selectionField = "hotspot",
                            options = options
                        )
                    )
                }
                response.hotspots.single()
            }
        }
        args.add("_hotspot", gson.toJsonTree(hotspot))
        val planName = args.string("name").ifBlank { "${hotspot.name} $date" }
        args.addProperty("name", planName)
        return OwlettSkillPreparation.WaitingConfirmation(
            gson.toJson(args),
            OwlettSkillCardPayload(
                kind = "plan_preview",
                title = AppText.get("Create Plan?"),
                summary = buildString {
                    cacheNotice?.let { appendLine(it) }
                    append(planName)
                    append("\n$date · $regionName")
                    append("\n${hotspot.name}")
                    if (hotspot.latitude != null && hotspot.longitude != null) {
                        append("\n%.4f, %.4f".format(hotspot.latitude, hotspot.longitude))
                    }
                },
                action = "execute"
            )
        )
    }

    override suspend fun execute(argumentsJson: String, context: OwlettSkillContext): OwlettSkillExecution {
        val args = requireNotNull(parseObject(argumentsJson))
        val hotspot = gson.fromJson(args.getAsJsonObject("_hotspot"), EbirdHotspotMatch::class.java)
        val planId = repository.createPlanIdempotent(
            operationId = context.operationId,
            name = args.string("name"),
            plannedDate = args.string("date"),
            regionCode = args.string("region_code"),
            regionName = args.string("region_name"),
            hotspot = hotspot
        )
        val card = OwlettSkillCardPayload(
            kind = "plan_complete",
            title = AppText.get("Plan created"),
            summary = "${args.string("name")}\n${args.string("date")} · ${hotspot.name}",
            action = "open_plan",
            planId = planId
        )
        return OwlettSkillExecution(
            toolResponse = gson.toJson(mapOf("status" to "created", "planId" to planId, "name" to args.string("name"))),
            card = card
        )
    }

    override fun applySelection(argumentsJson: String, field: String, valueJson: String): String {
        val args = parseObject(argumentsJson) ?: JsonObject()
        when (field) {
            "country" -> gson.fromJson(valueJson, EbirdRegionOption::class.java).let { country ->
                args.addProperty("country_code", country.code)
                args.addProperty("country_name", country.name)
                if (args.string("region_name").isBlank()) {
                    args.addProperty("region_code", country.code)
                    args.addProperty("region_name", country.name)
                } else {
                    args.remove("region_code")
                }
                args.remove("_hotspot")
            }
            "region" -> gson.fromJson(valueJson, EbirdRegionOption::class.java).let { region ->
                args.addProperty("region_code", region.code)
                args.addProperty("region_name", region.name)
                args.remove("_hotspot")
            }
            "hotspot" -> args.add("_hotspot", JsonParser.parseString(valueJson))
        }
        return gson.toJson(args)
    }

    private fun regionOptions(
        args: JsonObject,
        field: String,
        title: String,
        matches: List<EbirdRegionOption>
    ): OwlettSkillPreparation {
        if (matches.isEmpty()) return skillError(AppText.get("No matching eBird region was found."))
        return OwlettSkillPreparation.WaitingInput(
            gson.toJson(args),
            OwlettSkillCardPayload(
                kind = "selection",
                title = title,
                selectionField = field,
                options = matches.take(20).map { option ->
                    OwlettSkillOption(option.code, option.name, option.code, gson.toJson(option))
                }
            )
        )
    }

    companion object { const val ID = "create_plan" }
}

object OwlettTargetListPolicy {
    const val STALE_AFTER_MS = 24L * 60L * 60L * 1_000L

    data class Result(
        val desired: List<PlanExpectedSpecies>,
        val added: List<PlanExpectedSpecies>,
        val removed: List<PlanExpectedSpecies>,
        val kept: List<PlanExpectedSpecies>,
        val manuallyRemoved: List<PlanExpectedSpecies>
    )

    fun calculate(
        planId: Long,
        mode: String,
        names: List<String>,
        threshold: Float?,
        preserveManual: Boolean,
        existing: List<PlanExpectedSpecies>,
        stats: List<PlanSpeciesStat>,
        rare: List<PlanRareObservation>,
        localMatches: List<PlanExpectedSpecies> = emptyList(),
        now: Long = System.currentTimeMillis()
    ): Result {
        val candidates = (
            existing +
                stats.map { it.toExpected("analysis", now) } +
                rare.map { it.toExpected("rare", now) } +
                localMatches
            )
            .distinctBy { it.speciesKey }
        val matched = candidates.filter { candidate -> names.any { name -> candidate.matches(name) } }
        var desired = when (mode) {
            "add" -> existing + matched
            "remove" -> existing.filterNot { candidate -> names.any { name -> candidate.matches(name) } }
            "replace" -> matched
            "keep_recent_rare" -> rare.map { it.toExpected("rare", now) }
            "max_combined_frequency" -> stats
                .filter { it.combinedFrequency <= (threshold ?: 0f) }
                .map { it.toExpected("analysis", now) }
            else -> existing
        }.distinctBy { it.speciesKey }
        val manual = existing.filter { it.source !in setOf("analysis", "rare") }
        if (preserveManual) desired = (desired + manual).distinctBy { it.speciesKey }
        val desiredKeys = desired.map { it.speciesKey }.toSet()
        val existingKeys = existing.map { it.speciesKey }.toSet()
        return Result(
            desired = desired,
            added = desired.filter { it.speciesKey !in existingKeys },
            removed = existing.filter { it.speciesKey !in desiredKeys },
            kept = existing.filter { it.speciesKey in desiredKeys },
            manuallyRemoved = manual.filter { it.speciesKey !in desiredKeys }
        )
    }

    fun versionToken(analysisGeneratedAtMs: Long?, expected: List<PlanExpectedSpecies>): String = buildString {
        append(analysisGeneratedAtMs ?: 0L)
        append('|')
        expected.sortedBy { it.speciesKey }.forEach { item ->
            append(item.speciesKey).append(':').append(item.source).append(':').append(item.selectedAtMs).append(';')
        }
    }

    private fun PlanSpeciesStat.toExpected(source: String, now: Long) = PlanExpectedSpecies(
        planId, speciesKey, speciesCode, scientificName, commonName, displayNameZh, source, now
    )

    private fun PlanRareObservation.toExpected(source: String, now: Long) = PlanExpectedSpecies(
        planId, speciesKey, speciesCode, scientificName, commonName, displayNameZh, source, now
    )

    private fun PlanExpectedSpecies.matches(query: String): Boolean {
        val normalized = query.trim().lowercase()
        return normalized.isNotBlank() && listOf(speciesKey, speciesCode, scientificName, commonName, displayNameZh.orEmpty())
            .any { it.trim().lowercase() == normalized }
    }
}

private class UpdateTargetsSkill(
    private val repository: PlansTripsRepository,
    private val taxonomy: BirdTaxonomyRepository
) : OwlettSkill {
    override val descriptor = OwlettSkillDescriptor(
        id = ID,
        slashCommand = "/targets",
        displayName = AppText.get("Target list"),
        description = AppText.get("Preview changes to a Plan target list"),
        writesAppData = true
    )
    override val toolDefinition = OwlettToolDefinition(
        name = ID,
        description = "Prepare deterministic changes to the expected species list of the attached Plan. Never writes before confirmation.",
        parametersJsonSchema = """
            {"type":"object","properties":{
              "mode":{"type":"string","enum":["add","remove","replace","keep_recent_rare","max_combined_frequency"]},
              "plan_id":{"type":"integer"},
              "species":{"type":"array","items":{"type":"string"}},
              "remove_manual":{"type":"boolean","description":"True only if the user explicitly asks to remove manually added species; otherwise false"},
              "maximum_combined_frequency":{"type":"number","minimum":0,"maximum":1,"description":"0 to 1, required for max_combined_frequency; never infer this value from an ambiguous word such as common"}
            },"required":["mode"]}
        """.trimIndent()
    )

    override suspend fun prepare(argumentsJson: String, context: OwlettSkillContext): OwlettSkillPreparation {
        val args = parseObject(argumentsJson) ?: return invalidArguments(AppText.get("Target-list parameters were not valid JSON."))
        val planId = args.long("_plan_id") ?: args.long("plan_id") ?: context.attachedPlanId
        if (planId == null) {
            val plans = repository.listPlans()
            if (plans.isEmpty()) return skillError(AppText.get("Create a Plan before editing a target list."))
            return OwlettSkillPreparation.WaitingInput(
                gson.toJson(args),
                OwlettSkillCardPayload(
                    kind = "selection",
                    title = AppText.get("Choose a Plan"),
                    summary = AppText.get("Target-list changes are never applied by matching a typed Plan name."),
                    selectionField = "plan",
                    options = plans.map { plan ->
                        OwlettSkillOption(
                            id = plan.id.toString(),
                            label = plan.name,
                            supportingText = "${plan.plannedDate} · ${plan.hotspotName}",
                            valueJson = plan.id.toString()
                        )
                    }
                )
            )
        }
        val plan = repository.getPlan(planId) ?: return skillError(AppText.get("The selected Plan no longer exists."))
        args.addProperty("_plan_id", planId)
        val mode = args.string("mode")
        if (mode == "keep_recent_rare" && plan.sourceId != "ebird") return clarification(
            "此来源没有已验证的稀有鸟分类。可以改为最近30天出现且历史低频的鸟种，但需要先向用户说明并确认，再用 targets_replace 提交明确名单。")
        if (mode !in SUPPORTED_MODES) return clarification(AppText.get("Please specify how the target list should change."))
        val dependsOnRecent = mode == "keep_recent_rare"
        val stale = plan.analysisGeneratedAtMs == null ||
            System.currentTimeMillis() - plan.analysisGeneratedAtMs > OwlettTargetListPolicy.STALE_AFTER_MS
        if (dependsOnRecent && stale && !args.boolean("_analysis_refreshed")) {
            return OwlettSkillPreparation.WaitingConfirmation(
                gson.toJson(args),
                OwlettSkillCardPayload(
                    kind = "analysis_prompt",
                    title = AppText.get("Organize recent bird activity first?"),
                    summary = if (plan.analysisGeneratedAtMs == null) {
                        AppText.format("{0} has no bird-activity snapshot yet.", plan.name)
                    } else {
                        AppText.format("{0} was last organized more than 24 hours ago.", plan.name)
                    },
                    action = "analyze",
                    planId = planId
                )
            )
        }
        if (mode == "max_combined_frequency" && args.float("maximum_combined_frequency") == null) {
            return clarification(AppText.get("“Common” needs a numeric boundary. What maximum report-day frequency should be kept?"))
        }
        val threshold = normalizedThreshold(args.float("maximum_combined_frequency"))
        if (mode == "max_combined_frequency" && (threshold == null || threshold !in 0f..1f)) {
            return clarification(AppText.get("Please provide a report-day frequency between 0 and 1 (or 0% and 100%)."))
        }
        val names = args.stringList("species")
        if (mode in setOf("add", "remove", "replace") && names.isEmpty()) {
            return clarification(AppText.get("Please name at least one species for this target-list change."))
        }
        val expected = repository.listExpectedSpecies(planId)
        val stats = repository.listSpeciesStats(planId)
        val rare = repository.listRareObservations(planId)
        val localMatches = if (mode in setOf("add", "replace")) {
            val knownSpecies = expected.map { it.identityFields() } +
                stats.map { listOf(it.speciesKey, it.speciesCode, it.scientificName, it.commonName, it.displayNameZh.orEmpty()) } +
                rare.map { listOf(it.speciesKey, it.speciesCode, it.scientificName, it.commonName, it.displayNameZh.orEmpty()) }
            val resolved = mutableListOf<PlanExpectedSpecies>()
            names.forEachIndexed { index, name ->
                if (knownSpecies.any { fields -> fields.matchesName(name) }) return@forEachIndexed
                val matches = taxonomy.searchSpecies(name, 8)
                if (matches.isEmpty()) {
                    return skillError(AppText.format("No local bird matched “{0}”. Check the name and try again.", name))
                }
                val exact = matches.firstOrNull { detail ->
                    listOf(detail.scientificName, detail.commonName, detail.chineseName, detail.zhCnName)
                        .matchesName(name)
                }
                if (matches.size > 1 && exact == null) {
                    return OwlettSkillPreparation.WaitingInput(
                        gson.toJson(args),
                        OwlettSkillCardPayload(
                            kind = "selection",
                            title = AppText.get("Choose a species"),
                            summary = AppText.format("“{0}” matches more than one local species.", name),
                            selectionField = "target_species:$index",
                            options = matches.map { detail ->
                                val display = detail.chineseName.ifBlank {
                                    detail.zhCnName.ifBlank { detail.commonName }
                                }
                                OwlettSkillOption(
                                    detail.scientificName,
                                    display,
                                    listOf(detail.commonName, detail.scientificName)
                                        .filter(String::isNotBlank)
                                        .joinToString(" · "),
                                    detail.scientificName
                                )
                            }
                        )
                    )
                }
                val detail = exact ?: matches.first()
                resolved += PlanExpectedSpecies(
                    planId = planId,
                    speciesKey = canonicalSpeciesKey("", detail.scientificName, detail.commonName),
                    speciesCode = "",
                    scientificName = detail.scientificName,
                    commonName = detail.commonName,
                    displayNameZh = detail.chineseName.ifBlank { detail.zhCnName }.takeIf(String::isNotBlank),
                    source = "manual_agent",
                    selectedAtMs = System.currentTimeMillis()
                )
            }
            resolved
        } else emptyList()
        val manualPolicy = args.string("_manual_policy")
        val initial = OwlettTargetListPolicy.calculate(
            planId = planId,
            mode = mode,
            names = names,
            threshold = threshold,
            preserveManual = false,
            existing = expected,
            stats = stats,
            rare = rare,
            localMatches = localMatches
        )
        if (initial.manuallyRemoved.isNotEmpty() && manualPolicy.isBlank() && !context.automatic) {
            return OwlettSkillPreparation.WaitingInput(
                gson.toJson(args),
                OwlettSkillCardPayload(
                    kind = "manual_policy",
                    title = AppText.get("Keep manually added species?"),
                    summary = AppText.format("This filter would remove {0} manually added species.", initial.manuallyRemoved.size),
                    selectionField = "manual_policy",
                    manualSpecies = initial.manuallyRemoved.map(PlanExpectedSpecies::displayName),
                    options = listOf(
                        OwlettSkillOption("preserve", AppText.get("Keep manual species"), AppText.get("Recommended"), "preserve"),
                        OwlettSkillOption("remove", AppText.get("Remove them too"), AppText.get("Include them in this change"), "remove")
                    )
                )
            )
        }
        val result = OwlettTargetListPolicy.calculate(
            planId = planId,
            mode = mode,
            names = names,
            threshold = threshold,
            preserveManual = manualPolicy == "preserve" || (context.automatic && manualPolicy.isBlank() && !args.boolean("remove_manual")),
            existing = expected,
            stats = stats,
            rare = rare,
            localMatches = localMatches
        )
        args.add("_desired_species", gson.toJsonTree(result.desired))
        args.addProperty("_version_token", OwlettTargetListPolicy.versionToken(plan.analysisGeneratedAtMs, expected))
        return OwlettSkillPreparation.WaitingConfirmation(
            gson.toJson(args),
            OwlettSkillCardPayload(
                kind = "targets_preview",
                title = AppText.format("Update {0}?", plan.name),
                summary = AppText.format("Add {0} · Remove {1} · Keep {2}", result.added.size, result.removed.size, result.kept.size),
                action = "execute",
                planId = planId,
                addedSpecies = result.added.map(PlanExpectedSpecies::displayName),
                removedSpecies = result.removed.map(PlanExpectedSpecies::displayName),
                keptSpecies = result.kept.map(PlanExpectedSpecies::displayName),
                manualSpecies = result.manuallyRemoved.map(PlanExpectedSpecies::displayName)
            )
        )
    }

    override suspend fun execute(argumentsJson: String, context: OwlettSkillContext): OwlettSkillExecution {
        val args = requireNotNull(parseObject(argumentsJson))
        val planId = requireNotNull(args.long("_plan_id"))
        val plan = repository.getPlan(planId) ?: return executionError(AppText.get("The selected Plan no longer exists."))
        val currentExpected = repository.listExpectedSpecies(planId)
        val expectedToken = args.string("_version_token")
        val currentToken = OwlettTargetListPolicy.versionToken(plan.analysisGeneratedAtMs, currentExpected)
        if (expectedToken != currentToken) {
            args.remove("_desired_species")
            args.remove("_version_token")
            args.remove("_manual_policy")
            return OwlettSkillExecution(
                toolResponse = "",
                card = OwlettSkillCardPayload(
                    kind = "targets_refresh",
                    title = AppText.get("Plan changed"),
                    summary = AppText.get("The target-list preview is being recalculated before anything is written.")
                ),
                reprepareArgumentsJson = gson.toJson(args)
            )
        }
        val desiredType = object : TypeToken<List<PlanExpectedSpecies>>() {}.type
        val desired = runCatching {
            gson.fromJson<List<PlanExpectedSpecies>>(args.get("_desired_species"), desiredType)
        }.getOrNull() ?: return executionError(AppText.get("The prepared target list could not be restored."))
        repository.replaceExpectedSpeciesIdempotent(context.operationId, planId, desired)
        return OwlettSkillExecution(
            toolResponse = gson.toJson(mapOf("status" to "updated", "planId" to planId, "speciesCount" to desired.size)),
            card = OwlettSkillCardPayload(
                kind = "targets_complete",
                title = AppText.get("Target list updated"),
                summary = AppText.format("{0} now contains {1} expected species.", plan.name, desired.size),
                action = "open_plan",
                planId = planId
            )
        )
    }

    override fun applySelection(argumentsJson: String, field: String, valueJson: String): String {
        val args = parseObject(argumentsJson) ?: JsonObject()
        when (field) {
            "plan" -> args.addProperty("_plan_id", valueJson.toLongOrNull())
            "manual_policy" -> args.addProperty("_manual_policy", valueJson)
            else -> if (field.startsWith("target_species:")) {
                val index = field.substringAfter(':').toIntOrNull()
                val names = args.getAsJsonArray("species")
                if (index != null && names != null && index in 0 until names.size()) {
                    names.set(index, gson.toJsonTree(valueJson))
                }
            }
        }
        return gson.toJson(args)
    }

    private fun normalizedThreshold(value: Float?): Float? = value?.let { if (it > 1f) it / 100f else it }

    companion object {
        const val ID = "update_target_list"
        private val SUPPORTED_MODES = setOf(
            "add", "remove", "replace", "keep_recent_rare", "max_combined_frequency"
        )
    }
}

private fun PlanExpectedSpecies.identityFields(): List<String> = listOf(
    speciesKey,
    speciesCode,
    scientificName,
    commonName,
    displayNameZh.orEmpty()
)

private fun List<String>.matchesName(query: String): Boolean {
    val normalized = query.trim().lowercase()
    return normalized.isNotBlank() && any { it.trim().lowercase() == normalized }
}

private class BirdLookupSkill(
    private val repository: PlansTripsRepository,
    private val taxonomy: BirdTaxonomyRepository,
    private val ebird: EbirdRecentObservationsClient,
    private val sources: com.example.birdingsoundmvp.planning.BirdObservationSources? = null
) : OwlettSkill {
    override val descriptor = OwlettSkillDescriptor(
        id = ID,
        slashCommand = "/bird",
        displayName = AppText.get("Bird guide"),
        description = AppText.get("Show local bird information and recent eBird activity"),
        writesAppData = false
    )
    override val toolDefinition = OwlettToolDefinition(
        name = ID,
        description = "Look up a bird in the app's local field guide and, when a Plan is attached, recent eBird observations at its hotspot.",
        parametersJsonSchema = """
            {"type":"object","properties":{
              "query":{"type":"string","description":"Chinese, English, or scientific species name"},
              "scientific_name":{"type":"string","description":"Resolved scientific name when known"},
              "include_recent_observations":{"type":"boolean","description":"True when the user asks where or how recently this species has been reported"}
            },"required":["query"]}
        """.trimIndent()
    )

    override suspend fun prepare(argumentsJson: String, context: OwlettSkillContext): OwlettSkillPreparation {
        val speciesPreparation = prepareSpecies(argumentsJson, taxonomy, "bird_species")
        if (speciesPreparation !is OwlettSkillPreparation.Ready) return speciesPreparation
        val args = requireNotNull(parseObject(speciesPreparation.normalizedArgumentsJson))
        if (args.boolean("include_recent_observations") &&
            args.long("_plan_id") == null &&
            context.attachedPlanId == null
        ) {
            val plans = repository.listPlans()
            if (plans.isEmpty()) {
                return clarification(AppText.get("Attach or create a Plan so I know which hotspot to check for recent observations."))
            }
            return OwlettSkillPreparation.WaitingInput(
                gson.toJson(args),
                OwlettSkillCardPayload(
                    kind = "selection",
                    title = AppText.get("Choose a hotspot"),
                    summary = AppText.get("Select a Plan whose eBird hotspot should be checked."),
                    selectionField = "bird_plan",
                    options = plans.map { plan ->
                        OwlettSkillOption(
                            plan.id.toString(),
                            plan.name,
                            "${plan.plannedDate} · ${plan.hotspotName}",
                            plan.id.toString()
                        )
                    }
                )
            )
        }
        return speciesPreparation
    }

    override suspend fun execute(argumentsJson: String, context: OwlettSkillContext): OwlettSkillExecution {
        val args = requireNotNull(parseObject(argumentsJson))
        val scientific = args.string("scientific_name")
        val detail = taxonomy.findSpecies(scientific, args.string("query"))
            ?: return executionError(AppText.get("No matching species was found in the local bird database."))
        val wantsRecent = args.boolean("include_recent_observations")
        val plan = (args.long("_plan_id") ?: context.attachedPlanId)?.let(repository::getPlan)
        var recentNote = ""
        var recentCache: ObservationCacheRead? = null
        val recent = when {
            !wantsRecent -> emptyList()
            plan == null -> emptyList<OwlettBirdObservationSummary>().also {
                recentNote = AppText.get("No hotspot was selected for recent observations.")
            }
            plan.hotspotId.isBlank() -> emptyList<OwlettBirdObservationSummary>().also {
                recentNote = AppText.get("The selected Plan does not have an eBird hotspot.")
            }
            (sources?.find(plan.sourceId)?.capabilities?.requiresApiKey ?: true) && context.ebirdApiKey.isBlank() -> emptyList<OwlettBirdObservationSummary>().also {
                recentNote = AppText.get("Add an eBird API key in Settings to check recent observations.")
            }
            else -> when (val response = run {
                val criteria = gson.toJsonTree(mapOf("locationId" to plan.hotspotId, "location" to plan.sourceLocationJson,
                    "end" to LocalDate.now().toString(), "backDays" to 30)).asJsonObject
                val cached = cachedObservation(context, "recent", plan.sourceId, criteria) {
                    when (val fetched = if (plan.sourceId == "ebird") ebird.fetchRecentObservations(context.ebirdApiKey, listOf(plan.hotspotId), 30)
                        else sources?.require(plan.sourceId)?.observationsFor(plan)?.fetchRecentNotableObservations(context.ebirdApiKey, plan.hotspotId, 30)
                            ?: EbirdRecentObservationsResponse.Failure("当前版本不支持此鸟况来源")) {
                        is EbirdRecentObservationsResponse.Success -> gson.toJsonTree(mapOf("records" to fetched.observations, "failedDates" to emptyList<String>())).asJsonObject
                        is EbirdRecentObservationsResponse.Failure -> gson.toJsonTree(mapOf("records" to emptyList<String>(), "failedDates" to listOf(fetched.message))).asJsonObject
                    }
                }
                recentCache = cached
                val failures = cached.query.data.getAsJsonArray("failedDates")
                if (failures.size() > 0) EbirdRecentObservationsResponse.Failure(failures.first().asString)
                else EbirdRecentObservationsResponse.Success(gson.fromJson(cached.query.data.get("records"),
                    Array<com.example.birdingsoundmvp.planning.EbirdRecentObservation>::class.java).toList())
            }) {
                is EbirdRecentObservationsResponse.Success -> response.observations
                    .filter { observation ->
                        canonicalSpeciesKey(observation.speciesCode, observation.scientificName, observation.commonName) ==
                            canonicalSpeciesKey("", detail.scientificName, detail.commonName) ||
                            observation.scientificName.equals(detail.scientificName, ignoreCase = true)
                    }
                    .sortedByDescending { it.observedAt }
                    .take(5)
                    .map { OwlettBirdObservationSummary(it.observedAt, it.locationName, it.count) }
                    .also {
                        recentNote = if (it.isEmpty()) {
                            AppText.format("No reports for this species were found at {0} in the last 30 days.", plan.hotspotName)
                        } else {
                            AppText.format("Recent reports at {0}", plan.hotspotName)
                        }
                    }
                is EbirdRecentObservationsResponse.Failure -> emptyList<OwlettBirdObservationSummary>().also {
                    recentNote = response.message
                }
            }
        }
        val displayName = detail.chineseName.ifBlank { detail.zhCnName.ifBlank { detail.commonName } }
        val card = OwlettSkillCardPayload(
            kind = "bird_detail",
            title = displayName,
            summary = listOf(detail.commonName, detail.scientificName).filter(String::isNotBlank).joinToString(" · "),
            action = "open_species",
            birdDetail = detail,
            recentObservations = recent,
            recentObservationsNote = recentNote + if (recentCache?.reused == true) "\n复用本对话快照，未重新查询" else ""
        )
        val account = detail.ibirding
        val response = mapOf(
            "query_id" to recentCache?.takeIf { it.stored }?.query?.id,
            "cacheReused" to recentCache?.reused,
            "species" to displayName,
            "scientificName" to detail.scientificName,
            "description" to account?.description.orEmpty().take(800),
            "range" to account?.rangeText.orEmpty().take(500),
            "habits" to account?.habits.orEmpty().take(500),
            "voice" to account?.voice.orEmpty().take(400),
            "recentObservationStatus" to recentNote,
            "recentHotspotObservations" to recent
        )
        return OwlettSkillExecution(gson.toJson(response), card)
    }

    override fun applySelection(argumentsJson: String, field: String, valueJson: String): String {
        if (field == "bird_plan") {
            val args = parseObject(argumentsJson) ?: JsonObject()
            args.addProperty("_plan_id", valueJson.toLongOrNull())
            return gson.toJson(args)
        }
        return applySpeciesSelection(argumentsJson, field, valueJson)
    }

    companion object { const val ID = "lookup_bird" }
}

private class BirdCallsSkill(
    private val taxonomy: BirdTaxonomyRepository,
    private val xenoCanto: XenoCantoClient
) : OwlettSkill {
    override val descriptor = OwlettSkillDescriptor(
        id = ID,
        slashCommand = "/calls",
        displayName = AppText.get("Bird calls"),
        description = AppText.get("Find playable xeno-canto recordings"),
        writesAppData = false
    )
    override val toolDefinition = OwlettToolDefinition(
        name = ID,
        description = "Find xeno-canto calls. Default uses attached Plan or system location, NOT worldwide. Pass country (ISO2 or country name) and optional locality (English place keyword) when the user or conversation specifies a region; global=true only when requested. App widens a system-local search to the country and then worldwide ONLY if that country has zero records, never on network errors. Explicit/context regions are not silently broadened.",
        parametersJsonSchema = """
            {"type":"object","properties":{
              "query":{"type":"string","description":"Chinese, English, or scientific species name"},
              "scientific_name":{"type":"string"},
              "country":{"type":"string"},
              "locality":{"type":"string"},
              "global":{"type":"boolean"}
            },"required":["query"]}
        """.trimIndent()
    )

    override suspend fun prepare(argumentsJson: String, context: OwlettSkillContext): OwlettSkillPreparation {
        if (context.xenoCantoApiKey.isNullOrBlank()) {
            return OwlettSkillPreparation.Immediate(
                toolResponse = "{\"error\":\"xeno-canto API key is not configured\"}",
                card = OwlettSkillCardPayload(
                    kind = "settings_prompt",
                    title = AppText.get("Set up xeno-canto"),
                    summary = AppText.get("Add your xeno-canto API key in Settings to search and play recordings."),
                    action = "open_settings"
                ),
                isError = true
            )
        }
        return prepareSpecies(argumentsJson, taxonomy, "bird_species")
    }

    override suspend fun execute(argumentsJson: String, context: OwlettSkillContext): OwlettSkillExecution {
        val args = requireNotNull(parseObject(argumentsJson))
        val scientific = args.string("scientific_name")
        val detail = taxonomy.findSpecies(scientific, args.string("query"))
            ?: return executionError(AppText.get("No matching species was found in the local bird database."))
        val region = BirdCallRegions.resolve(args.string("country"), args.string("locality"), args.get("global")?.asBoolean == true,
            context.attachedBirdCallRegion, context.resolveBirdCallRegion)
            ?: return executionError("无法确定你所在的国家或地区。请开启系统定位及应用的位置选项，或告诉我在哪个国家/地区查找鸟鸣。")
        return when (val response = xenoCanto.searchRegional(requireNotNull(context.xenoCantoApiKey), detail.scientificName, region)) {
            is XenoCantoResponse.Failure -> executionError(response.message)
            is XenoCantoResponse.Success -> {
                val displayName = detail.chineseName.ifBlank { detail.zhCnName.ifBlank { detail.commonName } }
                OwlettSkillExecution(
                    toolResponse = gson.toJson(
                        mapOf(
                            "species" to displayName,
                            "scientificName" to detail.scientificName,
                            "searchScope" to response.scopeNote,
                            "totalInScope" to response.total,
                            "recordings" to response.recordings.map { recording ->
                                mapOf(
                                    "id" to recording.id,
                                    "type" to recording.soundType,
                                    "quality" to recording.quality,
                                    "recordist" to recording.recordist,
                                    "location" to recording.location,
                                    "country" to recording.country,
                                    "date" to recording.date,
                                    "license" to recording.license
                                )
                            }
                        )
                    ),
                    card = OwlettSkillCardPayload(
                        kind = "bird_calls",
                        title = AppText.format("{0} calls", displayName),
                        summary = "${detail.scientificName}\n${response.scopeNote}" + if (response.recordings.isEmpty()) "\n当前范围没有可播放的录音" else "",
                        recordings = response.recordings
                    )
                )
            }
        }
    }

    override fun applySelection(argumentsJson: String, field: String, valueJson: String): String =
        applySpeciesSelection(argumentsJson, field, valueJson)

    companion object { const val ID = "find_bird_calls" }
}

private fun prepareSpecies(
    argumentsJson: String,
    taxonomy: BirdTaxonomyRepository,
    selectionField: String
): OwlettSkillPreparation {
    val args = parseObject(argumentsJson) ?: return invalidArguments(AppText.get("Species parameters were not valid JSON."))
    val query = args.string("scientific_name").ifBlank { args.string("query") }
    if (query.isBlank()) return clarification(AppText.get("Which bird species should I look up?"))
    val matches = taxonomy.searchSpecies(query, 8)
    if (matches.isEmpty()) return skillError(AppText.get("No matching species was found in the local bird database."))
    val exact = matches.firstOrNull { detail ->
        listOf(detail.scientificName, detail.commonName, detail.chineseName, detail.zhCnName)
            .any { it.equals(query, ignoreCase = true) }
    }
    if (matches.size > 1 && exact == null) {
        return OwlettSkillPreparation.WaitingInput(
            gson.toJson(args),
            OwlettSkillCardPayload(
                kind = "selection",
                title = AppText.get("Choose a species"),
                summary = AppText.format("“{0}” matches more than one local species.", query),
                selectionField = selectionField,
                options = matches.map { detail ->
                    val display = detail.chineseName.ifBlank { detail.zhCnName.ifBlank { detail.commonName } }
                    OwlettSkillOption(
                        id = detail.scientificName,
                        label = display,
                        supportingText = listOf(detail.commonName, detail.scientificName)
                            .filter(String::isNotBlank)
                            .joinToString(" · "),
                        valueJson = detail.scientificName
                    )
                }
            )
        )
    }
    args.addProperty("scientific_name", (exact ?: matches.first()).scientificName)
    return OwlettSkillPreparation.Ready(gson.toJson(args))
}

private fun applySpeciesSelection(argumentsJson: String, field: String, valueJson: String): String {
    val args = parseObject(argumentsJson) ?: JsonObject()
    if (field == "bird_species") args.addProperty("scientific_name", valueJson)
    return gson.toJson(args)
}

private fun matchingRegions(regions: List<EbirdRegionOption>, query: String): List<EbirdRegionOption> {
    val normalized = query.trim()
    val exact = regions.filter {
        EbirdRegionNames.matches(it, normalized)
    }
    if (exact.isNotEmpty()) return exact
    return regions.filter { it.name.contains(normalized, ignoreCase = true) }.take(20)
}

private fun invalidArguments(message: String) = OwlettSkillPreparation.Immediate(
    toolResponse = gson.toJson(mapOf("error" to message, "retryable" to true)),
    isError = true
)

private fun clarification(message: String) = OwlettSkillPreparation.Immediate(
    toolResponse = gson.toJson(mapOf("needsUserInput" to true, "message" to message)),
    awaitsUserInput = true
)

private fun skillError(message: String) = OwlettSkillPreparation.Immediate(
    toolResponse = gson.toJson(mapOf("error" to message)),
    card = OwlettSkillCardPayload(kind = "error", title = AppText.get("Skill unavailable"), summary = message),
    isError = true
)

private fun executionError(message: String) = OwlettSkillExecution(
    toolResponse = gson.toJson(mapOf("error" to message)),
    card = OwlettSkillCardPayload(kind = "error", title = AppText.get("Skill failed"), summary = message),
    isError = true
)

private fun parseObject(json: String): JsonObject? = runCatching {
    JsonParser.parseString(json.ifBlank { "{}" }).takeIf { it.isJsonObject }?.asJsonObject
}.getOrNull()

private fun JsonObject.string(name: String): String = get(name)
    ?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
    ?.let { runCatching { it.asString }.getOrNull() }
    .orEmpty()

private fun JsonObject.long(name: String): Long? = get(name)
    ?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
    ?.let { runCatching { it.asLong }.getOrNull() }

private fun JsonObject.float(name: String): Float? = get(name)
    ?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
    ?.let { runCatching { it.asFloat }.getOrNull() }

private fun JsonObject.boolean(name: String): Boolean = get(name)
    ?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
    ?.let { runCatching { it.asBoolean }.getOrNull() }
    ?: false

private fun JsonObject.stringList(name: String): List<String> = get(name)
    ?.takeIf { it.isJsonArray }
    ?.asJsonArray
    ?.mapNotNull { item -> item.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf(String::isNotBlank) }
    .orEmpty()

private val gson = Gson()
