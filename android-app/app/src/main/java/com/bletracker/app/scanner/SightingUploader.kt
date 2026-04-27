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

/**
 * Drains the [SightingQueueStore] by uploading queued sightings to the
 * backend in FIFO order, up to 25 at a time per flush.
 *
 * Two upload paths are provided:
 * - [start]: launches a periodic background loop that flushes every 10 seconds.
 * - [kick]:  triggers an immediate flush (used after enqueueing a new sighting).
 *
 * A [Mutex] ensures that concurrent [kick] and the periodic loop do not
 * upload the same sighting twice. Upload failures stop the current batch
 * immediately and leave unacknowledged items in the queue for the next flush.
 *
 * @param scope       Coroutine scope tied to the [BleScanService] lifecycle.
 * @param queueStore  Persistent queue of sightings waiting to be uploaded.
 * @param backendApi  HTTP client used to deliver sightings to the server.
 */
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

    /**
     * Start the periodic 10-second flush loop. No-op if already running.
     */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                flushOnce()
                delay(10_000)
            }
        }
    }

    /**
     * Schedule an immediate flush. No-op if a kick-triggered flush is
     * already in progress.
     */
    fun kick() {
        if (kickJob?.isActive == true) return
        kickJob = scope.launch(Dispatchers.IO) {
            flushOnce()
        }
    }

    /**
     * Upload up to 25 queued sightings to the backend in order.
     *
     * Acquires [flushMutex] to prevent concurrent flushes. Stops processing
     * the batch on the first upload failure so that order is preserved and
     * items are retried on the next flush cycle.
     */
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
