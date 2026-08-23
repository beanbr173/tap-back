package com.kreativesolutions.tapback.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kreativesolutions.tapback.PingOverlayActivity
import com.kreativesolutions.tapback.R

object TapBackNotifications {
    const val CHANNEL_PINGS = "tapback_pings_v2"
    const val CHANNEL_ACKS = "tapback_acks"
    const val ACTION_ACK = "com.kreativesolutions.tapback.ACK_ALERT"
    const val EXTRA_ALERT_ID = "alert_id"
    const val EXTRA_FROM_NAME = "from_name"
    const val EXTRA_TYPE = "type"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val audio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PINGS,
                context.getString(R.string.notification_channel_pings),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_pings_desc)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
                setSound(alarm, audio)
                setBypassDnd(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ACKS,
                context.getString(R.string.notification_channel_acks),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notification_channel_acks_desc)
            }
        )
    }

    fun overlayIntent(context: Context, alertId: String, fromName: String): Intent {
        return Intent(context, PingOverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_ALERT_ID, alertId)
            putExtra(EXTRA_FROM_NAME, fromName)
            putExtra(EXTRA_TYPE, "ping")
        }
    }

    fun showPing(context: Context, alertId: String, fromName: String) {
        ensureChannels(context)
        val ackIntent = Intent(context, TapBackAckReceiver::class.java).apply {
            action = ACTION_ACK
            putExtra(EXTRA_ALERT_ID, alertId)
            putExtra(EXTRA_FROM_NAME, fromName)
        }
        val ackPending = PendingIntent.getBroadcast(
            context,
            alertId.hashCode(),
            ackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val overlayPending = PendingIntent.getActivity(
            context,
            alertId.hashCode() + 1,
            overlayIntent(context, alertId, fromName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val popupArt = BitmapFactory.decodeResource(context.resources, R.drawable.tapback_popup_art)
        val iconArt = BitmapFactory.decodeResource(context.resources, R.drawable.ic_launcher_art)
        val notification = NotificationCompat.Builder(context, CHANNEL_PINGS)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(iconArt)
            .setContentTitle(context.getString(R.string.ping_title, fromName))
            .setContentText(context.getString(R.string.ping_body))
            .setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(popupArt)
                    .bigLargeIcon(null as android.graphics.Bitmap?)
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(ackPending)
            .addAction(0, context.getString(R.string.i_am_here), ackPending)
            .setFullScreenIntent(overlayPending, true)
            .setTimeoutAfter(15 * 60 * 1000L)
            .build()
        notifyIfAllowed(context, alertId.hashCode(), notification)
        runCatching { context.startActivity(overlayIntent(context, alertId, fromName)) }
    }

    fun showAck(context: Context, fromName: String) {
        ensureChannels(context)
        val iconArt = BitmapFactory.decodeResource(context.resources, R.drawable.ic_launcher_art)
        val notification = NotificationCompat.Builder(context, CHANNEL_ACKS)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(iconArt)
            .setContentTitle(context.getString(R.string.ack_title, fromName))
            .setContentText(context.getString(R.string.ack_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notifyIfAllowed(context, fromName.hashCode() + 17, notification)
    }

    fun cancelPing(context: Context, alertId: String) {
        NotificationManagerCompat.from(context).cancel(alertId.hashCode())
    }

    private fun notifyIfAllowed(
        context: Context,
        id: Int,
        notification: android.app.Notification
    ) {
        val manager = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= 33 && !manager.areNotificationsEnabled()) return
        manager.notify(id, notification)
    }
}
