package com.bletracker.app.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.bletracker.app.data.LocationPayload
import java.util.concurrent.atomic.AtomicLong

object LocationSnapshot {
    private const val MAX_LOCATION_AGE_MS = 2 * 60 * 1000L
    private const val REFRESH_INTERVAL_MS = 15_000L

    @Volatile
    private var cachedLocation: Location? = null
    private val lastRefreshRequestAt = AtomicLong(0L)

    fun current(context: Context): LocationPayload? {
        if (!hasLocationPermission(context)) {
            return null
        }

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val now = System.currentTimeMillis()
        val best = buildList<Location> {
            cachedLocation?.let(::add)
            addAll(lastKnownLocations(manager))
        }
            .filter { location -> now - location.time <= MAX_LOCATION_AGE_MS }
            .maxByOrNull { it.time }

        requestRefreshIfNeeded(context, manager, now)

        return best?.toPayload()
    }

    private fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun lastKnownLocations(manager: LocationManager): List<Location> =
        listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }

    @SuppressLint("MissingPermission")
    private fun requestRefreshIfNeeded(context: Context, manager: LocationManager, now: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }

        val previous = lastRefreshRequestAt.get()
        if (now - previous < REFRESH_INTERVAL_MS || !lastRefreshRequestAt.compareAndSet(previous, now)) {
            return
        }

        val executor = ContextCompat.getMainExecutor(context)
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val enabled = runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
            if (!enabled) continue

            val signal = CancellationSignal()
            runCatching {
                manager.getCurrentLocation(provider, signal, executor) { location ->
                    if (location != null) {
                        cachedLocation = location
                    }
                }
            }
        }
    }

    private fun Location.toPayload() = LocationPayload(
        lat = latitude,
        lng = longitude,
        accuracyMeters = accuracy,
    )
}
