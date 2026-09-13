package com.example.birdingsoundmvp.owlett

import android.content.Context
import android.location.Geocoder
import android.telephony.TelephonyManager
import com.example.birdingsoundmvp.location.LocationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

class BirdCallLocationResolver(context: Context) {
    private val app = context.applicationContext
    private val locations = LocationProvider(app)
    private var cached: Pair<Long, BirdCallRegion>? = null

    @Suppress("DEPRECATION")
    suspend fun resolve(enabled: Boolean): BirdCallRegion? {
        if (!enabled) return null
        cached?.takeIf { System.currentTimeMillis() - it.first in 0..600_000 }?.let { return it.second }
        val location = locations.currentLocation()
        val result = withTimeoutOrNull(5_000) {
            runInterruptible(Dispatchers.IO) {
                val address = if (location != null && Geocoder.isPresent()) runCatching {
                    Geocoder(app, Locale.ENGLISH).getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
                }.getOrNull() else null
                val country = address?.countryCode?.let(BirdCallRegions::countryName)
                if (country != null) BirdCallRegion(country, address.adminArea.orEmpty()) else null
            }
        } ?: runCatching {
            // Current mobile network, not SIM home country or the UI language.
            app.getSystemService(TelephonyManager::class.java)?.networkCountryIso
                ?.let(BirdCallRegions::countryName)?.let { BirdCallRegion(it, origin = "系统网络所在国家") }
        }.getOrNull()
        if (result != null) cached = System.currentTimeMillis() to result
        return result
    }
}
