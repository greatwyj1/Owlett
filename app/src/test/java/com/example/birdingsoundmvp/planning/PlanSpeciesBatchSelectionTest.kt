package com.example.birdingsoundmvp.planning

import org.junit.Assert.*
import org.junit.Test

class PlanSpeciesBatchSelectionTest {
    private fun species(code: String, scientific: String, source: String = "analysis") =
        PlanExpectedSpecies(1, code.ifBlank { scientific.lowercase() }, code, scientific, scientific, null, source, 12L)

    @Test fun selectingDeduplicatesAcrossListsAndRetainsManualSources() {
        val manual = species("eurbla", "Turdus merula", "manual")
        val alias = species("", "TURDUS MERULA", "rare")
        val rare = species("buwtea", "Spatula discors", "rare")
        val selected = PlanSpeciesBatchSelection.apply(listOf(manual), listOf(alias, rare, rare), true)
        assertEquals(listOf(manual, rare), selected)
        assertEquals("manual", selected.first().source)
        assertEquals(12L, selected.first().selectedAtMs)
    }

    @Test fun clearingOnlyAffectsSpeciesInThatList() {
        val a = species("eurbla", "Turdus merula")
        val b = species("buwtea", "Spatula discors")
        val c = species("grtit1", "Parus major", "manual")
        val result = PlanSpeciesBatchSelection.apply(listOf(a, b, c), listOf(species("", "Turdus merula"), b), false)
        assertEquals(listOf(c), result)
    }

    @Test fun emptyListsAreNoOps() {
        val existing = listOf(species("eurbla", "Turdus merula"))
        assertEquals(existing, PlanSpeciesBatchSelection.apply(existing, emptyList(), true))
        assertEquals(existing, PlanSpeciesBatchSelection.apply(existing, emptyList(), false))
    }
}
