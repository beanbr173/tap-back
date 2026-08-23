package com.kreativesolutions.tapback

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kreativesolutions.tapback.api.AlertLog
import com.kreativesolutions.tapback.api.ScheduleItem
import com.kreativesolutions.tapback.fcm.TapBackNotifications
import com.kreativesolutions.tapback.ui.theme.TapBackTheme
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeAckFromIntent()
        setContent {
            TapBackTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TapBackAppScreen(viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeAckFromIntent()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun maybeAckFromIntent() {
        val alertId = intent?.getStringExtra(TapBackNotifications.EXTRA_ALERT_ID).orEmpty()
        if (alertId.isNotBlank()) {
            viewModel.ack(alertId)
            intent?.removeExtra(TapBackNotifications.EXTRA_ALERT_ID)
        }
    }
}

@Composable
private fun TapBackAppScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var nameDraft by remember { mutableStateOf(state.displayName) }
    var joinCode by remember { mutableStateOf("") }
    var apiDraft by remember { mutableStateOf(state.apiBaseUrl) }
    var selectedDays by remember { mutableStateOf((0..6).toSet()) }
    var showAlarmPicker by remember { mutableStateOf(false) }

    LaunchedEffect(state.displayName) {
        if (nameDraft.isBlank()) nameDraft = state.displayName
    }
    LaunchedEffect(state.apiBaseUrl) {
        if (apiDraft.isBlank()) apiDraft = state.apiBaseUrl
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = stringResource(R.string.tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )

        if (!state.error.isNullOrBlank()) {
            Text(state.error ?: "", color = MaterialTheme.colorScheme.error)
        }

        ServerCard(
            apiDraft = apiDraft,
            onApiDraft = { apiDraft = it },
            onSave = { viewModel.setApiBaseUrl(apiDraft) }
        )

        if (!state.isRegistered) {
            SetupCard(
                nameDraft = nameDraft,
                onNameDraft = { nameDraft = it },
                busy = state.busy,
                onContinue = { viewModel.register(nameDraft) }
            )
        } else if (!state.isPaired) {
            PairCard(
                inviteCode = state.inviteCode,
                joinCode = joinCode,
                onJoinCode = { joinCode = it },
                busy = state.busy,
                onCreate = { viewModel.createInvite() },
                onJoin = { viewModel.joinInvite(joinCode) }
            )
        } else {
            HomeCard(
                partnerName = state.partnerName,
                outgoing = state.latestOutgoing,
                incoming = state.latestIncomingUnacked,
                deviceId = state.deviceId,
                busy = state.busy,
                onSend = { viewModel.sendCheckIn() },
                onAck = { id -> viewModel.ack(id) }
            )
            LogCard(alerts = state.alerts, deviceId = state.deviceId)
            ScheduleCard(
                schedules = state.schedules,
                selectedDays = selectedDays,
                onToggleDay = { day ->
                    selectedDays = if (selectedDays.contains(day)) {
                        selectedDays - day
                    } else {
                        selectedDays + day
                    }
                },
                busy = state.busy,
                onAdd = { showAlarmPicker = true },
                onToggle = { item, enabled -> viewModel.toggleSchedule(item, enabled) },
                onRemove = { viewModel.removeSchedule(it) }
            )
            if (showAlarmPicker) {
                AlarmPickerDialog(
                    onDismiss = { showAlarmPicker = false },
                    onConfirm = { hour, minute ->
                        viewModel.addSchedule(hour, minute, selectedDays.sorted())
                        showAlarmPicker = false
                    }
                )
            }
            OutlinedButton(
                onClick = { viewModel.unlink() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy
            ) {
                Text(stringResource(R.string.unlink))
            }
        }

        Text(
            text = stringResource(R.string.app_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun ServerCard(apiDraft: String, onApiDraft: (String) -> Unit, onSave: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings), fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = apiDraft,
                onValueChange = onApiDraft,
                label = { Text(stringResource(R.string.api_url)) },
                placeholder = { Text("https://tapback.YOUR_SUBDOMAIN.workers.dev") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            TextButton(onClick = onSave, modifier = Modifier.align(Alignment.End)) {
                Text("Save server URL")
            }
        }
    }
}

@Composable
private fun SetupCard(
    nameDraft: String,
    onNameDraft: (String) -> Unit,
    busy: Boolean,
    onContinue: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = nameDraft,
                onValueChange = onNameDraft,
                label = { Text(stringResource(R.string.your_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Button(
                onClick = onContinue,
                enabled = !busy && nameDraft.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.continue_label))
            }
        }
    }
}

@Composable
private fun PairCard(
    inviteCode: String,
    joinCode: String,
    onJoinCode: (String) -> Unit,
    busy: Boolean,
    onCreate: () -> Unit,
    onJoin: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.pair_title), fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.pair_body), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = onCreate,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.create_code))
            }
            if (inviteCode.isNotBlank()) {
                Text(stringResource(R.string.share_this_code), style = MaterialTheme.typography.bodySmall)
                Text(
                    text = inviteCode,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(stringResource(R.string.waiting_for_partner), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = joinCode,
                onValueChange = { onJoinCode(it.uppercase()) },
                label = { Text(stringResource(R.string.enter_code)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedButton(
                onClick = onJoin,
                enabled = !busy && joinCode.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.join))
            }
        }
    }
}

@Composable
private fun HomeCard(
    partnerName: String,
    outgoing: AlertLog?,
    incoming: AlertLog?,
    deviceId: String,
    busy: Boolean,
    onSend: () -> Unit,
    onAck: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.partner_label, partnerName.ifBlank { "your person" }),
                fontWeight = FontWeight.SemiBold
            )
            Button(
                onClick = onSend,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) stringResource(R.string.sending) else stringResource(R.string.send_check_in))
            }
            if (incoming != null) {
                Text("They checked in on you. Tap to say you're here.")
                Button(onClick = { onAck(incoming.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.i_am_here))
                }
            }
            if (outgoing != null) {
                Text(
                    text = statusLine(outgoing, deviceId),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun LogCard(alerts: List<AlertLog>, deviceId: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.log_title), fontWeight = FontWeight.SemiBold)
            if (alerts.isEmpty()) {
                Text(stringResource(R.string.log_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                alerts.take(30).forEach { alert ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(statusLine(alert, deviceId), fontWeight = FontWeight.Medium)
                        Text(
                            text = timestampLine(alert),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleCard(
    schedules: List<ScheduleItem>,
    selectedDays: Set<Int>,
    onToggleDay: (Int) -> Unit,
    busy: Boolean,
    onAdd: () -> Unit,
    onToggle: (ScheduleItem, Boolean) -> Unit,
    onRemove: (ScheduleItem) -> Unit
) {
    val labels = listOf("S", "M", "T", "W", "T", "F", "S")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.schedule_title), fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.schedule_body), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.repeat), fontWeight = FontWeight.Medium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                labels.forEachIndexed { index, label ->
                    val selected = selectedDays.contains(index)
                    TextButton(onClick = { onToggleDay(index) }) {
                        Text(
                            text = label,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            }
                        )
                    }
                }
            }
            Button(onClick = onAdd, enabled = !busy && selectedDays.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.set_alarm))
            }
            schedules.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(formatAlarmTime(item.hour, item.minute), fontWeight = FontWeight.SemiBold)
                        Text(repeatLabel(item.days), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = item.enabled, onCheckedChange = { onToggle(item, it) })
                    TextButton(onClick = { onRemove(item) }) {
                        Text(stringResource(R.string.delete_schedule))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmPickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    val state = rememberTimePickerState(initialHour = 9, initialMinute = 0, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) {
                Text(stringResource(R.string.add_schedule))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.later)) }
        },
        title = { Text(stringResource(R.string.set_alarm)) },
        text = { TimePicker(state = state) }
    )
}

private fun formatAlarmTime(hour: Int, minute: Int): String {
    val calendar = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, hour)
        set(java.util.Calendar.MINUTE, minute)
    }
    return android.text.format.DateFormat.format("h:mm a", calendar).toString()
}

private fun repeatLabel(days: List<Int>): String {
    if (days.sorted() == listOf(0, 1, 2, 3, 4, 5, 6)) return "Every day"
    if (days.sorted() == listOf(1, 2, 3, 4, 5)) return "Weekdays"
    if (days.sorted() == listOf(0, 6)) return "Weekends"
    val names = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    return days.sorted().joinToString(" ") { names[it] }
}

private fun statusLine(alert: AlertLog, deviceId: String): String {
    val role = if (alert.senderId == deviceId) "You sent" else "You received"
    val kind = if (alert.kind == "scheduled") "scheduled" else "on demand"
    val status = when {
        alert.ackedAt != null -> "tapped back"
        alert.receivedAt != null -> "received, waiting"
        else -> "sent"
    }
    return "$role · $kind · $status"
}

private fun timestampLine(alert: AlertLog): String {
    fun fmt(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))
    val parts = mutableListOf("Sent ${fmt(alert.sentAt)}")
    alert.receivedAt?.let { parts.add("Received ${fmt(it)}") }
    alert.ackedAt?.let { parts.add("Ack ${fmt(it)}") }
    return parts.joinToString("  ·  ")
}
