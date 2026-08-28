package com.kreativesolutions.tapback.fcm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.kreativesolutions.tapback.R
import com.kreativesolutions.tapback.TapBackApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TapBackAckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TapBackNotifications.ACTION_ACK) return
        val alertId = intent.getStringExtra(TapBackNotifications.EXTRA_ALERT_ID).orEmpty()
        if (alertId.isBlank()) return
        val pending = goAsync()
        val app = context.applicationContext as TapBackApp
        app.applicationScope.launch {
            try {
                val session = app.currentSession()
                val baseUrl = app.appSettings.apiBaseUrl.first()
                if (session != null && baseUrl.isNotBlank()) {
                    app.api.ackAlert(baseUrl, session, alertId)
                }
                PingAlertService.stopAndClear(context, alertId)
            } catch (_: Exception) {
                // Keep the notification so they can try again from the app.
            } finally {
                pending.finish()
            }
        }
        Toast.makeText(context, R.string.ack_confirmed, Toast.LENGTH_SHORT).show()
    }
}
