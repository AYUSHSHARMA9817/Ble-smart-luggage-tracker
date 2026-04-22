package com.bletracker.app.scanner

import android.util.Log
import com.bletracker.app.data.BackendApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SightingUploader(
    private val scope: CoroutineScope,
    private val queueStore: SightingQueueStore,
    private val backendApi: BackendApi,
) {
    private companion object {
        const val TAG = "BleSightingUploader"
    }

    private var job: Job? = null
    private var kickJob: Job? = null
    private val flushMutex = Mutex()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                flushOnce()
                delay(10_000)
            }
        }
    }

    fun kick() {
        if (kickJob?.isActive == true) return
        kickJob = scope.launch(Dispatchers.IO) {
            flushOnce()
        }
    }

    suspend fun flushOnce() {
        flushMutex.withLock {
            val batch = queueStore.snapshot(limit = 25)
            if (batch.isEmpty()) return

            Log.d(TAG, "Attempting upload of ${batch.size} queued sighting(s)")

            var sentCount = 0
            for (item in batch) {
                val success = runCatching { backendApi.sendQueuedSighting(item) }
                    .onSuccess {
                        Log.d(TAG, "Uploaded sighting device=${item.deviceId} seq=${item.packet.seqNum}")
                    }
                    .onFailure { error ->
                        Log.e(TAG, "Upload failed device=${item.deviceId} seq=${item.packet.seqNum}", error)
                    }
                    .isSuccess
                if (!success) {
                    break
                }
                sentCount++
            }

            if (sentCount > 0) {
                queueStore.removeFirst(sentCount)
                Log.d(TAG, "Removed $sentCount uploaded sighting(s) from queue")
            }
        }
    }
}
