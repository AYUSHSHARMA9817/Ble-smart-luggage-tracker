package com.bletracker.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class BackendApi(private val prefs: Prefs) {
    fun health(): JSONObject = getJsonObject("/api/health")

    fun fetchBootstrap(): BootstrapDto {
        val json = getJsonObject("/api/bootstrap", authenticated = true)
        return BootstrapDto(
            user = parseUser(json.getJSONObject("user")),
            devices = parseDeviceArray(json.getJSONArray("devices")),
            alerts = parseAlertArray(json.getJSONArray("alerts")),
            geofences = parseGeofenceArray(json.getJSONArray("geofences")),
            serverTime = json.getString("serverTime"),
        )
    }

    fun fetchAlerts(): List<AlertDto> =
        getJsonArray("/api/alerts", authenticated = true).map { parseAlert(it) }

    fun fetchOpenAlerts(): List<AlertDto> =
        getJsonArray("/api/alerts?openOnly=true", authenticated = true).map { parseAlert(it) }

    fun fetchGeofences(): List<GeofenceDto> =
        getJsonArray("/api/geofences", authenticated = true).map { parseGeofence(it) }

    fun signUp(name: String, email: String, password: String): AuthSessionDto {
        val body = JSONObject().put("name", name).put("email", email).put("password", password)
        return parseAuthSession(postJson("/api/users", body))
    }

    fun signIn(email: String, password: String): AuthSessionDto {
        val body = JSONObject().put("email", email).put("password", password)
        return parseAuthSession(postJson("/api/auth/login", body))
    }

    fun registerDevice(deviceId: String, displayName: String, manualCode: String): DeviceDto {
        val body = JSONObject()
            .put("deviceId", deviceId)
            .put("displayName", displayName)
            .put("manualCode", manualCode)
        return parseDevice(postJson("/api/devices/register", body, authenticated = true))
    }

    fun createAdminRegistration(
        adminSecret: String,
        deviceId: String,
        manualCode: String,
        note: String,
    ): AdminRegistrationDto {
        val body = JSONObject()
            .put("adminSecret", adminSecret)
            .put("deviceId", deviceId)
            .put("manualCode", manualCode)
            .put("note", note)
        return parseAdminRegistration(postJson("/api/admin/device-registrations", body))
    }

    fun createGeofence(name: String, lat: Double, lng: Double, radiusMeters: Int): GeofenceDto {
        val body = JSONObject()
            .put("name", name)
            .put("center", JSONObject().put("lat", lat).put("lng", lng))
            .put("radiusMeters", radiusMeters)
        return parseGeofence(postJson("/api/geofences", body, authenticated = true))
    }

    fun deleteGeofence(geofenceId: String) {
        delete("/api/geofences/${encodePathSegment(geofenceId)}", authenticated = true)
    }

    fun removeDevice(deviceId: String) {
        delete("/api/devices/${encodePathSegment(deviceId)}", authenticated = true)
    }

    fun acknowledgeAlert(alertId: String): AlertDto {
        val body = JSONObject().put("alertId", alertId)
        return parseAlert(postJson("/api/alerts/ack", body, authenticated = true))
    }

    fun setDeviceMonitoring(deviceId: String, enabled: Boolean): DeviceDto {
        val body = JSONObject().put("enabled", enabled)
        return parseDevice(
            postJson("/api/devices/${encodePathSegment(deviceId)}/monitoring", body, authenticated = true)
        )
    }

    fun sendSighting(payload: SightingPayload) {
        sendSightingInternal(
            scannerUserId = payload.scannerUserId,
            manufacturerId = payload.manufacturerId,
            deviceId = payload.deviceId,
            rssi = payload.rssi,
            location = payload.location,
            trackerPacket = payload.packet,
        )
    }

    fun sendQueuedSighting(payload: QueuedSighting) {
        sendSightingInternal(
            scannerUserId = payload.scannerUserId,
            manufacturerId = payload.manufacturerId,
            deviceId = payload.deviceId,
            rssi = payload.rssi,
            location = payload.location,
            trackerPacket = payload.packet,
        )
    }

    private fun sendSightingInternal(
        scannerUserId: String,
        manufacturerId: String,
        deviceId: String,
        rssi: Int?,
        location: LocationPayload?,
        trackerPacket: TrackerPacket,
    ) {
        val packetJson = JSONObject()
            .put("bagState", trackerPacket.bagState)
            .put("batteryLevel", trackerPacket.batteryLevel)
            .put("seqNum", trackerPacket.seqNum)
            .put("packetType", trackerPacket.packetType)
            .put("healthStatus", trackerPacket.healthStatus)
            .put("daysSinceChange", trackerPacket.daysSinceChange)

        val body = JSONObject()
            .put("scannerUserId", scannerUserId)
            .put("manufacturerId", manufacturerId)
            .put("deviceId", deviceId)
            .put("rssi", rssi)
            .put("packet", packetJson)

        location?.let {
            body.put(
                "location",
                JSONObject()
                    .put("lat", it.lat)
                    .put("lng", it.lng)
                    .put("accuracyMeters", it.accuracyMeters)
            )
        }

        postJson("/api/sightings", body)
    }

    private fun getJsonObject(path: String, authenticated: Boolean = false): JSONObject {
        val connection = openConnection(path, "GET", authenticated)
        ensureSuccess(connection)
        return connection.inputStream.use { stream ->
            JSONObject(BufferedReader(InputStreamReader(stream)).readText())
        }
    }

    private fun getJsonArray(path: String, authenticated: Boolean = false): List<JSONObject> {
        val connection = openConnection(path, "GET", authenticated)
        ensureSuccess(connection)
        return connection.inputStream.use { stream ->
            val array = JSONArray(BufferedReader(InputStreamReader(stream)).readText())
            (0 until array.length()).map { array.getJSONObject(it) }
        }
    }

    private fun postJson(path: String, body: JSONObject, authenticated: Boolean = false): JSONObject {
        val connection = openConnection(path, "POST", authenticated)
        connection.doOutput = true
        OutputStreamWriter(connection.outputStream).use { it.write(body.toString()) }
        ensureSuccess(connection)
        return connection.inputStream.use { stream ->
            JSONObject(BufferedReader(InputStreamReader(stream)).readText())
        }
    }

    private fun openConnection(path: String, method: String, authenticated: Boolean): HttpURLConnection {
        val url = URL("${prefs.backendBaseUrl}$path")
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Content-Type", "application/json")
            if (authenticated && prefs.authToken.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${prefs.authToken}")
            }
        }
    }

    private fun delete(path: String, authenticated: Boolean = false) {
        val connection = openConnection(path, "DELETE", authenticated)
        ensureSuccess(connection)
        connection.inputStream.close()
    }

    private fun ensureSuccess(connection: HttpURLConnection) {
        val code = connection.responseCode
        if (code in 200..299) return

        val errorBody = runCatching {
            val stream = connection.errorStream ?: connection.inputStream
            BufferedReader(InputStreamReader(stream)).readText()
        }.getOrDefault("")

        val message = parseErrorMessage(errorBody)
            ?: "HTTP $code ${connection.requestMethod} ${connection.url}: $errorBody"

        throw IOException(message)
    }

    private fun parseErrorMessage(errorBody: String): String? {
        if (errorBody.isBlank()) return null
        return runCatching {
            val json = JSONObject(errorBody)
            json.optString("error").trim().ifBlank { null }
        }.getOrNull()
    }

    private fun encodePathSegment(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun parseUser(json: JSONObject) = UserDto(
        id = json.getString("id"),
        name = json.getString("name"),
        email = json.getString("email"),
    )

    private fun parseDeviceArray(array: JSONArray): List<DeviceDto> =
        (0 until array.length()).map { parseDevice(array.getJSONObject(it)) }

    private fun parseAlertArray(array: JSONArray): List<AlertDto> =
        (0 until array.length()).map { parseAlert(array.getJSONObject(it)) }

    private fun parseGeofenceArray(array: JSONArray): List<GeofenceDto> =
        (0 until array.length()).map { parseGeofence(array.getJSONObject(it)) }

    private fun parseAuthSession(json: JSONObject) = AuthSessionDto(
        authToken = json.getString("authToken"),
        expiresAt = json.getString("expiresAt"),
        user = parseUser(json.getJSONObject("user")),
    )

    private fun parseAdminRegistration(json: JSONObject) = AdminRegistrationDto(
        id = json.getString("id"),
        deviceId = json.getString("deviceId"),
        manualCode = json.getString("manualCode"),
        note = json.optString("note"),
        createdAt = json.getString("createdAt"),
    )

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

    private fun parseAlert(json: JSONObject) = AlertDto(
        id = json.getString("id"),
        type = json.getString("type"),
        status = json.getString("status"),
        deviceId = json.getString("deviceId"),
        message = json.getString("message"),
        createdAt = json.getString("createdAt"),
    )

    private fun parseGeofence(json: JSONObject): GeofenceDto {
        val center = json.getJSONObject("center")
        return GeofenceDto(
            id = json.getString("id"),
            userId = json.getString("userId"),
            name = json.getString("name"),
            center = GeofenceCenterDto(
                lat = center.getDouble("lat"),
                lng = center.getDouble("lng"),
            ),
            radiusMeters = json.getInt("radiusMeters"),
            enabled = json.optBoolean("enabled", true),
        )
    }
}
