package com.kreativesolutions.tapback

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        val alertId = intent.getStringExtra(TapBackNotifications.EXTRA_ALERT_ID).orEmpty()
        val fromName = intent.getStringExtra(TapBackNotifications.EXTRA_FROM_NAME).orEmpty()
            .ifBlank { "Someone" }

        setContent {
            TapBackTheme {
                PingOverlayScreen(
                    fromName = fromName,
                    onHere = { ackAndClose(alertId) },
                    onLater = { finish() }
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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
        TapBackNotifications.cancelPing(this, alertId)
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1414))
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        Image(
            painter = painterResource(R.drawable.tapback_popup_art),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(RoundedCornerShape(24.dp)),
            contentScale = ContentScale.Crop
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.ping_title, fromName),
                color = Color(0xFF5EEAD4),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.ping_body),
                color = Color(0xFFE7F4F2),
                fontSize = 18.sp,
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
                    .height(64.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE07A5F),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(stringResource(R.string.i_am_here), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.later), color = Color(0xFFB7C9C6))
            }
        }
    }
}
