package com.example.birdingsoundmvp.owlett

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OwlettTitleGeneratorTest {
    @Test
    fun `title uses normalized first line`() {
        assertEquals("Birds near the lake", OwlettTitleGenerator.fromFirstMessage("  Birds   near the lake  \nSecond line"))
    }

    @Test
    fun `title is bounded and blank messages use fallback`() {
        assertTrue(OwlettTitleGenerator.fromFirstMessage("x".repeat(100)).length <= 36)
        assertEquals("新建对话", OwlettTitleGenerator.fromFirstMessage("  \n"))
    }
}
