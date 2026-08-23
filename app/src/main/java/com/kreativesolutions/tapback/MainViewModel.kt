package com.kreativesolutions.tapback

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kreativesolutions.tapback.api.AlertLog
import com.kreativesolutions.tapback.api.DeviceSession
import com.kreativesolutions.tapback.api.ScheduleItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.TimeZone

data class TapBackUiState(
    val apiBaseUrl: String = "",
    val displayName: String = "",
    val deviceId: String = "",
    val pairId: String = "",
    val partnerName: String = "",
    val inviteCode: String = "",
    val alerts: List<AlertLog> = emptyList(),
    val schedules: List<ScheduleItem> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val info: String? = null
) {
    val isRegistered: Boolean get() = deviceId.isNotBlank()
    val isPaired: Boolean get() = pairId.isNotBlank()
    val latestOutgoing: AlertLog?
        get() = alerts.firstOrNull { it.senderId == deviceId }
    val latestIncomingUnacked: AlertLog?
        get() = alerts.firstOrNull { it.receiverId == deviceId && it.ackedAt == null }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TapBackApp
    private val settings = app.appSettings
    private val api = app.api

    private val _state = MutableStateFlow(TapBackUiState())
    val state: StateFlow<TapBackUiState> = _state

    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            settings.apiBaseUrl.collect { value ->
                _state.value = _state.value.copy(apiBaseUrl = value)
            }
        }
        viewModelScope.launch {
            settings.displayName.collect { value ->
                _state.value = _state.value.copy(displayName = value)
            }
        }
        viewModelScope.launch {
            settings.deviceId.collect { value ->
                _state.value = _state.value.copy(deviceId = value)
                if (value.isNotBlank()) startPolling()
            }
        }
        viewModelScope.launch {
            settings.pairId.collect { value ->
                _state.value = _state.value.copy(pairId = value)
            }
        }
        viewModelScope.launch {
            settings.partnerName.collect { value ->
                _state.value = _state.value.copy(partnerName = value)
            }
        }
        viewModelScope.launch {
            settings.inviteCode.collect { value ->
                _state.value = _state.value.copy(inviteCode = value)
            }
        }
    }

    fun setApiBaseUrl(value: String) {
        viewModelScope.launch { settings.setApiBaseUrl(value) }
    }

    fun register(displayName: String) {
        runAction {
            val name = displayName.trim()
            require(name.isNotBlank()) { "Enter your name." }
            val session = api.registerDevice(
                baseUrl = requireBaseUrl(),
                displayName = name,
                fcmToken = app.currentFcmToken()
            )
            settings.setSession(session.deviceId, session.deviceSecret, session.displayName)
        }
    }

    fun createInvite() {
        runAction {
            val invite = api.createInvite(requireBaseUrl(), requireSession())
            settings.setInviteCode(invite.code)
        }
    }

    fun joinInvite(code: String) {
        runAction {
            val pair = api.joinInvite(requireBaseUrl(), requireSession(), code)
            settings.setPair(pair.pairId, pair.partnerName)
        }
    }

    fun sendCheckIn() {
        runAction {
            val pairId = _state.value.pairId
            require(pairId.isNotBlank()) { "Connect with someone first." }
            api.sendAlert(requireBaseUrl(), requireSession(), pairId)
            refresh()
        }
    }

    fun ack(alertId: String) {
        runAction {
            api.ackAlert(requireBaseUrl(), requireSession(), alertId)
            refresh()
        }
    }

    fun addSchedule(hour: Int, minute: Int, days: List<Int>) {
        runAction {
            val pairId = _state.value.pairId
            require(pairId.isNotBlank()) { "Connect with someone first." }
            require(days.isNotEmpty()) { "Pick at least one day." }
            api.createSchedule(
                baseUrl = requireBaseUrl(),
                auth = requireSession(),
                pairId = pairId,
                hour = hour,
                minute = minute,
                timezone = TimeZone.getDefault().id,
                days = days
            )
            refresh()
        }
    }

    fun toggleSchedule(item: ScheduleItem, enabled: Boolean) {
        runAction {
            api.setScheduleEnabled(requireBaseUrl(), requireSession(), item.id, enabled)
            refresh()
        }
    }

    fun removeSchedule(item: ScheduleItem) {
        runAction {
            api.deleteSchedule(requireBaseUrl(), requireSession(), item.id)
            refresh()
        }
    }

    fun unlink() {
        runAction {
            runCatching { api.unlink(requireBaseUrl(), requireSession()) }
            settings.clearPair()
            _state.value = _state.value.copy(alerts = emptyList(), schedules = emptyList())
        }
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { refreshNow() }
                .onFailure { _state.value = _state.value.copy(error = it.message) }
        }
    }

    fun clearMessages() {
        _state.value = _state.value.copy(error = null, info = null)
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                runCatching { refreshNow() }
                delay(8_000)
            }
        }
    }

    private suspend fun refreshNow() {
        val session = sessionOrNull() ?: return
        val baseUrl = settings.apiBaseUrl.first()
        if (baseUrl.isBlank()) return
        val me = api.me(baseUrl, session)
        if (me.pair != null) {
            settings.setPair(me.pair.pairId, me.pair.partnerName)
        }
        val alerts = api.listAlerts(baseUrl, session)
        val schedules = runCatching { api.listSchedules(baseUrl, session) }.getOrDefault(emptyList())
        _state.value = _state.value.copy(alerts = alerts, schedules = schedules, error = null)
    }

    private fun runAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, info = null)
            try {
                block()
            } catch (error: Exception) {
                _state.value = _state.value.copy(error = error.message ?: "Something went wrong.")
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    private suspend fun requireBaseUrl(): String {
        val url = settings.apiBaseUrl.first()
        require(url.isNotBlank()) { "Paste your Worker URL in Settings first." }
        return url
    }

    private suspend fun requireSession(): DeviceSession {
        return sessionOrNull() ?: error("Register a name first.")
    }

    private suspend fun sessionOrNull(): DeviceSession? {
        val id = settings.deviceId.first()
        val secret = settings.deviceSecret.first()
        val name = settings.displayName.first()
        if (id.isBlank() || secret.isBlank()) return null
        return DeviceSession(id, secret, name)
    }
}
