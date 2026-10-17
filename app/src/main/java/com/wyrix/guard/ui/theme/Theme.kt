package com.wyrix.guard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkScheme = darkColorScheme(
    primary = ElectricBlue, onPrimary = White,
    secondary = SecurityGreen, onSecondary = Midnight,
    error = EmergencyRed, onError = White,
    background = Midnight, onBackground = White,
    surface = Navy, onSurface = White,
    surfaceVariant = Navy, onSurfaceVariant = TextSecondary,
    outline = TextSecondary,
)

private val LightScheme = lightColorScheme(
    primary = ElectricBlue, onPrimary = White,
    secondary = SecurityGreen, onSecondary = Midnight,
    error = EmergencyRed, onError = White,
    background = LightBackground, onBackground = LightText,
    surface = LightSurface, onSurface = LightText,
    surfaceVariant = LightBackground, onSurfaceVariant = LightTextSecondary,
    outline = LightTextSecondary,
)

@Composable
fun WyrixTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = WyrixTypography,
        content = content,
    )
}
