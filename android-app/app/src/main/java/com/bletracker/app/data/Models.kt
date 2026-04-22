package com.bletracker.app.data

data class TrackerPacket(
    val bagState: Int,
    val batteryLevel: Int,
    val seqNum: Int,
    val packetType: Int,
    val healthStatus: Int,
    val daysSinceChange: Int,
)

data class SightingPayload(
    val scannerUserId: String,
    val manufacturerId: String,
    val deviceId: String,
    val rssi: Int?,
    val location: LocationPayload?,
    val packet: TrackerPacket,
)

data class QueuedSighting(
    val scannerUserId: String,
    val manufacturerId: String,
    val deviceId: String,
    val rssi: Int?,
    val location: LocationPayload?,
    val packet: TrackerPacket,
    val queuedAtEpochMs: Long,
)

data class LocationPayload(
    val lat: Double,
    val lng: Double,
    val accuracyMeters: Float?,
)

data class UserDto(
    val id: String,
    val name: String,
    val email: String,
)

data class AuthSessionDto(
    val authToken: String,
    val expiresAt: String,
    val user: UserDto,
)

data class BootstrapDto(
    val user: UserDto,
    val devices: List<DeviceDto>,
    val alerts: List<AlertDto>,
    val geofences: List<GeofenceDto>,
    val serverTime: String,
)

data class DeviceDto(
    val id: String,
    val deviceId: String,
    val ownerUserId: String?,
    val displayName: String,
    val lastSeenAt: String?,
    val lastLocation: LocationPayload?,
    val lastRssi: Int?,
    val geofenceState: String?,
    val status: String,
    val proximityMonitoringEnabled: Boolean,
    val lastPacket: DevicePacketDto?,
)

data class DevicePacketDto(
    val bagState: Int,
    val batteryLevel: Int,
    val seqNum: Int,
    val packetType: Int,
    val healthStatus: Int,
    val daysSinceChange: Int,
    val packetTypeName: String,
    val bagStateName: String,
    val batteryLevelName: String,
)

data class AlertDto(
    val id: String,
    val type: String,
    val status: String,
    val deviceId: String,
    val message: String,
    val createdAt: String,
)

data class GeofenceDto(
    val id: String,
    val userId: String,
    val name: String,
    val center: GeofenceCenterDto,
    val radiusMeters: Int,
    val enabled: Boolean,
)

data class GeofenceCenterDto(
    val lat: Double,
    val lng: Double,
)

data class RelaySnapshotDto(
    val deviceId: String,
    val bagState: Int,
    val packetType: Int,
    val seqNum: Int,
    val rssi: Int?,
    val seenAtEpochMs: Long,
)
