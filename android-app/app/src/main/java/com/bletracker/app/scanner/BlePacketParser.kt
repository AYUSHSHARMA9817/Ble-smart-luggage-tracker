package com.bletracker.app.scanner

import android.bluetooth.le.ScanResult
import android.util.SparseArray
import com.bletracker.app.data.TrackerPacket

object BlePacketParser {
    const val MANUFACTURER_ID = 0xFF01

    data class ParsedResult(
        val deviceIdHex: String,
        val packet: TrackerPacket,
        val rssi: Int,
        val manufacturerId: Int,
    )

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

    private fun ByteArray.readUInt8(index: Int): Int = this[index].toInt() and 0xFF

    private fun ByteArray.readUInt32LE(index: Int): Long {
        return (readUInt8(index).toLong()) or
            (readUInt8(index + 1).toLong() shl 8) or
            (readUInt8(index + 2).toLong() shl 16) or
            (readUInt8(index + 3).toLong() shl 24)
    }
}
