package com.bletracker.app.scanner

import android.bluetooth.le.ScanResult
import android.util.SparseArray
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

        return ParsedResult(
            deviceIdHex = "0x" + deviceId.toString(16).uppercase().padStart(8, '0'),
            packet = packet,
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
}
