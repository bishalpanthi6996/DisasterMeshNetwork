package com.example.disastermesh.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Tactical Color Palette
val TacticalDarkGray = Color(0xFF0F111A)
val TacticalSurface = Color(0xFF1A1D29)
val HighVisOrange = Color(0xFFFF9800) // For Resources
val EmergencyRed = Color(0xFFE53935)  // For SOS
val SafeGreen = Color(0xFF00E676)     // For Verified/Official
val InfoBlue = Color(0xFF2979FF)      // For Normal Mesh

private val DarkColorScheme = darkColorScheme(
    primary = EmergencyRed,
    secondary = SafeGreen,
    tertiary = HighVisOrange,
    background = TacticalDarkGray,
    surface = TacticalSurface,
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = Color(0xFFECEFF1),
    onSurface = Color(0xFFECEFF1),
    error = EmergencyRed
)

@Composable
fun DisasterMeshTheme(
    darkTheme: Boolean = true, // Always default to dark for tactical/battery reasons
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
