package com.alizz.filemanager.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Simple, original palette. Dynamic color on Android 12+ when enabled.
private val LightScheme = lightColorScheme(
    primary = Color(0xFF2F6B3C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB2E0B8),
    onPrimaryContainer = Color(0xFF0A2E14),
    secondary = Color(0xFF4E6351),
    surface = Color(0xFFF7FBF2),
    onSurface = Color(0xFF191C19),
    surfaceVariant = Color(0xFFDDE5DA),
    outline = Color(0xFF72796F),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF97D5A0),
    onPrimary = Color(0xFF06330F),
    primaryContainer = Color(0xFF1B5127),
    onPrimaryContainer = Color(0xFFB2E0B8),
    secondary = Color(0xFFB8CCB9),
    surface = Color(0xFF111511),
    onSurface = Color(0xFFE0E4DC),
    surfaceVariant = Color(0xFF3A444A).copy(alpha = 0.55f),
    outline = Color(0xFF8B938A),
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Composable
fun FileManagerTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
