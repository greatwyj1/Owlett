package com.example.birdingsoundmvp.ui

import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlansTripsListBehaviorTest {
    @Test
    fun possibleAndRareSpeciesUseDifferentLazyListKeys() {
        val speciesKey = "amecro"

        assertNotEquals(
            possibleSpeciesItemKey(speciesKey),
            rareSpeciesItemKey(speciesKey)
        )
    }
}
