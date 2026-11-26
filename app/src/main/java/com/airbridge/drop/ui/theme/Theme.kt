package com.airbridge.drop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AirBridgeDarkColors = darkColorScheme(
    primary = Color(0xFF4F9CF9),
    onPrimary = Color(0xFF061529),
    primaryContainer = Color(0xFF153156),
    onPrimaryContainer = Color(0xFFD6E6FF),
    secondary = Color(0xFF34D399),
    onSecondary = Color(0xFF04261B),
    secondaryContainer = Color(0xFF0D3A2B),
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = Color(0xFFA78BFA),
    background = Color(0xFF0B101B),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF131B2B),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF94A3B8),
    error = Color(0xFFF87171)
)

@Composable
fun AirBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AirBridgeDarkColors,
        content = content
    )
}
