package com.soundcue.babycare.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.soundcue.babycare.R
import com.soundcue.babycare.domain.AlertPayload
import com.soundcue.babycare.domain.EventSeverity

object AlertNotifier {

    private const val CHANNEL_ID = "babycare_alerts"
    private var nextId = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.channel_babycare_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.channel_babycare_desc)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 250, 150, 250)
                }
                mgr.createNotificationChannel(channel)
            }
        }
    }

    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun notify(context: Context, payload: AlertPayload) {
        ensureChannel(context)
        if (!hasPermission(context)) return

        val iconRes = android.R.drawable.ic_popup_reminder

        val priority = when (payload.severity) {
            EventSeverity.HIGH -> NotificationCompat.PRIORITY_HIGH
            EventSeverity.MEDIUM -> NotificationCompat.PRIORITY_DEFAULT
            EventSeverity.LOW -> NotificationCompat.PRIORITY_LOW
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setContentTitle(payload.title)
            .setContentText(payload.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
            .setSubText("SoundCue · 육아 모드")
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVibrate(longArrayOf(0, 250, 150, 250))
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .build()

        NotificationManagerCompat.from(context).notify(nextId++, notification)
    }
}
