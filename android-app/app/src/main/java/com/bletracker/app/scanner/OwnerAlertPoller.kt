package com.bletracker.app.scanner

import android.content.Context
import android.util.Log
import com.bletracker.app.data.BackendApi
import com.bletracker.app.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Periodically polls the backend for open alerts belonging to the signed-in
 * owner and posts a local Android notification for any unseen alert.
 *
 * Alert IDs that have already triggered a notification are tracked in
 * [Prefs.notifiedAlertIds] (persisted, bounded to [MAX_TRACKED_ALERTS])
 * and per-type keys are tracked in [Prefs.notifiedOpenAlertKeys] so that
 * a re-opened alert of the same type on the same device notifies the user
 * again, but an already-notified alert does not fire a second time.
 *
 * Polling is only started when an auth token is present; if the token is
 * missing at [start] time, the poll job is not created.
 *
 * @param context    Used to post notifications via [NotificationFactory].
 * @param scope      Coroutine scope tied to the [BleScanService] lifecycle.
 * @param prefs      User preferences including the auth token and notification tracking sets.
 * @param backendApi HTTP client used to fetch open alerts from the server.
 */
class OwnerAlertPoller(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val backendApi: BackendApi,
) {
    private companion object {
        const val TAG = "OwnerAlertPoller"
        /** Maximum number of alert IDs retained in [Prefs.notifiedAlertIds]. */
        const val MAX_TRACKED_ALERTS = 100
    }

    private var job: Job? = null

    /**
     * Start the 5-second polling loop. No-op if already running or if no
     * auth token is available.
     */
    fun start() {
        if (job?.isActive == true) return
        if (prefs.authToken.isBlank()) return

        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                pollOnce()
                delay(5_000)
            }
        }
    }

    /**
     * Fetch open alerts from the backend and notify the user about any that
     * have not been seen before. Updates the persisted notification tracking
     * sets in [Prefs] afterwards.
     *
     * Errors (network failures, auth errors) are swallowed and logged so
     * that a transient failure does not crash the polling loop.
     */
    private suspend fun pollOnce() {
        runCatching {
            val alerts = backendApi.fetchOpenAlerts()
            val notifiedIds = prefs.notifiedAlertIds.toMutableSet()
            val notifiedKeys = prefs.notifiedOpenAlertKeys.toMutableSet()
            // Build the set of currently active alert keys to prune stale ones.
            val activeKeys = alerts.map { "${it.type}:${it.deviceId}" }.toSet()

            for (alert in alerts) {
                val alertKey = "${alert.type}:${alert.deviceId}"
                if (alert.status != "open" || notifiedIds.contains(alert.id) || notifiedKeys.contains(alertKey)) {
                    continue
                }
                NotificationFactory.notifyAlert(
                    context = context,
                    alertId = alert.id,
                    title = alert.type.replace('_', ' '),
                    message = alert.message,
                )
                notifiedIds.add(alert.id)
                notifiedKeys.add(alertKey)
                Log.d(TAG, "Notified alert ${alert.id} type=${alert.type}")
            }

            // Trim the ID set to avoid unbounded growth; retain only the most recent ones.
            prefs.notifiedAlertIds = notifiedIds.toList().takeLast(MAX_TRACKED_ALERTS).toSet()
            // Remove keys that are no longer in the active alert list so they can re-notify if the alert reopens.
            prefs.notifiedOpenAlertKeys = notifiedKeys.intersect(activeKeys)
        }.onFailure { error ->
            Log.e(TAG, "Alert poll failed", error)
        }
    }
}
