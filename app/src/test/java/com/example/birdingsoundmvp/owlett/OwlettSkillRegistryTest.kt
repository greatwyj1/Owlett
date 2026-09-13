package com.example.birdingsoundmvp.owlett

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OwlettSkillRegistryTest {
    @Test
    fun `slash commands resolve case insensitively and preserve registry order`() {
        val plan = FakeSkill("create_plan", "/plan")
        val bird = FakeSkill("lookup_bird", "/bird")
        val registry = OwlettSkillRegistry(listOf(plan, bird))

        assertEquals(listOf("create_plan", "lookup_bird"), registry.descriptors.map { it.id })
        assertEquals("create_plan", registry.findBySlash(" /PLAN ")?.descriptor?.id)
        assertEquals("lookup_bird", registry.find("lookup_bird")?.descriptor?.id)
        assertNull(registry.findBySlash("/missing"))
    }

    private class FakeSkill(id: String, slash: String) : OwlettSkill {
        override val descriptor = OwlettSkillDescriptor(id, slash, id, id, writesAppData = false)
        override val toolDefinition = OwlettToolDefinition(id, id, "{\"type\":\"object\"}")

        override suspend fun prepare(
            argumentsJson: String,
            context: OwlettSkillContext
        ): OwlettSkillPreparation = OwlettSkillPreparation.Ready(argumentsJson)

        override suspend fun execute(
            argumentsJson: String,
            context: OwlettSkillContext
        ): OwlettSkillExecution = OwlettSkillExecution(
            "{}",
            OwlettSkillCardPayload("test", "test")
        )
    }
}
