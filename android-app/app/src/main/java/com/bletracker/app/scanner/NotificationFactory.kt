package com.bletracker.app.scanner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import com.bletracker.app.MainActivity

object NotificationFactory {
    const val CHANNEL_ID = "ble_tracker_scan"
    const val ALERT_CHANNEL_ID = "ble_tracker_alerts"
    const val NOTIFICATION_ID = 4242
    private val alertSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    private val alertAudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val scanChannel = NotificationChannel(
                CHANNEL_ID,
                "BLE tracker monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background tracker monitoring"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }

            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Tracker attention alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Professional attention alerts for bag state, proximity, and device events"
                setShowBadge(true)
                enableLights(true)
                lightColor = Color.parseColor("#F59E0B")
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 140, 120, 180)
                setSound(alertSoundUri, alertAudioAttributes)
            }

            manager.createNotificationChannel(scanChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }

    fun build(context: Context, content: String): Notification {
        ensureChannel(context)
        val intent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("BLE tracker monitoring")
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(intent)
            .build()
    }

    fun notifyAlert(context: Context, alertId: String, title: String, message: String) {
        ensureChannel(context)
        val intent = PendingIntent.getActivity(
            context,
            alertId.hashCode(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setLights(Color.parseColor("#F59E0B"), 800, 1400)
            .setVibrate(longArrayOf(0, 140, 120, 180))
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(alertId.hashCode(), notification)
    }
}
