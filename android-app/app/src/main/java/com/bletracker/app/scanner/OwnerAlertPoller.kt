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

class OwnerAlertPoller(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val backendApi: BackendApi,
) {
    private companion object {
        const val TAG = "OwnerAlertPoller"
        const val MAX_TRACKED_ALERTS = 100
    }

    private var job: Job? = null

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

    private suspend fun pollOnce() {
        runCatching {
            val alerts = backendApi.fetchOpenAlerts()
            val notifiedIds = prefs.notifiedAlertIds.toMutableSet()
            val notifiedKeys = prefs.notifiedOpenAlertKeys.toMutableSet()
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

            prefs.notifiedAlertIds = notifiedIds.toList().takeLast(MAX_TRACKED_ALERTS).toSet()
            prefs.notifiedOpenAlertKeys = notifiedKeys.intersect(activeKeys)
        }.onFailure { error ->
            Log.e(TAG, "Alert poll failed", error)
        }
    }
}
