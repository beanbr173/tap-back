package com.kreativesolutions.tapback

import android.app.Application
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import com.kreativesolutions.tapback.api.DeviceSession
import com.kreativesolutions.tapback.api.TapBackApi
import com.kreativesolutions.tapback.fcm.TapBackNotifications
import com.kreativesolutions.tapback.prefs.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TapBackApp : Application() {
    val appSettings: AppSettings by lazy { AppSettings(this) }
    val api: TapBackApi by lazy { TapBackApi() }
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        TapBackNotifications.ensureChannels(this)
    }

    suspend fun currentSession(): DeviceSession? {
        val id = appSettings.deviceId.first()
        val secret = appSettings.deviceSecret.first()
        val name = appSettings.displayName.first()
        if (id.isBlank() || secret.isBlank()) return null
        return DeviceSession(id, secret, name)
    }

    suspend fun currentFcmToken(): String? = withContext(Dispatchers.IO) {
        runCatching {
            Tasks.await(FirebaseMessaging.getInstance().token)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun syncFcmToken(token: String) {
        applicationScope.launch {
            val session = currentSession() ?: return@launch
            val baseUrl = appSettings.apiBaseUrl.first()
            if (baseUrl.isBlank()) return@launch
            runCatching { api.updateDevice(baseUrl, session, fcmToken = token) }
        }
    }

    companion object {
        lateinit var instance: TapBackApp
            private set
    }
}
