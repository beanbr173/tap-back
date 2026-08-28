package com.kreativesolutions.tapback.fcm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat

class PingAlertService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationId: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alertId = intent?.getStringExtra(TapBackNotifications.EXTRA_ALERT_ID).orEmpty()
        val fromName = intent?.getStringExtra(TapBackNotifications.EXTRA_FROM_NAME)
            .orEmpty()
            .ifBlank { "Someone" }
        if (alertId.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        notificationId = alertId.hashCode()
        val notification = TapBackNotifications.buildPingNotification(
            this,
            alertId,
            fromName,
            ringing = true
        )
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(notificationId, notification)
        }

        acquireWakeLock()
        wakeScreen()
        AlertSoundPlayer.start(this)
        TapBackNotifications.launchOverlay(this, alertId, fromName)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        AlertSoundPlayer.stop()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_DETACH)
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tapback:ping").apply {
            setReferenceCounted(false)
            acquire(15 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    private fun wakeScreen() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        @Suppress("DEPRECATION")
        val screenLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "tapback:screen"
        )
        runCatching {
            screenLock.acquire(3000L)
            if (screenLock.isHeld) screenLock.release()
        }
    }

    companion object {
        fun start(context: Context, alertId: String, fromName: String) {
            val intent = Intent(context, PingAlertService::class.java).apply {
                putExtra(TapBackNotifications.EXTRA_ALERT_ID, alertId)
                putExtra(TapBackNotifications.EXTRA_FROM_NAME, fromName)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopAndClear(context: Context, alertId: String) {
            AlertSoundPlayer.stop()
            TapBackNotifications.cancelPing(context, alertId)
            context.stopService(Intent(context, PingAlertService::class.java))
        }
    }
}
