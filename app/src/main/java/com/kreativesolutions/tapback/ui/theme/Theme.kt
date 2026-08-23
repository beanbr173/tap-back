package com.kreativesolutions.tapback.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF042F2E),
    secondary = Color(0xFFE07A5F),
    onSecondary = Color(0xFF1A0D08),
    background = Color(0xFF0B1414),
    onBackground = Color(0xFFE7F4F2),
    surface = Color(0xFF152020),
    onSurface = Color(0xFFE7F4F2),
    surfaceVariant = Color(0xFF1C2C2C),
    onSurfaceVariant = Color(0xFFB7C9C6),
    error = Color(0xFFFF8A80)
)

@Composable
fun TapBackTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
