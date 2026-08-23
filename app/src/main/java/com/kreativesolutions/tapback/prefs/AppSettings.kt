package com.kreativesolutions.tapback.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kreativesolutions.tapback.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.tapBackStore: DataStore<Preferences> by preferencesDataStore(name = "tapback")

class AppSettings(private val context: Context) {
    val apiBaseUrl: Flow<String> = context.tapBackStore.data.map { prefs ->
        prefs[KEY_API_BASE_URL]?.trim().orEmpty()
            .ifBlank { BuildConfig.API_BASE_URL.trim() }
    }

    val displayName: Flow<String> = context.tapBackStore.data.map { prefs ->
        prefs[KEY_DISPLAY_NAME].orEmpty()
    }

    val deviceId: Flow<String> = context.tapBackStore.data.map { prefs ->
        prefs[KEY_DEVICE_ID].orEmpty()
    }

    val deviceSecret: Flow<String> = context.tapBackStore.data.map { prefs ->
        prefs[KEY_DEVICE_SECRET].orEmpty()
    }

    val pairId: Flow<String> = context.tapBackStore.data.map { prefs ->
        prefs[KEY_PAIR_ID].orEmpty()
    }

    val partnerName: Flow<String> = context.tapBackStore.data.map { prefs ->
        prefs[KEY_PARTNER_NAME].orEmpty()
    }

    val inviteCode: Flow<String> = context.tapBackStore.data.map { prefs ->
        prefs[KEY_INVITE_CODE].orEmpty()
    }

    suspend fun setApiBaseUrl(value: String) {
        context.tapBackStore.edit { it[KEY_API_BASE_URL] = value.trim() }
    }

    suspend fun setDisplayName(value: String) {
        context.tapBackStore.edit { it[KEY_DISPLAY_NAME] = value.trim() }
    }

    suspend fun setSession(deviceId: String, deviceSecret: String, displayName: String) {
        context.tapBackStore.edit { prefs ->
            prefs[KEY_DEVICE_ID] = deviceId
            prefs[KEY_DEVICE_SECRET] = deviceSecret
            prefs[KEY_DISPLAY_NAME] = displayName
        }
    }

    suspend fun setPair(pairId: String, partnerName: String) {
        setGroup(pairId, partnerName, inviteCode = null)
    }

    suspend fun setGroup(groupId: String, partnerName: String, inviteCode: String?) {
        context.tapBackStore.edit { prefs ->
            prefs[KEY_PAIR_ID] = groupId
            prefs[KEY_PARTNER_NAME] = partnerName
            if (!inviteCode.isNullOrBlank()) prefs[KEY_INVITE_CODE] = inviteCode
        }
    }

    suspend fun setInviteCode(code: String) {
        context.tapBackStore.edit { it[KEY_INVITE_CODE] = code }
    }

    suspend fun clearPair() {
        context.tapBackStore.edit { prefs ->
            prefs.remove(KEY_PAIR_ID)
            prefs.remove(KEY_PARTNER_NAME)
            prefs.remove(KEY_INVITE_CODE)
        }
    }

    companion object {
        private val KEY_API_BASE_URL = stringPreferencesKey("api_base_url")
        private val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_DEVICE_SECRET = stringPreferencesKey("device_secret")
        private val KEY_PAIR_ID = stringPreferencesKey("pair_id")
        private val KEY_PARTNER_NAME = stringPreferencesKey("partner_name")
        private val KEY_INVITE_CODE = stringPreferencesKey("invite_code")
    }
}
