package com.bletracker.app.scanner

import android.bluetooth.le.ScanResult
import android.util.SparseArray
import com.bletracker.app.data.SensorReadingsPayload
import com.bletracker.app.data.TrackerPacket

/**
 * Parses the manufacturer-specific BLE advertisement payload emitted by the
 * ESP32 tracker firmware into a strongly-typed [ParsedResult].
 *
 * Expected payload layout (10 bytes, little-endian):
 * ```
 * Offset  Size  Field
 *   0      4    device_id       (uint32, LE)
 *   4      1    bag_state       (0 = closed, 1 = open)
 *   5      1    battery_level   (0–3)
 *   6      1    seq_num
 *   7      1    packet_type     (0 = heartbeat, 1 = state change, 2 = self test)
 *   8      1    health_status   (bit flags)
 *   9      1    days_since_change
 * ```
 *
 * Optional TLV payload may follow after byte 9:
 * - type (1 byte), len (1 byte), value (len bytes)
 *
 * TLV types:
 * - 0x01: temperature_c_x100 (int16 LE)
 * - 0x02: humidity_rh_x100   (uint16 LE)
 * - 0x03: lux                (uint16 LE)
 * - 0x10: accel_mg_xyz       (int16 LE x/y/z)
 * - 0x11: gyro_dps_x100_xyz  (int16 LE x/y/z)
 * - 0x12: vibration_score    (uint16 LE)
 */
object BlePacketParser {
    /** Manufacturer ID registered for this tracker product (matches firmware MANUFACTURER_ID). */
    const val MANUFACTURER_ID = 0xFF01

    /**
     * Holds the decoded fields from a single BLE advertisement.
     *
     * @property deviceIdHex    Canonical device ID string, e.g. `"0x0000AB01"`.
     * @property packet         Decoded tracker payload fields.
     * @property rssi           Received signal strength in dBm.
     * @property manufacturerId Raw manufacturer ID integer from the advertisement.
     */
    data class ParsedResult(
        val deviceIdHex: String,
        val packet: TrackerPacket,
        val sensors: SensorReadingsPayload?,
        val rssi: Int,
        val manufacturerId: Int,
    )

    /**
     * Parse a BLE scan result into a [ParsedResult].
     *
     * Returns `null` if the scan record is missing, does not contain a
     * manufacturer data entry for [MANUFACTURER_ID], or the payload is
     * shorter than the expected 10 bytes.
     *
     * @param scanResult The raw Android BLE scan result.
     * @return Decoded result, or `null` if the packet cannot be parsed.
     */
    fun parse(scanResult: ScanResult): ParsedResult? {
        val record = scanResult.scanRecord ?: return null
        val data: SparseArray<ByteArray> = record.manufacturerSpecificData
        val payload = data.get(MANUFACTURER_ID) ?: return null
        if (payload.size < 10) {
            return null
        }

        val deviceId = payload.readUInt32LE(0)
        val packet = TrackerPacket(
            bagState = payload.readUInt8(4),
            batteryLevel = payload.readUInt8(5),
            seqNum = payload.readUInt8(6),
            packetType = payload.readUInt8(7),
            healthStatus = payload.readUInt8(8),
            daysSinceChange = payload.readUInt8(9),
        )

        val sensors = parseSensorTlv(payload, 10)

        return ParsedResult(
            deviceIdHex = "0x" + deviceId.toString(16).uppercase().padStart(8, '0'),
            packet = packet,
            sensors = sensors,
            rssi = scanResult.rssi,
            manufacturerId = MANUFACTURER_ID,
        )
    }

    /** Read an unsigned 8-bit integer from this byte array at [index]. */
    private fun ByteArray.readUInt8(index: Int): Int = this[index].toInt() and 0xFF

    /**
     * Read an unsigned 32-bit integer stored in little-endian byte order
     * from this byte array starting at [index].
     */
    private fun ByteArray.readUInt32LE(index: Int): Long {
        return (readUInt8(index).toLong()) or
            (readUInt8(index + 1).toLong() shl 8) or
            (readUInt8(index + 2).toLong() shl 16) or
            (readUInt8(index + 3).toLong() shl 24)
    }

    private fun parseSensorTlv(payload: ByteArray, startOffset: Int): SensorReadingsPayload? {
        if (payload.size <= startOffset + 1) return null

        var offset = startOffset
        var temperatureC: Double? = null
        var humidityRh: Double? = null
        var lux: Int? = null
        var accelXMg: Int? = null
        var accelYMg: Int? = null
        var accelZMg: Int? = null
        var gyroXDps: Double? = null
        var gyroYDps: Double? = null
        var gyroZDps: Double? = null
        var vibrationScore: Int? = null

        while (offset + 2 <= payload.size) {
            val type = payload.readUInt8(offset)
            val len = payload.readUInt8(offset + 1)
            offset += 2
            if (offset + len > payload.size) break

            when (type) {
                0x01 -> if (len == 2) {
                    val raw = payload.readInt16LE(offset)
                    temperatureC = raw / 100.0
                }
                0x02 -> if (len == 2) {
                    val raw = payload.readUInt16LE(offset)
                    humidityRh = raw / 100.0
                }
                0x03 -> if (len == 2) {
                    lux = payload.readUInt16LE(offset)
                }
                0x10 -> if (len == 6) {
                    accelXMg = payload.readInt16LE(offset)
                    accelYMg = payload.readInt16LE(offset + 2)
                    accelZMg = payload.readInt16LE(offset + 4)
                }
                0x11 -> if (len == 6) {
                    gyroXDps = payload.readInt16LE(offset) / 100.0
                    gyroYDps = payload.readInt16LE(offset + 2) / 100.0
                    gyroZDps = payload.readInt16LE(offset + 4) / 100.0
                }
                0x12 -> if (len == 2) {
                    vibrationScore = payload.readUInt16LE(offset)
                }
            }

            offset += len
        }

        if (
            temperatureC == null &&
            humidityRh == null &&
            lux == null &&
            accelXMg == null &&
            accelYMg == null &&
            accelZMg == null &&
            gyroXDps == null &&
            gyroYDps == null &&
            gyroZDps == null &&
            vibrationScore == null
        ) return null
        return SensorReadingsPayload(
            temperatureC = temperatureC,
            humidityRh = humidityRh,
            lux = lux,
            accelXMg = accelXMg,
            accelYMg = accelYMg,
            accelZMg = accelZMg,
            gyroXDps = gyroXDps,
            gyroYDps = gyroYDps,
            gyroZDps = gyroZDps,
            vibrationScore = vibrationScore,
        )
    }

    private fun ByteArray.readUInt16LE(index: Int): Int =
        readUInt8(index) or (readUInt8(index + 1) shl 8)

    private fun ByteArray.readInt16LE(index: Int): Int {
        val u16 = readUInt16LE(index)
        return if (u16 and 0x8000 != 0) u16 - 0x10000 else u16
    }
}
