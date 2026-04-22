package com.bletracker.app.data

import android.content.Context

class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("ble_tracker_prefs", Context.MODE_PRIVATE)

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

    var scannerUserId: String
        get() = prefs.getString("scannerUserId", "scanner_android_1") ?: "scanner_android_1"
        set(value) = prefs.edit().putString("scannerUserId", value.trim()).apply()

    var ownerUserId: String
        get() = prefs.getString("ownerUserId", "") ?: ""
        set(value) = prefs.edit().putString("ownerUserId", value.trim()).apply()

    var authToken: String
        get() = prefs.getString("authToken", "") ?: ""
        set(value) = prefs.edit().putString("authToken", value.trim()).apply()

    var googleWebClientId: String
        get() = prefs.getString("googleWebClientId", "") ?: ""
        set(value) = prefs.edit().putString("googleWebClientId", value.trim()).apply()

    var scannerAutostartEnabled: Boolean
        get() = prefs.getBoolean("scannerAutostartEnabled", false)
        set(value) = prefs.edit().putBoolean("scannerAutostartEnabled", value).apply()

    var notifiedAlertIds: Set<String>
        get() = prefs.getStringSet("notifiedAlertIds", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("notifiedAlertIds", value).apply()

    var notifiedOpenAlertKeys: Set<String>
        get() = prefs.getStringSet("notifiedOpenAlertKeys", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("notifiedOpenAlertKeys", value).apply()

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
    private companion object {
        const val DEFAULT_BACKEND_URL = "http://10.150.47.183:8787"
        const val LEGACY_EMULATOR_URL = "http://10.0.2.2:8787"
    }
}
