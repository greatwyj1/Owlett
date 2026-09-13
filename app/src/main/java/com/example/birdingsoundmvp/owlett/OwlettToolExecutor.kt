package com.example.birdingsoundmvp.owlett

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object OwlettToolParameters {
    fun validate(argumentsJson: String, schemaJson: String, prepared: Boolean = false) {
        val value = JsonParser.parseString(argumentsJson)
        val schema = JsonParser.parseString(schemaJson).asJsonObject
        validateValue(value, schema, prepared, "参数")
    }

    private fun validateValue(value: JsonElement, schema: JsonObject, prepared: Boolean, path: String) {
        val type = schema.get("type")?.asString
        val valid = when (type) {
            "object" -> value.isJsonObject
            "array" -> value.isJsonArray
            "string" -> value.isJsonPrimitive && value.asJsonPrimitive.isString
            "boolean" -> value.isJsonPrimitive && value.asJsonPrimitive.isBoolean
            "number", "integer" -> value.isJsonPrimitive && value.asJsonPrimitive.isNumber &&
                value.asDouble.isFinite() && (type != "integer" || value.asDouble % 1.0 == 0.0)
            else -> true
        }
        require(valid) { "$path 类型不正确" }
        schema.getAsJsonArray("enum")?.let { require(value in it) { "$path 不在允许的选项中" } }
        if (value.isJsonObject) {
            val properties = schema.getAsJsonObject("properties") ?: JsonObject()
            schema.getAsJsonArray("required")?.forEach {
                require(value.asJsonObject.has(it.asString) && !value.asJsonObject[it.asString].isJsonNull) { "缺少必要参数：${it.asString}" }
            }
            value.asJsonObject.entrySet().forEach { (key, child) ->
                if (prepared && key.startsWith("_")) return@forEach
                require(!key.startsWith("_") && properties.has(key)) { "不允许的参数：$key" }
                validateValue(child, properties.getAsJsonObject(key), prepared, "$path.$key")
            }
        }
        if (value.isJsonArray) schema.getAsJsonObject("items")?.let { item ->
            value.asJsonArray.forEach { validateValue(it, item, prepared, path) }
        }
    }
}

class OwlettToolExecutor(private val tools: List<OwlettTool>) {
    init { require(tools.map { it.descriptor.id }.distinct().size == tools.size) }
    fun find(id: String) = tools.firstOrNull { it.descriptor.id == id }
    fun definitions(allowed: Set<String>?) = tools.filter { allowed == null || it.descriptor.id in allowed }.map { it.toolDefinition }

    suspend fun prepare(tool: OwlettTool, arguments: String, context: OwlettSkillContext): OwlettSkillPreparation {
        checkAccess(tool, context)
        OwlettToolParameters.validate(arguments, tool.toolDefinition.parametersJsonSchema, prepared = true)
        val preparation = tool.prepare(arguments, context)
        return if (preparation is OwlettSkillPreparation.Ready && tool.descriptor.writesAppData) {
            OwlettSkillPreparation.WaitingConfirmation(preparation.normalizedArgumentsJson,
                OwlettSkillCardPayload("tool_preview", tool.descriptor.displayName, "确认执行这项数据修改", "execute"))
        } else preparation
    }

    suspend fun execute(tool: OwlettTool, arguments: String, context: OwlettSkillContext): OwlettSkillExecution {
        checkAccess(tool, context)
        check(!tool.descriptor.writesAppData || context.automatic || context.writeAuthorized) { "数据修改尚未得到确认" }
        check(!com.example.birdingsoundmvp.transfer.DataTransferGate.active) { "数据迁移期间暂不能执行工具" }
        return tool.execute(arguments, context)
    }

    private fun checkAccess(tool: OwlettTool, context: OwlettSkillContext) {
        check(context.allowedToolIds == null || tool.descriptor.id in context.allowedToolIds) { "当前任务未开放此工具" }
    }
}

class OwlettFailureGuard(private val maximum: Int = 3) {
    private var previous: String? = null
    private var repeats = 0
    fun record(toolId: String, error: String?): Boolean {
        val signature = error?.let { "$toolId:$it" }
        repeats = if (signature == null) 0 else if (signature == previous) repeats + 1 else 1
        previous = signature
        return repeats >= maximum
    }
}
