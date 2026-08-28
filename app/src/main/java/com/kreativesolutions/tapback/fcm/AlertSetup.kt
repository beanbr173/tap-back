package com.kreativesolutions.tapback.fcm

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

data class AlertSetup(
    val overlayGranted: Boolean,
    val fullScreenGranted: Boolean,
    val batteryUnrestricted: Boolean
) {
    val allGood: Boolean
        get() = overlayGranted && fullScreenGranted && batteryUnrestricted

    companion object {
        fun snapshot(context: Context): AlertSetup {
            val app = context.applicationContext
            return AlertSetup(
                overlayGranted = Settings.canDrawOverlays(app),
                fullScreenGranted = canUseFullScreen(app),
                batteryUnrestricted = isBatteryUnrestricted(app)
            )
        }

        fun canUseFullScreen(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 34) return true
            return context.getSystemService(NotificationManager::class.java)
                ?.canUseFullScreenIntent() != false
        }

        fun isBatteryUnrestricted(context: Context): Boolean {
            val pm = context.getSystemService(PowerManager::class.java) ?: return true
            return pm.isIgnoringBatteryOptimizations(context.packageName)
        }

        fun openOverlaySettings(context: Context) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        fun openFullScreenSettings(context: Context) {
            if (Build.VERSION.SDK_INT < 34) return
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }

        fun openBatterySettings(context: Context) {
            val pkg = Uri.parse("package:${context.packageName}")
            val exact = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = pkg
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(exact) }.onFailure {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
