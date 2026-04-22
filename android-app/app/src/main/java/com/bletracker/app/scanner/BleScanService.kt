package com.bletracker.app.scanner

import android.Manifest
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.bletracker.app.data.BackendApi
import com.bletracker.app.data.Prefs
import com.bletracker.app.data.QueuedSighting
import com.bletracker.app.data.RelaySnapshotDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class BleScanService : Service() {
    private companion object {
        const val TAG = "BleScanService"
    }

    private val scanFilters = listOf(
        ScanFilter.Builder()
            .setManufacturerData(
                BlePacketParser.MANUFACTURER_ID,
                ByteArray(10),
                ByteArray(10),
            )
            .build()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: Prefs
    private lateinit var backendApi: BackendApi
    private lateinit var queueStore: SightingQueueStore
    private lateinit var uploader: SightingUploader
    private lateinit var alertPoller: OwnerAlertPoller
    private val recentlyQueued = LinkedHashMap<String, Long>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::handleResult)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with errorCode=$errorCode")
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        backendApi = BackendApi(prefs)
        queueStore = SightingQueueStore(this)
        uploader = SightingUploader(scope, queueStore, backendApi)
        alertPoller = OwnerAlertPoller(this, scope, prefs, backendApi)
        startForeground(
            NotificationFactory.NOTIFICATION_ID,
            NotificationFactory.build(this, "Relaying nearby BLE tracker packets")
        )
        Log.i(TAG, "Foreground relay service created")
        uploader.start()
        alertPoller.start()
        startBleScan()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        stopBleScan()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startBleScan() {
        if (!hasScanPermission()) {
            Log.w(TAG, "Stopping relay service because BLE permissions are missing")
            stopSelf()
            return
        }

        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: run {
            Log.e(TAG, "Bluetooth adapter unavailable")
            stopSelf()
            return
        }

        val scanner = adapter.bluetoothLeScanner ?: run {
            Log.e(TAG, "Bluetooth LE scanner unavailable")
            stopSelf()
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)
            .build()

        runCatching {
            scanner.startScan(scanFilters, settings, scanCallback)
            Log.i(TAG, "BLE scan started for manufacturer 0x%04X".format(BlePacketParser.MANUFACTURER_ID))
        }.onFailure { error ->
            Log.e(TAG, "Failed to start BLE scan", error)
        }
    }

    private fun stopBleScan() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val scanner = manager.adapter?.bluetoothLeScanner ?: return
        runCatching { scanner.stopScan(scanCallback) }
    }

    private fun handleResult(result: ScanResult) {
        val parsed = BlePacketParser.parse(result) ?: return
        val dedupeKey = "${parsed.deviceIdHex}:${parsed.packet.seqNum}:${parsed.packet.packetType}"
        val now = System.currentTimeMillis()
        val previous = recentlyQueued[dedupeKey]
        if (previous != null && now - previous < 2_000) {
            return
        }
        recentlyQueued[dedupeKey] = now
        if (recentlyQueued.size > 200) {
            val oldestKey = recentlyQueued.entries.minByOrNull { it.value }?.key
            if (oldestKey != null) {
                recentlyQueued.remove(oldestKey)
            }
        }
        Log.d(
            TAG,
            "Parsed packet device=${parsed.deviceIdHex} mfr=0x%04X seq=%d type=%d rssi=%d"
                .format(parsed.manufacturerId, parsed.packet.seqNum, parsed.packet.packetType, parsed.rssi)
        )
        prefs.latestRelaySnapshot = RelaySnapshotDto(
            deviceId = parsed.deviceIdHex,
            bagState = parsed.packet.bagState,
            packetType = parsed.packet.packetType,
            seqNum = parsed.packet.seqNum,
            rssi = parsed.rssi,
            seenAtEpochMs = now,
        )
        queueStore.enqueue(
            QueuedSighting(
                scannerUserId = prefs.scannerUserId,
                manufacturerId = "0xFF01",
                deviceId = parsed.deviceIdHex,
                rssi = parsed.rssi,
                location = LocationSnapshot.current(this),
                packet = parsed.packet,
                queuedAtEpochMs = System.currentTimeMillis(),
            )
        )
        Log.d(TAG, "Queued sighting device=${parsed.deviceIdHex} seq=${parsed.packet.seqNum}")
        uploader.kick()
    }

    private fun hasScanPermission(): Boolean {
        val bluetoothGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
        val connectGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        return bluetoothGranted && connectGranted
    }
}
