package com.bletracker.app.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.bletracker.app.data.Prefs

/**
 * [BroadcastReceiver] that auto-starts [BleScanService] after the device
 * boots or after the app is updated.
 *
 * The service is only started if [Prefs.scannerAutostartEnabled] is `true`,
 * so users who did not opt in to background monitoring are not affected.
 *
 * Registered for [Intent.ACTION_BOOT_COMPLETED] and
 * [Intent.ACTION_MY_PACKAGE_REPLACED] in AndroidManifest.xml.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val prefs = Prefs(context)
        if (!prefs.scannerAutostartEnabled) {
            return
        }

        val serviceIntent = Intent(context, BleScanService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
