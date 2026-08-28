package com.kreativesolutions.tapback

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kreativesolutions.tapback.fcm.PingAlertService
import com.kreativesolutions.tapback.fcm.TapBackNotifications
import com.kreativesolutions.tapback.ui.theme.TapBackTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PingOverlayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(KeyguardManager::class.java)
                ?.requestDismissKeyguard(this, null)
        }

        render()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        dismissWithoutAck()
    }

    private fun render() {
        val alertId = intent.getStringExtra(TapBackNotifications.EXTRA_ALERT_ID).orEmpty()
        val fromName = intent.getStringExtra(TapBackNotifications.EXTRA_FROM_NAME).orEmpty()
            .ifBlank { "Someone" }
        setContent {
            TapBackTheme {
                PingOverlayScreen(
                    fromName = fromName,
                    onHere = { ackAndClose(alertId) },
                    onLater = { dismissWithoutAck() }
                )
            }
        }
    }

    private fun dismissWithoutAck() {
        val alertId = intent.getStringExtra(TapBackNotifications.EXTRA_ALERT_ID).orEmpty()
        val fromName = intent.getStringExtra(TapBackNotifications.EXTRA_FROM_NAME).orEmpty()
            .ifBlank { "Someone" }
        PingAlertService.stopAndClear(this, alertId)
        if (alertId.isNotBlank()) {
            TapBackNotifications.showPingReminder(this, alertId, fromName)
        }
        finish()
    }

    private fun ackAndClose(alertId: String) {
        if (alertId.isBlank()) {
            finish()
            return
        }
        val app = application as TapBackApp
        app.applicationScope.launch {
            runCatching {
                val session = app.currentSession()
                val baseUrl = app.appSettings.apiBaseUrl.first()
                if (session != null && baseUrl.isNotBlank()) {
                    app.api.ackAlert(baseUrl, session, alertId)
                }
            }
        }
        PingAlertService.stopAndClear(this, alertId)
        Toast.makeText(this, R.string.ack_confirmed, Toast.LENGTH_SHORT).show()
        finish()
    }
}

@Composable
private fun PingOverlayScreen(
    fromName: String,
    onHere: () -> Unit,
    onLater: () -> Unit
) {
    var busy by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1414))
    ) {
        Image(
            painter = painterResource(R.drawable.tapback_popup_art),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0x660B1414),
                            Color(0xCC0B1414),
                            Color(0xF20B1414)
                        )
                    )
                )
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.ping_title, fromName),
                    color = Color(0xFF5EEAD4),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.ping_body),
                    color = Color(0xFFE7F4F2),
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center
                )
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        if (!busy) {
                            busy = true
                            onHere()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE07A5F),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(
                        stringResource(R.string.i_am_here),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.later), color = Color(0xFFB7C9C6), fontSize = 18.sp)
                }
            }
        }
    }
}
