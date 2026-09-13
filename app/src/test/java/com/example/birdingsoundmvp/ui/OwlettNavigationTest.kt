package com.example.birdingsoundmvp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwlettNavigationTest {
    @Test
    fun `Owlett is the third bottom navigation destination`() {
        assertEquals(
            listOf(AppTab.RECORDING, AppTab.TRIPS, AppTab.OWLETT, AppTab.SETTINGS),
            AppTab.entries
        )
    }

    @Test
    fun `bottom navigation hides only for Owlett while the keyboard is visible`() {
        assertFalse(shouldShowBottomNavigation(AppTab.OWLETT, isReviewingTrip = false, isImeVisible = true))
        assertTrue(shouldShowBottomNavigation(AppTab.OWLETT, isReviewingTrip = false, isImeVisible = false))
        assertTrue(shouldShowBottomNavigation(AppTab.RECORDING, isReviewingTrip = false, isImeVisible = true))
        assertFalse(shouldShowBottomNavigation(AppTab.TRIPS, isReviewingTrip = true, isImeVisible = false))
    }
}
