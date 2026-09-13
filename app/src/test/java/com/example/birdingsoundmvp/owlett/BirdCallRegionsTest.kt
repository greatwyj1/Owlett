package com.example.birdingsoundmvp.owlett

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BirdCallRegionsTest {
    @Test fun normalizesCountriesWithoutGuessingFromLanguage() {
        assertEquals("China", BirdCallRegions.countryName("中国"))
        assertEquals("United States", BirdCallRegions.countryName("US"))
        assertEquals("United Kingdom", BirdCallRegions.countryName("UK"))
        assertNull(BirdCallRegions.countryName("未知地区"))
    }
    @Test fun explicitContextAndAttachmentOverrideDevice() = runBlocking {
        val attached = BirdCallRegion("Japan", origin = "对话指定地区")
        val explicit = BirdCallRegions.resolve("中国", "Beijing", false, attached) { error("device not needed") }!!
        assertEquals("China", explicit.country)
        assertEquals("Beijing", explicit.locality)
        assertEquals(attached, BirdCallRegions.resolve("", "", false, attached) { error("device not needed") })
        assertEquals("", BirdCallRegions.resolve("", "", true, attached) { error("device not needed") }!!.country)
    }
    @Test fun usesSystemRegionAndDoesNotInventLocationWhenMissing() = runBlocking {
        val device = BirdCallRegion("China", "Sichuan")
        assertEquals(device, BirdCallRegions.resolve("", "", false, null) { device })
        assertNull(BirdCallRegions.resolve("", "", false, null) { null })
    }
}
