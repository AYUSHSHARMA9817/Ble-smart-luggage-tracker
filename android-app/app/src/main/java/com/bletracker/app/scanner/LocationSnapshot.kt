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

/**
 * Provides the best available recent GPS/network location as a [LocationPayload]
 * without requiring a persistent location listener.
 *
 * Strategy:
 * 1. Check the cached [cachedLocation] (updated by [requestRefreshIfNeeded]).
 * 2. Query [LocationManager.getLastKnownLocation] for each available provider.
 * 3. Filter out locations older than [MAX_LOCATION_AGE_MS] (2 minutes).
 * 4. Return the most recent remaining location.
 *
 * [requestRefreshIfNeeded] fires a one-shot [LocationManager.getCurrentLocation]
 * request (API 30+) at most once every [REFRESH_INTERVAL_MS] (15 seconds) to
 * keep the cached location reasonably fresh without draining the battery.
 */
object LocationSnapshot {
    /** Locations older than this are considered stale and discarded. */
    private const val MAX_LOCATION_AGE_MS = 2 * 60 * 1000L

    /** Minimum interval between active refresh requests to limit battery usage. */
    private const val REFRESH_INTERVAL_MS = 15_000L

    /** Most recent location obtained from a [LocationManager.getCurrentLocation] callback. */
    @Volatile
    private var cachedLocation: Location? = null

    /** Epoch-ms timestamp of the last refresh request, used for rate-limiting. */
    private val lastRefreshRequestAt = AtomicLong(0L)

    /**
     * Return the best recent location as a [LocationPayload], or `null` if
     * location permission is not granted or no fresh location is available.
     *
     * Also schedules a background refresh if enough time has passed since the
     * last refresh request.
     *
     * @param context Android context used to check permissions and obtain the system service.
     */
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

    /** Return true if [Manifest.permission.ACCESS_FINE_LOCATION] is granted. */
    private fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Query the last known location from GPS, network, and passive providers.
     * Errors from individual providers (e.g. provider disabled) are silently ignored.
     */
    private fun lastKnownLocations(manager: LocationManager): List<Location> =
        listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }

    /**
     * If at least [REFRESH_INTERVAL_MS] have elapsed since the last request,
     * fire a one-shot [LocationManager.getCurrentLocation] for each enabled
     * provider (GPS and network) to update [cachedLocation].
     *
     * Requires API level 30 (Android R); silently skips on older devices.
     * The permission check is deferred to the caller via [current].
     */
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

    /** Convert an Android [Location] to a [LocationPayload] for backend upload. */
    private fun Location.toPayload() = LocationPayload(
        lat = latitude,
        lng = longitude,
        accuracyMeters = accuracy,
    )
}
