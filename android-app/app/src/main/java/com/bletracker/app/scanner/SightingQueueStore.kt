package com.bletracker.app.scanner

import android.content.Context
import com.bletracker.app.data.LocationPayload
import com.bletracker.app.data.QueuedSighting
import com.bletracker.app.data.SensorReadingsPayload
import com.bletracker.app.data.TrackerPacket
import org.json.JSONArray
import org.json.JSONObject

/**
 * Thread-safe persistent FIFO queue of [QueuedSighting] items backed by
 * [android.content.SharedPreferences].
 *
 * All public methods are `@Synchronized` to prevent concurrent reads and
 * writes from the BLE scan callback thread and the uploader coroutine.
 *
 * Queue capacity is capped at [MAX_QUEUE_SIZE] (500 items). When the cap is
 * reached the oldest items are dropped to make room for new ones.
 *
 * @param context Android context used to open the SharedPreferences file.
 */
class SightingQueueStore(context: Context) {
    private val prefs = context.getSharedPreferences("ble_tracker_queue", Context.MODE_PRIVATE)

    /**
     * Append [sighting] to the end of the queue. If the queue has reached
     * [MAX_QUEUE_SIZE], the oldest items are evicted first.
     */
    @Synchronized
    fun enqueue(sighting: QueuedSighting) {
        val items = loadMutable()
        items.add(toJson(sighting))
        if (items.size > MAX_QUEUE_SIZE) {
            repeat(items.size - MAX_QUEUE_SIZE) {
                items.removeAt(0)
            }
        }
        prefs.edit().putString(KEY, JSONArray(items).toString()).apply()
    }

    /**
     * Return up to [limit] items from the front of the queue without
     * removing them. The caller is responsible for calling [removeFirst]
     * after successfully processing the returned items.
     *
     * @param limit Maximum number of items to return (default 100).
     */
    @Synchronized
    fun snapshot(limit: Int = 100): List<QueuedSighting> {
        return loadMutable().take(limit).map(::fromJson)
    }

    /**
     * Remove the first [count] items from the queue. Used by the uploader
     * after a successful batch upload.
     *
     * @param count Number of items to remove. Clamped to the current queue size.
     */
    @Synchronized
    fun removeFirst(count: Int) {
        val items = loadMutable()
        repeat(minOf(count, items.size)) {
            items.removeAt(0)
        }
        prefs.edit().putString(KEY, JSONArray(items).toString()).apply()
    }

    /** Load the raw JSON array from SharedPreferences as a mutable list. */
    private fun loadMutable(): MutableList<JSONObject> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val array = JSONArray(raw)
        return MutableList(array.length()) { index -> array.getJSONObject(index) }
    }

    /** Serialise a [QueuedSighting] to a [JSONObject] for persistent storage. */
    private fun toJson(sighting: QueuedSighting): JSONObject {
        val json = JSONObject()
            .put("scannerUserId", sighting.scannerUserId)
            .put("manufacturerId", sighting.manufacturerId)
            .put("deviceId", sighting.deviceId)
            .put("queuedAtEpochMs", sighting.queuedAtEpochMs)
            .put("rssi", sighting.rssi)
            .put(
                "packet",
                JSONObject()
                    .put("bagState", sighting.packet.bagState)
                    .put("batteryLevel", sighting.packet.batteryLevel)
                    .put("seqNum", sighting.packet.seqNum)
                    .put("packetType", sighting.packet.packetType)
                    .put("healthStatus", sighting.packet.healthStatus)
                    .put("daysSinceChange", sighting.packet.daysSinceChange)
            )

        sighting.location?.let {
            json.put(
                "location",
                JSONObject()
                    .put("lat", it.lat)
                    .put("lng", it.lng)
                    .put("accuracyMeters", it.accuracyMeters)
            )
        }

        sighting.sensors?.let { sensors ->
            json.put(
                "sensors",
                JSONObject()
                    .put("temperatureC", sensors.temperatureC)
                    .put("humidityRh", sensors.humidityRh)
                    .put("lux", sensors.lux)
                    .put("accelXMg", sensors.accelXMg)
                    .put("accelYMg", sensors.accelYMg)
                    .put("accelZMg", sensors.accelZMg)
                    .put("gyroXDps", sensors.gyroXDps)
                    .put("gyroYDps", sensors.gyroYDps)
                    .put("gyroZDps", sensors.gyroZDps)
                    .put("vibrationScore", sensors.vibrationScore)
            )
        }

        return json
    }

    /** Deserialise a [JSONObject] (previously produced by [toJson]) into a [QueuedSighting]. */
    private fun fromJson(json: JSONObject): QueuedSighting {
        val packet = json.getJSONObject("packet")
        val locationJson = json.optJSONObject("location")
        val sensorsJson = json.optJSONObject("sensors")
        return QueuedSighting(
            scannerUserId = json.getString("scannerUserId"),
            manufacturerId = json.getString("manufacturerId"),
            deviceId = json.getString("deviceId"),
            rssi = if (json.has("rssi") && !json.isNull("rssi")) json.getInt("rssi") else null,
            location = locationJson?.let {
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
            packet = TrackerPacket(
                bagState = packet.getInt("bagState"),
                batteryLevel = packet.getInt("batteryLevel"),
                seqNum = packet.getInt("seqNum"),
                packetType = packet.getInt("packetType"),
                healthStatus = packet.getInt("healthStatus"),
                daysSinceChange = packet.getInt("daysSinceChange"),
            ),
            sensors = sensorsJson?.let {
                SensorReadingsPayload(
                    temperatureC = if (it.has("temperatureC") && !it.isNull("temperatureC")) it.getDouble("temperatureC") else null,
                    humidityRh = if (it.has("humidityRh") && !it.isNull("humidityRh")) it.getDouble("humidityRh") else null,
                    lux = if (it.has("lux") && !it.isNull("lux")) it.getInt("lux") else null,
                    accelXMg = if (it.has("accelXMg") && !it.isNull("accelXMg")) it.getInt("accelXMg") else null,
                    accelYMg = if (it.has("accelYMg") && !it.isNull("accelYMg")) it.getInt("accelYMg") else null,
                    accelZMg = if (it.has("accelZMg") && !it.isNull("accelZMg")) it.getInt("accelZMg") else null,
                    gyroXDps = if (it.has("gyroXDps") && !it.isNull("gyroXDps")) it.getDouble("gyroXDps") else null,
                    gyroYDps = if (it.has("gyroYDps") && !it.isNull("gyroYDps")) it.getDouble("gyroYDps") else null,
                    gyroZDps = if (it.has("gyroZDps") && !it.isNull("gyroZDps")) it.getDouble("gyroZDps") else null,
                    vibrationScore = if (it.has("vibrationScore") && !it.isNull("vibrationScore")) it.getInt("vibrationScore") else null,
                )
            },
            queuedAtEpochMs = json.getLong("queuedAtEpochMs"),
        )
    }

    private companion object {
        /** SharedPreferences key under which the JSON array is stored. */
        const val KEY = "queued_sightings"
        /** Maximum number of sightings retained; oldest are evicted when exceeded. */
        const val MAX_QUEUE_SIZE = 500
    }
}
