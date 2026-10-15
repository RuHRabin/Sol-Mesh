package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TerminalDarkColorScheme = darkColorScheme(
    primary = TerminalGreenBright,
    onPrimary = Color(0xFF041208),
    primaryContainer = Color(0xFF0D2818),
    onPrimaryContainer = TerminalGreenBright,
    secondary = TerminalCyan,
    onSecondary = Color(0xFF021B27),
    secondaryContainer = Color(0xFF0C2A3A),
    onSecondaryContainer = TerminalCyan,
    tertiary = TerminalAmber,
    onTertiary = Color(0xFF2E1900),
    background = TerminalBlack,
    onBackground = TerminalTextPrimary,
    surface = TerminalBg,
    onSurface = TerminalTextPrimary,
    surfaceVariant = TerminalSurface,
    onSurfaceVariant = TerminalTextSecondary,
    outline = TerminalBorder,
    outlineVariant = TerminalBorderSubtle,
    error = TerminalRed,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // Hacker Terminal mode is permanently dark and high-contrast
    MaterialTheme(
        colorScheme = TerminalDarkColorScheme,
        typography = Typography,
        content = content
    )
}
