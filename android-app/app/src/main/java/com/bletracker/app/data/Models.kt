package com.bletracker.app.data

/** Raw 10-byte BLE tracker payload decoded from a manufacturer-specific advertisement. */
data class TrackerPacket(
    /** 0 = closed, 1 = open. */
    val bagState: Int,
    /** 0 = critical power, 1 = low power, 2 = stable supply, 3 = external power present. */
    val batteryLevel: Int,
    /** 8-bit rolling sequence number; wraps 255 → 0. */
    val seqNum: Int,
    /** 0 = HEARTBEAT, 1 = STATE_CHANGE, 2 = SELF_TEST. */
    val packetType: Int,
    /** Bitmask of health fault flags (bit 0 = reed fault, bit 1 = boot contradiction, bit 2 = ADC fault). */
    val healthStatus: Int,
    /** Number of days since the bag state last changed, capped at 255. */
    val daysSinceChange: Int,
)

/** Payload for an immediate (non-queued) sighting upload to the backend. */
data class SightingPayload(
    val scannerUserId: String,
    val manufacturerId: String,
    val deviceId: String,
    val rssi: Int?,
    val location: LocationPayload?,
    val packet: TrackerPacket,
)

/** A sighting that has been persisted to [SightingQueueStore] pending upload. */
data class QueuedSighting(
    val scannerUserId: String,
    val manufacturerId: String,
    val deviceId: String,
    val rssi: Int?,
    val location: LocationPayload?,
    val packet: TrackerPacket,
    /** Epoch-millisecond timestamp at which this sighting was enqueued locally. */
    val queuedAtEpochMs: Long,
)

/** GPS/network location attached to a sighting. */
data class LocationPayload(
    val lat: Double,
    val lng: Double,
    /** Horizontal accuracy radius in metres, if available. */
    val accuracyMeters: Float?,
)

/** Minimal user profile returned by the backend. */
data class UserDto(
    val id: String,
    val name: String,
    val email: String,
)

/** Successful authentication response containing a bearer token and user profile. */
data class AuthSessionDto(
    val authToken: String,
    val expiresAt: String,
    val user: UserDto,
)

/** Full bootstrap payload returned by `/api/bootstrap` on app start. */
data class BootstrapDto(
    val user: UserDto,
    val devices: List<DeviceDto>,
    val alerts: List<AlertDto>,
    val geofences: List<GeofenceDto>,
    val serverTime: String,
)

/** Device record as returned by the backend API. */
data class DeviceDto(
    val id: String,
    /** Canonical hex device ID string, e.g. `"0x0000AB01"`. */
    val deviceId: String,
    val ownerUserId: String?,
    val displayName: String,
    val lastSeenAt: String?,
    val lastLocation: LocationPayload?,
    val lastRssi: Int?,
    /** One of `"inside"`, `"outside"`, or `"unknown"`. */
    val geofenceState: String?,
    /** One of `"claimed"` or `"unclaimed"`. */
    val status: String,
    val proximityMonitoringEnabled: Boolean,
    val lastPacket: DevicePacketDto?,
)

/** Enriched packet record stored on the device, with human-readable label fields. */
data class DevicePacketDto(
    val bagState: Int,
    val batteryLevel: Int,
    val seqNum: Int,
    val packetType: Int,
    val healthStatus: Int,
    val daysSinceChange: Int,
    val packetTypeName: String,
    val bagStateName: String,
    /** Human-readable power status label from the backend. */
    val batteryLevelName: String,
)

/** Alert record as returned by the backend API. */
data class AlertDto(
    val id: String,
    /** Alert type string, e.g. `"BAG_OPENED"`, `"PROXIMITY_LOST"`. */
    val type: String,
    /** One of `"open"`, `"acknowledged"`, or `"closed"`. */
    val status: String,
    val deviceId: String,
    val message: String,
    val createdAt: String,
)

/** Geofence record as returned by the backend API. */
data class GeofenceDto(
    val id: String,
    val userId: String,
    val name: String,
    val center: GeofenceCenterDto,
    val radiusMeters: Int,
    val enabled: Boolean,
)

/** WGS-84 coordinate pair for a geofence centre. */
data class GeofenceCenterDto(
    val lat: Double,
    val lng: Double,
)

/**
 * Snapshot of the most recently relayed BLE packet, persisted to [Prefs]
 * so the UI can display it even when the service is restarted.
 */
data class RelaySnapshotDto(
    val deviceId: String,
    val bagState: Int,
    val packetType: Int,
    val seqNum: Int,
    val rssi: Int?,
    val seenAtEpochMs: Long,
)
