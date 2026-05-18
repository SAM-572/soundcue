package com.soundcue.babycare.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

class AlertListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        // Gemma 생성 텍스트 → 워치 TTS 재생
        if (event.path == SPEAK_PATH) {
            val json = runCatching {
                JSONObject(String(event.data, Charsets.UTF_8))
            }.getOrNull() ?: return
            val text = json.optString("text").takeIf { it.isNotBlank() } ?: return
            WatchTts.speak(text)
            return
        }

        if (event.path != MESSAGE_PATH) return

        val json = runCatching {
            JSONObject(String(event.data, Charsets.UTF_8))
        }.getOrNull() ?: return

        val alert = WatchAlert(
            title = json.optString("title", "알림"),
            watchText = json.optString("watchText", json.optString("title", "알림")),
            subtype = json.optString("subtype").takeIf { it.isNotBlank() },
            reasoning = json.optString("reasoning").takeIf { it.isNotBlank() },
            severity = json.optString("severity", "LOW"),
            timestamp = json.optLong("timestamp", System.currentTimeMillis())
        )

        AlertStore.latest.value = alert

        wakeScreen(applicationContext)
        vibrateFor(applicationContext, alert.severity)
        postHeadsUpNotification(applicationContext, alert)
        launchUi(applicationContext)
    }

    private fun wakeScreen(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wake = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "SoundCue:WatchAlert"
        )
        runCatching { wake.acquire(5_000) }
    }

    private fun launchUi(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        context.startActivity(intent)
    }

    private fun vibrateFor(context: Context, severity: String) {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // 더 강한 패턴 (이전 대비 진동 시간 2배)
        val pattern: LongArray
        val amps: IntArray
        when (severity) {
            "HIGH" -> {
                // 강진동 3회 + 짧은 재진동 2회
                pattern = longArrayOf(0, 800, 200, 800, 200, 800, 400, 300, 150, 300)
                amps = intArrayOf(0, 255, 0, 255, 0, 255, 0, 220, 0, 220)
            }
            "MEDIUM" -> {
                pattern = longArrayOf(0, 500, 250, 500, 250, 500)
                amps = intArrayOf(0, 220, 0, 220, 0, 220)
            }
            else -> {
                pattern = longArrayOf(0, 300, 200, 300)
                amps = intArrayOf(0, 180, 0, 180)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createWaveform(pattern, amps, -1)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun postHeadsUpNotification(context: Context, alert: WatchAlert) {
        ensureChannel(context)

        // POST_NOTIFICATIONS permission check (Wear OS Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = launch?.let {
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val priority = if (alert.severity == "HIGH") NotificationCompat.PRIORITY_MAX
        else NotificationCompat.PRIORITY_HIGH

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(alert.watchText)
            .setContentText(alert.subtype ?: alert.title)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        buildString {
                            append(alert.title)
                            alert.subtype?.let { append("\n[$it]") }
                            alert.reasoning?.let { append("\n$it") }
                        }
                    )
            )
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIF_ID, notif)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "SoundCue Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Baby sound alerts"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 150, 400)
                }
                mgr.createNotificationChannel(ch)
            }
        }
    }

    companion object {
        const val MESSAGE_PATH = "/soundcue/alert"
        const val SPEAK_PATH = "/soundcue/speak"
        private const val CHANNEL_ID = "soundcue_watch_alerts"
        private const val NOTIF_ID = 7701
    }
}
