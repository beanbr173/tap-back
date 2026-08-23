package com.kreativesolutions.tapback.fcm

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.kreativesolutions.tapback.TapBackApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TapBackMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        (application as TapBackApp).syncFcmToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val type = data["type"].orEmpty()
        val alertId = data["alertId"].orEmpty()
        val fromName = data["fromName"].orEmpty().ifBlank { "Someone" }
        val app = application as TapBackApp
        when (type) {
            "ping" -> {
                if (alertId.isBlank()) return
                TapBackNotifications.showPing(this, alertId, fromName)
                app.applicationScope.launch {
                    runCatching {
                        val session = app.currentSession() ?: return@launch
                        val baseUrl = app.appSettings.apiBaseUrl.first()
                        if (baseUrl.isBlank()) return@launch
                        app.api.markReceived(baseUrl, session, alertId)
                    }
                }
            }
            "ack" -> TapBackNotifications.showAck(this, fromName)
        }
    }
}
