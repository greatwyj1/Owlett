package com.example.birdingsoundmvp.owlett

data class OwlettSceneSkill(val descriptor: OwlettSkillDescriptor, val version: Int, val toolIds: Set<String>) {
    fun instructions(): String = requireNotNull(javaClass.getResourceAsStream("/owlett/skills/${descriptor.id}.md"))
        .bufferedReader().use { it.readText() }
}

object OwlettSceneSkills {
    private val query = setOf("plans_search", "plans_read", "trips_search", "trips_read", "species_search", "skill_read", "observations_cache_read")
    private fun scene(id: String, command: String, name: String, description: String, writes: Boolean, vararg tools: String) =
        OwlettSceneSkill(OwlettSkillDescriptor(id, command, name, description, writes), when (id) {
            "organize_activity" -> 3
            "find_bird_calls", "lookup_bird" -> 2
            else -> 1
        }, query + tools)
    val all = listOf(
        scene("create_plan", "/plan", "计划管理", "创建、编辑、回收或恢复观鸟计划", true,
            "plans_create", "plans_update", "plans_trash", "plans_restore", "locations_search", "regions_list"),
        scene("organize_activity", "/activity", "整理鸟况", "整理计划鸟况与查询近期观察", true,
            "organize_activity", "locations_search", "observations_query", "regions_list"),
        scene("update_target_list", "/targets", "目标鸟种", "根据鸟况和你的要求调整目标清单", true,
            "update_target_list", "targets_replace", "organize_activity", "observations_query"),
        scene("lookup_bird", "/bird", "鸟种资料", "查询本地资料和近期观察", false, "lookup_bird", "observations_query", "locations_search", "regions_list"),
        scene("find_bird_calls", "/calls", "鸟种鸣声", "查找鸣声并提供播放控件", false, "find_bird_calls", "playback_control"),
        scene("link_trip", "/link", "关联行程", "查询并调整计划与已完成录音的关联", true, "link_trip", "trip_links_update"),
        scene("collect_clips", "/clips", "整理录音", "筛选已有识别记录、整理与播放片段", false,
            "collect_clips", "detections_query", "playback_control"),
        scene("settle_plan", "/settle", "行程结算", "结算关联行程并修正识别遗漏或错误", true,
            "settlement_read", "settlement_update", "detections_query"),
        scene("app_settings", "/settings", "普通设置", "查看和修改外观、显示及识别参数", true,
            "settings_read", "settings_update")
    )
    fun find(id: String?) = all.firstOrNull { it.descriptor.id == id }
    fun catalog(): String = all.joinToString("\n") { "${it.descriptor.id}: ${it.descriptor.description}" }
    fun allowed(manualId: String?, automatic: Boolean): Set<String>? =
        if (automatic) null else find(manualId)?.toolIds ?: emptySet()
}
