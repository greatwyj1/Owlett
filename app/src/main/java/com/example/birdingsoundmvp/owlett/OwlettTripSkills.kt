package com.example.birdingsoundmvp.owlett

import com.example.birdingsoundmvp.planning.PlansTripsRepository
import com.example.birdingsoundmvp.taxonomy.BirdTaxonomyRepository
import com.example.birdingsoundmvp.trip.TripHistoryRepository
import com.example.birdingsoundmvp.trip.TripHistoryItem
import com.example.birdingsoundmvp.trip.TripSession
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun tripLabel(trip: TripHistoryItem): String = Instant.ofEpochMilli(trip.startedAtMs).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " · ${trip.durationMs / 1000} 秒"

class OwlettTripSkill(
    private val link: Boolean,
    private val trips: TripHistoryRepository,
    private val plans: PlansTripsRepository,
    private val taxonomy: BirdTaxonomyRepository
) : OwlettSkill {
    override val descriptor = if (link) OwlettSkillDescriptor("link_trip", "/link", "关联行程", "将已完成的录音行程关联到观鸟计划", true)
        else OwlettSkillDescriptor("collect_clips", "/clips", "整理录音", "按鸟种和置信度整理已有识别片段", false)
    override val toolDefinition = OwlettToolDefinition(descriptor.id,
        if (link) "Find a completed recording Trip and a Plan by date/period/name, and ADD their association. Never replace existing links. Ask when ambiguous."
        else "Find audio clips using SAVED detections only, no uploads or new recognition. Resolve species locally. confidence accepts 0.3 or 30%, NOT 3. Ask when ambiguous.",
        """{"type":"object","properties":{"trip_id":{"type":"string"},"date":{"type":"string","description":"Recording date YYYY-MM-DD or M.D; use current year if omitted"},"period":{"type":"string","enum":["morning","afternoon","evening","all"]},"trip_keyword":{"type":"string"},"plan_id":{"type":"integer"},"plan_keyword":{"type":"string"},"plan_date":{"type":"string"},"same_date":{"type":"boolean"},"species":{"type":"string"},"confidence":{"type":"string","description":"0..1 or percent; omit to keep all"},"comparison":{"type":"string","enum":["gt","gte"]}},"required":[]}""")
    private val gson = Gson()

    override suspend fun prepare(argumentsJson: String, context: OwlettSkillContext): OwlettSkillPreparation {
        val args = runCatching { JsonParser.parseString(argumentsJson).asJsonObject }.getOrNull() ?: return fail("无法读取参数，请重新描述需求。")
        val completed = trips.listTrips().filter { it.isCompleted }
        val chosen = args.text("_trip_id").ifBlank { args.text("trip_id").ifBlank { context.attachedTripId.orEmpty() } }
        val keyword = args.text("trip_keyword")
        val matches = runCatching {
            completed.filter { trip ->
                if (chosen.isNotBlank()) trip.tripId == chosen else OwlettTripClips.matchesDate(trip, args.text("date"), args.text("period")) &&
                    (keyword.isBlank() || (tripLabel(trip) + " " + trip.tripId + " " + plans.listPlanTripLinks().filter { it.tripId == trip.tripId }
                        .mapNotNull { plans.getPlan(it.planId)?.name }.joinToString()).contains(keyword, true))
            }
        }.getOrElse { return fail("日期无法解析，请补充明确的月、日和时段。") }
        if (matches.isEmpty()) return fail("未找到符合条件的已完成录音行程。")
        if (matches.size != 1) return choose(args, "_trip_id", "选择录音行程", matches.map {
            OwlettSkillOption(it.tripId, tripLabel(it), "${it.detectionCount} 条识别 · ${if (it.audioBytes > 0) "有音频" else "音频已删除"}", it.tripId)
        })
        val trip = matches.single()
        args.addProperty("_trip_id", trip.tripId)
        if (link) {
            val planId = args.text("_plan_id").ifBlank { args.text("plan_id").ifBlank { context.attachedPlanId?.toString().orEmpty() } }.toLongOrNull()
            val query = args.text("plan_keyword")
            val date = if (args.text("same_date") == "true") Instant.ofEpochMilli(trip.startedAtMs).atZone(ZoneId.systemDefault()).toLocalDate().toString() else args.text("plan_date")
            val candidates = plans.listPlans().filter { plan ->
                if (planId != null) plan.id == planId else (date.isBlank() || plan.plannedDate == date) &&
                    (query.isBlank() || "${plan.name} ${plan.hotspotName}".contains(query, true))
            }
            if (candidates.isEmpty()) return fail("未找到对应观鸟计划，请检查日期或鸟点名称。")
            if (candidates.size != 1) return choose(args, "_plan_id", "选择观鸟计划", candidates.map {
                OwlettSkillOption(it.id.toString(), it.name, "${it.plannedDate} · ${it.hotspotName}", it.id.toString())
            })
            val plan = candidates.single()
            args.addProperty("_plan_id", plan.id)
            return OwlettSkillPreparation.WaitingConfirmation(args.toString(), OwlettSkillCardPayload("link_preview", "关联录音行程", "${tripLabel(trip)} → ${plan.name}（${plan.plannedDate}）", "execute", planId = plan.id, tripId = trip.tripId))
        }
        val species = args.text("_species").ifBlank { args.text("species") }
        if (species.isBlank()) return fail("请告诉我需要整理哪一种鸟的录音。")
        val birds = taxonomy.searchSpecies(species, 50)
        val exact = birds.filter { listOf(it.scientificName, it.commonName, it.chineseName, it.zhCnName).any { name -> name.equals(species, true) } }
        val candidates = exact.ifEmpty { birds }
        if (candidates.isEmpty()) return fail("本地鸟种库中没有找到“$species”，请核对名称。")
        if (candidates.size != 1) return choose(args, "_species", "选择鸟种", candidates.map {
            OwlettSkillOption(it.scientificName, it.chineseName.ifBlank { it.commonName }, it.scientificName, it.scientificName)
        })
        args.addProperty("_species", candidates.single().scientificName)
        args.addProperty("_species_label", candidates.single().chineseName.ifBlank { candidates.single().commonName })
        runCatching { OwlettTripClips.threshold(args.text("confidence")) }.onFailure { return fail(it.message.orEmpty()) }
        if (args.text("confidence").isNotBlank() && args.text("comparison") !in listOf("gt", "gte")) return fail("请说明置信度是“大于”还是“不低于”所给阈值。")
        return OwlettSkillPreparation.Ready(args.toString())
    }

    override suspend fun execute(argumentsJson: String, context: OwlettSkillContext): OwlettSkillExecution {
        val args = JsonParser.parseString(argumentsJson).asJsonObject
        val trip = trips.listTrips().firstOrNull { it.tripId == args.text("_trip_id") && it.isCompleted }
            ?: return errorResult("行程已不存在或尚未完成。")
        if (link) {
            val planId = args.text("_plan_id").toLongOrNull() ?: return errorResult("请重新选择计划。")
            val plan = plans.getPlan(planId) ?: return errorResult("计划已被删除。")
            val added = plans.addPlanTripIdempotent(context.operationId, planId, trip.tripId)
            val summary = if (added) "已将 ${tripLabel(trip)} 关联到 ${plan.name}。" else "这个录音行程已经关联到该计划。"
            return OwlettSkillExecution(gson.toJson(mapOf("status" to "complete", "summary" to summary, "planId" to planId, "tripId" to trip.tripId)),
                OwlettSkillCardPayload("link_complete", "行程已关联", summary, "open_plan", planId = planId, tripId = trip.tripId))
        }
        val session = TripSession.load(File(trip.path)) ?: return errorResult("无法读取行程。")
        val clips = OwlettTripClips.collect(trip.tripId, args.text("_species"), trips.loadDetections(trip.tripId), session.segments,
            OwlettTripClips.threshold(args.text("confidence")), args.text("comparison") == "gt")
        val summary = if (clips.isEmpty()) "没有符合条件的已保存识别记录；这不表示录音中一定没有该鸟种。" else "找到 ${clips.size} 段录音，总计 ${clips.sumOf { it.endMs - it.startMs } / 1000} 秒。"
        return OwlettSkillExecution(gson.toJson(mapOf("status" to "complete", "tripId" to trip.tripId, "species" to args.text("_species"), "clipCount" to clips.size, "summary" to summary,
            "clips" to clips.take(20), "omitted" to maxOf(0, clips.size - 20))),
            OwlettSkillCardPayload("trip_clips", "${args.text("_species_label")} · 录音片段", summary, tripId = trip.tripId, clips = clips))
    }

    override fun applySelection(argumentsJson: String, field: String, valueJson: String): String = JsonParser.parseString(argumentsJson).asJsonObject.apply {
        if (field in setOf("_trip_id", "_plan_id", "_species")) addProperty(field, valueJson)
    }.toString()

    private fun choose(args: JsonObject, field: String, title: String, options: List<OwlettSkillOption>) = OwlettSkillPreparation.WaitingInput(args.toString(),
        OwlettSkillCardPayload("selection", title, "存在多个候选，请选择。", selectionField = field, options = options))
    private fun fail(message: String) = OwlettSkillPreparation.Immediate(gson.toJson(mapOf("error" to message)), OwlettSkillCardPayload("error", "需要补充信息", message), true, true)
    private fun errorResult(message: String) = OwlettSkillExecution(gson.toJson(mapOf("error" to message)), OwlettSkillCardPayload("error", "操作未完成", message), true)
    private fun JsonObject.text(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
}
