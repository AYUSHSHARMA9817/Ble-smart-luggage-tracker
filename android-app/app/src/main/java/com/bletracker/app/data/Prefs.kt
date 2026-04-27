package com.bletracker.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Typed accessor for all persistent user preferences stored in
 * `SharedPreferences("ble_tracker_prefs")`.
 *
 * All string values are trimmed on read and write. Trailing slashes are
 * removed from [backendBaseUrl] to simplify URL construction elsewhere.
 *
 * [latestRelaySnapshot] is stored as individual keys rather than serialised
 * JSON to keep the implementation free of JSON parsing dependencies and to
 * allow atomic reads via SharedPreferences' internal lock.
 *
 * @param context Application context used to open the SharedPreferences file.
 */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("ble_tracker_prefs", Context.MODE_PRIVATE)

    /**
     * Base URL of the backend server (no trailing slash).
     * Falls back to [DEFAULT_BACKEND_URL] when blank or set to the legacy
     * Android emulator loopback address.
     */
    var backendBaseUrl: String
        get() {
            val value = prefs.getString("backendBaseUrl", DEFAULT_BACKEND_URL)
                ?.trim()
                ?.removeSuffix("/")
                .orEmpty()
            return when {
                value.isBlank() -> DEFAULT_BACKEND_URL
                value == LEGACY_EMULATOR_URL -> DEFAULT_BACKEND_URL
                else -> value
            }
        }
        set(value) = prefs.edit().putString("backendBaseUrl", value.trim().removeSuffix("/")).apply()

    /** Identifier used as `scannerUserId` in uploaded sighting payloads. */
    var scannerUserId: String
        get() = prefs.getString("scannerUserId", "scanner_android_1") ?: "scanner_android_1"
        set(value) = prefs.edit().putString("scannerUserId", value.trim()).apply()

    /** Owner user ID, set after a successful sign-in. */
    var ownerUserId: String
        get() = prefs.getString("ownerUserId", "") ?: ""
        set(value) = prefs.edit().putString("ownerUserId", value.trim()).apply()

    /** Bearer token for authenticating API requests. Empty string when signed out. */
    var authToken: String
        get() = prefs.getString("authToken", "") ?: ""
        set(value) = prefs.edit().putString("authToken", value.trim()).apply()

    /** Admin registration secret cached locally for the dedicated admin UI mode. */
    var adminRegistrationSecret: String
        get() = prefs.getString("adminRegistrationSecret", "") ?: ""
        set(value) = prefs.edit().putString("adminRegistrationSecret", value.trim()).apply()

    /** Google OAuth web client ID used by [GoogleSignInManager], if configured. */
    var googleWebClientId: String
        get() = prefs.getString("googleWebClientId", "") ?: ""
        set(value) = prefs.edit().putString("googleWebClientId", value.trim()).apply()

    /** When `true`, [BootCompletedReceiver] auto-starts [BleScanService] after boot. */
    var scannerAutostartEnabled: Boolean
        get() = prefs.getBoolean("scannerAutostartEnabled", false)
        set(value) = prefs.edit().putBoolean("scannerAutostartEnabled", value).apply()

    /**
     * IDs of alerts that have already triggered a local notification.
     * Bounded to the most recent 100 IDs to prevent unbounded growth.
     */
    var notifiedAlertIds: Set<String>
        get() = prefs.getStringSet("notifiedAlertIds", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("notifiedAlertIds", value).apply()

    /**
     * `"<type>:<deviceId>"` keys for open alerts that have been notified,
     * used to avoid re-notifying for the same active alert on each poll cycle.
     * Pruned to only currently-active alert keys after each poll.
     */
    var notifiedOpenAlertKeys: Set<String>
        get() = prefs.getStringSet("notifiedOpenAlertKeys", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("notifiedOpenAlertKeys", value).apply()

    /**
     * Snapshot of the most recently relayed BLE packet, stored as individual
     * SharedPreferences keys so it survives process death. Returns `null`
     * when no packet has been relayed yet (identified by a blank device ID).
     */
    var latestRelaySnapshot: RelaySnapshotDto?
        get() {
            val deviceId = prefs.getString("relay_deviceId", "")?.trim().orEmpty()
            if (deviceId.isBlank()) return null
            return RelaySnapshotDto(
                deviceId = deviceId,
                bagState = prefs.getInt("relay_bagState", 0),
                packetType = prefs.getInt("relay_packetType", 0),
                seqNum = prefs.getInt("relay_seqNum", 0),
                rssi = if (prefs.contains("relay_rssi")) prefs.getInt("relay_rssi", 0) else null,
                seenAtEpochMs = prefs.getLong("relay_seenAtEpochMs", 0L),
            )
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    remove("relay_deviceId")
                    remove("relay_bagState")
                    remove("relay_packetType")
                    remove("relay_seqNum")
                    remove("relay_rssi")
                    remove("relay_seenAtEpochMs")
                } else {
                    putString("relay_deviceId", value.deviceId)
                    putInt("relay_bagState", value.bagState)
                    putInt("relay_packetType", value.packetType)
                    putInt("relay_seqNum", value.seqNum)
                    if (value.rssi == null) remove("relay_rssi") else putInt("relay_rssi", value.rssi)
                    putLong("relay_seenAtEpochMs", value.seenAtEpochMs)
                }
            }.apply()
        }

    /** Cached owner device list shown immediately on next app launch before backend refresh. */
    var cachedDevices: List<DeviceDto>
        get() {
            val raw = prefs.getString("cachedDevices", "")?.trim().orEmpty()
            if (raw.isBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).map { index ->
                    parseDevice(array.getJSONObject(index))
                }
            }.getOrDefault(emptyList())
        }
        set(value) = prefs.edit().putString("cachedDevices", JSONArray().apply {
            value.forEach { device -> put(deviceToJson(device)) }
        }.toString()).apply()

    /** Last admin-issued registration result, including the one-time code shown once by the backend. */
    var latestAdminRegistration: AdminRegistrationDto?
        get() {
            val raw = prefs.getString("latestAdminRegistration", "")?.trim().orEmpty()
            if (raw.isBlank()) return null
            return runCatching {
                val json = JSONObject(raw)
                AdminRegistrationDto(
                    id = json.getString("id"),
                    deviceId = json.getString("deviceId"),
                    manualCode = json.getString("manualCode"),
                    note = json.optString("note"),
                    createdAt = json.getString("createdAt"),
                )
            }.getOrNull()
        }
        set(value) = prefs.edit().apply {
            if (value == null) {
                remove("latestAdminRegistration")
            } else {
                putString(
                    "latestAdminRegistration",
                    JSONObject()
                        .put("id", value.id)
                        .put("deviceId", value.deviceId)
                        .put("manualCode", value.manualCode)
                        .put("note", value.note)
                        .put("createdAt", value.createdAt)
                        .toString()
                )
            }
        }.apply()

    private companion object {
        /** Production backend URL. Update before release to point at the deployed server. */
        const val DEFAULT_BACKEND_URL = "https://ble-smart-luggage-tracker.onrender.com"
        /** Android emulator loopback URL used in previous versions; redirected to the default. */
        const val LEGACY_EMULATOR_URL = "http://10.0.2.2:8787"
    }

    private fun deviceToJson(device: DeviceDto): JSONObject =
        JSONObject()
            .put("id", device.id)
            .put("deviceId", device.deviceId)
            .put("ownerUserId", device.ownerUserId)
            .put("displayName", device.displayName)
            .put("lastSeenAt", device.lastSeenAt)
            .put("lastLocation", device.lastLocation?.let { location ->
                JSONObject()
                    .put("lat", location.lat)
                    .put("lng", location.lng)
                    .put("accuracyMeters", location.accuracyMeters)
            })
            .put("lastRssi", device.lastRssi)
            .put("geofenceState", device.geofenceState)
            .put("status", device.status)
            .put("proximityMonitoringEnabled", device.proximityMonitoringEnabled)
            .put("lastPacket", device.lastPacket?.let { packet ->
                JSONObject()
                    .put("bagState", packet.bagState)
                    .put("batteryLevel", packet.batteryLevel)
                    .put("seqNum", packet.seqNum)
                    .put("packetType", packet.packetType)
                    .put("healthStatus", packet.healthStatus)
                    .put("daysSinceChange", packet.daysSinceChange)
                    .put("packetTypeName", packet.packetTypeName)
                    .put("bagStateName", packet.bagStateName)
                    .put("batteryLevelName", packet.batteryLevelName)
            })

    private fun parseDevice(json: JSONObject): DeviceDto {
        val lastPacketJson = json.optJSONObject("lastPacket")
        return DeviceDto(
            id = json.getString("id"),
            deviceId = json.getString("deviceId"),
            ownerUserId = json.optString("ownerUserId").ifBlank { null },
            displayName = json.getString("displayName"),
            lastSeenAt = json.optString("lastSeenAt").ifBlank { null },
            lastLocation = json.optJSONObject("lastLocation")?.let {
                LocationPayload(
                    lat = it.getDouble("lat"),
                    lng = it.getDouble("lng"),
                    accuracyMeters = if (it.has("accuracyMeters") && !it.isNull("accuracyMeters")) {
                        it.getDouble("accuracyMeters").toFloat()
                    } else {
                        null
                    }
                )
            },
            lastRssi = if (json.has("lastRssi") && !json.isNull("lastRssi")) json.getInt("lastRssi") else null,
            geofenceState = json.optString("geofenceState").ifBlank { null },
            status = json.getString("status"),
            proximityMonitoringEnabled = json.optBoolean("proximityMonitoringEnabled", true),
            lastPacket = lastPacketJson?.let {
                DevicePacketDto(
                    bagState = it.getInt("bagState"),
                    batteryLevel = it.getInt("batteryLevel"),
                    seqNum = it.getInt("seqNum"),
                    packetType = it.getInt("packetType"),
                    healthStatus = it.getInt("healthStatus"),
                    daysSinceChange = it.getInt("daysSinceChange"),
                    packetTypeName = it.getString("packetTypeName"),
                    bagStateName = it.getString("bagStateName"),
                    batteryLevelName = it.getString("batteryLevelName"),
                )
            }
        )
    }
}
