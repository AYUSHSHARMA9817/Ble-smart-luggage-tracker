package com.bletracker.app.scanner

import android.content.Context
import com.bletracker.app.data.LocationPayload
import com.bletracker.app.data.QueuedSighting
import com.bletracker.app.data.TrackerPacket
import org.json.JSONArray
import org.json.JSONObject

class SightingQueueStore(context: Context) {
    private val prefs = context.getSharedPreferences("ble_tracker_queue", Context.MODE_PRIVATE)

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

    @Synchronized
    fun snapshot(limit: Int = 100): List<QueuedSighting> {
        return loadMutable().take(limit).map(::fromJson)
    }

    @Synchronized
    fun removeFirst(count: Int) {
        val items = loadMutable()
        repeat(minOf(count, items.size)) {
            items.removeAt(0)
        }
        prefs.edit().putString(KEY, JSONArray(items).toString()).apply()
    }

    private fun loadMutable(): MutableList<JSONObject> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val array = JSONArray(raw)
        return MutableList(array.length()) { index -> array.getJSONObject(index) }
    }

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

        return json
    }

    private fun fromJson(json: JSONObject): QueuedSighting {
        val packet = json.getJSONObject("packet")
        val locationJson = json.optJSONObject("location")
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
            queuedAtEpochMs = json.getLong("queuedAtEpochMs"),
        )
    }

    private companion object {
        const val KEY = "queued_sightings"
        const val MAX_QUEUE_SIZE = 500
    }
}
