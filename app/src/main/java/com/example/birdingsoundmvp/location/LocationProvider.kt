package com.example.birdingsoundmvp.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class BirdingLocation(
    val latitude: Double,
    val longitude: Double,
    val provider: String
)

class LocationProvider(context: Context) {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun lastKnownLocation(maxAgeMs: Long = Long.MAX_VALUE): BirdingLocation? {
        if (!hasLocationPermission()) return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        val best = providers.mapNotNull { provider ->
            try {
                locationManager.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }.filter { System.currentTimeMillis() - it.time in 0..maxAgeMs }.maxByOrNull { it.time }
        return best?.toBirdingLocation()
    }

    @android.annotation.SuppressLint("MissingPermission")
    suspend fun currentLocation(): BirdingLocation? {
        if (!hasLocationPermission()) return null
        lastKnownLocation(10 * 60_000)?.let { return it }
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) } ?: return null
        return withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { continuation ->
                val signal = android.os.CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                try {
                    LocationManagerCompat.getCurrentLocation(locationManager, provider, signal, ContextCompat.getMainExecutor(appContext)) {
                        if (continuation.isActive) continuation.resume(it?.toBirdingLocation())
                    }
                } catch (_: SecurityException) { if (continuation.isActive) continuation.resume(null) }
                  catch (_: IllegalArgumentException) { if (continuation.isActive) continuation.resume(null) }
            }
        }
    }

    private fun Location.toBirdingLocation(): BirdingLocation = BirdingLocation(
        latitude = latitude,
        longitude = longitude,
        provider = provider ?: "unknown"
    )
}
