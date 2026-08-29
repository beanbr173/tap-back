package com.kreativesolutions.tapback

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kreativesolutions.tapback.api.AlertLog
import com.kreativesolutions.tapback.api.DeviceSession
import com.kreativesolutions.tapback.api.GroupInfo
import com.kreativesolutions.tapback.api.Member
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
    val groups: List<GroupInfo> = emptyList(),
    val members: List<Member> = emptyList(),
    val alerts: List<AlertLog> = emptyList(),
    val schedules: List<ScheduleItem> = emptyList(),
    val busy: Boolean = false,
    val sending: Boolean = false,
    val error: String? = null,
    val info: String? = null
) {
    val isRegistered: Boolean get() = deviceId.isNotBlank()
    val isPaired: Boolean get() = pairId.isNotBlank() || groups.isNotEmpty()
    val selectedGroup: GroupInfo?
        get() = groups.find { it.groupId == pairId } ?: groups.firstOrNull()
    val networkAlerts: List<AlertLog>
        get() = if (pairId.isBlank()) alerts else alerts.filter { it.pairId == pairId }
    val networkSchedules: List<ScheduleItem>
        get() = if (pairId.isBlank()) schedules else schedules.filter { it.pairId == pairId }
    val latestOutgoing: AlertLog?
        get() = networkAlerts.firstOrNull { it.senderId == deviceId }
    val latestIncomingUnacked: AlertLog?
        get() = networkAlerts.firstOrNull { it.receiverId == deviceId && it.ackedAt == null }
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

    fun selectGroup(groupId: String) {
        val group = _state.value.groups.find { it.groupId == groupId } ?: return
        viewModelScope.launch { applyGroup(group) }
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
            val invite = api.createInvite(
                requireBaseUrl(),
                requireSession(),
                _state.value.pairId.ifBlank { null }
            )
            settings.setInviteCode(invite.code)
            refreshNow()
        }
    }

    fun joinInvite(code: String) {
        runAction {
            val group = api.joinInvite(requireBaseUrl(), requireSession(), code)
            applyGroup(group)
            refreshNow()
            val latest = _state.value.groups.find { it.groupId == group.groupId } ?: group
            applyGroup(latest)
        }
    }

    fun sendCheckIn(receiverId: String? = null) {
        runAction(sending = true) {
            val groupId = selectedGroupId()
            require(_state.value.members.isNotEmpty()) { "Nobody else has joined yet." }
            api.sendAlert(requireBaseUrl(), requireSession(), groupId, receiverId)
            refreshNow()
        }
    }

    fun ack(alertId: String) {
        runAction {
            api.ackAlert(requireBaseUrl(), requireSession(), alertId)
            refreshNow()
        }
    }

    fun addSchedule(hour: Int, minute: Int, days: List<Int>, receiverId: String?) {
        runAction {
            val groupId = selectedGroupId()
            require(days.isNotEmpty()) { "Pick at least one day." }
            require(!receiverId.isNullOrBlank()) { "Pick who this alarm is for." }
            api.createSchedule(
                baseUrl = requireBaseUrl(),
                auth = requireSession(),
                pairId = groupId,
                hour = hour,
                minute = minute,
                timezone = TimeZone.getDefault().id,
                days = days,
                receiverId = receiverId
            )
            refreshNow()
        }
    }

    fun toggleSchedule(item: ScheduleItem, enabled: Boolean) {
        runAction {
            api.setScheduleEnabled(requireBaseUrl(), requireSession(), item.id, enabled)
            refreshNow()
        }
    }

    fun removeSchedule(item: ScheduleItem) {
        runAction {
            api.deleteSchedule(requireBaseUrl(), requireSession(), item.id)
            refreshNow()
        }
    }

    fun unlink() {
        runAction {
            val groupId = _state.value.pairId.ifBlank { null }
            runCatching { api.unlink(requireBaseUrl(), requireSession(), groupId) }
            refreshNow()
            if (_state.value.groups.isEmpty()) {
                settings.clearPair()
                _state.value = _state.value.copy(
                    members = emptyList(),
                    alerts = emptyList(),
                    schedules = emptyList(),
                    inviteCode = ""
                )
            }
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
        val groups = me.groups
        val preferredId = settings.pairId.first()
        val selected = groups.find { it.groupId == preferredId } ?: groups.firstOrNull()
        if (selected != null) {
            applyGroup(selected)
        } else {
            settings.clearPair()
            _state.value = _state.value.copy(members = emptyList(), inviteCode = "")
        }
        val alerts = api.listAlerts(baseUrl, session)
        val schedules = runCatching { api.listSchedules(baseUrl, session) }.getOrDefault(emptyList())
        _state.value = _state.value.copy(
            groups = groups,
            alerts = alerts,
            schedules = schedules,
            error = null
        )
    }

    private suspend fun applyGroup(group: GroupInfo) {
        settings.setGroup(group.groupId, group.partnerLabel, group.inviteCode.ifBlank { null })
        _state.value = _state.value.copy(
            pairId = group.groupId,
            partnerName = group.partnerLabel,
            inviteCode = group.inviteCode,
            members = group.members
        )
    }

    private fun selectedGroupId(): String {
        val groupId = _state.value.pairId
        require(groupId.isNotBlank()) { "Connect with your family first." }
        return groupId
    }

    private fun runAction(sending: Boolean = false, block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, sending = sending, error = null, info = null)
            try {
                block()
            } catch (error: Exception) {
                _state.value = _state.value.copy(error = error.message ?: "Something went wrong.")
            } finally {
                _state.value = _state.value.copy(busy = false, sending = false)
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
